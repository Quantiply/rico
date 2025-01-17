/*
 * Copyright 2014-2015 Quantiply Corporation. All rights reserved.
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
package com.quantiply.samza.metrics;

import com.codahale.metrics.Histogram;

import java.util.function.Function;

/**
 * Common metrics for event streams
 */
public class EventStreamMetrics {
    public final static long DEFAULT_WINDOW_DURATION_MS = 60000L;
    public final Histogram lagFromOriginMs;
    public final Histogram lagFromPreviousMs;
    public final WindowedMapGauge<Long> maxLagMsBySource;

    public EventStreamMetrics(StreamMetricRegistry registry) {
        //Setting 60s as window duration to match snapshot reporter
        this(registry, name -> new WindowedMapGauge<>(name, DEFAULT_WINDOW_DURATION_MS, Long::max));
    }

    public EventStreamMetrics(StreamMetricRegistry registry, int maxLagLimit) {
        //Setting 60s as window duration to match snapshot reporter
        this(registry, name -> new TopNWindowedMapGauge<Long>(name, DEFAULT_WINDOW_DURATION_MS, Long::max, maxLagLimit));
    }

    public EventStreamMetrics(StreamMetricRegistry registry, Function<String,WindowedMapGauge<Long>> gaugeFactory) {
        lagFromOriginMs = registry.histogram("lag-from-origin-ms");
        lagFromPreviousMs = registry.histogram("lag-from-previous-step-ms");
        maxLagMsBySource = registry.samzaGauge("max-lag-by-origin-ms", gaugeFactory);
    }
}
