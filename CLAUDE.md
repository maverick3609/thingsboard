## Project Overview

ThingsBoard is an open-source IoT platform (v4.3.1.1) for device management, data collection, processing, and visualization. It's a multi-module Maven project with a Java 17/Spring Boot 3.5 backend and Angular 20 frontend.

## Architecture

### Module Layout

| Module | Purpose |
|--------|---------|
| `application/` | Main Spring Boot app — REST controllers, services, actor system |
| `common/` | Shared libraries: actor framework, data models, queue/transport abstractions, protobuf definitions |
| `dao/` | Data access layer (JPA + Cassandra), entity models, caching |
| `rule-engine/` | Rule engine API (`rule-engine-api/`) and built-in node implementations (`rule-engine-components/`) |
| `transport/` | Protocol transports: MQTT, HTTP, CoAP, LwM2M, SNMP |
| `edqs/` | Event-Driven Queue System |
| `ui-ngx/` | Angular 20 frontend (Angular Material, NgRx, ECharts, Leaflet) |
| `msa/` | Microservice packaging: js-executor, web-ui, vc-executor, black-box-tests |
| `netty-mqtt/` | Custom MQTT protocol implementation |
| `rest-client/` | Java REST API client library |
| `monitoring/` | Monitoring module |
| `tools/` | Utility tools |

### Backend Patterns

- **Entry point**: `application/.../ThingsboardServerApplication.java` — uses `@SpringBootConfiguration` + `@EnableAsync` + `@EnableScheduling`
- **Component scan**: `org.thingsboard.server` and `org.thingsboard.script`
- **Controllers**: `@RestController` with `@PreAuthorize` for auth. OpenAPI 3.0 annotations (`io.swagger.v3.oas.annotations`). Controller constants in `ControllerConstants.java`
- **DAO pattern**: Generic `Dao<T>` interface → JPA repositories. Supports PostgreSQL (primary) and Cassandra (timeseries)
- **Rule engine**: Actor-based message processing. `TbMsg` is the central message type. Rule nodes annotated with `@RuleNode`. Messages route through rule chains with SUCCESS/FAILURE relation types
- **Actor system**: Hierarchical actors — App → Tenant → Device/RuleChain/RuleNode. Located in `application/.../actors/`
- **Async**: Uses Guava `ListenableFuture` throughout (not CompletableFuture)
- **Queues**: Abstracted message queue layer (`common/queue/`) supporting Kafka, RabbitMQ, AWS SQS, Azure Service Bus, Google Pub/Sub

### Frontend Patterns

- **Angular prefix**: `tb-` (e.g., `<tb-component>`)
- **State management**: NgRx (store + effects)
- **Styling**: SCSS + Tailwind CSS 3
- **Build**: Custom esbuild via `@angular-builders/custom-esbuild:application`
- **Output**: `ui-ngx/target/generated-resources/public`

## Code Style

- **Java**: Java 17, Lombok annotations (`@Data`, `@Slf4j`, etc.), Apache 2.0 license headers (enforced by `mvn license:format`)
- **Frontend**: 2-space indentation, UTF-8, ESLint with Angular/TypeScript rules
- **Lombok config**: `addconstructorproperties=true`, `@Lazy` is copyable