# Manual verification transition: review inventory

Branch: `feature/buy-now-checkout`. Starting commit:
`dd32e687423ac80a91c7309614c3ee254ba3b791`. The working tree was clean before
this change. Changes remain unstaged and uncommitted for review.

## Diff scope

Remove 55 individually inventoried files: 31 JUnit classes, one test-only Payment
launcher, two service test configuration files, 14 harness/helper/script/plan
files, two test-only Compose files, and five historical phase reports. Replace
the phase reports with the current [manual guide](manual-verification.md),
retaining runtime contracts, schema and recovery limitations. Update README and
all seven POMs. No application endpoint or business behavior is removed.

## Deleted files

Each file was read and classified before deletion. The phase reports contained
obsolete automated-run procedures and historical verification claims; the
current operational information is consolidated in the manual guide.

- `auth-service/src/test/java/com/orderflow/auth/AuthServiceApplicationTests.java`
- `auth-service/src/test/java/com/orderflow/auth/controller/AuthControllerTest.java`
- `auth-service/src/test/java/com/orderflow/auth/service/AuthServiceTest.java`
- `auth-service/src/test/java/com/orderflow/auth/service/JwtServiceTest.java`
- `auth-service/src/test/resources/application.yml`
- `compose.phase1-test.yml`
- `compose.phase3-test.yml`
- `discovery-server/src/test/java/com/orderflow/discovery/DiscoveryServerApplicationTests.java`
- `docs/buy-now-phase1.md`
- `docs/buy-now-phase2.md`
- `docs/buy-now-phase3.md`
- `docs/buy-now-phase4.md`
- `docs/buy-now-phase5.md`
- `gateway/src/test/java/com/orderflow/gateway/GatewayApplicationTests.java`
- `gateway/src/test/java/com/orderflow/gateway/JwtGatewayFilterTest.java`
- `gateway/src/test/java/com/orderflow/gateway/WebhookRoutingTest.java`
- `inventory-service/src/test/java/com/orderflow/inventory/InventoryServiceApplicationTests.java`
- `inventory-service/src/test/java/com/orderflow/inventory/reservation/StockReservationControllerTest.java`
- `inventory-service/src/test/java/com/orderflow/inventory/reservation/StockReservationTest.java`
- `order-service/src/test/java/com/orderflow/order/OrderServiceApplicationTests.java`
- `order-service/src/test/java/com/orderflow/order/checkout/BuyApiTest.java`
- `order-service/src/test/java/com/orderflow/order/checkout/BuyNowTest.java`
- `order-service/src/test/java/com/orderflow/order/checkout/CheckoutAttemptTest.java`
- `order-service/src/test/java/com/orderflow/order/client/PaymentStatusClientTest.java`
- `order-service/src/test/java/com/orderflow/order/client/ProductClientTest.java`
- `order-service/src/test/java/com/orderflow/order/consumer/PaymentEventConcurrencyTest.java`
- `order-service/src/test/java/com/orderflow/order/consumer/PaymentEventConsumerTest.java`
- `order-service/src/test/java/com/orderflow/order/outbox/OrderNotificationTest.java`
- `order-service/src/test/java/com/orderflow/order/service/LegacyPaymentEffectStoreTest.java`
- `order-service/src/test/java/com/orderflow/order/service/OrderPricingPersistenceTest.java`
- `order-service/src/test/java/com/orderflow/order/service/OrderPricingTest.java`
- `payment-service/src/test/java/com/orderflow/payment/OrderClientTest.java`
- `payment-service/src/test/java/com/orderflow/payment/PaymentEventProducerTest.java`
- `payment-service/src/test/java/com/orderflow/payment/PaymentReliabilityTest.java`
- `payment-service/src/test/java/com/orderflow/payment/PaymentSecurityTest.java`
- `payment-service/src/test/java/com/orderflow/payment/PaymentServiceApplicationTests.java`
- `payment-service/src/test/java/com/orderflow/payment/StripeRequestTest.java`
- `payment-service/src/test/java/com/orderflow/payment/integration/PaymentIntegrationApplication.java`
- `payment-service/src/test/resources/application.yml`
- `product-service/src/test/java/com/orderflow/product/CheckoutPricingTest.java`
- `product-service/src/test/java/com/orderflow/product/ProductServiceApplicationTests.java`
- `scripts/checkout-integration.py`
- `scripts/checkout-integration.sh`
- `scripts/test-phase1.sh`
- `scripts/test-phase2.sh`
- `scripts/test-phase3.sh`
- `scripts/test-phase4.sh`
- `scripts/test-plans/Phase5Agent.java`
- `scripts/test-plans/final-buy-now.md`
- `scripts/test-plans/final-buy-now.py`
- `scripts/test-plans/final-buy-now.sh`
- `scripts/test-plans/phase5.py`
- `scripts/test-plans/test-harness.sh`
- `scripts/test-plans/tests/Phase5AgentTest.java`
- `scripts/test-plans/tests/test_phase5.py`

