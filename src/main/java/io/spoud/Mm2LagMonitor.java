package io.spoud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.kafka.KafkaClientService;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.mirror.DefaultReplicationPolicy;
import org.apache.kafka.connect.mirror.ReplicationPolicy;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.*;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

@Startup
@ApplicationScoped
public class Mm2LagMonitor {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Long> mm2Offsets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> logEndOffsets = new ConcurrentHashMap<>();
    private ReplicationPolicy replicationPolicy;
    private MeterRegistry registry;

    public Mm2LagMonitor(@ConfigProperty(name = "replication.policy.jar", defaultValue = "") String replicationPolicyJar,
                         MeterRegistry registry) {
        Log.info("Starting mm2 lag monitor");
        this.registry = registry;
        if (replicationPolicyJar.isBlank()) {
            Log.info("Using default replication policy");
            replicationPolicy = new DefaultReplicationPolicy();
        } else {
            Log.info("Loading replication policy from %s".formatted(replicationPolicyJar));
            try {
                replicationPolicy = getReplicationPolicy(new File(replicationPolicyJar));
            } catch (Exception ex) {
                Log.error("Error loading replication policy from %s".formatted(replicationPolicyJar), ex);
                Quarkus.blockingExit();
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    @Jacksonized @Builder
    public static class OffsetMessage {
        private long offset;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    @Jacksonized @Builder
    public static class OffsetMetadata {
        @JsonProperty("MirrorSourceConnector")
        private PartitionInfo mirrorConnectorOffsets;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    @Jacksonized @Builder
    public static class PartitionInfo {
        private String cluster;
        private String topic;
        private int partition;
    }

    @Inject
    KafkaClientService kafkaClientService;

    @Incoming("mm2")
    public void handleOffsetUpdate(Record<String, String> record) {
        try {
            var keyJson = record.key()
                .replaceAll(Pattern.quote("["), "{")
                .replaceAll(Pattern.quote("]"), "}")
                .replaceFirst(",", ":");
            var valueJson = record.value();
            var metadata = mapper.readValue(keyJson, OffsetMetadata.class);
            var offset = mapper.readValue(valueJson, OffsetMessage.class);
            if (metadata.getMirrorConnectorOffsets() == null) {
                return;
            }
            updateLogEndOffsets(metadata.getMirrorConnectorOffsets().getTopic(), metadata.getMirrorConnectorOffsets().getPartition());
            mm2Offsets.put(metadata.getMirrorConnectorOffsets().getTopic() + "-" + metadata.getMirrorConnectorOffsets().getPartition(), offset.getOffset());
            registry.gauge("mm2_target_offsets",
                List.of(
                    new ImmutableTag("topic", metadata.getMirrorConnectorOffsets().topic),
                    new ImmutableTag("partition", Integer.toString(metadata.getMirrorConnectorOffsets().getPartition())),
                    new ImmutableTag("cluster", metadata.getMirrorConnectorOffsets().getCluster())),
                mm2Offsets,
                (map) -> map.get(metadata.getMirrorConnectorOffsets().getTopic() + "-" + metadata.getMirrorConnectorOffsets().getPartition()));
            Log.info("Received offset update: %s -> %s".formatted(record.key(), record.value()));
        } catch (JsonProcessingException ex) {
            Log.error("Error parsing offset update: %s -> %s".formatted(record.key(), record.value()));
        }
    }

    private String getOldTopicName(String topic) {
        return replicationPolicy.originalTopic(topic);
    }

    private void updateLogEndOffsets(String topic, int partition) {
        var finalTopic = getOldTopicName(topic);
        var consumer = kafkaClientService.getConsumer("replicated");
        var relevantPartition = new TopicPartition(finalTopic, partition);
        consumer.runOnPollingThread(rawConsumer -> {
            rawConsumer.assign(List.of(relevantPartition));
            rawConsumer.seekToEnd(List.of(relevantPartition));
            var logEndOffset = rawConsumer.position(relevantPartition);
            logEndOffsets.put(topic + "-" + partition, logEndOffset);
            Log.info("Updating log end offset for %s-%s: %s".formatted(finalTopic, partition, logEndOffset));
            registry.gauge("mm2_source_offsets",
                List.of(
                    new ImmutableTag("topic", topic), // we do not want to use the old topic name here, so that it is easier to associate the offsets of the old and new topic
                    new ImmutableTag("partition", Integer.toString(partition))),
                logEndOffsets,
                (map) -> map.get(finalTopic + "-" + partition));
        }).subscribe().with(
            (x) -> {},
            (e) -> Log.error("Error updating log end offset for %s-%s".formatted(finalTopic, partition), e));
    }

    public static ReplicationPolicy getReplicationPolicy(File jarFile) throws Exception {
        try (JarFile file = new JarFile(jarFile)) {
            URL jarURL = jarFile.toURI().toURL();
            URLClassLoader classLoader = new URLClassLoader(new URL[]{jarURL}, ReplicationPolicy.class.getClassLoader());

            for (JarEntry entry : Collections.list(file.entries())) {
                if (entry.getName().endsWith(".class")) {
                    String className = entry.getName().replace("/", ".").replaceAll("\\.class$", "");
                    Class<?> loadedClass = classLoader.loadClass(className);

                    if (ReplicationPolicy.class.isAssignableFrom(loadedClass)) {
                        return (ReplicationPolicy) loadedClass.getDeclaredConstructor().newInstance();
                    }
                }
            }
        }

        return null;
    }
}
