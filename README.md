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

---

The Payment Service owns the payment lifecycle for e-commerce orders. It creates Razorpay checkout orders, verifies callbacks and webhooks, and publishes payment outcomes to the order service through Kafka. Payment state changes and their outgoing events are recorded together in MySQL using a transactional outbox.

The service handles cases where Razorpay confirms a capture after a payment has expired locally. It verifies the capture with Razorpay, reconciles the payment and its attempt to `SUCCESS`, and records a single success outbox entry even when the webhook is replayed.

## Payment Events

The Payment Service consumes order creation events from Kafka and publishes payment outcomes back to the order service. Razorpay webhooks arrive over HTTP. The service verifies them before updating payment state and recording the corresponding Outbox event.

| Direction | Kafka topic | Event | When it’s used |
|---|---|---|---|
| Consumes | `order-events` | `OrderCreatedKafkaEvent` | Creates a `PENDING` payment for a new order. A replay reuses the payment already associated with that order. |
| Publishes | `payment-succeeded` | `PaymentSucceededEvent` | Razorpay confirms a capture through webhook reconciliation or checkout verification. The event includes `eventId`, `paymentId`, `orderId`, and `amount`. |
| Publishes | `payment-failed` | `PaymentFailedEvent` | A pending payment expires locally after a check with Razorpay. The event includes `eventId`, `paymentId`, `orderId`, and `reason`. |

A Razorpay `payment.failed` webhook can mark an individual **attempt** as failed. The local expiry flow marks the **payment** as failed and queues the Kafka event the order service uses to cancel a pending order.

Both outgoing topics use the **order ID as the Kafka message key**. The Outbox publisher reuses the stored event ID when it retries a send, so the order service can recognize a replay. Since success and failure travel on separate topics, they can arrive in either order.

[Order consumer](src/main/java/com/aashir/payment/kafka/OrderKafkaConsumer.java) · [Outbox service](src/main/java/com/aashir/payment/service/OutboxService.java) · [Expiry service](src/main/java/com/aashir/payment/service/PaymentExpiryService.java)
