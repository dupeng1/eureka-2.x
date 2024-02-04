package com.netflix.appinfo;

/**
 * This provides a more granular healthcheck contract than the existing {@link HealthCheckCallback}
 *
 * @author Nitesh Kant
 */
/**
 * 检测当前client的状态，如果Client的状态发生改变，将会触发新的注册事件，更新Eureka Server的注册表中该服务实例的相关信息。
 * 其在spring-cloud-netflix-eurekaClient中的实现类为EurekaHealthCheckHandler，主要组合了spring-boot-actuator中的HealthAggregator和Healthlndicator，
 * 以实现对Spring Boot 应用的状态检测
 */
public interface HealthCheckHandler {

    InstanceInfo.InstanceStatus getStatus(InstanceInfo.InstanceStatus currentStatus);

}
