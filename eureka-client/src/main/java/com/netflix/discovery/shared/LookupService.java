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
package com.netflix.discovery.shared;

import java.util.List;

import com.netflix.appinfo.InstanceInfo;

/**
 * Lookup service for finding active instances.
 *
 * @author Karthik Ranganathan, Greg Kim.
 * @param <T> for backward compatibility

 */
public interface LookupService<T> {

    /**
     * Returns the corresponding {@link Application} object which is basically a
     * container of all registered <code>appName</code> {@link InstanceInfo}s.
     *
     * @param appName
     * @return a {@link Application} or null if we couldn't locate any app of
     *         the requested appName
     */
    //根据appName查找对应的Application对象，该对象维护了一个服务实例列表，即Application对象维护了一个指定应用的服务实例列表的容器。
    //根据服务实例注册的appNarne来获取封装有相同appNarne 的服务实例信息容器
    Application getApplication(String appName);

    /**
     * Returns the {@link Applications} object which is basically a container of
     * all currently registered {@link Application}s.
     *
     * @return {@link Applications}
     */
    //包装了Eureka服务返回的全部注册信息，其中维护了一个Application对象的集合
    //返回当前注册表中所有的服务实例信息
    Applications getApplications();

    /**
     * Returns the {@link List} of {@link InstanceInfo}s matching the the passed
     * in id. A single {@link InstanceInfo} can possibly be registered w/ more
     * than one {@link Application}s
     *
     * @param id
     * @return {@link List} of {@link InstanceInfo}s or
     *         {@link java.util.Collections#emptyList()}
     */
    //根据实例Id，查询对应的服务实例列表
    //根据服务实例的id获取服务实例信息
    List<InstanceInfo> getInstancesById(String id);

    /**
     * Gets the next possible server to process the requests from the registry
     * information received from eureka.
     *
     * <p>
     * The next server is picked on a round-robin fashion. By default, this
     * method just returns the servers that are currently with
     * {@link com.netflix.appinfo.InstanceInfo.InstanceStatus#UP} status.
     * This configuration can be controlled by overriding the
     * {@link com.netflix.discovery.EurekaClientConfig#shouldFilterOnlyUpInstances()}.
     *
     * Note that in some cases (Eureka emergency mode situation), the instances
     * that are returned may not be unreachable, it is solely up to the client
     * at that point to timeout quickly and retry the next server.
     * </p>
     *
     * @param virtualHostname
     *            the virtual host name that is associated to the servers.
     * @param secure
     *            indicates whether this is a HTTP or a HTTPS request - secure
     *            means HTTPS.
     * @return the {@link InstanceInfo} information which contains the public
     *         host name of the next server in line to process the request based
     *         on the round-robin algorithm.
     * @throws java.lang.RuntimeException if the virtualHostname does not exist
     */
    //获取下一个用于处理请求的服务实例（只返回UP状态的服务实例，可以通过重写EurekaClientConfig#shouldFilterOnlyUpInstances()方法进行修改）
    InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure);
}
