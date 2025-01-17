/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.quantiply.samza.system.elasticsearch;

import com.quantiply.elasticsearch.HTTPBulkLoader;
import io.searchbox.client.JestClient;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Creates {@link HTTPBulkLoader} instances based on properties from the Samza job.
 */
public class HTTPBulkLoaderFactory {
  private final ElasticsearchConfig config;

  public HTTPBulkLoaderFactory(ElasticsearchConfig config) {
    this.config = config;
  }

  public HTTPBulkLoader getBulkLoader(String systemName, JestClient client, Consumer<HTTPBulkLoader.BulkReport> onFlush) {
    HTTPBulkLoader.Config loaderConf = new HTTPBulkLoader.Config(
        systemName, config.getBulkFlushMaxActions(),
        config.getBulkFlushIntervalMS()
    );
    return new HTTPBulkLoader(loaderConf, client, Optional.of(onFlush));
  }
}
