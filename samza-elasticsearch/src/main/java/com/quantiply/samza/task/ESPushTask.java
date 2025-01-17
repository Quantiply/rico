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

import com.quantiply.avro.AvroToJson;
import com.quantiply.elasticsearch.HTTPBulkLoader;
import com.quantiply.rico.elasticsearch.Action;
import com.quantiply.rico.elasticsearch.ActionRequestKey;
import com.quantiply.rico.elasticsearch.VersionType;
import com.quantiply.samza.MetricAdaptor;
import com.quantiply.samza.serde.AvroSerde;
import com.quantiply.samza.serde.AvroSerdeFactory;
import com.quantiply.samza.serde.JsonSerde;
import com.quantiply.samza.serde.JsonSerdeFactory;
import org.apache.samza.SamzaException;
import org.apache.samza.config.Config;
import org.apache.samza.job.JobRunner;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.MessageCollector;
import org.apache.samza.task.TaskContext;
import org.apache.samza.task.TaskCoordinator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.InvalidParameterException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 Samza task for pushing to Elasticsearch
 - Requires byte serdes for message keys and values

 */
public class ESPushTask extends BaseTask {
    protected SystemStream esStream = new SystemStream(ESPushTaskConfig.CFS_ES_SYSTEM_NAME, ESPushTaskConfig.CFG_ES_STREAM_NAME);
    protected AvroSerde avroSerde;
    protected JsonSerde jsonSerde;
    protected final AvroToJson avroToJson = new AvroToJson();

    @Override
    protected void _init(Config config, TaskContext context, MetricAdaptor metricAdaptor) throws Exception {
        jsonSerde = new JsonSerdeFactory().getSerde("json", config);
        if (getErrorHandler().dropOnError()) {
            logger.warn("Task is configured to drop messages on error");
        }
        boolean isStreamConfig = ESPushTaskConfig.isStreamConfig(config);
        if (isStreamConfig) {
            ESPushTaskConfig.getStreamMap(config).forEach((stream, esIndexSpec) -> registerHandler(stream, getHandler(config, esIndexSpec)));
        }
        else {
            registerDefaultHandler(getHandler(config, ESPushTaskConfig.getDefaultConfig(config)));
        }
    }

    private Process getHandler(Config config, ESPushTaskConfig.ESIndexSpec esIndexSpec) {
        if (avroSerde == null && esIndexSpec.metadataSrc.equals(ESPushTaskConfig.MetadataSrc.KEY_AVRO)) {
            //Requires additional config for schema registry
            avroSerde = new AvroSerdeFactory().getSerde("avro", config);
        }
        BiFunction<IncomingMessageEnvelope, ESPushTaskConfig.ESIndexSpec, OutgoingMessageEnvelope> msgExtractor = getOutMsgExtractor(esIndexSpec);
        return (envelope, collector, coordinator) -> handleMsg(envelope, collector, coordinator, esIndexSpec, msgExtractor);
    }

    private void handleMsg(IncomingMessageEnvelope envelope, MessageCollector collector, TaskCoordinator coordinator, ESPushTaskConfig.ESIndexSpec spec, BiFunction<IncomingMessageEnvelope, ESPushTaskConfig.ESIndexSpec, OutgoingMessageEnvelope> msgExtractor) throws IOException {
        collector.send(msgExtractor.apply(envelope, spec));
    }

    private BiFunction<IncomingMessageEnvelope, ESPushTaskConfig.ESIndexSpec, OutgoingMessageEnvelope> getOutMsgExtractor(ESPushTaskConfig.ESIndexSpec spec) {
        BiFunction<IncomingMessageEnvelope, ESPushTaskConfig.ESIndexSpec, OutgoingMessageEnvelope> func = null;
        switch (spec.metadataSrc) {
            case KEY_DOC_ID:
                func = this::getSimpleOutMsg;
                break;
            case KEY_AVRO:
                func = this::getAvroKeyOutMsg;
                break;
            case KEY_JSON:
                func = this::getJsonKeyOutMsg;
                break;
            case EMBEDDED:
                func = this::getEmbeddedOutMsg;
                break;
        }
        return func;
    }

