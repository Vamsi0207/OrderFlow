# OrderFlow

A scalable **e-commerce backend built using Spring Boot microservices**, following a distributed and event-driven architecture.

OrderFlow provides user authentication, product management, inventory management, order processing, payment integration with Stripe, Redis caching, and asynchronous communication using Apache Kafka.

---

## Architecture

```text
                         ┌──────────────────┐
                         │      Client      │
                         └────────┬─────────┘
                                  │
                                  ▼
                         ┌──────────────────┐
                         │   API Gateway    │
                         │      :8080       │
                         └────────┬─────────┘
                                  │
              ┌───────────────────┼───────────────────┐
              │                   │                   │
              ▼                   ▼                   ▼
       ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
       │    Auth     │     │   Product   │     │    Order    │
       │   Service   │     │   Service   │     │   Service   │
       │    :8081    │     │    :8082    │     │    :8083    │
       └─────────────┘     └──────┬──────┘     └──────┬──────┘
                                  │                   │
                                  ▼                   │
                            ┌───────────┐             │
                            │   Redis   │             │
                            └───────────┘             │
                                                      │
                                                      ▼
                                               ┌─────────────┐
                                               │  Inventory  │
                                               │   Service   │
                                               │    :8084    │
                                               └─────────────┘

                         ┌──────────────────┐
                         │ Payment Service  │
                         │      :8085       │
                         └────────┬─────────┘
                                  │
                                  ▼
                            ┌───────────┐
                            │  Stripe   │
                            └───────────┘

                  ┌─────────────────────────────────┐
                  │              Kafka              │
                  │                                 │
                  │  order.created                  │
                  │  payment.completed             │
                  │  payment.failed                │
                  └─────────────────────────────────┘

                         ┌──────────────────┐
                         │  Eureka Server   │
                         │      :8761       │
                         └──────────────────┘

                         ┌──────────────────┐
                         │    PostgreSQL    │
                         └──────────────────┘
```

---

## Microservices

| Service | Port | Responsibility |
|---|---:|---|
| API Gateway | `8080` | Single entry point and request routing |
| Auth Service | `8081` | User registration, authentication and JWT security |
| Product Service | `8082` | Product management and retrieval |
| Order Service | `8083` | Order creation and order lifecycle management |
| Inventory Service | `8084` | Inventory and stock management |
| Payment Service | `8085` | Payment processing and Stripe integration |
| Discovery Server | `8761` | Service discovery using Eureka |

---

## Key Features

### 🔐 Authentication & Security

- User registration and authentication
- Spring Security
- JWT-based authentication
- Secured REST APIs
- API Gateway integration
- User identity propagation to downstream services

### 📦 Product Management

- Create, retrieve, update and delete products
- Product information APIs
- PostgreSQL persistence
- Redis caching using Spring Cache
- Cache eviction on product updates and deletion

### 🛒 Order Management

- Create and manage orders
- Order lifecycle management
- Integration with inventory management
- Event-driven payment status updates

### Buy Now

`POST /api/v1/buy` accepts product IDs and quantities with a valid user JWT and
`Idempotency-Key`. It obtains authoritative prices, persists a recoverable order,
reserves inventory by order ID, and initializes one payment per order. Initialization
is separate from payment success. Signed Stripe outcomes and Kafka events drive
order confirmation or cancellation; uncertain outcomes keep stock reserved.

### 📊 Inventory Management

- Inventory management
- Stock availability handling
- Stock updates associated with orders

### ⚡ Event-Driven Architecture

Apache Kafka is used for asynchronous communication between services.

Current Kafka topics:

```text
order.created
payment.completed
payment.failed
```

Payment events are consumed by the Order Service to update order status based on the payment result.

### 🚀 Redis Caching

Redis is implemented in the Product Service using a **cache-aside strategy**.

Caching is used for frequently accessed product data:

- Product details
- Product information

Cache entries are evicted when products are updated or deleted.

PostgreSQL remains the source of truth.

### 💳 Stripe Payments

OrderFlow integrates **Stripe PaymentIntents** for payment processing.

The payment flow is:

```text
Client
   │
   │ Create Payment
   ▼
Payment Service
   │
   │ Create PaymentIntent
   ▼
Stripe
   │
   │ Payment processing
   ▼
Stripe Webhook
   │
   ▼
Payment Service
   │
   ├── COMPLETED
   │
   └── FAILED
   │
   ▼
Kafka
   │
   ├── payment.completed
   └── payment.failed
   │
   ▼
Order Service
```

The Payment Service:

1. Persists a durable payment attempt with `PENDING` status.
2. Initializes or retrieves its idempotent Stripe PaymentIntent.
3. Returns an active intent's client secret only to the authenticated owner during initialization.
4. Receives Stripe webhook events.
5. Verifies webhook signatures.
6. Corroborates the current intent: full success becomes `COMPLETED`; definitive cancellation becomes `FAILED`. Retryable or uncertain outcomes remain pending.
7. Publishes the corresponding Kafka event.

Payment creation also includes **idempotency handling** to prevent duplicate payments for the same order.

### 🔎 Service Discovery

Netflix Eureka is used for service discovery.

