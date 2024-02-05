package com.netflix.eureka.cluster;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.endpoint.EndpointUtils;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to manage lifecycle of a collection of {@link PeerEurekaNode}s.
 *
 * @author Tomasz Bak
 */

/**
 * 用来管理Eureka集群节点PeerEurekaNode生命周期的工具，被DefaultEurekaServerContext 的initialize初始化方法中执行
 * 主要定义了eureka集群节点更新逻辑，通过定时任务定时更新，默认10分钟更新一次，更新逻辑是删除旧的节点，添加新的节点，
 * 旧的节点调用shutdown做关闭操作，新的节点调用createPeerEurekaNode进行创建，集群节点最终存储在List<PeerEurekaNode>结构中
 */

@Singleton
public abstract class PeerEurekaNodes {

    private static final Logger logger = LoggerFactory.getLogger(PeerEurekaNodes.class);
    //注册器
    protected final PeerAwareInstanceRegistry registry;
    //服务端配置对象
    protected final EurekaServerConfig serverConfig;
    //客户端配置
    protected final EurekaClientConfig clientConfig;
    protected final ServerCodecs serverCodecs;
    //InstanceInfo实例管理器
    private final ApplicationInfoManager applicationInfoManager;
    //Eureka集群节点集合
    private volatile List<PeerEurekaNode> peerEurekaNodes = Collections.emptyList();
    //Eureka集群节点的url集合
    private volatile Set<String> peerEurekaNodeUrls = Collections.emptySet();
    //定时任务执行器
    private ScheduledExecutorService taskExecutor;