    protected OutgoingMessageEnvelope getSimpleOutMsg(IncomingMessageEnvelope envelope, ESPushTaskConfig.ESIndexSpec spec) {
        return getSimpleOutMsg(envelope, spec, Optional.empty());
    }

    protected OutgoingMessageEnvelope getSimpleOutMsg(IncomingMessageEnvelope envelope, ESPushTaskConfig.ESIndexSpec spec, Optional<Long> tsNowMsOpt) {
        long tsNowMs = tsNowMsOpt.orElse(System.currentTimeMillis());
        String document = getDocumentAsString(envelope);

        //Message key is used for the document id if set
        String id = null;
        if (envelope.getKey() != null) {
            id = new String((byte [])envelope.getKey(), StandardCharsets.UTF_8);
        }
        ActionRequestKey key = ActionRequestKey.newBuilder()
            .setId(id)
            .setAction(Action.INDEX)
            .build();
        return getOutMsg(envelope, key, spec, tsNowMs, document);
    }

    protected OutgoingMessageEnvelope getAvroKeyOutMsg(IncomingMessageEnvelope envelope, ESPushTaskConfig.ESIndexSpec spec) {
        return getAvroKeyOutMsg(envelope, spec, Optional.empty());
    }

    protected OutgoingMessageEnvelope getAvroKeyOutMsg(IncomingMessageEnvelope envelope, ESPushTaskConfig.ESIndexSpec spec, Optional<Long> tsNowMsOpt) {
        long tsNowMs = tsNowMsOpt.orElse(System.currentTimeMillis());
        String document = getDocumentAsString(envelope);
        ActionRequestKey key = (ActionRequestKey) avroSerde.fromBytes((byte[]) envelope.getKey());
        return getOutMsg(envelope, key, spec, tsNowMs, document);
    }

    protected OutgoingMessageEnvelope getJsonKeyOutMsg(IncomingMessageEnvelope envelope, ESPushTaskConfig.ESIndexSpec spec) {
        return getJsonKeyOutMsg(envelope, spec, Optional.empty());
    }

    protected OutgoingMessageEnvelope getJsonKeyOutMsg(IncomingMessageEnvelope envelope, ESPushTaskConfig.ESIndexSpec spec, Optional<Long> tsNowMsOpt) {
        long tsNowMs = tsNowMsOpt.orElse(System.currentTimeMillis());
        String document = getDocumentAsString(envelope);
        ActionRequestKey key;
        try {
            key = avroToJson.jsonToObject((byte[]) envelope.getKey(), ActionRequestKey.class);
        } catch (IOException e) {
            throw new SamzaException("Invalid JSON key input", e);
        }
        return getOutMsg(envelope, key, spec, tsNowMs, document);
    }

    protected OutgoingMessageEnvelope getEmbeddedOutMsg(IncomingMessageEnvelope envelope, ESPushTaskConfig.ESIndexSpec spec) {
        return getEmbeddedOutMsg(envelope, spec, Optional.empty());
    }

    protected OutgoingMessageEnvelope getEmbeddedOutMsg(IncomingMessageEnvelope envelope, ESPushTaskConfig.ESIndexSpec spec, Optional<Long> tsNowMsOpt) {
        long tsNowMs = tsNowMsOpt.orElse(System.currentTimeMillis());
        Map<String, Object> document = (Map<String, Object>)jsonSerde.fromBytes((byte[]) envelope.getMessage());
        ActionRequestKey.Builder keyBuilder = ActionRequestKey.newBuilder();
        keyBuilder.setAction(Action.INDEX);
        if (document.containsKey("_id") && document.get("_id") instanceof String) {
            keyBuilder.setId((String) document.get("_id"));
            document.remove("_id");
        }
        if (document.containsKey("_version") && document.get("_version") instanceof Number) {
            keyBuilder.setVersion(((Number) document.get("_version")).longValue());
            document.remove("_version");
        }
        if (document.containsKey("_version_type") && document.get("_version_type") instanceof String) {
            keyBuilder.setVersionType(VersionType.valueOf(((String) document.get("_version_type")).toUpperCase()));
            document.remove("_version_type");
        }
        if (document.containsKey("@timestamp") && document.get("@timestamp") instanceof Number) {
            long msgTs = ((Number) document.get("@timestamp")).longValue();
            keyBuilder.setPartitionTsUnixMs(msgTs);
            keyBuilder.setEventTsUnixMs(msgTs);
            document.remove("@timestamp");
        }
        ActionRequestKey key = keyBuilder.build();
        String docStr = jsonSerde.toString(document);
        return getOutMsg(envelope, key, spec, tsNowMs, getDocForUpdate(key, docStr));
    }

