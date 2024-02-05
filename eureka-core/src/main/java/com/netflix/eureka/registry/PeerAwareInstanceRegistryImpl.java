/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.registry;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.AmazonInfo.MetaDataKey;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.DataCenterInfo.Name;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.eureka.registry.rule.DownOrStartingRule;
import com.netflix.eureka.registry.rule.FirstMatchWinsCompositeRule;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.registry.rule.LeaseExistsRule;
import com.netflix.eureka.registry.rule.OverrideExistsRule;
import com.netflix.eureka.resources.CurrentRequestVersion;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.Version;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.resources.ASGResource.ASGStatus;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.transport.EurekaServerHttpClientFactory;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.DefaultMonitorRegistry;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import static com.netflix.eureka.Names.METRIC_REGISTRY_PREFIX;

/**
 * 集群之间节点同步的服务注册器，所有操作都在其父类AbstractInstanceRegistry中
 * Handles replication of all operations to {@link AbstractInstanceRegistry} to peer
 * <em>Eureka</em> nodes to keep them all in sync.
 *
 * <p>
 * 主要操作是副本的注册，续约，取消，到期和状态更改
 * Primary operations that are replicated are the
 * <em>Registers,Renewals,Cancels,Expirations and Status Changes</em>
 * </p>
 *
 * <p>
 * 当eureka服务器启动时，它将尝试从对等的eureka节点获取所有注册表信息，如果由于某种原因该操作失败，
 * 则服务器将不允许用户在指定的时间段内获取注册表信息
 * When the eureka server starts up it tries to fetch all the registry
 * information from the peer eureka nodes.If for some reason this operation
 * fails, the server does not allow the user to get the registry information for
 * a period specified in
 * {@link com.netflix.eureka.EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}.
 * </p>
 *
 * <p>
 * 关于续约的重要注意事项。如果续约失败次数超过EurekaServerConfig.getRenewalPercentThreshold（）中指定的指定阈值，
 * 则在EurekaServerConfig＃getRenewalThresholdUpdateIntervalMs（）时间内，eureka将其视为危险，并停止实例过期
 * One important thing to note about <em>renewals</em>.If the renewal drops more
 * than the specified threshold as specified in
 * {@link com.netflix.eureka.EurekaServerConfig#getRenewalPercentThreshold()} within a period of
 * {@link com.netflix.eureka.EurekaServerConfig#getRenewalThresholdUpdateIntervalMs()}, eureka
 * perceives this as a danger and stops expiring instances.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 */

/**
 * 服务注册器，继承AbstractInstanceRegistry抽象类，实现PeerAwareInstanceRegistry服务注册接口，
 * 包含了服务注册，续约，下线，过期，状态改变等 <br>
 * init初始化：注册表缓存ResponseCache初始化，续约阈值定时更新任务初始化，初始化远程注册表 <br>
 * showdown:执行所有清理和关闭操作 <br>
 * syncUp：集群之间的数据同步节点复制 <br>
 * cancel：服务下线，并同步到其他节点 <br>
 * register：服务注册，并同步到其他节点 <br>
 * renew： 续约，并同步到其他节点 <br>
 */
