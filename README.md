<h1 align="center">Payment Service</h1>

<p align="center">
  <strong>Razorpay checkout and payment reconciliation for an event-driven e-commerce platform</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.x-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4.1.x" />
  <img src="https://img.shields.io/badge/Razorpay-Payments-0C2451?logo=razorpay&logoColor=white" alt="Razorpay" />
  <img src="https://img.shields.io/badge/Apache%20Kafka-Event%20Streaming-231F20?logo=apachekafka&logoColor=white" alt="Apache Kafka" />
  <img src="https://img.shields.io/badge/MySQL-Database-4479A1?logo=mysql&logoColor=white" alt="MySQL" />
</p>

<p align="center">
  <a href="#how-it-works">Flow</a> ·
  <a href="#http-api">HTTP API</a> ·
  <a href="#payment-events">Events</a> ·
  <a href="#reliability">Reliability</a> ·
  <a href="#tech-stack--project-structure">Code</a> ·
  <a href="#running-locally">Local setup</a> ·
  <a href="#testing">Testing</a>
</p>

---

The Payment Service owns the payment lifecycle for e-commerce orders. It creates Razorpay checkout orders, verifies callbacks and webhooks, and publishes payment outcomes to the order service through Kafka. Payment state changes and their outgoing events are recorded together in MySQL using a transactional outbox.

