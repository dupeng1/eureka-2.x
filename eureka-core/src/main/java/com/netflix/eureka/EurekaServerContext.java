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

package com.netflix.eureka;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;

/**
 * @author David Liu
 */
//Eureka服务端上下文对象，包含了初始化，关闭，获取服务配置，获取集群节点，获取服务注册器，获取服务信息管理器
public interface EurekaServerContext {
    //初始化
    void initialize() throws Exception;
    //关闭
    void shutdown() throws Exception;
    //获取服务配置
    EurekaServerConfig getServerConfig();
    //获取集群节点管理管理类
    PeerEurekaNodes getPeerEurekaNodes();
    //服务器编解码器
    ServerCodecs getServerCodecs();
    //服务注册器
    PeerAwareInstanceRegistry getRegistry();
    //instanceInfo实例信息管理器
    ApplicationInfoManager getApplicationInfoManager();

}
