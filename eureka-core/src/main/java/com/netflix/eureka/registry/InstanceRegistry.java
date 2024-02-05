package com.netflix.eureka.registry;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.LookupService;
import com.netflix.discovery.shared.Pair;
import com.netflix.eureka.lease.LeaseManager;

import java.util.List;
import java.util.Map;

/**
 * @author Tomasz Bak
 */
//提供应用实例的注册与发现服务
public interface InstanceRegistry extends LeaseManager<InstanceInfo>, LookupService<String> {
    //允许开始传输数据
    void openForTraffic(ApplicationInfoManager applicationInfoManager, int count);
    //关闭
    void shutdown();
    //存储实例覆盖状态：使用的是InstanceInfo.InstanceStatus overriddenStatus 覆盖状态，
    //使用该状态来修改注册中心服务的注册状态
    @Deprecated
    void storeOverriddenStatusIfRequired(String id, InstanceStatus overriddenStatus);

    void storeOverriddenStatusIfRequired(String appName, String id, InstanceStatus overriddenStatus);
    //更新服务注册状态
    boolean statusUpdate(String appName, String id, InstanceStatus newStatus,
                         String lastDirtyTimestamp, boolean isReplication);
    //删除覆盖的状态
    boolean deleteStatusOverride(String appName, String id, InstanceStatus newStatus,
                                 String lastDirtyTimestamp, boolean isReplication);
    //服务状态快照
    Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot();
    //获取本地服务注册表，从本地ConcurrentHashMap缓存的服务注册表中获取
    Applications getApplicationsFromLocalRegionOnly();
    //获取服务注册表
    List<Application> getSortedApplications();

    /**
     * Get application information.
     *
     * @param appName The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link java.net.URL} by this property
     *                            {@link com.netflix.eureka.EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     */
    //根据名字获取服务
    Application getApplication(String appName, boolean includeRemoteRegion);

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @return the information about the instance.
     */
    //根据名字和id获取实例信息
    InstanceInfo getInstanceByAppAndId(String appName, String id);

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id the unique identifier of the instance.
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link java.net.URL} by this property
     *                             {@link com.netflix.eureka.EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the information about the instance.
     */
    //根据名字和id获取实例信息
    InstanceInfo getInstanceByAppAndId(String appName, String id, boolean includeRemoteRegions);
    //完全清除注册表
    void clearRegistry();
    //初始化的响应缓存
    void initializedResponseCache();
    //获取响应缓存
    ResponseCache getResponseCache();
    //最后一分钟续约次数，用作自我保护计算值
    long getNumOfRenewsInLastMin();
    //获取每分钟续约次数，用作自我保护计算值
    int getNumOfRenewsPerMinThreshold();
    //检查续订次数是否小于阈值。
    int isBelowRenewThresold();
    //最近注册的实例
    List<Pair<Long, String>> getLastNRegisteredInstances();
    //最近取消的实例
    List<Pair<Long, String>> getLastNCanceledInstances();

    /**
     * Checks whether lease expiration is enabled.
     * @return true if enabled
     */
    //最近过期的实例
    boolean isLeaseExpirationEnabled();
    //是否开启自我保护
    boolean isSelfPreservationModeEnabled();

}
