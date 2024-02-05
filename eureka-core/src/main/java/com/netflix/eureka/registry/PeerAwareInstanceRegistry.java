/*
 * Copyright 2015 Netflix, Inc.
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

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.resources.ASGResource;

import java.util.List;

/**
 * @author Tomasz Bak
 */

/**
 * 应用对象注册表接口，“对等感知实例注册表”，实现了 Eureka-Server 集群内注册信息同步功能
 */
public interface PeerAwareInstanceRegistry extends InstanceRegistry {
    //初始化PeerEurekaNodes集群节点
    void init(PeerEurekaNodes peerEurekaNodes) throws Exception;

    /**
     * Populates the registry information from a peer eureka node. This
     * operation fails over to other nodes until the list is exhausted if the
     * communication fails.
     */
    //注册表信息同步，如果节点之间通信失败，列表中耗尽该操作故障转移到其他节点
    int syncUp();

    /**
     * Checks to see if the registry access is allowed or the server is in a
     * situation where it does not all getting registry information. The server
     * does not return registry information for a period specified in
     * {@link com.netflix.eureka.EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}, if it cannot
     * get the registry information from the peer eureka nodes at start up.
     *
     * @return false - if the instances count from a replica transfer returned
     *         zero and if the wait time has not elapsed, otherwise returns true
     */
    //检查是否有访问权限
     boolean shouldAllowAccess(boolean remoteRegionRequired);
    //注册InstanceInfo到其他Eureka节点
     void register(InstanceInfo info, boolean isReplication);
    //修改状态
     void statusUpdate(final String asgName, final ASGResource.ASGStatus newStatus, final boolean isReplication);
}