    private String getDocumentAsString(IncomingMessageEnvelope envelope) {
        String document = null;
        if (envelope.getMessage() != null) {
            document = new String((byte[]) envelope.getMessage(), StandardCharsets.UTF_8);
        }
        return document;
    }

    private OutgoingMessageEnvelope getOutMsg(IncomingMessageEnvelope envelope, ActionRequestKey key, ESPushTaskConfig.ESIndexSpec spec, long tsNowMs, String document) {
        validateInput(key, spec, envelope, tsNowMs, document);
        return new OutgoingMessageEnvelope(esStream, new HTTPBulkLoader.ActionRequest(key, getIndex(spec, key), spec.docType, tsNowMs, getDocForUpdate(key, document)));
    }

    private String getIndex(ESPushTaskConfig.ESIndexSpec spec, ActionRequestKey requestKey) {
        if (spec.indexNameDateFormat.isPresent()) {
            ZonedDateTime dateTime = Instant.ofEpochMilli(requestKey.getPartitionTsUnixMs()).atZone(spec.indexNameDateZone);
            //ES index names must be lowercase
            String dateStr = dateTime.format(DateTimeFormatter.ofPattern(spec.indexNameDateFormat.get())).toLowerCase();
            return spec.indexNamePrefix + dateStr;
        }
        return spec.indexNamePrefix;
    }

    private void validateInput(ActionRequestKey key, ESPushTaskConfig.ESIndexSpec spec, IncomingMessageEnvelope envelope, long tsNowMs, String document) {
        if (key.getAction() == null) {
            key.setAction(Action.INDEX);
        }
        if (key.getAction().equals(Action.INDEX)) {
            if (key.getId() == null) {
                key.setId(getMessageIdFromSource(envelope));
            }
            if (key.getPartitionTsUnixMs() == null && key.getAction() != Action.DELETE && key.getAction() != Action.UPDATE) {
                key.setPartitionTsUnixMs(tsNowMs);
            }
        }
        else if (key.getAction().equals(Action.UPDATE) || key.getAction().equals(Action.DELETE)) {
            if (key.getId() == null) {
                throw new InvalidParameterException("Document id is required for UPDATE and DELETE actions");
            }
            if (spec.indexNameDateFormat.isPresent() && key.getPartitionTsUnixMs() == null) {
                throw new InvalidParameterException("Partition timestamp is required for UPDATE and DELETE actions on time-partitioned indexes");
            }
        }
        if (key.getVersionType() == null) {
            key.setVersionType(spec.defaultVersionType.orElse(null));
        }
        if (!key.getAction().equals(Action.DELETE) && document == null) {
            throw new InvalidParameterException("Document must be provided for action: " + key.getAction());
        }
    }

    private String getDocForUpdate(ActionRequestKey key, String documentIn) {
        if (key.getAction().equals(Action.UPDATE)) {
            return "{\"doc\":" + documentIn + "}";
        }
        return documentIn;
    }

    /*
    *    For testing in the IDE
    */
    public static void main(String [] args) {
        String jobName = "jsonkey";
        String rootDir = Paths.get(".").toAbsolutePath().normalize().toString();
        String[] params = {
                "--config-factory",
                "org.apache.samza.config.factories.PropertiesConfigFactory",
                "--config-path",
                String.format("file://%s/samza-elasticsearch/src/main/config/%s.properties", rootDir, jobName)
        };
        JobRunner.main(params);
    }

}