> **Verified in Razorpay Test Mode.** With `POST /payments/verify` blocked in the browser, ngrok showed HTTP `200` for the capture webhook and the order reached `CONFIRMED`. [Reproduce the test](#verify-a-webhook-in-razorpay-test-mode)

## How It Works

![Payment processing flow from checkout and Razorpay verification through the transactional outbox to Kafka and the order service](docs/images/payment-processing-flow.svg)

1. **Start checkout.** An order creation event creates a `PENDING` payment. When the customer starts checkout, the service saves the checkout request and payment attempt before calling Razorpay with the stored amount and currency. A retry with the same key can retrieve the original completed checkout. If the gateway times out, the attempt stays open because Razorpay may have created the order before the response was lost.
2. **Verify the result.** The browser can submit the Razorpay checkout result as soon as checkout finishes. Razorpay also sends a signed webhook on capture, including when the browser callback succeeds. The webhook gives the service a way to reconcile the payment if the customer closes the tab or the callback fails.
3. **Save the outcome and publish it.** A verified capture updates the payment and its attempt to `SUCCESS` and creates the outbox entry in the same database transaction. The publisher sends the stored event to Kafka and records completion after Kafka acknowledges the send. The order service uses that event to confirm a pending order.

[Checkout service](src/main/java/com/aashir/payment/service/CheckoutService.java) · [Verification service](src/main/java/com/aashir/payment/service/PaymentVerificationService.java) · [Outbox publisher](src/main/java/com/aashir/payment/service/OutboxPublisher.java)

## HTTP API

The storefront sends the customer's bearer token with requests under `/payments`. Razorpay authenticates webhook deliveries with a signature on the request body.

| Endpoint | What it does |
|---|---|
| `POST /payments/{orderId}/checkout` | Starts or replays checkout for the customer's order. Requires the `Idempotency-Key` header and the `method` query parameter. |
| `POST /payments/verify` | Accepts `paymentId`, `razorpayOrderId`, `razorpayPaymentId`, and `razorpaySignature` in the request body. Returns the verification status. |
| `GET /payments/{orderId}` | Reads the customer's stored payment status, amount, and currency. The storefront can check this after a timeout or reload. |
| `POST /api/webhooks/razorpay` | Accepts the raw body with `X-Razorpay-Signature` and `x-razorpay-event-id`. Returns `200` for an accepted or duplicate delivery. |

[Payment controller](src/main/java/com/aashir/payment/controller/PaymentController.java) · [Webhook controller](src/main/java/com/aashir/payment/controller/RazorpayWebhookController.java)

## Payment Events

| Direction | Kafka topic | Event | When it’s used |
|---|---|---|---|
| Consumes | `order-events` | `OrderCreatedKafkaEvent` | Creates a `PENDING` payment for a new order. A replay reuses the existing payment. |
| Publishes | `payment-succeeded` | `PaymentSucceededEvent` | Checkout verification or webhook reconciliation confirms a capture. Carries `eventId`, `paymentId`, `orderId`, and `amount`. |
| Publishes | `payment-failed` | `PaymentFailedEvent` | The expiry job closes a pending payment after checking Razorpay. Carries `eventId`, `paymentId`, `orderId`, and `reason`. |

Both outgoing topics use the order ID as the Kafka message key. If Kafka accepts a send before the publisher records completion, the retry uses the same `eventId` so the order service can recognize the replay. Success and failure travel on separate topics and can arrive in either order.

[Order event consumer](src/main/java/com/aashir/payment/kafka/OrderKafkaConsumer.java) · [Outbox service](src/main/java/com/aashir/payment/service/OutboxService.java)

## Reliability

### Webhook reconciliation

The webhook controller verifies the signature against the original body and stores the delivery in `webhook_events` before acknowledging it. The worker claims the stored event, fetches the payment from Razorpay, and checks the gateway IDs, amount, currency, and status against the local checkout. A `payment.failed` webhook can close an individual attempt. The expiry job decides when to close the payment.

| Situation | What the service does |
|---|---|
| Razorpay retries a delivery | Uses `x-razorpay-event-id` to acknowledge the repeat and keep the original webhook row. |
| The same capture is reported again | Preserves the successful payment and attempt. A unique outbox key keeps one success record per payment. |
| A failure arrives after a successful capture | Keeps the successful state. Optimistic locking rejects a concurrent write based on an older payment or attempt version. |
| A database write fails during capture reconciliation | Rolls back the payment, attempt, and success outbox entry together. The webhook becomes eligible for retry. |

The worker scans for eligible events every **5 seconds** by default. Retries become eligible after **5 seconds, 15 seconds, 45 seconds, 2 minutes, and 5 minutes**. After six unsuccessful attempts, the event moves to `DEAD` with its last error. Events stuck in `PROCESSING` for more than **10 minutes** can be reclaimed after a restart or crash.

[Reconciliation service](src/main/java/com/aashir/payment/service/WebhookPaymentReconciliationService.java) · [Webhook processor](src/main/java/com/aashir/payment/service/WebhookEventProcessor.java) · [Retry rules](src/main/java/com/aashir/payment/entity/WebhookEvent.java)

### Expiry and late capture

A pending payment becomes eligible for expiry after **30 minutes** by default. Before closing it, the service checks Razorpay for each linked checkout order. It defers expiry when Razorpay reports an authorized or captured payment or when the lookup fails.

A capture can still arrive after expiry. A verified late capture reconciles the payment and its attempt to `SUCCESS`. If the order service has already cancelled the order and released its stock, the order stays `CANCELLED` and the consumer records one `late_payment_reviews` entry for that order and payment.

[Expiry service](src/main/java/com/aashir/payment/service/PaymentExpiryService.java) · [Order service consumer](https://github.com/Aashirshaikh07/e-commerce-spring-boot/blob/main/src/main/java/com/aashir/ecommerce/kafka/PaymentKafkaConsumer.java)

## Tech Stack & Project Structure

| Technology | Role in the service |
|---|---|
| Java 21 and Maven Wrapper | Build and run with the repository's Maven version. |
| Spring Boot 4.1.1 and Spring Security | Serve the API, authenticate callers, and run scheduled workers. |
| Razorpay Java SDK | Create checkout orders and retrieve payment details. |
| Spring Data JPA and MySQL | Persist payments, attempts, webhook deliveries, and outbox records. |
| Spring for Apache Kafka | Consume order creation events and publish payment outcomes. |
| JUnit, Mockito, and Testcontainers | Exercise reconciliation rules and check transaction behavior against MySQL. |

### Where to start in the code

| Location | What you’ll find |
|---|---|
| `controller/` and `gateway/` | The HTTP entry points, Razorpay client, and webhook signature verifier. |
| `service/` | Checkout, verification, expiry, reconciliation, and outbox publishing. |
| `entity/` and `repository/` | Payment state rules and queries for claiming webhook events and finding pending outbox entries. |
| `event/` and `kafka/` | Kafka payloads and the consumer that starts the payment lifecycle. |
| `src/test/` | Unit tests and MySQL integration tests. |

[Build configuration](pom.xml) · [Source tree](src/main/java/com/aashir/payment)

## Running Locally

### Configuration

Point `JAVA_HOME` at JDK 21. Create a local MySQL database named `payment_db`. The service defaults to port `8083`, MySQL at `localhost:3306`, and Kafka at `localhost:9092`. The database user defaults to `root`. Set `SPRING_DATASOURCE_USERNAME` to use another account.

Set these variables in the shell or IDE run configuration that starts the Payment Service.

| Variable | Value for local development |
|---|---|
| `DB_PASSWORD` | Password for the local MySQL account. |
| `JWT_SECRET` | The signing secret used by the order service to issue customer JWTs. |
| `SERVICE_SECRET` | Shared credential configured in both services for internal requests. |
| `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` | API credentials from the Razorpay Test Mode dashboard. |
| `RAZORPAY_WEBHOOK_SECRET` | Signing secret configured on the Test Mode webhook. |

[Application properties](src/main/resources/application.properties)

### Start the service

If you need a local Kafka broker, start it with the supplied Compose file. Then start the service from the repository root.

```sh
docker compose up -d kafka
sh mvnw spring-boot:run
```

In Windows PowerShell, use `.\mvnw.cmd spring-boot:run`. Run the order service and storefront for an end-to-end checkout so the order creation event reaches the payment consumer. [Local Kafka setup](docker-compose.yml) · [Order service repository](https://github.com/Aashirshaikh07/e-commerce-spring-boot)

### Verify a webhook in Razorpay Test Mode

1. **Connect the tunnel.** Run `ngrok http 8083` in a separate terminal. In the Razorpay Test Mode dashboard, set the webhook URL to your current HTTPS forwarding address plus `/api/webhooks/razorpay`. Subscribe to `payment.captured`. Add `payment.failed` to test failed attempts.
2. **Isolate the webhook path.** In the storefront's browser, block `http://localhost:8083/payments/verify` with DevTools. Keep DevTools open, place a fresh order, and complete a test payment. Check that Razorpay reports a capture and DevTools shows the verification request as blocked. [Chrome DevTools request blocking](https://developer.chrome.com/docs/devtools/request-conditions/)
3. **Follow the delivery.** Open the ngrok inspector at `http://127.0.0.1:4040`. Find `POST /api/webhooks/razorpay` with status `200`. Match the request's `x-razorpay-event-id` to the `webhook_events` row or the worker's log.
4. **Check the order.** The webhook row should reach `PROCESSED`, the payment should reach `SUCCESS`, and the order should show `CONFIRMED`. Remove the browser blocking rule when the test is complete.

| What you see | Where to check |
|---|---|
| `POST /` returns `401` | Check the Razorpay dashboard URL for the full `/api/webhooks/razorpay` path. |
| The full path returns `401` with `invalid_signature` | Check that the dashboard's signing secret matches `RAZORPAY_WEBHOOK_SECRET`. |
| The webhook returns `200` and the order is still pending | The response acknowledges receipt. Check the event's status and `last_error`, then check the success outbox entry and Kafka consumer. |

## Testing

Start Docker for the MySQL Testcontainers tests. The application context smoke test uses the local database and environment variables above.

```sh
sh mvnw test
```

In Windows PowerShell, run `.\mvnw.cmd test`.

- **Unit tests** cover webhook signature verification, state transitions, expiry, and worker retries.
- **MySQL integration tests** cover duplicate delivery IDs, replayed captures, transaction rollback, and concurrent attempts to change payment state.

[Webhook repository tests](src/test/java/com/aashir/payment/repository/WebhookEventRepositoryIntegrationTest.java) · [Reconciliation integration tests](src/test/java/com/aashir/payment/service/WebhookPaymentReconciliationIntegrationTest.java)
