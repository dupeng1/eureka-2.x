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

package com.netflix.eureka.lease;

import com.netflix.eureka.registry.AbstractInstanceRegistry;

/**
 * This class is responsible for creating/renewing and evicting a <em>lease</em>
 * for a particular instance.
 *
 * <p>
 * Leases determine what instances receive traffic. When there is no renewal
 * request from the client, the lease gets expired and the instances are evicted
 * out of {@link AbstractInstanceRegistry}. This is key to instances receiving traffic
 * or not.
 * <p>
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 * @param <T>
 */
/**
 * 实例租约管理器接口，根据租约更新的动作类型操作实例的租约信息，提供服务实例基于时间可控性的管理（租约管理）<br>
 * 1、register ： 服务注册进来设置租约的serviceUp<br>
 * 2、cancel : 服务下线，设置实例租约的cancel<br>
 * 3、renew ： 服务报活（心跳检测）设置租约的renew<br>
 * 4、evict : 遍历注册表通过实例租约isExpired方法校验是否过期，过期强制服务下线操作<br>
 * 其中 1-3 是Eureka 客户端发起操作，4,为服务端定时任务轮询服务注册表，主动剔除过期实例。<br>
 * LeaseManager默认实现类 AbstractInstanceRegistry<br>
 * @param <T>
 */
public interface LeaseManager<T> {

    /**
     * Assign a new {@link Lease} to the passed in {@link T}.
     *
     * @param r
     *            - T to register
     * @param leaseDuration
     * @param isReplication
     *            - whether this is a replicated entry from another eureka node.
     */
    //注册实例，更新实例租约信息
    void register(T r, int leaseDuration, boolean isReplication);

    /**
     * Cancel the {@link Lease} associated w/ the passed in <code>appName</code>
     * and <code>id</code>.
     *
     * @param appName
     *            - unique id of the application.
     * @param id
     *            - unique id within appName.
     * @param isReplication
     *            - whether this is a replicated entry from another eureka node.
     * @return true, if the operation was successful, false otherwise.
     */
    //剔除实例时，更新实例租约信息
    boolean cancel(String appName, String id, boolean isReplication);

    /**
     * Renew the {@link Lease} associated w/ the passed in <code>appName</code>
     * and <code>id</code>.
     *
     * @param id
     *            - unique id within appName
     * @param isReplication
     *            - whether this is a replicated entry from another ds node
     * @return whether the operation of successful
     */
    //刷新实例时更新实例租约信息
    boolean renew(String appName, String id, boolean isReplication);

    /**
     * Evict {@link T}s with expired {@link Lease}(s).
     */
    //剔除已过期的租约实例信息
    void evict();
}