    @Inject
    public PeerEurekaNodes(
            PeerAwareInstanceRegistry registry,
            EurekaServerConfig serverConfig,
            EurekaClientConfig clientConfig,
            ServerCodecs serverCodecs,
            ApplicationInfoManager applicationInfoManager) {
        this.registry = registry;
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.applicationInfoManager = applicationInfoManager;
    }
    //获取集群节点集合，不可修改
    public List<PeerEurekaNode> getPeerNodesView() {
        return Collections.unmodifiableList(peerEurekaNodes);
    }
    //获取集群节点集合
    public List<PeerEurekaNode> getPeerEurekaNodes() {
        return peerEurekaNodes;
    }
    //此实例提供对等复制实例的最小数量，被认为是健康的
    public int getMinNumberOfAvailablePeers() {
        return serverConfig.getHealthStatusMinNumberOfAvailablePeers();
    }
    //开始
    public void start() {
        // 创建一个名字为Eureka-PeerNodesUpdater"单线程的定时执行器
        taskExecutor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "Eureka-PeerNodesUpdater");
                        thread.setDaemon(true);
                        return thread;
                    }
                }
        );
        try {
            //更新集群中的节点中的注册信息
            updatePeerEurekaNodes(resolvePeerUrls());
            //创建runnable线程，业务逻辑为：updatePeerEurekaNodes(resolvePeerUrls());
            Runnable peersUpdateTask = new Runnable() {
                @Override
                public void run() {
                    try {
                        //更新集群中的节点中的注册信息，默认10分钟更新一次，更新逻辑是删除旧的节点，添加新的节点，旧的节点调用shutdown做关闭操作
                        //新的节点调用createPeerEurekaNode进行创建，集群节点最终存储在List结构中
                        updatePeerEurekaNodes(resolvePeerUrls());
                    } catch (Throwable e) {
                        logger.error("Cannot update the replica Nodes", e);
                    }

                }
            };
            //定时任务
            taskExecutor.scheduleWithFixedDelay(
                    peersUpdateTask,
                    serverConfig.getPeerEurekaNodesUpdateIntervalMs(),
                    //定时器时间间隔默认：10分钟peerEurekaNodesUpdateIntervalMs=10 * MINUTES
                    serverConfig.getPeerEurekaNodesUpdateIntervalMs(),
                    TimeUnit.MILLISECONDS
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        for (PeerEurekaNode node : peerEurekaNodes) {
            logger.info("Replica node URL:  {}", node.getServiceUrl());
        }
    }
    //关闭，关闭节点更新的定时任务，清空peerEurekaNodes ，peerEurekaNodeUrls ,调用每个节点的shutDown方法
    public void shutdown() {
        taskExecutor.shutdown();
        List<PeerEurekaNode> toRemove = this.peerEurekaNodes;

        this.peerEurekaNodes = Collections.emptyList();
        this.peerEurekaNodeUrls = Collections.emptySet();

        for (PeerEurekaNode node : toRemove) {
            node.shutDown();
        }
    }

    /**
     * Resolve peer URLs.
     *
     * @return peer URLs with node's own URL filtered out
     */
    //基于相同的Zone得到Eureka集群中多个节点的url,过滤掉当前节点
    protected List<String> resolvePeerUrls() {
        // 当前实例信息
        InstanceInfo myInfo = applicationInfoManager.getInfo();
        // 获取当前实例所属的zone
        String zone = InstanceInfo.getZone(clientConfig.getAvailabilityZones(clientConfig.getRegion()), myInfo);
        // 获取当前zone下的eureka server urls
        List<String> replicaUrls = EndpointUtils
                .getDiscoveryServiceUrls(clientConfig, zone, new EndpointUtils.InstanceInfoBasedUrlRandomizer(myInfo));
        //移除当前eureka节点的url
        int idx = 0;
        while (idx < replicaUrls.size()) {
            if (isThisMyUrl(replicaUrls.get(idx))) {
                replicaUrls.remove(idx);
            } else {
                idx++;
            }
        }
        return replicaUrls;
    }

    /**
     * Given new set of replica URLs, destroy {@link PeerEurekaNode}s no longer available, and
     * create new ones.
     *
     * @param newPeerUrls peer node URLs; this collection should have local node's URL filtered out
     */
    /**
     * 修改集群节点，在定时器中被执行，newPeerUrls是集群中的eureka server节点的url，过滤了本地节点的url
     * 做法是删除老的不可用的节点调用shutDown方法，使用createPeerEurekaNode创建新的节点添加新的节点
     * @param newPeerUrls
     */
    protected void updatePeerEurekaNodes(List<String> newPeerUrls) {
        if (newPeerUrls.isEmpty()) {
            logger.warn("The replica size seems to be empty. Check the route 53 DNS Registry");
            return;
        }
        //需要关闭的节点
        Set<String> toShutdown = new HashSet<>(peerEurekaNodeUrls);
        //移除掉新的节点，新的节点不需要关闭
        toShutdown.removeAll(newPeerUrls);
        //需要添加的节点
        Set<String> toAdd = new HashSet<>(newPeerUrls);
        //移除掉老的节点，老的节点不需要添加
        toAdd.removeAll(peerEurekaNodeUrls);
        // 判断集群实例是否有变化，判断方法通过两个差集是否为空，简单方便值得借鉴
        if (toShutdown.isEmpty() && toAdd.isEmpty()) { // No change
            return;
        }

        // Remove peers no long available
        //节点集合，本地缓存的所有节点
        List<PeerEurekaNode> newNodeList = new ArrayList<>(peerEurekaNodes);
        //如果需要关闭的节点集合不为空
        if (!toShutdown.isEmpty()) {
            logger.info("Removing no longer available peer nodes {}", toShutdown);
            int i = 0;
            while (i < newNodeList.size()) {
                PeerEurekaNode eurekaNode = newNodeList.get(i);
                //如果当前节点需要关闭，包含在toShutdown中
                if (toShutdown.contains(eurekaNode.getServiceUrl())) {
                    //从newNodeList中移除掉
                    newNodeList.remove(i);
                    //执行节点的关闭方法
                    eurekaNode.shutDown();
                } else {
                    i++;
                }
            }
        }

        // Add new peers
        // 如果需要添加新的节点
        if (!toAdd.isEmpty()) {
            logger.info("Adding new peer nodes {}", toAdd);
            for (String peerUrl : toAdd) {
                //调用 createPeerEurekaNode 创建新的节点，添加到节点集合中
                newNodeList.add(createPeerEurekaNode(peerUrl));
            }
        }

        this.peerEurekaNodes = newNodeList;
        this.peerEurekaNodeUrls = new HashSet<>(newPeerUrls);
    }

    //创建集群节点PeerEurekaNode
    protected abstract PeerEurekaNode createPeerEurekaNode(String peerEurekaNodeUrl);

    /**
     * @deprecated 2016-06-27 use instance version of {@link #isThisMyUrl(String)}
     *
     * Checks if the given service url contains the current host which is trying
     * to replicate. Only after the EIP binding is done the host has a chance to
     * identify itself in the list of replica nodes and needs to take itself out
     * of replication traffic.
     *
     * @param url the service url of the replica node that the check is made.
     * @return true, if the url represents the current node which is trying to
     *         replicate, false otherwise.
     */
    public static boolean isThisMe(String url) {
        InstanceInfo myInfo = ApplicationInfoManager.getInstance().getInfo();
        String hostName = hostFromUrl(url);
        return hostName != null && hostName.equals(myInfo.getHostName());
    }

    /**
     * Checks if the given service url contains the current host which is trying
     * to replicate. Only after the EIP binding is done the host has a chance to
     * identify itself in the list of replica nodes and needs to take itself out
     * of replication traffic.
     *
     * @param url the service url of the replica node that the check is made.
     * @return true, if the url represents the current node which is trying to
     *         replicate, false otherwise.
     */
    public boolean isThisMyUrl(String url) {
        final String myUrlConfigured = serverConfig.getMyUrl();
        if (myUrlConfigured != null) {
            return myUrlConfigured.equals(url);
        }
        return isInstanceURL(url, applicationInfoManager.getInfo());
    }
    
    /**
     * Checks if the given service url matches the supplied instance
     *
     * @param url the service url of the replica node that the check is made.
     * @param instance the instance to check the service url against
     * @return true, if the url represents the supplied instance, false otherwise.
     */
    public boolean isInstanceURL(String url, InstanceInfo instance) {
        String hostName = hostFromUrl(url);
        String myInfoComparator = instance.getHostName();
        if (clientConfig.getTransportConfig().applicationsResolverUseIp()) {
            myInfoComparator = instance.getIPAddr();
        }
        return hostName != null && hostName.equals(myInfoComparator);
    }

    public static String hostFromUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            logger.warn("Cannot parse service URI {}", url, e);
            return null;
        }
        return uri.getHost();
    }
}