@Singleton
public class PeerAwareInstanceRegistryImpl extends AbstractInstanceRegistry implements PeerAwareInstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(PeerAwareInstanceRegistryImpl.class);

    private static final String US_EAST_1 = "us-east-1";
    private static final int PRIME_PEER_NODES_RETRY_MS = 30000;

    private long startupTime = 0;
    private boolean peerInstancesTransferEmptyOnStartup = true;
    //把功能抽成枚举，心跳检查，注册，取消注册，状态改变，删除覆盖状态
    public enum Action {
        Heartbeat, Register, Cancel, StatusUpdate, DeleteStatusOverride;

        private com.netflix.servo.monitor.Timer timer = Monitors.newTimer(this.name());

        public com.netflix.servo.monitor.Timer getTimer() {
            return this.timer;
        }
    }

    private static final Comparator<Application> APP_COMPARATOR = new Comparator<Application>() {
        public int compare(Application l, Application r) {
            return l.getName().compareTo(r.getName());
        }
    };

    private final MeasuredRate numberOfReplicationsLastMin;
    //客户端
    protected final EurekaClient eurekaClient;
    //集群节点管理
    protected volatile PeerEurekaNodes peerEurekaNodes;

    private final InstanceStatusOverrideRule instanceStatusOverrideRule;

    private Timer timer = new Timer(
            "ReplicaAwareInstanceRegistry - RenewalThresholdUpdater", true);

    @Inject
    public PeerAwareInstanceRegistryImpl(
            EurekaServerConfig serverConfig,
            EurekaClientConfig clientConfig,
            ServerCodecs serverCodecs,
            EurekaClient eurekaClient, EurekaServerHttpClientFactory eurekaServerHttpClientFactory
    ) {
        super(serverConfig, clientConfig, serverCodecs, eurekaServerHttpClientFactory);
        this.eurekaClient = eurekaClient;
        //最后一分钟的复制次数
        this.numberOfReplicationsLastMin = new MeasuredRate(1000 * 60 * 1);
        // We first check if the instance is STARTING or DOWN, then we check explicit overrides,
        // then we check the status of a potentially existing lease.
        this.instanceStatusOverrideRule = new FirstMatchWinsCompositeRule(new DownOrStartingRule(),
                new OverrideExistsRule(overriddenInstanceStatusMap), new LeaseExistsRule());
    }

    @Override
    protected InstanceStatusOverrideRule getInstanceInfoOverrideRule() {
        return this.instanceStatusOverrideRule;
    }
    //初始化方法
    @Override
    public void init(PeerEurekaNodes peerEurekaNodes) throws Exception {
        // 最后一分钟的复制次数定时器Timer开始
        this.numberOfReplicationsLastMin.start();
        this.peerEurekaNodes = peerEurekaNodes;
        //初始化ResponseCache ,负责缓存客户端查询的注册表信息 30s/1次，初始化注册表响应缓存
        //初始化了一个ResponseCache响应缓存，ResponseCacheImpl是具体实现
        initializedResponseCache();
        //续约阈值定时更新任务，15min/1次 调用 updateRenewalThreshold()方法更新，定时更新续约阈值
        scheduleRenewalThresholdUpdateTask();
        //初始化远程注册表，默认没有远程Region
        initRemoteRegionRegistry();

        try {
            //注册到对象监视器
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register the JMX monitor for the InstanceRegistry :", e);
        }
    }

    /**
     * 执行所有清理和关闭操作。
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        try {
            //注销对象监视
            DefaultMonitorRegistry.getInstance().unregister(Monitors.newObjectMonitor(this));
        } catch (Throwable t) {
            logger.error("Cannot shutdown monitor registry", t);
        }
        try {
            //集群节点关闭
            peerEurekaNodes.shutdown();
        } catch (Throwable t) {
            logger.error("Cannot shutdown ReplicaAwareInstanceRegistry", t);
        }
        //最后一分钟的复制次数定时器 Timer停止
        numberOfReplicationsLastMin.stop();
        timer.cancel();
        //执行所有清理和关闭操作。
        //deltaRetentionTimer.cancel();  增量保留计时器
        //evictionTimer.cancel(); 服务剔除计时器
        //renewsLastMin.stop();  最后一分钟的复制次数机器停止
        super.shutdown();
    }

    /**
     * Schedule the task that updates <em>renewal threshold</em> periodically.
     * The renewal threshold would be used to determine if the renewals drop
     * dramatically because of network partition and to protect expiring too
     * many instances at a time.
     *
     */
    //续约阈值定时更新任务，15min/1次 调用 updateRenewalThreshold()方法 更新，
    //每renewalThresholdUpdateIntervalMs=900秒更新一次续约阀值
    private void scheduleRenewalThresholdUpdateTask() {
        //定时任务
        timer.schedule(new TimerTask() {
                           @Override
                           public void run() {
                               //更新续约阈值
                               updateRenewalThreshold();
                           }
                       }, serverConfig.getRenewalThresholdUpdateIntervalMs(),
                serverConfig.getRenewalThresholdUpdateIntervalMs());
    }

    /**
     * 集群数据同步，从集群中eureka节点复制注册表信息。如果通信失败，此操作将故障转移到其他节点，直到列表用尽。
     * Populates the registry information from a peer eureka node. This
     * operation fails over to other nodes until the list is exhausted if the
     * communication fails.
     */
    @Override
    public int syncUp() {
        // Copy entire entry from neighboring DS node
        int count = 0;
        //getRegistrySyncRetries重试次数默认5次
        for (int i = 0; ((i < serverConfig.getRegistrySyncRetries()) && (count == 0)); i++) {
            if (i > 0) {
                try {
                    //通信中断，等待下一次切换实例
                    Thread.sleep(serverConfig.getRegistrySyncRetryWaitMs());
                } catch (InterruptedException e) {
                    logger.warn("Interrupted during registry transfer..");
                    break;
                }
            }
            //获取注册表
            Applications apps = eurekaClient.getApplications();
            //循环服务列表，依次注册
            for (Application app : apps.getRegisteredApplications()) {
                for (InstanceInfo instance : app.getInstances()) {
                    try {
                        if (isRegisterable(instance)) {
                            //获取InstanceInfo之后注册到当前节点，
                            // 保存到 ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry 中缓存起来
                            //这里register的isReplication是true
                            register(instance, instance.getLeaseInfo().getDurationInSecs(), true);
                            count++;
                        }
                    } catch (Throwable t) {
                        logger.error("During DS init copy", t);
                    }
                }
            }
        }
        return count;
    }
    //运行开始传输数据
    @Override
    public void openForTraffic(ApplicationInfoManager applicationInfoManager, int count) {
        // Renewals happen every 30 seconds and for a minute it should be a factor of 2.
        //每分钟的预期续订次数 2次，30s/一次续约
        this.expectedNumberOfClientsSendingRenews = count;
        //每分钟续约次数阈值  = expectedNumberOfRenewsPerMin每分钟续约次数 * 85%
        //如果客户端续约低于这个阈值，将会开启服务端的自我保护功能
        updateRenewsPerMinThreshold();
        logger.info("Got {} instances from neighboring DS node", count);
        logger.info("Renew threshold is: {}", numberOfRenewsPerMinThreshold);
        this.startupTime = System.currentTimeMillis();
        if (count > 0) {
            this.peerInstancesTransferEmptyOnStartup = false;
        }
        DataCenterInfo.Name selfName = applicationInfoManager.getInfo().getDataCenterInfo().getName();
        boolean isAws = Name.Amazon == selfName;
        if (isAws && serverConfig.shouldPrimeAwsReplicaConnections()) {
            logger.info("Priming AWS connections for all replicas..");
            primeAwsReplicas(applicationInfoManager);
        }
        //改变服务的状态为UP
        logger.info("Changing status to UP");
        applicationInfoManager.setInstanceStatus(InstanceStatus.UP);
        //这里使用定时任务开启新的服务剔除任务
        super.postInit();
    }

    /**
     * Prime connections for Aws replicas.
     * <p>
     * Sometimes when the eureka servers comes up, AWS firewall may not allow
     * the network connections immediately. This will cause the outbound
     * connections to fail, but the inbound connections continue to work. What
     * this means is the clients would have switched to this node (after EIP
     * binding) and so the other eureka nodes will expire all instances that
     * have been switched because of the lack of outgoing heartbeats from this
     * instance.
     * </p>
     * <p>
     * The best protection in this scenario is to block and wait until we are
     * able to ping all eureka nodes successfully atleast once. Until then we
     * won't open up the traffic.
     * </p>
     */
    private void primeAwsReplicas(ApplicationInfoManager applicationInfoManager) {
        boolean areAllPeerNodesPrimed = false;
        while (!areAllPeerNodesPrimed) {
            String peerHostName = null;
            try {
                Application eurekaApps = this.getApplication(applicationInfoManager.getInfo().getAppName(), false);
                if (eurekaApps == null) {
                    areAllPeerNodesPrimed = true;
                    logger.info("No peers needed to prime.");
                    return;
                }
                for (PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
                    for (InstanceInfo peerInstanceInfo : eurekaApps.getInstances()) {
                        LeaseInfo leaseInfo = peerInstanceInfo.getLeaseInfo();
                        // If the lease is expired - do not worry about priming
                        if (System.currentTimeMillis() > (leaseInfo
                                .getRenewalTimestamp() + (leaseInfo
                                .getDurationInSecs() * 1000))
                                + (2 * 60 * 1000)) {
                            continue;
                        }
                        peerHostName = peerInstanceInfo.getHostName();
                        logger.info("Trying to send heartbeat for the eureka server at {} to make sure the " +
                                "network channels are open", peerHostName);
                        // Only try to contact the eureka nodes that are in this instance's registry - because
                        // the other instances may be legitimately down
                        if (peerHostName.equalsIgnoreCase(new URI(node.getServiceUrl()).getHost())) {
                            node.heartbeat(
                                    peerInstanceInfo.getAppName(),
                                    peerInstanceInfo.getId(),
                                    peerInstanceInfo,
                                    null,
                                    true);
                        }
                    }
                }
                areAllPeerNodesPrimed = true;
            } catch (Throwable e) {
                logger.error("Could not contact {}", peerHostName, e);
                try {
                    Thread.sleep(PRIME_PEER_NODES_RETRY_MS);
                } catch (InterruptedException e1) {
                    logger.warn("Interrupted while priming : ", e1);
                    areAllPeerNodesPrimed = true;
                }
            }
        }
    }

    /**
     * Checks to see if the registry access is allowed or the server is in a
     * situation where it does not all getting registry information. The server
     * does not return registry information for a period specified in
     * {@link EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}, if it cannot
     * get the registry information from the peer eureka nodes at start up.
     *
     * @return false - if the instances count from a replica transfer returned
     *         zero and if the wait time has not elapsed, otherwise returns true
     */
    @Override
    public boolean shouldAllowAccess(boolean remoteRegionRequired) {
        if (this.peerInstancesTransferEmptyOnStartup) {
            if (!(System.currentTimeMillis() > this.startupTime + serverConfig.getWaitTimeInMsWhenSyncEmpty())) {
                return false;
            }
        }
        if (remoteRegionRequired) {
            for (RemoteRegionRegistry remoteRegionRegistry : this.regionNameVSRemoteRegistry.values()) {
                if (!remoteRegionRegistry.isReadyForServingData()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean shouldAllowAccess() {
        return shouldAllowAccess(true);
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "shouldAllowAccess", type = DataSourceType.GAUGE)
    public int shouldAllowAccessMetric() {
        return shouldAllowAccess() ? 1 : 0;
    }


    /**
     * @deprecated use {@link com.netflix.eureka.cluster.PeerEurekaNodes#getPeerEurekaNodes()} directly.
     *
     * Gets the list of peer eureka nodes which is the list to replicate
     * information to.
     *
     * @return the list of replica nodes.
     */
    @Deprecated
    public List<PeerEurekaNode> getReplicaNodes() {
        return Collections.unmodifiableList(peerEurekaNodes.getPeerEurekaNodes());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#cancel(java.lang.String,
     * java.lang.String, long, boolean)
     */
    //取消注册，服务下线
    @Override
    public boolean cancel(final String appName, final String id,
                          final boolean isReplication) {
        //调用父类的下线方法
        if (super.cancel(appName, id, isReplication)) {
            //复制到其他的Eureka节点
            replicateToPeers(Action.Cancel, appName, id, null, null, isReplication);

            return true;
        }
        return false;
    }

    /**
     * Registers the information about the {@link InstanceInfo} and replicates
     * this information to all peer eureka nodes. If this is replication event
     * from other replica nodes then it is not replicated.
     *
     * @param info
     *            the {@link InstanceInfo} to be registered and replicated.
     * @param isReplication
     *            true if this is a replication event from other replica nodes,
     *            false otherwise.
     */
    /**
     * 注册服务信息 InstanceInfo，并将此信息InstanceInfo复制到所有对等的eureka server节点。如果这是来自其他副本节点的复制事件，则不会复制它。
     * 1、更新租期失效时间
     * 2、调用super.register服务注册（AbstractInstanceRegistry.register）
     * 3、调用replicateToPeers把服务实例拷贝到其他Eureka Server节点
     * @param info
     * @param isReplication
     */
    @Override
    public void register(final InstanceInfo info, final boolean isReplication) {
        //租期失效时间 90 s
        int leaseDuration = Lease.DEFAULT_DURATION_IN_SECS;
        if (info.getLeaseInfo() != null && info.getLeaseInfo().getDurationInSecs() > 0) {
            //如果服务的租期失效时间大于默认的90s，则重新赋值租期时间
            leaseDuration = info.getLeaseInfo().getDurationInSecs();
        }
        //调用父类的注册
        super.register(info, leaseDuration, isReplication);
        //注册信息同步到集群中其他节点
        replicateToPeers(Action.Register, info.getAppName(), info.getId(), info, null, isReplication);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#renew(java.lang.String,
     * java.lang.String, long, boolean)
     */
    /**
     * 1是调用super的续约方法
     * 2是续约成功后，调用replicateToPeers把续约成功的实例信息同步到其他的Eureka节点上，这个方法我们在分析注册流程的时候已经看过，
     * 不管是注册，续约，取消注册，状态改变等操作都要执行replicateToPeers进行Eureka集群节点之间的数据同步.
     * @param appName
     * @param id
     * @param isReplication
     * @return
     */
    public boolean renew(final String appName, final String id, final boolean isReplication) {
        //调用父类的续约
        if (super.renew(appName, id, isReplication)) {
            //续约的实例信息复制到其他的Eureka节点
            //注意这里是传入的Action是Action.Heartbeat 心跳
            replicateToPeers(Action.Heartbeat, appName, id, null, null, isReplication);
            return true;
        }
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#statusUpdate(java.lang.String,
     * java.lang.String, com.netflix.appinfo.InstanceInfo.InstanceStatus,
     * java.lang.String, boolean)
     */
    //修改服务状态
    @Override
    public boolean statusUpdate(final String appName, final String id,
                                final InstanceStatus newStatus, String lastDirtyTimestamp,
                                final boolean isReplication) {
        if (super.statusUpdate(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {
            //状态同步到其他节点
            replicateToPeers(Action.StatusUpdate, appName, id, null, newStatus, isReplication);
            return true;
        }
        return false;
    }

    //删除状态
    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {
        if (super.deleteStatusOverride(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {
            replicateToPeers(Action.DeleteStatusOverride, appName, id, null, null, isReplication);
            return true;
        }
        return false;
    }

    /**
     * Replicate the <em>ASG status</em> updates to peer eureka nodes. If this
     * event is a replication from other nodes, then it is not replicated to
     * other nodes.
     *
     * @param asgName the asg name for which the status needs to be replicated.
     * @param newStatus the {@link ASGStatus} information that needs to be replicated.
     * @param isReplication true if this is a replication event from other nodes, false otherwise.
     */
    @Override
    public void statusUpdate(final String asgName, final ASGStatus newStatus, final boolean isReplication) {
        // If this is replicated from an other node, do not try to replicate again.
        if (isReplication) {
            return;
        }
        for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
            replicateASGInfoToReplicaNodes(asgName, newStatus, node);

        }
    }
    //是否启用租约到期
    @Override
    public boolean isLeaseExpirationEnabled() {
        if (!isSelfPreservationModeEnabled()) {
            // The self preservation mode is disabled, hence allowing the instances to expire.
            return true;
        }
        return numberOfRenewsPerMinThreshold > 0 && getNumOfRenewsInLastMin() > numberOfRenewsPerMinThreshold;
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "isLeaseExpirationEnabled", type = DataSourceType.GAUGE)
    public int isLeaseExpirationEnabledMetric() {
        return isLeaseExpirationEnabled() ? 1 : 0;
    }

    /**
     * Checks to see if the self-preservation mode is enabled.
     *
     * <p>
     * The self-preservation mode is enabled if the expected number of renewals
     * per minute {@link #getNumOfRenewsInLastMin()} is lesser than the expected
     * threshold which is determined by {@link #getNumOfRenewsPerMinThreshold()}
     * . Eureka perceives this as a danger and stops expiring instances as this
     * is most likely because of a network event. The mode is disabled only when
     * the renewals get back to above the threshold or if the flag
     * {@link EurekaServerConfig#shouldEnableSelfPreservation()} is set to
     * false.
     * </p>
     *
     * @return true if the self-preservation mode is enabled, false otherwise.
     */
    @Override
    public boolean isSelfPreservationModeEnabled() {
        return serverConfig.shouldEnableSelfPreservation();
    }

    @com.netflix.servo.annotations.Monitor(name = METRIC_REGISTRY_PREFIX + "isSelfPreservationModeEnabled", type = DataSourceType.GAUGE)
    public int isSelfPreservationModeEnabledMetric() {
        return isSelfPreservationModeEnabled() ? 1 : 0;
    }

    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Updates the <em>renewal threshold</em> based on the current number of
     * renewals. The threshold is a percentage as specified in
     * {@link EurekaServerConfig#getRenewalPercentThreshold()} of renewals
     * received per minute {@link #getNumOfRenewsInLastMin()}.
     */
    //更新续约阈值，每隔15分钟，更新一次自我保护阈值
    private void updateRenewalThreshold() {
        try {
            //获取到注册表
            Applications apps = eurekaClient.getApplications();
            // 计算有多少个注册的服务实例
            int count = 0;
            for (Application app : apps.getRegisteredApplications()) {
                for (InstanceInfo instance : app.getInstances()) {
                    // 判断注册服务是否注册到本实例
                    if (this.isRegisterable(instance)) {
                        // 计算应用实例数
                        ++count;
                    }
                }
            }
            //加锁
            synchronized (lock) {
                //只有当阀值大于当前预期值时或者关闭了自我保护模式才更新
                // Update threshold only if the threshold is greater than the
                // current expected threshold or if self preservation is disabled.
                //当节点数量count大于最小续约数量时，或者没有开启自我保护机制的情况下，
                // 重新计算expectedNumberOfClientsSendingRenews和numberOfRenewsPerMinThreshold
                if ((count) > (serverConfig.getRenewalPercentThreshold() * expectedNumberOfClientsSendingRenews)
                        || (!this.isSelfPreservationModeEnabled())) {
                    //判断如果阈值时候大于预期的阈值 或者 关闭了我保护
                    //更新每分钟的预期续订次数：服务数 * 2 ，每个客户端30s/次，1分钟2次
                    this.expectedNumberOfClientsSendingRenews = count;
                    //更新每分钟阈值的续订次数 ：服务数 * 2 * 0.85 (百分比阈值)
                    updateRenewsPerMinThreshold();
                }
            }
            logger.info("Current renewal threshold is : {}", numberOfRenewsPerMinThreshold);
        } catch (Throwable e) {
            logger.error("Cannot update renewal threshold", e);
        }
    }

    /**
     * Gets the list of all {@link Applications} from the registry in sorted
     * lexical order of {@link Application#getName()}.
     *
     * @return the list of {@link Applications} in lexical order.
     */
    @Override
    public List<Application> getSortedApplications() {
        List<Application> apps = new ArrayList<>(getApplications().getRegisteredApplications());
        Collections.sort(apps, APP_COMPARATOR);
        return apps;
    }

    /**
     * Gets the number of <em>renewals</em> in the last minute.
     *
     * @return a long value representing the number of <em>renewals</em> in the last minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfReplicationsInLastMin",
            description = "Number of total replications received in the last minute",
            type = com.netflix.servo.annotations.DataSourceType.GAUGE)
    public long getNumOfReplicationsInLastMin() {
        return numberOfReplicationsLastMin.getCount();
    }

    /**
     * Checks if the number of renewals is lesser than threshold.
     *
     * @return 0 if the renewals are greater than threshold, 1 otherwise.
     */
    @com.netflix.servo.annotations.Monitor(name = "isBelowRenewThreshold", description = "0 = false, 1 = true",
            type = com.netflix.servo.annotations.DataSourceType.GAUGE)
    @Override
    public int isBelowRenewThresold() {
        if ((getNumOfRenewsInLastMin() <= numberOfRenewsPerMinThreshold)
                &&
                ((this.startupTime > 0) && (System.currentTimeMillis() > this.startupTime + (serverConfig.getWaitTimeInMsWhenSyncEmpty())))) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * Checks if an instance is registerable in this region. Instances from other regions are rejected.
     *
     * @param instanceInfo  th instance info information of the instance
     * @return true, if it can be registered in this server, false otherwise.
     */
    public boolean isRegisterable(InstanceInfo instanceInfo) {
        DataCenterInfo datacenterInfo = instanceInfo.getDataCenterInfo();
        String serverRegion = clientConfig.getRegion();
        if (AmazonInfo.class.isInstance(datacenterInfo)) {
            AmazonInfo info = AmazonInfo.class.cast(instanceInfo.getDataCenterInfo());
            String availabilityZone = info.get(MetaDataKey.availabilityZone);
            // Can be null for dev environments in non-AWS data center
            if (availabilityZone == null && US_EAST_1.equalsIgnoreCase(serverRegion)) {
                return true;
            } else if ((availabilityZone != null) && (availabilityZone.contains(serverRegion))) {
                // If in the same region as server, then consider it registerable
                return true;
            }
        }
        return true; // Everything non-amazon is registrable.
    }

    /**
     * Replicates all eureka actions to peer eureka nodes except for replication
     * traffic to this node.
     *
     */
    /**
     * 将所有eureka操作复制到对等eureka节点，isReplication用来标识这次请求是不是其他节点复制过来的，
     * 可以看到如果peerEurekaNodes为空，或者isReplication为true的话，则不继续往下
     */
    private void replicateToPeers(Action action, String appName, String id,
                                  InstanceInfo info /* optional */,
                                  InstanceStatus newStatus /* optional */, boolean isReplication) {
        //开始计时
        Stopwatch tracer = action.getTimer().start();
        try {
            //是否是其他节点复制过来的
            if (isReplication) {
                //最后一分钟的复制次数+1
                numberOfReplicationsLastMin.increment();
            }
            // If it is a replication already, do not replicate again as this will create a poison replication
            //如果已经是复制，则不要再次复制
            // 如果当前节点接收到的实例信息本就是另一个节点同步来的，则不会继续同步给其他节点，避免形成“广播效应”，造成死循环
            if (peerEurekaNodes == Collections.EMPTY_LIST || isReplication) {
                return;
            }
            //遍历集群所有节点
            for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
                // If the url represents this host, do not replicate to yourself.
                //如果该URL代表此主机，请不要复制到您自己，当前节点不复制
                if (peerEurekaNodes.isThisMyUrl(node.getServiceUrl())) {
                    continue;
                }
                //复制实例到其他某个Eureka
                replicateInstanceActionsToPeers(action, appName, id, info, newStatus, node);
            }
        } finally {
            tracer.stop();
        }
    }

    /**
     * Replicates all instance changes to peer eureka nodes except for
     * replication traffic to this node.
     *
     */
    //集群之间的节点复制，这里根据不同的Action来调用PeerEurekaNode的不同方法
    private void replicateInstanceActionsToPeers(Action action, String appName,
                                                 String id, InstanceInfo info, InstanceStatus newStatus,
                                                 PeerEurekaNode node) {
        try {
            InstanceInfo infoFromRegistry;
            CurrentRequestVersion.set(Version.V2);
            //判断请求的是什么操作
            switch (action) {
                //取消注册，调用 PeerEurekaNode.cancel
                case Cancel:
                    node.cancel(appName, id);
                    break;
                //心跳请求，调用PeerEurekaNode.heartbeat
                case Heartbeat:
                    //续约心跳，获取服务的状态
                    InstanceStatus overriddenStatus = overriddenInstanceStatusMap.get(id);
                    //根据名字和id获取实例InstanceInfo
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    //调用PeerEurekaNode.heartbeat方法
                    node.heartbeat(appName, id, infoFromRegistry, overriddenStatus, false);
                    break;
                //服务注册调用PeerEurekaNode.register
                case Register:
                    node.register(info);
                    break;
                //状态修改调用PeerEurekaNode.statusUpdate
                case StatusUpdate:
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.statusUpdate(appName, id, newStatus, infoFromRegistry);
                    break;
                //状态删除调用PeerEurekaNode.deleteStatusOverride
                case DeleteStatusOverride:
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.deleteStatusOverride(appName, id, infoFromRegistry);
                    break;
            }
        } catch (Throwable t) {
            logger.error("Cannot replicate information to {} for action {}", node.getServiceUrl(), action.name(), t);
        } finally {
            CurrentRequestVersion.remove();
        }
    }

    /**
     * Replicates all ASG status changes to peer eureka nodes except for
     * replication traffic to this node.
     */
    private void replicateASGInfoToReplicaNodes(final String asgName,
                                                final ASGStatus newStatus, final PeerEurekaNode node) {
        CurrentRequestVersion.set(Version.V2);
        try {
            node.statusUpdate(asgName, newStatus);
        } catch (Throwable e) {
            logger.error("Cannot replicate ASG status information to {}", node.getServiceUrl(), e);
        } finally {
            CurrentRequestVersion.remove();
        }
    }

    @Override
    @com.netflix.servo.annotations.Monitor(name = "localRegistrySize",
            description = "Current registry size", type = DataSourceType.GAUGE)
    public long getLocalRegistrySize() {
        return super.getLocalRegistrySize();
    }
}
