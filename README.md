# Flink Dynamic Event Processing

The goal of this exercise is to leverage flink in achieving a dynamic, scalable, real-time event processing pipeline.

## Concept

In it's most basic form, the setup looks like this:

![Image](https://github.com/user-attachments/assets/50ed2be7-1860-4aaa-903b-43b07ae3b66e)

Note that only the message streams (Kafka) and Flink are provided as part of this setup.  
In production environment, the rules queue would be fed from a database via CDC, and result queues would be consumed by dedicated dispatcher services responsible for forwarding the message to the end consumer using the specified transport.

In this architecture, Flink is responsible for maintaining the rules state in memory, meaning it can very quickly apply the necessary logic and move on to the next message.  
The state can be distributed across multiple Flink nodes, allowing for horizontal scaling of the processing pipeline.  
Checkpoints ensure fault tolerance, at the cost of some events potentially being processed twice after downtime or deployment. This can be mitigated by introducing a cache at the dispatcher layer, ensuring that even if the same message is processed twice, it only gets sent out once.

[The job source code is located here.](./jobs/route-event/src/main/java/io/crystalplanet/databus/router/RouteEventJob.java)

## Testing instructions

### Set up

1. Run `./bin/build.sh` to build the job `*.jar` archive. Requires maven to be installed.
2. Run `docker-compose up -d` to start the cluster.  
If this is your first run, you'll need to set up Kafka topics or Flink's jobmanager will fail to boot.  
If that's the case, run the following commands followed up by `docker-compose restart`.

```sh
docker exec databus-kafka-1 kafka-topics.sh --create --topic events --bootstrap-server kafka:9092 --partitions 1 --replication-factor 1
docker exec databus-kafka-1 kafka-topics.sh --create --topic rules --bootstrap-server kafka:9092 --partitions 1 --replication-factor 1
docker exec databus-kafka-1 kafka-topics.sh --create --topic results-http --bootstrap-server kafka:9092 --partitions 1 --replication-factor 1
```

3. Go to `localhost:8080` to access the Kafka UI for easy inspection of topics and events.

### Sending events

Let's start by sending an even to the `events` topic. At this point, we're expecting it to be discarded as we haven't set up any rules yet.

```sh
docker exec -it databus-kafka-1 kafka-console-producer.sh --bootstrap-server kafka:9092 --topic events
```
```json
{"type": "user:signup", "data": {"userId": 123}}
```

Inside the Kafka UI, you should see the event you just posted in the `events` topic. Let's add some routing rules now.

```sh
docker exec -it databus-kafka-1 kafka-console-producer.sh --bootstrap-server kafka:9092 --topic rules
```
```json
{"ruleId": "rule_a", "events": ["user:signup", "subscription:created"], "destinationUrl": "https://foo.bar/baz", "transforms": [], "filters": []}
{"ruleId": "rule_b", "events": ["subscription:created"], "destinationUrl": "https://themoney.com/hook", "transforms": [], "filters": []}
```

With these rules in place, all `user:signup` events should be forwarded to `https://foo.bar/baz` and all `subscription:created` events will be routed to `https://foo.bar/baz` _and_ `https://themoney.com/hook`.  
Let's try pushing some more messages to the `events` queue:

```json
{"type": "user:signup", "data": {"userId": 123}}
{"type": "user:login", "data": {"userId": 123}}
{"type": "user:signup", "data": {"userId": 999}}
{"type": "subscription:created", "data": {"userId": 123, "subscriptionId": 11}}
{"type": "user:signup", "data": {"userId": 456}}
{"type": "user:signup", "data": {"userId": 789}}
{"type": "user:login", "data": {"userId": 456}}
{"type": "user:signup", "data": {"userId": 789}}
{"type": "subscription:created", "data": {"userId": 456, "subscriptionId": 66}}
{"type": "user:login", "data": {"userId": 789}}
{"type": "subscription:created", "data": {"userId": 789, "subscriptionId": 33}}
```

This will result in 10 messages being written to the `results-http` topic:

- 4 `user:signup` events routed to `https://foo.bar/baz`
- 3 `subscription:created` events routed to `https://foo.bar/baz`
- 3 `subscription:created` duplicates routed to `https://themoney.com/hook`
- 0 `user:login` events as there's no rule to cover them.

Let's say we now want to alter `rule_a` to also include `user:login`. We'd do this by dispatching the following message to the `rules` topic:

```json
{"ruleId": "rule_a", "events": ["user:login", "user:signup", "subscription:created"], "destinationUrl": "https://foo.bar/baz", "transforms": [], "filters": []}
```

When we try to dispatch a login event now, it should also be pushed into `results-http` with a `https://foo.bar/baz` destination.

```json
{"type": "user:login", "data": {"userId": 123}}
```

## Next steps

This being a very minimal PoC setup, there are a lot of things that aren't covered yet:

- Checkpointing and retaining rule state across jobmanager restarts.
- Advanced filtering and transformation based on rules.
- Enriching events with data from external sources, asynchronously or through CDC.
- Prioritizing the processing of new rules over events when changes are made.
- Data aggregation operators.
- Stream and state partitioning.