Each microservice registers with the Discovery Server, allowing services to discover each other dynamically.

### 🌐 API Gateway

Spring Cloud Gateway provides a centralized entry point for client requests.

```text
Client
   │
   ▼
API Gateway
   │
   ├── Auth Service
   ├── Product Service
   ├── Order Service
   ├── Inventory Service
   └── Payment Service
```

### 📚 API Documentation

REST APIs are documented using **OpenAPI / Swagger**.

This provides interactive API documentation for exploring endpoints, request parameters and response models.

---

## Technology Stack

### Backend

- **Java**
- **Spring Boot**
- **Spring Security**
- **Spring Data JPA**
- **Spring Cloud**
- **Spring Cloud Gateway**
- **Netflix Eureka**

### Database & Caching

- **PostgreSQL**
- **Redis**

### Messaging

- **Apache Kafka**

### Payments

- **Stripe**

### Manual Verification

- Postman or individual HTTP requests
- Browser-based API and Discovery inspection
- Manual Stripe sandbox/test-mode payment workflow

### API Documentation

- **OpenAPI**
- **Swagger**

### Infrastructure

- **Docker**
- **Docker Compose**

---

## Event Flow

A simplified order and payment flow:

```text
1. Client creates an order
          │
          ▼
2. Order Service
          │
          ▼
3. Payment initialization
          │
          ▼
4. Payment Service
          │
          ▼
5. Stripe PaymentIntent
          │
          ▼
6. Stripe processes payment
          │
          ▼
7. Stripe Webhook
          │
          ▼
8. Payment Service updates payment status
          │
          ▼
9. Kafka payment event
          │
          ▼
10. Order Service consumes event
          │
          ▼
11. Order status updated
```

---

## Security Flow

```text
Client
   │
   │ Login
   ▼
Auth Service
   │
   │ JWT
   ▼
Client
   │
   │ JWT
   ▼
API Gateway
   │
   │ Authenticated request
   ▼
Microservices
```

JWT-based authentication is used to secure protected endpoints across the application.

---

## Project Structure

```text
OrderFlow/
│
├── auth-service/
├── discovery-server/
├── gateway/
├── inventory-service/
├── order-service/
├── payment-service/
├── product-service/
│
├── docker-compose.yml
└── README.md
```

---

## Running the Project

### Prerequisites

- Docker
- Docker Compose
- Git
- Java 21 JDK for host-side JAR packaging (Maven wrappers are included)

### Clone the Repository

```bash
git clone https://github.com/Vamsi0207/OrderFlow.git
cd OrderFlow
```

### Environment Variables

External credentials are provided through environment variables.

Required private settings are `DB_PASSWORD`, `JWT_SECRET`, `STRIPE_SECRET_KEY`,
and `STRIPE_WEBHOOK_SECRET`. Use the same JWT signing secret for the services
that verify it. For manual local payments, use Stripe sandbox/test-mode
credentials and the actual endpoint or forwarding session's signing secret.
See [manual setup](docs/manual-verification.md) before starting the application.

> **Never commit real API keys or secrets to the repository.**

### Start the Application

```bash
docker compose up --build -d
```

Check the running services:

```bash
docker compose ps
```

### Stop the Application

```bash
docker compose down
```

---

## Health Checks

Spring Boot Actuator is used for service health monitoring.

Example:

```bash
curl http://localhost:8082/actuator/health
```

Expected response when healthy (not verified by packaging):

```json
{
  "status": "UP"
}
```

---

## Manual Verification

This checkout uses manual verification only. Automated test sources, test-only
resources and dependencies, and the end-to-end harness have been removed. Each
service defaults to `maven.test.skip=true`; production compilation and executable
Spring Boot JAR packaging remain enabled.

Use a Java 21 JDK and the service's Maven wrapper to build without executing tests:

```bash
cd auth-service
bash ./mvnw -B -Dmaven.test.skip=true clean package
```

Repeat for the other six services. See [manual verification](docs/manual-verification.md)
for the all-service build command, local setup, actual request bodies, Buy Now,
Stripe webhook observations, persistence and recovery boundaries. See the
[change inventory](docs/manual-testing-changes.md) for every removed file and
build validation performed during this change.

The documented manual scenarios are expected behavior, not claims that they
have been executed or passed. No containers, application requests, payments or
webhooks were started by the test-removal task.

---

## Design Highlights

- Microservice-based architecture
- Service discovery using Eureka
- Centralized routing using API Gateway
- JWT-based authentication
- Asynchronous communication using Apache Kafka
- Redis cache-aside caching
- PostgreSQL persistence
- Stripe PaymentIntent integration
- Stripe webhook signature verification
- Payment idempotency handling
- OpenAPI / Swagger API documentation
- Dockerized development environment
- Manual API and payment verification

---

## Future Improvements

Potential production-level improvements include:

- Distributed tracing and centralized observability
- Advanced fault tolerance and resilience patterns
- Kubernetes deployment
- CI/CD pipeline
- Centralized configuration management
- Enhanced service-to-service authorization

---

## Author

**OrderFlow** — A backend-focused e-commerce microservices project built with Spring Boot and modern distributed-system technologies.
