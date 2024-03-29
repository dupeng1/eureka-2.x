/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.providers;

import java.util.List;

import com.netflix.config.ConfigurationManager;
import com.netflix.discovery.CommonConstants;
import com.netflix.discovery.DefaultEurekaClientConfig;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * @author Tomasz Bak
 */
public class DefaultEurekaClientConfigEurekaServerServiceUrlsTest {

    private static final String SERVICE_URI = "http://my.eureka.server:8080/";

    @Test
    public void testURLSeparator() throws Exception {
        testURLSeparator(",");
        testURLSeparator(" ,");
        testURLSeparator(", ");
        testURLSeparator(" , ");
        testURLSeparator(" ,  ");
    }

    private void testURLSeparator(String separator) {
        ConfigurationManager.getConfigInstance().setProperty(CommonConstants.DEFAULT_CONFIG_NAMESPACE + ".serviceUrl.default", SERVICE_URI + separator + SERVICE_URI);

        DefaultEurekaClientConfig clientConfig = new DefaultEurekaClientConfig();

        List<String> serviceUrls = clientConfig.getEurekaServerServiceUrls("default");
        assertThat(serviceUrls.get(0), is(equalTo(SERVICE_URI)));
        assertThat(serviceUrls.get(1), is(equalTo(SERVICE_URI)));
    }
}