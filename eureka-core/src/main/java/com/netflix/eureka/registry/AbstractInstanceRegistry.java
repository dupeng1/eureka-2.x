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

import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.eureka.transport.EurekaServerHttpClientFactory;
import jakarta.annotation.Nullable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.common.cache.CacheBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.Pair;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.annotations.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.eureka.util.EurekaMonitors.*;

/**
 * Handles all registry requests from eureka clients.
 *
 * <p>
 * Primary operations that are performed are the
 * <em>Registers</em>, <em>Renewals</em>, <em>Cancels</em>, <em>Expirations</em>, and <em>Status Changes</em>. The
 * registry also stores only the delta operations
 * </p>
 *
 * @author Karthik Ranganathan
 *
 */

/**
 * InstanceRegistry的实现类，应用对象注册表抽象，处理客户端的注册请求，包括register注册，Renewals续约，Cancels下线，Expirations过期，
 * Status Changes状态改变，服务注册表以增量的方式增加
 */
public abstract class AbstractInstanceRegistry implements InstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AbstractInstanceRegistry.class);

    private static final String[] EMPTY_STR_ARRAY = new String[0];

    //相当于服务端的注册表，维护所有服务的注册信息，第一层Map的key是服务的应用名称，value也是一个map,其中key是实例id,value是实例的详细信息(Lease)
    private final ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry
            = new ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>>();
    protected Map<String, RemoteRegionRegistry> regionNameVSRemoteRegistry = new HashMap<String, RemoteRegionRegistry>();
    protected final ConcurrentMap<String, InstanceStatus> overriddenInstanceStatusMap = CacheBuilder
            .newBuilder().initialCapacity(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .<String, InstanceStatus>build().asMap();

    // CircularQueues here for debugging/statistics purposes only
    private final CircularQueue<Pair<Long, String>> recentRegisteredQueue;
    private final CircularQueue<Pair<Long, String>> recentCanceledQueue;
    private ConcurrentLinkedQueue<RecentlyChangedItem> recentlyChangedQueue = new ConcurrentLinkedQueue<>();

    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock read = readWriteLock.readLock();
    private final Lock write = readWriteLock.writeLock();
    protected final Object lock = new Object();

    private Timer deltaRetentionTimer = new Timer("Eureka-DeltaRetentionTimer", true);
    private Timer evictionTimer = new Timer("Eureka-EvictionTimer", true);
    //最近一分钟续约次数
    private final MeasuredRate renewsLastMin;

    private final AtomicReference<EvictionTask> evictionTaskRef = new AtomicReference<>();

    protected String[] allKnownRemoteRegions = EMPTY_STR_ARRAY;
    //每分钟续约阈值
    protected volatile int numberOfRenewsPerMinThreshold;
    //每分钟的预期续约次数
    protected volatile int expectedNumberOfClientsSendingRenews;

    protected final EurekaServerConfig serverConfig;
    protected final EurekaClientConfig clientConfig;
    protected final ServerCodecs serverCodecs;
    private EurekaServerHttpClientFactory eurekaServerHttpClientFactory;
    protected volatile ResponseCache responseCache;

    /**
     * Create a new, empty instance registry.
     */
    protected AbstractInstanceRegistry(EurekaServerConfig serverConfig, EurekaClientConfig clientConfig, ServerCodecs serverCodecs, EurekaServerHttpClientFactory eurekaServerHttpClientFactory) {
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.eurekaServerHttpClientFactory = eurekaServerHttpClientFactory;
        this.recentCanceledQueue = new CircularQueue<Pair<Long, String>>(1000);
        this.recentRegisteredQueue = new CircularQueue<Pair<Long, String>>(1000);

        this.renewsLastMin = new MeasuredRate(1000 * 60 * 1);

        this.deltaRetentionTimer.schedule(getDeltaRetentionTask(),
                serverConfig.getDeltaRetentionTimerIntervalInMs(),
                serverConfig.getDeltaRetentionTimerIntervalInMs());
    }

    @Override
    public synchronized void initializedResponseCache() {
        if (responseCache == null) {
            // 创建ResponseCacheImpl
            responseCache = new ResponseCacheImpl(serverConfig, serverCodecs, this);
        }
    }

    // 将配置文件中RemoteRegionUrlsWithName配置参数，转换为RemoteRegistry
    protected void initRemoteRegionRegistry() throws MalformedURLException {
        Map<String, String> remoteRegionUrlsWithName = serverConfig.getRemoteRegionUrlsWithName();
        if (!remoteRegionUrlsWithName.isEmpty()) {
            allKnownRemoteRegions = new String[remoteRegionUrlsWithName.size()];
            int remoteRegionArrayIndex = 0;
            for (Map.Entry<String, String> remoteRegionUrlWithName : remoteRegionUrlsWithName.entrySet()) {
                RemoteRegionRegistry remoteRegionRegistry = new RemoteRegionRegistry(
                        serverConfig,
                        clientConfig,
                        serverCodecs,
                        eurekaServerHttpClientFactory,
                        remoteRegionUrlWithName.getKey(),
                        new URL(remoteRegionUrlWithName.getValue()));
                regionNameVSRemoteRegistry.put(remoteRegionUrlWithName.getKey(), remoteRegionRegistry);
                allKnownRemoteRegions[remoteRegionArrayIndex++] = remoteRegionUrlWithName.getKey();
            }
        }
        logger.info("Finished initializing remote region registries. All known remote regions: {}",
                (Object) allKnownRemoteRegions);
    }

    @Override
    public ResponseCache getResponseCache() {
        return responseCache;
    }

    public long getLocalRegistrySize() {
        long total = 0;
        for (Map<String, Lease<InstanceInfo>> entry : registry.values()) {
            total += entry.size();
        }
        return total;
    }

    /**
     * Completely clear the registry.
     */
    @Override
    public void clearRegistry() {
        overriddenInstanceStatusMap.clear();
        recentCanceledQueue.clear();
        recentRegisteredQueue.clear();
        recentlyChangedQueue.clear();
        registry.clear();
    }

    // for server info use
    @Override
    public Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot() {
        return new HashMap<>(overriddenInstanceStatusMap);
    }

    /**
     * Registers a new instance with a given duration.
     *
     * @see com.netflix.eureka.lease.LeaseManager#register(java.lang.Object, int, boolean)
     */
    /**
     * 注册实例，更新实例租约信息
     * 1、判断当前服务是否已经被注册，如果是，则以最后更新时间为准，选择更新时间靠后的服务实例进行注册
     * 2、维护实例的租约信息Lease，并放到Eureka Server本地维护维护的registry注册表中，本质是一个Map（ConcurrentHashMap<String, Map<String, Lease>>）
     * 3、如果是服务是新注册的，把注册的实例封装成Leaset存储到registry注册表中，并更新每分钟续约阀值numberOfRenewsPerMinThreshold
     * 4、维护了两个队列，recentRegisteredQueue最近注册队列，recentlyChangedQueue最近更改队列，这个队列可以用来获取最近操作的信息。
     * 5、维护当前实例的OverriddenStatus
     * 6、更新服务实例的最后更新时间戳
     * 7、使ResponseCache缓存失效
     * @param registrant
     * @param leaseDuration
     * @param isReplication
     */
    public void register(InstanceInfo registrant, int leaseDuration, boolean isReplication) {
        //获取锁
        read.lock();
        try {
            //根据注册的服务的名字取本地服务注册表中获取服务注册信息，如果该服务已经被注册了，那么registry中将会存在它
            Map<String, Lease<InstanceInfo>> gMap = registry.get(registrant.getAppName());
            REGISTER.increment(isReplication);
            // 第一次注册时还不存在，所以 new 一个
            if (gMap == null) {
                //如果该服务实例没被注册，就把服务实例注册到本地的registry中，本质是一个Map
                final ConcurrentHashMap<String, Lease<InstanceInfo>> gNewMap = new ConcurrentHashMap<String, Lease<InstanceInfo>>();
                //没有的话就添加到registry中
                gMap = registry.putIfAbsent(registrant.getAppName(), gNewMap);
                if (gMap == null) {
                    gMap = gNewMap;
                }
            }
            //根据服务实例id获取服务的租约对象
            Lease<InstanceInfo> existingLease = gMap.get(registrant.getId());
            // Retain the last dirty timestamp without overwriting it, if there is already a lease
            //如果已经有租约，则保留最后的脏时间戳而不覆盖它
            if (existingLease != null && (existingLease.getHolder() != null)) {
                // registry中已经存在的当前服务的最后修改时间的时间戳
                Long existingLastDirtyTimestamp = existingLease.getHolder().getLastDirtyTimestamp();
                // 提交注册的当前服务的最后修改时间
                Long registrationLastDirtyTimestamp = registrant.getLastDirtyTimestamp();
                logger.debug("Existing lease found (existing={}, provided={}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);

                // this is a > instead of a >= because if the timestamps are equal, we still take the remote transmitted
                // InstanceInfo instead of the server local copy.

                //因为如果时间戳相等，我们仍然采用远程传输的InstanceInfo而不是服务器本地副本
                //如果已存在的该服务的修改时间 大于 当前提交注册的该服务的最后修改时间，
                //则采用registy中已存在的服务为准,因为要选择修改时间靠后的
                if (existingLastDirtyTimestamp > registrationLastDirtyTimestamp) {
                    logger.warn("There is an existing lease and the existing lease's dirty timestamp {} is greater" +
                            " than the one that is being registered {}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);
                    logger.warn("Using the existing instanceInfo instead of the new instanceInfo as the registrant");
                    //使用现有的instanceInfo代替新的instanceInfo作为注册者
                    registrant = existingLease.getHolder();
                }
            } else {
                //执行到这里，说明该服务是新注册
                // The lease does not exist and hence it is a new registration
                synchronized (lock) {
                    //这里在计算服务的续约频率值
                    if (this.expectedNumberOfClientsSendingRenews > 0) {
                        // Since the client wants to register it, increase the number of clients sending renews
                        //(expectedNumberOfRenewsPerMin)期待的每分钟续订次数
                        this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews + 1;
                        // 每分钟续约数阈值更新
                        updateRenewsPerMinThreshold();
                    }
                }
                logger.debug("No previous lease information found; it is new registration");
            }
            //新的心跳续约对象，包括注册信息，最后操作时间，注册事件，过期时间，剔除时间
            //创建租约对象，把注册实例和租期放进去
            Lease<InstanceInfo> lease = new Lease<>(registrant, leaseDuration);
            if (existingLease != null) {
                //设置服务上线时间
                lease.setServiceUpTimestamp(existingLease.getServiceUpTimestamp());
            }
            // 将新对象放入注册表
            gMap.put(registrant.getId(), lease);
            //添加到注册队列
            recentRegisteredQueue.add(new Pair<Long, String>(
                    System.currentTimeMillis(),
                    registrant.getAppName() + "(" + registrant.getId() + ")"));
            // This is where the initial state transfer of overridden status happens
            if (!InstanceStatus.UNKNOWN.equals(registrant.getOverriddenStatus())) {
                logger.debug("Found overridden status {} for instance {}. Checking to see if needs to be add to the "
                                + "overrides", registrant.getOverriddenStatus(), registrant.getId());
                if (!overriddenInstanceStatusMap.containsKey(registrant.getId())) {
                    logger.info("Not found overridden id {} and hence adding it", registrant.getId());
                    //添加服务的OverriddenStatus
                    overriddenInstanceStatusMap.put(registrant.getId(), registrant.getOverriddenStatus());
                }
            }
            //根据覆盖的状态规则设置状态
            InstanceStatus overriddenStatusFromMap = overriddenInstanceStatusMap.get(registrant.getId());
            if (overriddenStatusFromMap != null) {
                logger.info("Storing overridden status {} from map", overriddenStatusFromMap);
                registrant.setOverriddenStatus(overriddenStatusFromMap);
            }

            // Set the status based on the overridden status rules
            InstanceStatus overriddenInstanceStatus = getOverriddenInstanceStatus(registrant, existingLease, isReplication);
            registrant.setStatusWithoutDirty(overriddenInstanceStatus);

            // If the lease is registered with UP status, set lease service up timestamp
            //如果租约已注册为UP状态，请设置租约服务启动时间戳记
            if (InstanceStatus.UP.equals(registrant.getStatus())) {
                //更新服务上线时间戳
                lease.serviceUp();
            }
            //服务动作：ADDED添加，MODIFIED修改，DELETED删除
            registrant.setActionType(ActionType.ADDED);
            //添加到最近更改的队列
            recentlyChangedQueue.add(new RecentlyChangedItem(lease));
            //最后更新时间
            registrant.setLastUpdatedTimestamp();
            //使当前应用的ResponseCache失效
            invalidateCache(registrant.getAppName(), registrant.getVIPAddress(), registrant.getSecureVipAddress());
            logger.info("Registered instance {}/{} with status {} (replication={})",
                    registrant.getAppName(), registrant.getId(), registrant.getStatus(), isReplication);
        } finally {
            read.unlock();
        }
    }

    /**
     * 取消实例注册，这通常是由客户端调用时关闭通知服务器从流量中除去实例
     * Cancels the registration of an instance.
     *
     * <p>
     * This is normally invoked by a client when it shuts down informing the
     * server to remove the instance from traffic.
     * </p>
     *
     * @param appName the application name of the application.
     * @param id the unique identifier of the instance.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the instance was removed from the {@link AbstractInstanceRegistry} successfully, false otherwise.
     */
    @Override
    public boolean cancel(String appName, String id, boolean isReplication) {
        return internalCancel(appName, id, isReplication);
    }

    /**
     * {@link #cancel(String, String, boolean)} method is overridden by {@link PeerAwareInstanceRegistry}, so each
     * cancel request is replicated to the peers. This is however not desired for expires which would be counted
     * in the remote peers as valid cancellations, so self preservation mode would not kick-in.
     */
    /**
     * 这里主要是根据当前要取消的服务名从registry中查询出服务之后做了这些事情
     * 1、从registry中移除服务，
     * 2、从overriddenInstanceStatusMap状态map中移除服务状态
     * 3、添加到最近取消队列
     * 4、调用Lease.cancel方法，将租约对象中的逐出时间修改为当前时间
     * 5、修改服务的InstanceInfo的状态为DELETE
     * 6、添加到最近修改队列
     * 7、更新服务最后修改时间
     * 8、使ReponseCache缓存无效
     * @param appName
     * @param id
     * @param isReplication
     * @return
     */
    protected boolean internalCancel(String appName, String id, boolean isReplication) {
        //上锁
        read.lock();
        try {
            //服务取消数增加
            CANCEL.increment(isReplication);
            //获取当前提出的服务
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> leaseToCancel = null;
            if (gMap != null) {
                //从服务注册的map中移除掉当前服务
                leaseToCancel = gMap.remove(id);
            }
            //添加到最近取消队列
            recentCanceledQueue.add(new Pair<Long, String>(System.currentTimeMillis(), appName + "(" + id + ")"));
            //overriddenInstanceStatusMap 服务状态map中移除当前服务
            InstanceStatus instanceStatus = overriddenInstanceStatusMap.remove(id);
            if (instanceStatus != null) {
                logger.debug("Removed instance id {} from the overridden map which has value {}", id, instanceStatus.name());
            }
            if (leaseToCancel == null) {
                //没找到服务
                CANCEL_NOT_FOUND.increment(isReplication);
                logger.warn("DS: Registry: cancel failed because Lease is not registered for: {}/{}", appName, id);
                return false;
            } else {
                //调用Lease.cancel方法
                leaseToCancel.cancel();
                //获取服务实例信息
                InstanceInfo instanceInfo = leaseToCancel.getHolder();
                String vip = null;
                String svip = null;
                if (instanceInfo != null) {
                    //实例状态修改为删除
                    instanceInfo.setActionType(ActionType.DELETED);
                    //添加最近修改队列
                    recentlyChangedQueue.add(new RecentlyChangedItem(leaseToCancel));
                    //实例信息对象修改最后修改时间
                    instanceInfo.setLastUpdatedTimestamp();
                    vip = instanceInfo.getVIPAddress();
                    svip = instanceInfo.getSecureVipAddress();
                }
                //使缓存无效,调用responseCache.invalidate让服务在缓存中失效
                invalidateCache(appName, vip, svip);
                logger.info("Cancelled instance {}/{} (replication={})", appName, id, isReplication);
            }
        } finally {
            read.unlock();
        }

        synchronized (lock) {
            if (this.expectedNumberOfClientsSendingRenews > 0) {
                // Since the client wants to cancel it, reduce the number of clients to send renews.
                this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews - 1;
                updateRenewsPerMinThreshold();
            }
        }

        return true;
    }

    /**
     * Marks the given instance of the given app name as renewed, and also marks whether it originated from
     * replication.
     *
     * @see com.netflix.eureka.lease.LeaseManager#renew(java.lang.String, java.lang.String, boolean)
     */
    /**
     * 1、是增加续约计数，Eureka监控用的，然后根据续约的服务名在本地注册表registry（ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry）获取服务的Lease租约对象，
     * 然后根据租约对象获取InstanceInfo服务注册实例
     * 2、如果InstanceInfo为空，说明服务可能被删除，增加续约失败计数后，返回false
     * 3、如果当前实例的状态和overriddenInstanceStatus状态不一致，就修改实例状态为overriddenInstanceStatus
     * 4、增加最后更新计数，然后调用 Lease#renew方法修改lastUpdateTimestamp，即最后续约时间更新，最后续约时间更新了，Eureka Server就知道服务实例续约成功，否则到了最后续约失效时间服务端会考虑剔除服务
     * @param appName   appName应用名
     * @param id    id实例ID
     * @param isReplication isReplication是否是复制
     * @return
     */
    public boolean renew(String appName, String id, boolean isReplication) {
        //增加给定统计信息的计数器，EurekaMonitors.counter加一，用作Eureka监控
        RENEW.increment(isReplication);
        //从服务端维护的服务实例注册表中获取到当前续约服务名对应的服务信息
        Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
        Lease<InstanceInfo> leaseToRenew = null;
        if (gMap != null) {
            //获取服务的租约对象
            leaseToRenew = gMap.get(id);
        }
        //如果没找到服务，返回false
        if (leaseToRenew == null) {
            //续约服务没找到的计数器增加，则重新注册续约
            RENEW_NOT_FOUND.increment(isReplication);
            logger.warn("DS: Registry: lease doesn't exist, registering resource: {} - {}", appName, id);
            return false;
        } else {
            //根据租约对象获取到服务的实例信息对象InstanceInfo
            InstanceInfo instanceInfo = leaseToRenew.getHolder();
            //如果服务实例信息不为空
            if (instanceInfo != null) {
                // touchASGCache(instanceInfo.getASGName());
                //获取服务的状态
                InstanceStatus overriddenInstanceStatus = this.getOverriddenInstanceStatus(
                        instanceInfo, leaseToRenew, isReplication);
                //看是否处于宕机状态，如果服务的状态是UNKNOWN，说明服务可能被删除了，需要重新注册，返回false
                if (overriddenInstanceStatus == InstanceStatus.UNKNOWN) {
                    logger.info("Instance status UNKNOWN possibly due to deleted override for instance {}"
                            + "; re-register required", instanceInfo.getId());
                    //续约服务没找到的计数器增加
                    RENEW_NOT_FOUND.increment(isReplication);
                    return false;
                }
                //实例状态与实例的OverriddenStatus覆盖实例状态不同。因此将状态设置为OverriddenStatus状态
                if (!instanceInfo.getStatus().equals(overriddenInstanceStatus)) {
                    logger.info(
                            "The instance status {} is different from overridden instance status {} for instance {}. "
                                    + "Hence setting the status to overridden status", instanceInfo.getStatus().name(),
                                    overriddenInstanceStatus.name(),
                                    instanceInfo.getId());
                    //修改状态为overriddenInstanceStatus，
                    instanceInfo.setStatusWithoutDirty(overriddenInstanceStatus);

                }
            }
            //增加最后更新计数
            renewsLastMin.increment();
            // 更新续约有效期，执行续约，com.netflix.eureka.lease.Lease#renew
            leaseToRenew.renew();
            //续约成功
            return true;
        }
    }

    /**
     * @deprecated this is expensive, try not to use. See if you can use
     * {@link #storeOverriddenStatusIfRequired(String, String, InstanceStatus)} instead.
     *
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     *
     * @param id the unique identifier of the instance.
     * @param overriddenStatus Overridden status if any.
     */
    @Deprecated
    @Override
    public void storeOverriddenStatusIfRequired(String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null)
                || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got restarted -this will help us maintain
            // the overridden state from the replica
            logger.info(
                    "Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            List<InstanceInfo> instanceInfo = this.getInstancesById(id, false);
            if ((instanceInfo != null) && (!instanceInfo.isEmpty())) {
                instanceInfo.iterator().next().setOverriddenStatus(overriddenStatus);
                logger.info(
                        "Setting the overridden status for instance id {} and the value is {} ",
                        id, overriddenStatus.name());

            }
        }
    }

    /**
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     *
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param overriddenStatus overridden status if any.
     */
    @Override
    public void storeOverriddenStatusIfRequired(String appName, String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null) || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got
            // restarted -this will help us maintain the overridden state
            // from the replica
            logger.info("Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            InstanceInfo instanceInfo = this.getInstanceByAppAndId(appName, id, false);
            instanceInfo.setOverriddenStatus(overriddenStatus);
            logger.info("Set the overridden status for instance (appname:{}, id:{}} and the value is {} ",
                    appName, id, overriddenStatus.name());
        }
    }

    /**
     * Updates the status of an instance. Normally happens to put an instance
     * between {@link InstanceStatus#OUT_OF_SERVICE} and
     * {@link InstanceStatus#UP} to put the instance in and out of traffic.
     *
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param newStatus the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean statusUpdate(String appName, String id,
                                InstanceStatus newStatus, String lastDirtyTimestamp,
                                boolean isReplication) {
        read.lock();
        try {
            STATUS_UPDATE.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();
                InstanceInfo info = lease.getHolder();
                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }
                if ((info != null) && !(info.getStatus().equals(newStatus))) {
                    // Mark service as UP if needed
                    if (InstanceStatus.UP.equals(newStatus)) {
                        lease.serviceUp();
                    }
                    // This is NAC overridden status
                    overriddenInstanceStatusMap.put(id, newStatus);
                    // Set it for transfer of overridden status to replica on
                    // replica start up
                    info.setOverriddenStatus(newStatus);
                    long replicaDirtyTimestamp = 0;
                    info.setStatusWithoutDirty(newStatus);
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.parseLong(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Removes status override for a give instance.
     *
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param newStatus the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {
        read.lock();
        try {
            STATUS_OVERRIDE_DELETE.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();
                InstanceInfo info = lease.getHolder();

                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }

                InstanceStatus currentOverride = overriddenInstanceStatusMap.remove(id);
                if (currentOverride != null && info != null) {
                    info.setOverriddenStatus(InstanceStatus.UNKNOWN);
                    info.setStatusWithoutDirty(newStatus);
                    long replicaDirtyTimestamp = 0;
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.parseLong(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Evicts everything in the instance registry that has expired, if expiry is enabled.
     *
     * @see com.netflix.eureka.lease.LeaseManager#evict()
     */
    /**
     * 执行服务的剔除逻辑
     * 1、判断是否开启过期驱逐
     * 2、获取到所有的过期的服务，通过Lease.isExpired判断过期
     * 3、计算一个驱逐极限值 ：min( 过期数 ,注册表服务数 - 注册表服务数 * 0.85(续约阈值百分比) ）
     * 4、如果驱逐极限值 > 0 ,那就从过期的服务中随机驱逐 “驱逐极限”个服务
     * 5、调用internalCancel方法消息服务
     */
    @Override
    public void evict() {
        evict(0l);
    }

    public void evict(long additionalLeaseMs) {
        logger.debug("Running the evict task");
        // 是否需要开启自我保护机制，如果需要，那么直接RETURE， 不需要继续往下执行了
        if (!isLeaseExpirationEnabled()) {
            //如果没启用租约到期，直接返回
            logger.debug("DS: lease expiration is currently disabled.");
            return;
        }
        //首先收集所有过期的服务，以随机顺序将其逐出
        // We collect first all expired items, to evict them in random order. For large eviction sets,
        // if we do not that, we might wipe out whole apps before self preservation kicks in. By randomizing it,
        // the impact should be evenly distributed across all applications.
        List<Lease<InstanceInfo>> expiredLeases = new ArrayList<>();
        //循环注册表中的所有的服务
        for (Entry<String, Map<String, Lease<InstanceInfo>>> groupEntry : registry.entrySet()) {
            Map<String, Lease<InstanceInfo>> leaseMap = groupEntry.getValue();
            if (leaseMap != null) {
                //获取到租约
                for (Entry<String, Lease<InstanceInfo>> leaseEntry : leaseMap.entrySet()) {
                    Lease<InstanceInfo> lease = leaseEntry.getValue();
                    //如果服务过期，就把服务添加到expiredLeases map中
                    if (lease.isExpired(additionalLeaseMs) && lease.getHolder() != null) {
                        expiredLeases.add(lease);
                    }
                }
            }
        }

        //为了补偿GC暂停或本地时间差异导致的剔除任务执行时间差异，使用当前注册表大小作为触发自我保存的基础
        //否则，将清除完整的注册表。
        // To compensate for GC pauses or drifting local time, we need to use current registry size as a base for
        // triggering self-preservation. Without that we would wipe out full registry.
        //注册表大小
        int registrySize = (int) getLocalRegistrySize();
        //注册表中服务的续约阈值 = 注册大小 * 0.85
        int registrySizeThreshold = (int) (registrySize * serverConfig.getRenewalPercentThreshold());
        //驱逐极限 = 注册表大小 - 注册表续约阈值
        int evictionLimit = registrySize - registrySizeThreshold;
        //过期的服务数 和 evictionLimit  取最小 ，如果大于 0 说明需要有服务要剔除
        int toEvict = Math.min(expiredLeases.size(), evictionLimit);
        if (toEvict > 0) {
            //剔除 toEvict 个
            logger.info("Evicting {} items (expired={}, evictionLimit={})", toEvict, expiredLeases.size(), evictionLimit);
            //取随机值
            Random random = new Random(System.currentTimeMillis());
            for (int i = 0; i < toEvict; i++) {
                //选择一个随机项目（Knuth随机算法），随机剔除
                // Pick a random item (Knuth shuffle algorithm)
                int next = i + random.nextInt(expiredLeases.size() - i);
                Collections.swap(expiredLeases, i, next);
                //获取剔除服务的Lease
                Lease<InstanceInfo> lease = expiredLeases.get(i);
                //应用名
                String appName = lease.getHolder().getAppName();
                //实例ID
                String id = lease.getHolder().getId();
                //expired Counter 过期计数增加
                EXPIRED.increment();
                logger.warn("DS: Registry: expired lease for {}/{}", appName, id);
                internalCancel(appName, id, false);
            }
        }
    }


    /**
     * Returns the given app that is in this instance only, falling back to other regions transparently only
     * if specified in this client configuration.
     *
     * @param appName the application name of the application
     * @return the application
     *
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        return this.getApplication(appName, !disableTransparentFallback);
    }

    /**
     * Get application information.
     *
     * @param appName The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     */
    @Override
    public Application getApplication(String appName, boolean includeRemoteRegion) {
        Application app = null;

        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);

        if (leaseMap != null && leaseMap.size() > 0) {
            for (Entry<String, Lease<InstanceInfo>> entry : leaseMap.entrySet()) {
                if (app == null) {
                    app = new Application(appName);
                }
                app.addInstance(decorateInstanceInfo(entry.getValue()));
            }
        } else if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application;
                }
            }
        }
        return app;
    }

    /**
     * Get all applications in this instance registry, falling back to other regions if allowed in the Eureka config.
     *
     * @return the list of all known applications
     *
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    public Applications getApplications() {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        if (disableTransparentFallback) {
            return getApplicationsFromLocalRegionOnly();
        } else {
            return getApplicationsFromAllRemoteRegions();  // Behavior of falling back to remote region can be disabled.
        }
    }

    /**
     * Returns applications including instances from all remote regions. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with a <code>null</code> argument.
     */
    public Applications getApplicationsFromAllRemoteRegions() {
        return getApplicationsFromMultipleRegions(allKnownRemoteRegions);
    }

    /**
     * Returns applications including instances from local region only. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with an empty array.
     */
    @Override
    public Applications getApplicationsFromLocalRegionOnly() {
        return getApplicationsFromMultipleRegions(EMPTY_STR_ARRAY);
    }

    /**
     * This method will return applications with instances from all passed remote regions as well as the current region.
     * Thus, this gives a union view of instances from multiple regions. <br/>
     * The application instances for which this union will be done can be restricted to the names returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for every region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     * If you are not selectively requesting for a remote region, use {@link #getApplicationsFromAllRemoteRegions()}
     * or {@link #getApplicationsFromLocalRegionOnly()}
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> or empty no remote regions are
     *                      included.
     *
     * @return The applications with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be only for certain whitelisted apps as explained above.
     */
    public Applications getApplicationsFromMultipleRegions(String[] remoteRegions) {

        boolean includeRemoteRegion = null != remoteRegions && remoteRegions.length != 0;

        logger.debug("Fetching applications registry with remote regions: {}, Regions argument {}",
                includeRemoteRegion, remoteRegions);

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS.increment();
        } else {
            GET_ALL_CACHE_MISS.increment();
        }
        Applications apps = new Applications();
        apps.setVersion(1L);
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {
                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();
                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }
                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }
        if (includeRemoteRegion) {
            for (String remoteRegion : remoteRegions) {
                RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                if (null != remoteRegistry) {
                    Applications remoteApps = remoteRegistry.getApplications();
                    for (Application application : remoteApps.getRegisteredApplications()) {
                        if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                            logger.info("Application {}  fetched from the remote region {}",
                                    application.getName(), remoteRegion);

                            Application appInstanceTillNow = apps.getRegisteredApplications(application.getName());
                            if (appInstanceTillNow == null) {
                                appInstanceTillNow = new Application(application.getName());
                                apps.addApplication(appInstanceTillNow);
                            }
                            for (InstanceInfo instanceInfo : application.getInstances()) {
                                appInstanceTillNow.addInstance(instanceInfo);
                            }
                        } else {
                            logger.debug("Application {} not fetched from the remote region {} as there exists a "
                                            + "whitelist and this app is not in the whitelist.",
                                    application.getName(), remoteRegion);
                        }
                    }
                } else {
                    logger.warn("No remote registry available for the remote region {}", remoteRegion);
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    private boolean shouldFetchFromRemoteRegistry(String appName, String remoteRegion) {
        Set<String> whiteList = serverConfig.getRemoteRegionAppWhitelist(remoteRegion);
        if (null == whiteList) {
            whiteList = serverConfig.getRemoteRegionAppWhitelist(null); // see global whitelist.
        }
        return null == whiteList || whiteList.contains(appName);
    }

    /**
     * Get the registry information about all {@link Applications}.
     *
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return applications
     *
     * @deprecated Use {@link #getApplicationsFromMultipleRegions(String[])} instead. This method has a flawed behavior
     * of transparently falling back to a remote region if no instances for an app is available locally. The new
     * behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplications(boolean includeRemoteRegion) {
        GET_ALL_CACHE_MISS.increment();
        Applications apps = new Applications();
        apps.setVersion(1L);
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {

                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();

                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }

                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }
        if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Applications applications = remoteRegistry.getApplications();
                for (Application application : applications
                        .getRegisteredApplications()) {
                    Application appInLocalRegistry = apps
                            .getRegisteredApplications(application.getName());
                    if (appInLocalRegistry == null) {
                        apps.addApplication(application);
                    }
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    /**
     * Get the registry information about the delta changes. The deltas are
     * cached for a window specified by
     * {@link EurekaServerConfig#getRetentionTimeInMSInDeltaQueue()}. Subsequent
     * requests for delta information may return the same information and client
     * must make sure this does not adversely affect them.
     *
     * @return all application deltas.
     * @deprecated use {@link #getApplicationDeltasFromMultipleRegions(String[])} instead. This method has a
     * flawed behavior of transparently falling back to a remote region if no instances for an app is available locally.
     * The new behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplicationDeltas() {
        GET_ALL_CACHE_MISS_DELTA.increment();
        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDelta().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        write.lock();
        try {
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is : {}",
                    this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug(
                        "The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo
                        .getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();

            if (!disableTransparentFallback) {
                Applications allAppsInLocalRegion = getApplications(false);

                for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                    Applications applications = remoteRegistry.getApplicationDeltas();
                    for (Application application : applications.getRegisteredApplications()) {
                        Application appInLocalRegistry =
                                allAppsInLocalRegion.getRegisteredApplications(application.getName());
                        if (appInLocalRegistry == null) {
                            apps.addApplication(application);
                        }
                    }
                }
            }

            Applications allApps = getApplications(!disableTransparentFallback);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the application delta also including instances from the passed remote regions, with the instances from the
     * local region. <br/>
     *
     * The remote regions from where the instances will be chosen can further be restricted if this application does not
     * appear in the whitelist specified for the region as returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for a region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> all remote regions are included.
     *                      If empty list then no remote region is included.
     *
     * @return The delta with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be further be restricted as explained above. <code>null</code> if the application does
     * not exist locally or in remote regions.
     */
    public Applications getApplicationDeltasFromMultipleRegions(String[] remoteRegions) {
        if (null == remoteRegions) {
            remoteRegions = allKnownRemoteRegions; // null means all remote regions.
        }

        boolean includeRemoteRegion = remoteRegions.length != 0;

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS_DELTA.increment();
        } else {
            GET_ALL_CACHE_MISS_DELTA.increment();
        }

        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDeltaWithRegions().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        write.lock();
        try {
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is :{}", this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug("The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo.getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            if (includeRemoteRegion) {
                for (String remoteRegion : remoteRegions) {
                    RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                    if (null != remoteRegistry) {
                        Applications remoteAppsDelta = remoteRegistry.getApplicationDeltas();
                        if (null != remoteAppsDelta) {
                            for (Application application : remoteAppsDelta.getRegisteredApplications()) {
                                if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                                    Application appInstanceTillNow =
                                            apps.getRegisteredApplications(application.getName());
                                    if (appInstanceTillNow == null) {
                                        appInstanceTillNow = new Application(application.getName());
                                        apps.addApplication(appInstanceTillNow);
                                    }
                                    for (InstanceInfo instanceInfo : application.getInstances()) {
                                        appInstanceTillNow.addInstance(new InstanceInfo(instanceInfo));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Applications allApps = getApplicationsFromMultipleRegions(remoteRegions);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id) {
        return this.getInstanceByAppAndId(appName, id, true);
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id, boolean includeRemoteRegions) {
        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);
        Lease<InstanceInfo> lease = null;
        if (leaseMap != null) {
            lease = leaseMap.get(id);
        }
        if (lease != null
                && (!isLeaseExpirationEnabled() || !lease.isExpired())) {
            return decorateInstanceInfo(lease);
        } else if (includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application.getByInstanceId(id);
                }
            }
        }
        return null;
    }

    /**
     * @deprecated Try {@link #getInstanceByAppAndId(String, String)} instead.
     *
     * Get all instances by ID, including automatically asking other regions if the ID is unknown.
     *
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(String)
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id) {
        return this.getInstancesById(id, true);
    }

    /**
     * @deprecated Try {@link #getInstanceByAppAndId(String, String, boolean)} instead.
     *
     * Get the list of instances by its unique id.
     *
     * @param id the unique id of the instance
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return list of InstanceInfo objects.
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id, boolean includeRemoteRegions) {
        List<InstanceInfo> list = new ArrayList<>();

        for (Iterator<Entry<String, Map<String, Lease<InstanceInfo>>>> iter =
                     registry.entrySet().iterator(); iter.hasNext(); ) {

            Map<String, Lease<InstanceInfo>> leaseMap = iter.next().getValue();
            if (leaseMap != null) {
                Lease<InstanceInfo> lease = leaseMap.get(id);

                if (lease == null || (isLeaseExpirationEnabled() && lease.isExpired())) {
                    continue;
                }

                if (list == Collections.EMPTY_LIST) {
                    list = new ArrayList<>();
                }
                list.add(decorateInstanceInfo(lease));
            }
        }
        if (list.isEmpty() && includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                for (Application application : remoteRegistry.getApplications()
                        .getRegisteredApplications()) {
                    InstanceInfo instanceInfo = application.getByInstanceId(id);
                    if (instanceInfo != null) {
                        list.add(instanceInfo);
                        return list;
                    }
                }
            }
        }
        return list;
    }

    private InstanceInfo decorateInstanceInfo(Lease<InstanceInfo> lease) {
        InstanceInfo info = lease.getHolder();

        // client app settings
        int renewalInterval = LeaseInfo.DEFAULT_LEASE_RENEWAL_INTERVAL;
        int leaseDuration = LeaseInfo.DEFAULT_LEASE_DURATION;

        // TODO: clean this up
        if (info.getLeaseInfo() != null) {
            renewalInterval = info.getLeaseInfo().getRenewalIntervalInSecs();
            leaseDuration = info.getLeaseInfo().getDurationInSecs();
        }

        info.setLeaseInfo(LeaseInfo.Builder.newBuilder()
                .setRegistrationTimestamp(lease.getRegistrationTimestamp())
                .setRenewalTimestamp(lease.getLastRenewalTimestamp())
                .setServiceUpTimestamp(lease.getServiceUpTimestamp())
                .setRenewalIntervalInSecs(renewalInterval)
                .setDurationInSecs(leaseDuration)
                .setEvictionTimestamp(lease.getEvictionTimestamp()).build());

        info.setIsCoordinatingDiscoveryServer();
        return info;
    }

    /**
     * Servo route; do not call.
     *
     * @return servo data
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsInLastMin",
            description = "Number of total heartbeats received in the last minute", type = DataSourceType.GAUGE)
    @Override
    public long getNumOfRenewsInLastMin() {
        return renewsLastMin.getCount();
    }


    /**
     * Gets the threshold for the renewals per minute.
     *
     * @return the integer representing the threshold for the renewals per
     *         minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsPerMinThreshold", type = DataSourceType.GAUGE)
    @Override
    public int getNumOfRenewsPerMinThreshold() {
        return numberOfRenewsPerMinThreshold;
    }

    /**
     * Get the N instances that are most recently registered.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNRegisteredInstances() {
        List<Pair<Long, String>> list = new ArrayList<>(recentRegisteredQueue);
        Collections.reverse(list);
        return list;
    }

    /**
     * Get the N instances that have most recently canceled.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNCanceledInstances() {
        List<Pair<Long, String>> list = new ArrayList<>(recentCanceledQueue);
        Collections.reverse(list);
        return list;
    }

    private void invalidateCache(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress) {
        // invalidate cache
        responseCache.invalidate(appName, vipAddress, secureVipAddress);
    }

    protected void updateRenewsPerMinThreshold() {
        //修改numberOfRenewsPerMinThreshold每分钟续约阀值 = 2 *（85%），
        //RenewalPercentThreshold是获取续订阈值百分比
        this.numberOfRenewsPerMinThreshold = (int) (this.expectedNumberOfClientsSendingRenews
                * (60.0 / serverConfig.getExpectedClientRenewalIntervalSeconds())
                * serverConfig.getRenewalPercentThreshold());
    }

    private static final class RecentlyChangedItem {
        private long lastUpdateTime;
        private Lease<InstanceInfo> leaseInfo;

        public RecentlyChangedItem(Lease<InstanceInfo> lease) {
            this.leaseInfo = lease;
            lastUpdateTime = System.currentTimeMillis();
        }

        public long getLastUpdateTime() {
            return this.lastUpdateTime;
        }

        public Lease<InstanceInfo> getLeaseInfo() {
            return this.leaseInfo;
        }
    }

    //初始化服务剔除的定时任务
    protected void postInit() {
        renewsLastMin.start();
        if (evictionTaskRef.get() != null) {
            //如果服务剔除任务不为空，就执行cancel方法，该方法把任务的状态修改为了cancel任务取消
            evictionTaskRef.get().cancel();
        }
        // //创建新的服务剔除任务
        evictionTaskRef.set(new EvictionTask());
        //交给调度器去执行，延迟60s,每60s执行一次驱逐任务
        evictionTimer.schedule(evictionTaskRef.get(),
                serverConfig.getEvictionIntervalTimerInMs(),    //60s 逐出间隔计时器
                serverConfig.getEvictionIntervalTimerInMs());
    }

    /**
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        deltaRetentionTimer.cancel();
        evictionTimer.cancel();
        renewsLastMin.stop();
        responseCache.stop();
    }

    @com.netflix.servo.annotations.Monitor(name = "numOfElementsinInstanceCache", description = "Number of overrides in the instance Cache", type = DataSourceType.GAUGE)
    public long getNumberofElementsininstanceCache() {
        return overriddenInstanceStatusMap.size();
    }

    /* visible for testing */ class EvictionTask extends TimerTask {

        private final AtomicLong lastExecutionNanosRef = new AtomicLong(0l);

        @Override
        public void run() {
            try {
                //计算了任务执行的时间偏差即补偿时间
                long compensationTimeMs = getCompensationTimeMs();
                logger.info("Running the evict task with compensationTime {}ms", compensationTimeMs);
                evict(compensationTimeMs);
            } catch (Throwable e) {
                logger.error("Could not run the evict task", e);
            }
        }

        /**
         * compute a compensation time defined as the actual time this task was executed since the prev iteration,
         * vs the configured amount of time for execution. This is useful for cases where changes in time (due to
         * clock skew or gc for example) causes the actual eviction task to execute later than the desired time
         * according to the configured cycle.
         */
        long getCompensationTimeMs() {
            long currNanos = getCurrentTimeNano();
            long lastNanos = lastExecutionNanosRef.getAndSet(currNanos);
            if (lastNanos == 0l) {
                return 0l;
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(currNanos - lastNanos);
            long compensationTime = elapsedMs - serverConfig.getEvictionIntervalTimerInMs();
            return compensationTime <= 0l ? 0l : compensationTime;
        }

        long getCurrentTimeNano() {  // for testing
            return System.nanoTime();
        }

    }

    /* visible for testing */ static class CircularQueue<E> extends AbstractQueue<E> {

        private final ArrayBlockingQueue<E> delegate;
        private final int capacity;

        public CircularQueue(int capacity) {
            this.capacity = capacity;
            this.delegate = new ArrayBlockingQueue<>(capacity);
        }

        @Override
        public Iterator<E> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean offer(E e) {
            while (!delegate.offer(e)) {
                delegate.poll();
            }
            return true;
        }

        @Override
        public E poll() {
            return delegate.poll();
        }

        @Override
        public E peek() {
            return delegate.peek();
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Object[] toArray() {
            return delegate.toArray();
        }
    }

    /**
     * @return The rule that will process the instance status override.
     */
    protected abstract InstanceStatusOverrideRule getInstanceInfoOverrideRule();

    protected InstanceInfo.InstanceStatus getOverriddenInstanceStatus(InstanceInfo r,
                                                                    Lease<InstanceInfo> existingLease,
                                                                    boolean isReplication) {
        InstanceStatusOverrideRule rule = getInstanceInfoOverrideRule();
        logger.debug("Processing override status using rule: {}", rule);
        return rule.apply(r, existingLease, isReplication).status();
    }

    private TimerTask getDeltaRetentionTask() {
        return new TimerTask() {

            @Override
            public void run() {
                Iterator<RecentlyChangedItem> it = recentlyChangedQueue.iterator();
                while (it.hasNext()) {
                    if (it.next().getLastUpdateTime() <
                            System.currentTimeMillis() - serverConfig.getRetentionTimeInMSInDeltaQueue()) {
                        it.remove();
                    } else {
                        break;
                    }
                }
            }

        };
    }
}
