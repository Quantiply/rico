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
package com.quantiply.samza.task;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.quantiply.samza.MetricAdaptor;
import com.quantiply.samza.serde.AvroSerde;
import com.quantiply.samza.serde.AvroSerdeFactory;
import com.quantiply.samza.EventStreamMetrics;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.apache.samza.config.Config;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.MessageCollector;
import org.apache.samza.task.TaskContext;
import org.apache.samza.task.TaskCoordinator;

/**

  Converts Avro to JSON

  This task expects:
  - the input topic key to be serialized according the Camus/Confluent Platform spec
  - the input and output topics are configured to use ByteSerde
  - the output topic is specified with the "streams.out" property

  Caveats:
   - It currently assumes lowercase with underscore for naming JSON fields.  This could be configurable later

 */
public class AvroToJSONTask extends BaseTask {
    private AvroSerde avroSerde;
    private SystemStream outStream;
    private final ObjectMapper objMapper;

    @JsonIgnoreType
    abstract class IgnoreTypeMixIn { }

    {
        objMapper = new ObjectMapper();
        //Do not serialize Avro schemas
        objMapper.addMixInAnnotations(Schema.class, IgnoreTypeMixIn.class);
        //Use only public getters
        objMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        objMapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.PUBLIC_ONLY);
        //Use lower case with underscores for JSON field names
        objMapper.setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES);
    }

    @Override
    protected void _init(Config config, TaskContext context, MetricAdaptor metricAdaptor) throws Exception {
        registerDefaultHandler(this::processMsg, EventStreamMetrics::new);
        avroSerde = new AvroSerdeFactory().getSerde("avro", config);
        outStream = getSystemStream("out");
    }

    protected void processMsg(IncomingMessageEnvelope envelope, MessageCollector collector, TaskCoordinator coordinator, EventStreamMetrics metrics) throws Exception {
        SpecificRecord inMsg = (SpecificRecord) avroSerde.fromBytes((byte[]) envelope.getMessage());
        updateLagMetricsForCamusRecord(inMsg, System.currentTimeMillis(), metrics);
        byte[] outMsg = objMapper.writeValueAsBytes(inMsg);
        OutgoingMessageEnvelope outEnv;
        if (envelope.getKey() == null) {
            outEnv = new OutgoingMessageEnvelope(outStream, outMsg);
        }
        else {
            outEnv = new OutgoingMessageEnvelope(outStream, outMsg,
                    envelope.getSystemStreamPartition().getPartition().getPartitionId(),
                    envelope.getKey());
        }
        collector.send(outEnv);
    }

}
