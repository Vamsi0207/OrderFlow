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

1. Creates a Stripe PaymentIntent.
2. Stores the payment with `PENDING` status.
3. Returns the Stripe client secret.
4. Receives Stripe webhook events.
5. Verifies webhook signatures.
6. Updates payment status to `COMPLETED` or `FAILED`.
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

### Testing

- **JUnit**
- **Spring Boot Testing**
- **End-to-End API Testing**

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

### Clone the Repository

```bash
git clone <repository-url>
cd OrderFlow
```

### Environment Variables

External credentials are provided through environment variables.

For Stripe:

```env
STRIPE_SECRET_KEY=<your-stripe-secret-key>
STRIPE_WEBHOOK_SECRET=<your-stripe-webhook-secret>
```

> **Never commit real API keys or secrets to the repository.**

### Start the Application

```bash
docker compose up -d
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
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{
  "status": "UP"
}
```

---

## Testing

The project includes automated testing using JUnit and Spring Boot testing support.

The application has also been validated through end-to-end API flows covering interactions between the major services.

The Stripe payment flow was tested using Stripe test mode and Stripe webhook events.

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
- Automated and end-to-end testing

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