## Maven changes

All seven POMs set `maven.test.skip=true`, disabling test compilation and execution
by default. Existing Dockerfile `-DskipTests` flags remain compatible. No explicit
test-only plugin was declared. Inherited Maven lifecycle plugins remain; the
compiler/Lombok and Spring Boot repackage configuration are preserved.

Removed test-scoped dependency declarations:

- `auth-service/pom.xml`: `h2`, `spring-boot-starter-test`, `spring-security-test`.
- `discovery-server/pom.xml`: `spring-boot-starter-webmvc-test`.
- `gateway/pom.xml`: `spring-boot-starter-test`.
- `inventory-service/pom.xml`: `spring-boot-starter-test`.
- `order-service/pom.xml`: `spring-boot-starter-test`.
- `payment-service/pom.xml`: `h2`, `spring-boot-starter-test`, `spring-kafka-test`, `h2`. Payment's second H2 entry was dependency management.
- `product-service/pom.xml`: `spring-boot-starter-test`.

There are 11 removed direct dependencies and one removed dependency-management
entry. JJWT, Spring Security, WebClient/load balancing, PostgreSQL, Kafka, Redis,
Stripe, validation, JPA, Actuator, Eureka, OpenAPI and Lombok runtime/build
dependencies are retained.

## Preserved production files

The 170 tracked production source/resource and deployment files are compared against
their original SHA-256 hashes. This includes every `src/main/**` file, all seven
Dockerfiles, `docker-compose.yml`, and
`docker/postgres/init/01-create-databases.sql`. Direct runtime dependency XML is
also compared structurally against the original declarations. No database schema,
Kafka messaging, Stripe webhook verification, security, or Buy Now code changed.

Machine-local `/workspace/.orderflow/` logs, journals, pinned snapshots, old plan
delegates and other untracked environment artifacts were not deleted. They are
outside the Git repository; any old delegate is obsolete after removal of its
repository harness target. No containers, Docker volumes or database rows were
inspected or cleaned up.

## Build validation actually performed

All seven production `clean package` builds completed successfully using Java 21.
Each service was built with this command in its own directory:

```bash
bash ./mvnw -B -s /workspace/.orderflow/maven/settings.xml \
  -Dmaven.repo.local=/workspace/.orderflow/maven/repository \
  -Dmaven.test.skip=true clean package
```

The prepared environment used `JAVA_HOME=/workspace/.orderflow/jdk`,
`MAVEN_USER_HOME=/workspace/.orderflow/maven`, and
`MAVEN_OPTS=-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts`.
These are environment-specific build settings; the generic manual build command
is in the manual guide.

| Service | Production compilation / packaging | Executable JAR inspection | Automated test execution |
| --- | --- | --- | --- |
| auth-service | completed | Boot launcher and start class present | skipped |
| discovery-server | completed | Boot launcher and start class present | skipped |
| gateway | completed | Boot launcher and start class present | skipped |
| product-service | completed | Boot launcher and start class present | skipped |
| inventory-service | completed | Boot launcher and start class present | skipped |
| order-service | completed | Boot launcher and start class present | skipped |
| payment-service | completed | Boot launcher and start class present | skipped |

Every build log explicitly reports `Not copying test resources`,
`Not compiling test sources`, and `Tests are skipped.` There are no test-run
results in these build logs. Clean compilation occurred for each service, rather
than accepting an old JAR. ZIP inspection confirmed the Boot launcher manifest and
application start class, with no test classes, Phase 5 launcher/agent, H2, JUnit,
Mockito, Spring test, AssertJ, Hamcrest or Testcontainers libraries in the JARs.
No `target/test-classes` directory remains after these clean builds.

Initial offline attempts for all seven services failed because Maven clean plugin
versions 3.4.1 and 3.5.0 were not cached. The retry allowed Maven dependency access,
resolved the missing build plugins, and successfully built all seven applications.
No application change was needed to resolve this build-tool cache issue.

Local evidence is retained outside Git in
`/workspace/.orderflow/logs/manual-build-<service>.log` (offline attempts) and
`/workspace/.orderflow/logs/manual-package-<service>.log` (successful builds).
Static checks compare production hashes, runtime dependency declarations, build
plugin configuration and the deletion inventory. `git diff --check` also completed
without whitespace errors.

No unit tests, integration tests, end-to-end tests or harnesses were executed.
No containers or applications were started, no application HTTP requests were
sent, and no payments, webhooks or database cleanup were performed. Manual
scenarios and Docker image builds/runtime startup remain unverified. No commit,
push, branch change or staging was performed.
