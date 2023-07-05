# mm2-lag-monitor

This little app monitors the lag of mirror maker 2 and exposes the metrics to Prometheus.
Specifically, the app exposes the following metrics:

```
mm2_source_offsets{cluster="<source alias>",topic="<topic name>",partition="<partition number>"}
mm2_target_offsets{cluster="<target alias>",topic="<topic name>",partition="<partition number>"}
```

The former metric is the log end offset of the topic partition on the source cluster.
The latter metric is the offset that MM2 has committed to the `mm2-offsets...` internal topic on the target cluster.
The difference between the two is a rather accurate approximation of the lag of MM2.
The app samples these metrics whenever MM2 produces new offsets to the `mm2-offsets...` topic.

The metrics are exposed in the Prometheus format on port 8080 under the `/q/metrics` path.
Use the following query to get the lag of all partitions of all topics:

```
mm2_source_offsets -on(topic, partition) mm2_target_offsets
```

In order for the app to work, you need to set the following environment variables:

```
MP_MESSAGING_INCOMING_MM2_BOOTSTRAP_SERVERS=<target cluster bootstrap servers>
MP_MESSAGING_INCOMING_REPLICATED_BOOTSTRAP_SERVERS=<source cluster bootstrap servers>
MP_MESSAGING_INCOMING_MM2_TOPIC=<mm2-offsets... topic name on the target cluster>
```

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

## Packaging and running the application

The application can be packaged using:
```shell script
./mvnw package
```
It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./mvnw package -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using: 
```shell script
./mvnw package -Pnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using: 
```shell script
./mvnw package -Pnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/mm2-lag-monitor-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.

## Related Guides

- Micrometer Registry Prometheus ([guide](https://quarkus.io/guides/micrometer)): Enable Prometheus support for Micrometer
- Apache Kafka Client ([guide](https://quarkus.io/guides/kafka)): Connect to Apache Kafka with its native API
- SmallRye Reactive Messaging - Kafka Connector ([guide](https://quarkus.io/guides/kafka-reactive-getting-started)): Connect to Kafka with Reactive Messaging
- Scheduler ([guide](https://quarkus.io/guides/scheduler)): Schedule jobs and tasks
- Micrometer metrics ([guide](https://quarkus.io/guides/micrometer)): Instrument the runtime and your application with dimensional metrics using Micrometer.

## Provided Code

### Reactive Messaging codestart

Use SmallRye Reactive Messaging

[Related Apache Kafka guide section...](https://quarkus.io/guides/kafka-reactive-getting-started)

