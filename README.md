# SwiftPay P2P Payment Engine

SwiftPay is a high-performance, asynchronous Peer-to-Peer (P2P) payment engine designed for reliability and speed. It leverages an event-driven architecture using Apache Kafka, Redis for idempotency and caching, and PostgreSQL for robust ledger storage.

## Architecture Overview

The system is split into two microservices to ensure loose coupling and scalability:

1. **Transaction Gateway (`transaction-gateway`)**:
   - Acts as the entry point for API requests.
   - Responsible for basic request validation and idempotency checking via Redis.
   - Communicates with the Ledger Service via REST to validate account existence and balance.
   - Publishes `PaymentInitiatedEvent` to Kafka for asynchronous processing.

2. **Ledger Service (`ledger-service`)**:
   - The source of truth for account balances and transaction history.
   - Consumes events from Kafka.
   - Employs pessimistic locking to prevent race conditions during balance transfers, locking accounts in a deterministic order to avoid deadlocks.
   - Publishes `PaymentCompletedEvent` or `PaymentFailedEvent` back to the Gateway.

## Technologies Used

- **Java 21 & Spring Boot 3.2+**
- **Apache Kafka** (Event-driven messaging)
- **Redis** (Distributed idempotency locks & Caching)
- **PostgreSQL** (ACID compliant database)
- **Docker & Docker Compose** (Containerization)
- **Testcontainers** (Integration Testing)
- **K6** (Performance & Load Testing)

## Local Setup & Deployment

1. **Build the Project**
   Ensure you have JDK 21 and Maven installed.
   ```bash
   mvn clean install -DskipTests
   ```

2. **Run with Docker Compose**
   The entire stack (Postgres, Kafka, Redis, Gateway, Ledger) can be spun up using Docker Compose:
   ```bash
   docker-compose up --build -d
   ```
   *Note: This exposes Transaction Gateway on port `8081` and Ledger Service on port `8082`.*

## API Endpoints

### 1. Initiate Payment
- **URL:** `POST http://localhost:8081/v1/payments`
- **Payload:**
  ```json
  {
    "transactionId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
    "senderId": "11111111-1111-1111-1111-111111111111",
    "receiverId": "22222222-2222-2222-2222-222222222222",
    "amount": 50.00,
    "currency": "USD"
  }
  ```
- **Response:** `202 ACCEPTED`

### 2. Check Payment Status
- **URL:** `GET http://localhost:8081/v1/payments/{transactionId}`
- **Response:** `200 OK` (Returns the status: `PENDING`, `COMPLETED`, or `FAILED`)

## Performance Tuning & Load Testing

To meet the requirement of **250 TPS for 1 million transactions**, we have provided a K6 load testing script (`loadtest.js`). 

### Running the Load Test
Ensure [K6 is installed](https://k6.io/docs/get-started/installation/) on your machine.
```bash
k6 run loadtest.js
```
*Note: The script is configured to run for 4000 seconds (~66.6 minutes) to reach 1 million transactions at a constant rate of 250 TPS.*

### Capturing the PCAP Trace
While the K6 load test is running, you can capture the network traffic (PCAP trace) for analysis using `tcpdump`. Run this in a separate terminal:
```bash
sudo tcpdump -i any -w swiftpay-trace.pcap port 8081 or port 8082 or port 9092 or port 6379 or port 5432
```
This will capture traffic between your services and Kafka/Redis/Postgres.

## CI/CD Pipeline

The project includes a GitHub Actions workflow (`.github/workflows/ci.yml`) that automatically runs Maven verification (including all Testcontainers-based integration tests) on every push or pull request to the `main` branch.
