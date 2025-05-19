package io.crystalplanet.databus.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.streaming.api.datastream.*;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction;
import org.apache.flink.util.Collector;

import java.util.*;

public class RouteEventJob {

    public static void main(String[] args) throws Exception {
        // Set up execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        ObjectMapper objectMapper = new ObjectMapper();

        // Add Kafka sources
        KafkaSource<String> routingRulesSource = KafkaSource.<String>builder()
            .setBootstrapServers("kafka:9092")
            .setTopics("rules")
            .setGroupId("routing-rules-consumer")
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        KafkaSource<String> eventsSource = KafkaSource.<String>builder()
            .setBootstrapServers("kafka:9092")
            .setTopics("events")
            .setGroupId("events-consumer")
            .setStartingOffsets(OffsetsInitializer.latest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        DataStream<RoutingRule> routingRules = env
            .fromSource(routingRulesSource, WatermarkStrategy.noWatermarks(), "RoutingRulesSource")
            .map(json ->objectMapper.readValue(json, RoutingRule.class))
            .returns(RoutingRule.class);

        DataStream<Event> events = env
            .fromSource(eventsSource, WatermarkStrategy.noWatermarks(), "EventsSource")
            .map(json -> objectMapper.readValue(json, Event.class))
            .returns(Event.class);

        // Define broadcast state to map from event.type to all routing rules that apply to it.
        MapStateDescriptor<String, List<RoutingRule>> rulesStateDescriptor =
            new MapStateDescriptor<>(
                "routingRulesState",
                Types.STRING,
                Types.LIST(Types.POJO(RoutingRule.class))
            );

        // Enable rules broadcast to all parallel Flink operators.
        BroadcastStream<RoutingRule> rulesBroadcast = routingRules.broadcast(rulesStateDescriptor);

        // Process events
        DataStream<String> matchedEvents = events
            .connect(rulesBroadcast)
            .process(new BroadcastProcessFunction<Event, RoutingRule, String>() {
                // 
                @Override
                public void processElement(Event event, ReadOnlyContext ctx, Collector<String> out) throws Exception {
                    ReadOnlyBroadcastState<String, List<RoutingRule>> rulesState = ctx.getBroadcastState(rulesStateDescriptor);
                    List<RoutingRule> rules = rulesState.get(event.type);

                    if (rules != null) {
                        for (RoutingRule rule : rules) {
                            ProcessedEvent result = new ProcessedEvent();
                            result.destination = rule.destinationUrl;
                            result.payload = event;

                            // Emit the wrapped event
                            out.collect(objectMapper.writeValueAsString(result));
                        }
                    }
                }

                // Update rules state when changes are registered
                @Override
                public void processBroadcastElement(RoutingRule rule, Context ctx, Collector<String> out) throws Exception {
                    BroadcastState<String, List<RoutingRule>> state = ctx.getBroadcastState(rulesStateDescriptor);

                    for (String eventType : rule.events) {
                        List<RoutingRule> currentRules = state.get(eventType);

                        if (currentRules == null) {
                            currentRules = new ArrayList<>();
                        }
                        currentRules.removeIf(r -> Objects.equals(r.ruleId, rule.ruleId));
                        currentRules.add(rule);
                        state.put(eventType, currentRules);
                    }
                }
            });

        // Kafka sink for HTTP delivery
        KafkaSink<String> sink = KafkaSink.<String>builder()
            .setBootstrapServers("kafka:9092")
            .setRecordSerializer(
                KafkaRecordSerializationSchema.builder()
                    .setTopic("results-http")
                    .setValueSerializationSchema(new SimpleStringSchema())
                    .build()
            )
            .build();

        matchedEvents.sinkTo(sink);

        env.execute("Route Event Flink Job");
    }
}
