# ChatApp - Architecture, Flow & Design Document

## Table of Contents

1. [Overview](#overview)
2. [Tech Stack](#tech-stack)
3. [System Architecture](#system-architecture)
4. [Request Flow](#request-flow)
5. [Authentication Flow (JWT)](#authentication-flow-jwt)
6. [Messaging Flow](#messaging-flow)
7. [Frontend Architecture](#frontend-architecture)
8. [Database Schema](#database-schema)
9. [API Endpoints](#api-endpoints)
10. [Docker Infrastructure](#docker-infrastructure)
11. [Load Testing](#load-testing)
12. [Bottleneck Analysis](#bottleneck-analysis)
13. [Kafka Internals — Deep Dive](#kafka-internals--deep-dive)
14. [Kafka Debug & Observability](#kafka-debug--observability)
15. [PostgreSQL Inspection](#postgresql-inspection)
16. [Scaling Roadmap](#scaling-roadmap)

> All load test results are in [docs/LOAD_TEST_RESULTS.md](LOAD_TEST_RESULTS.md).

---

## Overview

ChatApp is a WhatsApp-like real-time messaging application built as an iterative **performance optimization project**. The goal was to start with the simplest possible architecture, measure it under load, identify bottlenecks, and fix them one at a time with data.

**Current version: V8.** Started at P95=2,086ms (V1 HTTP polling) — now at P95=26ms / 70ms at 200u / 500u (V8 Kafka async batch persistence).

**Design philosophy**: Start simple, measure everything, optimize with data.

---

## Tech Stack

| Layer        | Technology                          | Version   |
|--------------|-------------------------------------|-----------|
| Backend      | Spring Boot                         | 3.2.5     |
| Language     | Java                                | 17        |
| Database     | PostgreSQL                          | 16-alpine |
| Auth         | JWT (jjwt library, HMAC-SHA384)     | 0.12.5    |
| Passwords    | BCrypt (Spring Security)            | -         |
| Frontend     | React 18 via CDN (no build step)    | 18.x      |
| Container    | Docker Compose                      | -         |
| Load Test    | Java HttpClient (zero dependencies) | 17        |
| Messaging    | Apache Kafka (KRaft, no ZooKeeper)  | 3.7.2     |

---

## System Architecture

```
+-----------------------------------------------------------+
|                     User's Browser                         |
|  React 18 (CDN) + Babel in-browser JSX transform          |
|  Connects via WebSocket (STOMP) on page load              |
|  HTTP GET: /api/messages/conversation/{x} (once on open)  |
|  HTTP GET: /api/messages/conversations   (once on open)   |
|  WS SEND:  /app/chat.send  (each message)                 |
|  WS PUSH:  /user/queue/messages (server push on new msg)  |
+-------------------------------+---------------------------+
                                |
                     HTTP + WebSocket (port 8080)
                                |
+-------------------------------v---------------------------+
|                  Spring Boot Backend                       |
|  (Single container, single JVM, Tomcat embedded)          |
|                                                           |
|  +---------------------+  +---------------------------+   |
|  | Security Filter      |  | Request Logging Filter    |   |
|  | Chain:               |  | Logs: method, path,       |   |
|  |  1. RequestLogging   |  | status, duration for      |   |
|  |  2. JwtAuthFilter    |  | all /api/* requests       |   |
|  |  3. SecurityConfig   |  +---------------------------+   |
|  +---------------------+                                   |
|                                                           |
|  +---------------------+  +---------------------------+   |
|  | AuthController       |  | MessageController         |   |
|  |  POST /auth/register |  |  @MessageMapping /chat.send|  |
|  |  POST /auth/login    |  |  GET  /messages/conv/{id} |   |
|  +---------------------+  |  GET  /messages/convs      |   |
|                            +---------------------------+   |
|  +---------------------+                                   |
|  | UserController       |  +---------------------------+   |
|  |  GET /users/search   |  | Service Layer              |   |
|  +---------------------+  |  AuthService (BCrypt)       |   |
|                            |  MessageService:             |   |
|                            |   WS push + Kafka publish    |   |
|                            |   (no sync DB write)         |   |
|  +---------------------+  +---------------------------+   |
|  | JPA / Hibernate      |  +---------------------------+   |
|  | HikariCP Pool: 50    |  | SimpleBroker (in-memory)  |   |
|  | @Cacheable users     |  | /user/queue/messages      |   |
|  | batch_size: 50       |  +---------------------------+   |
|  +----------+-----------+                                  |
+--------------+--------------------------------------------+
               |                                              
         JDBC (port 5432)               Kafka (port 9092)
               |                             |
+--------------v-----------+  +--------------v--------------+
|    PostgreSQL 16         |  |     Apache Kafka 3.7.2      |
|  Database: chatapp       |  |  Topic: chat-messages       |
|  Tables: users, messages |  |  Partitions: 3              |
|  Indexes:                |  |  Consumers: 3 threads       |
|   (sender_id, ts DESC)   |  |  max.poll.records: 500      |
|   (receiver_id, ts DESC) |  |  Batch saveAll() → DB       |
+--------------------------+  +-----------------------------+
```

Key points:
- **3 Docker containers** (backend + postgres + kafka). No reverse proxy, no cache layer.
- **Frontend is served from Spring Boot's static resources** — no separate web server.
- **WebSocket (STOMP)** — messages sent and received over a persistent connection. No polling.
- **Kafka async persistence** — `sendMessage()` pushes WS instantly + publishes to Kafka. `MessagePersistenceConsumer` reads batches, writes via `saveAll()` with `hibernate.jdbc.batch_size=50`. All Kafka config is in `application.yml` (`spring.kafka.listener.type=batch`, `concurrency=3`); only a `NewTopic` bean in `KafkaConfig.java`.
- **3 consumer threads** (one per Kafka partition) for parallel DB writes.
- **HikariCP pool=50** — tuned from V1's default of 10.
- **Composite indexes** on `(sender_id, timestamp DESC)` and `(receiver_id, timestamp DESC)` for ordered scans without post-sort.

---

## Request Flow

Every HTTP request goes through this pipeline:

```
Browser Request
      |
      v
[Tomcat Thread Pool (200 threads default)]
      |
      v
[RequestLoggingFilter]
  - Logs: >>> GET /api/messages/conversations (auth) from 172.18.0.1
  - Times the entire request
  - Logs: <<< GET /api/messages/conversations 200 12ms
      |
      v
[JwtAuthFilter]
  - Extracts "Authorization: Bearer <token>" header
  - Calls JwtUtil.validateToken() -> parses HMAC-SHA384 signature
  - If valid: loads User from DB, sets SecurityContext
  - If missing/invalid: passes through (SecurityConfig will reject if protected)
      |
      v
[Spring Security FilterChain]
  - Public: /, /index.html, /css/**, /js/**, /api/auth/**, /actuator/**
  - Everything else: requires authentication
  - Session policy: STATELESS (no server-side sessions)
  - CSRF: disabled (stateless API)
      |
      v
[Controller -> Service -> Repository -> Database]
      |
      v
[JSON Response via Jackson]
```

---

## Authentication Flow (JWT)

### Registration

```
Client                          Backend                         Database
  |                                |                               |
  |  POST /api/auth/register       |                               |
  |  {username, email, password}   |                               |
  |------------------------------->|                               |
  |                                |  Check username exists?        |
  |                                |------------------------------>|
  |                                |  Check email exists?           |
  |                                |------------------------------>|
  |                                |  BCrypt.encode(password)       |
  |                                |  (cost factor 10, ~80-150ms)  |
  |                                |  INSERT INTO users             |
  |                                |------------------------------>|
  |                                |  Generate JWT (HMAC-SHA384)   |
  |  {token, userId, username}     |                               |
  |<-------------------------------|                               |
```

### Login

```
Client                          Backend                         Database
  |                                |                               |
  |  POST /api/auth/login          |                               |
  |  {username, password}          |                               |
  |------------------------------->|                               |
  |                                |  SELECT * FROM users           |
  |                                |  WHERE username = ?            |
  |                                |------------------------------>|
  |                                |  BCrypt.matches(plain, hash)  |
  |                                |  (~80-150ms CPU-bound)        |
  |                                |  Generate JWT (HMAC-SHA384)   |
  |  {token, userId, username}     |                               |
  |<-------------------------------|                               |
```

### Token structure
- Algorithm: **HMAC-SHA384** (auto-selected by jjwt based on key length)
- Expiration: **86,400,000 ms (24 hours)**
- Claims: `sub` (username), `iat`, `exp`
- Passed as: `Authorization: Bearer <token>`

---

## Messaging Flow

### Sending a message (V8 — Kafka async batch persistence)

```
Client (WebSocket)               Backend                         Kafka               Database
  |                                |                               |                   |
  |  STOMP SEND /app/chat.send     |                               |                   |
  |  {receiverId, content}         |                               |                   |
  |------------------------------->|                               |                   |
  |  (frame queued in             |                               |                   |
  |   inboundChannel — non-       |  @MessageMapping handler picks|                   |
  |   blocking, no thread wait)   |  up from thread pool          |                   |
  |                                |  Load receiver by ID (cached) |                   |
  |                                |                               |                   |
  |                                |  convertAndSendToUser(sender) |                   |
  |  PUSH /user/queue/messages     |  convertAndSendToUser(receiver)|                  |
  |  {id:0, senderId, receiverId,  |                               |                   |
  |   content, timestamp}          |  kafkaTemplate.send()         |                   |
  |<-------------------------------|------------------------------>|                   |
  |                                |                               |                   |
  |  (load test timer stops here)  |                               |                   |
  |                                |      [async, batch consumer]  |                   |
  |                                |                               |  poll up to 500   |
  |                                |                               |  events per cycle |
  |                                |                               |------──────────>|
  |                                |                               |  saveAll()        |
  |                                |                               |  batch INSERT     |
  |                                |                               |  (50 rows/stmt)   |
  |                                |                               |                   |
```

The WS push fires **before** the Kafka publish. The sender's echo arrives before the message hits the database. The `id: 0` in the response reflects that no DB-assigned ID exists yet.

The Kafka consumer runs on 3 threads (one per partition), polling up to 500 events per cycle and writing them via `saveAll()` with `hibernate.jdbc.batch_size=50` (Hibernate groups them into sub-batches of 50 rows per `INSERT ... VALUES` statement).

### Initial page load (HTTP)

On first opening the chat, two HTTP GET requests fetch existing data (page 0):
- `GET /api/messages/conversations?page=0` — first 15 partners (scroll down loads page 1, 2, ...)
- `GET /api/messages/conversation/{id}?page=0` — last 15 messages (scroll up loads page 1, 2, ...)

After that, all new messages arrive via WebSocket push. Older history is loaded on demand via scroll-triggered pagination.

---

## Frontend Architecture

The frontend has **zero build tooling** — no webpack, no vite, no npm:

```
backend/src/main/resources/static/
  index.html          # Loads React 18 + Babel + SockJS + STOMP.js from CDN
  css/app.css         # WhatsApp dark theme
  js/
    api.js            # HTTP fetch wrapper + WebSocket STOMP connection manager
    auth.js           # LoginPage + RegisterPage components
    chat.js           # ChatPage (real-time via WebSocket, infinite scroll pagination)
    app.js            # Root App component with auth state
```

- **React 18** loaded via `<script src="unpkg.com/react@18/...">` 
- **Babel standalone** transforms JSX in the browser at runtime
- **No routing library** — simple conditional rendering based on auth state
- All API calls go through `api.js` which:
  - Adds `Authorization: Bearer` header from localStorage
  - Logs every request: method, URL, duration, response size
  - Redirects to login on 401/403

---

## Database Schema

### users table

| Column     | Type                | Constraints                     |
|------------|---------------------|---------------------------------|
| id         | BIGINT (IDENTITY)   | PRIMARY KEY, auto-increment     |
| username   | VARCHAR(255)        | UNIQUE, NOT NULL                |
| email      | VARCHAR(255)        | UNIQUE, NOT NULL                |
| password   | VARCHAR(255)        | NOT NULL (BCrypt hash)          |
| created_at | TIMESTAMP           | Set on creation                 |

### messages table

| Column      | Type                | Constraints                    |
|-------------|---------------------|--------------------------------|
| id          | BIGINT (IDENTITY)   | PRIMARY KEY, auto-increment    |
| sender_id   | BIGINT              | FK -> users.id, NOT NULL       |
| receiver_id | BIGINT              | FK -> users.id, NOT NULL       |
| content     | TEXT                | NOT NULL                       |
| timestamp   | TIMESTAMP           | Set by @PrePersist             |
| is_read     | BOOLEAN             | Default false                  |

### Indexes

| Index Name           | Columns                    | Purpose                                          |
|----------------------|----------------------------|--------------------------------------------------|
| idx_msg_sender_ts    | sender_id, timestamp DESC  | Ordered scan for sender-side conversation queries |
| idx_msg_receiver_ts  | receiver_id, timestamp DESC| Ordered scan for receiver-side queries           |

Both paginated queries (`findConversation`, `findConversationPartnerIds`) use an `OR sender_id = :x OR receiver_id = :x` pattern. PostgreSQL resolves each branch with one of these composite indexes, merges via BitmapOr, and LIMIT is applied without a post-sort step.

---

## API Endpoints

| Method | Path                              | Auth | Description                        |
|--------|-----------------------------------|------|------------------------------------|
| POST   | /api/auth/register                | No   | Register new user                  |
| POST   | /api/auth/login                   | No   | Login, get JWT                     |
| POST   | /api/messages                     | Yes  | Send a message                     |
| GET    | /api/messages/conversation/{id}   | Yes  | Get all messages with user {id}    |
| GET    | /api/messages/conversations       | Yes  | Get list of conversation partners  |
| GET    | /api/users/search?q=              | Yes  | Search users by username           |

All authenticated endpoints require `Authorization: Bearer <jwt-token>` header.

---

## Docker Infrastructure

```yaml
services:
  postgres:            # PostgreSQL 16-alpine
    ports: 5432:5432
    healthcheck: pg_isready -U chatapp (every 5s)
    volume: pgdata (persistent)

  backend:             # Spring Boot (JRE 17)
    build: ./backend   # Multi-stage: maven build -> JRE runtime
    ports: 8080:8080
    depends_on: postgres (healthy)
    restart: on-failure
```

**Multi-stage Dockerfile**:
1. Stage 1 (`maven:3.9-eclipse-temurin-17`): Compiles source, runs `mvn package`
2. Stage 2 (`eclipse-temurin:17-jre`): Copies only the `.jar`, runs it — smaller image (~380MB vs ~760MB)

---

## Load Testing

### How the Load Test Works

`loadtest/LoadTest.java` is a **zero-dependency Java 17 program** using `java.net.http.HttpClient`.

```
Phase 1: REGISTER
  For each of N users:
    POST /api/auth/register -> store {userId, username, token}

Phase 1b: LOGIN
  For each user:
    POST /api/auth/login -> refresh token (ensures fresh JWT for WS auth)

Phase 2: CONNECT WEBSOCKETS
  For each user:
    Open WS to /ws
    Send STOMP CONNECT with Authorization: Bearer <token>
    Wait for CONNECTED frame
    Send STOMP SUBSCRIBE to /user/queue/messages
    Time the whole connect sequence as 'ws_connect'

Phase 3: LOAD (runs for D seconds)
  Each user thread loops until deadline:
    1. Pick a random partner
    2. Send STOMP SEND /app/chat.send {receiverId, content}
       Start timer
    3. Poll receiveQueue (blocking) with 5-second timeout
       <- Server pushes MessageResponse to /user/queue/messages
       <- processFrame() checks senderId == me.id, puts in receiveQueue
    4a. Echo received  -> record 'send_message' latency, count success
    4b. 5s timeout hit -> count as error (echo timeout, not server failure)
    Sleep 100-300ms random

Phase 4: REPORT
  Aggregate all latencies per endpoint
  Calculate p50, p90, p95, p99 percentiles
  Print formatted summary table including WS push count
```

> **The 5-second echo timeout**: the load test waits up to 5 seconds for its own message echo before counting a timeout error. In normal operation the echo arrives in ~15ms (P50). The 5s threshold only fires under extreme TCP backpressure — seen at 0.04% at 500 users on Docker's bridge network. It is not a server-side failure; the server saved the message. In production on a real network this would be negligible.

Each request is timed individually. Results are collected in a `ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>` — thread-safe, zero-contention metric collection.

### Running the Tests

```bash
# Warmup (200 users, 100s — results not saved)
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest \
  maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 200 100

# High (200 users, 120s)
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest \
  maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 200 120

# Stress (500 users, 120s)
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest \
  maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 500 120
```

Save results with PowerShell `Tee-Object`:

```powershell
docker run ... | Tee-Object -FilePath "loadtest\results\v7-websocket-200users-120s.txt"
```

> Full results for all versions: [docs/LOAD_TEST_RESULTS.md](LOAD_TEST_RESULTS.md)

---

## Bottleneck Analysis

> **Note**: Raw test numbers referenced in this section are from V3 (200-user, 120s realistic-login test). Full data for all versions is in [LOAD_TEST_RESULTS.md](LOAD_TEST_RESULTS.md).

### Bottleneck #1: BCrypt Login (CRITICAL)

**The problem**: Login is the single slowest endpoint across all load levels.

- At 10 users: login P50 = 147ms, all other endpoints P50 = 15-19ms
- At 200 users: login P50 = 716ms

**Why**: BCrypt password hashing is **intentionally CPU-expensive**. Each `BCrypt.matches()` call takes ~80-150ms of pure CPU time. With 200 concurrent threads all doing BCrypt, the CPU becomes saturated.

**Impact**: BCrypt blocks Tomcat threads. While a thread waits for BCrypt, it can't serve other requests. This cascades — `send_message` and `get_conversation` latencies spike because they're queued behind BCrypt-blocked threads.

**Fix options for V2**:
- Cache authentication tokens (don't re-login every request cycle)
- Use Redis to cache JWT validation results
- Lower BCrypt cost factor (trade security for speed — not recommended)
- Separate auth service to isolate CPU-heavy operations

### Bottleneck #2: Database Connection Pool Exhaustion (FIXED in V2)

**The problem (V1)**: HikariCP max pool size was **10 connections** serving **200 concurrent users**.

**The fix (V2)**: `hikari.maximum-pool-size=50`

**Result**: DB-bound endpoints (`send_message`, `get_conversation`) improved 1.5-2.9x at 50 users. However fixing this exposed the next bottleneck — BCrypt CPU saturation. Login got 1.9x *slower* because the pool had previously been throttling BCrypt concurrency.

**Lesson**: The pool was masking the CPU problem. This is how bottleneck cascades work.

### Bottleneck #3: No Pagination — Growing Query Payload (FIXED in V5+V6)

**The problem**: `GET /api/messages/conversation/{id}` returned **ALL messages** between two users. No limit, no pagination.

- Early in the test: returns 1-5 messages → fast
- Late in test (200 users, 120s): could return hundreds of messages per pair → slow, large payload

**Evidence**: `get_conversation` avg went from 19ms (10 users) to 937ms (200 users) — 49x degradation, the worst of any endpoint.

**Fix (V5)**: `PageRequest.of(page, 15)` on `findConversation`; SQL subquery with `Pageable` on `findConversationPartnerIds`. Both endpoints accept `?page=N` (default 0). Frontend uses infinite scroll — scroll up in messages loads older pages, scroll down in sidebar loads more conversations. Payloads bounded to 15 rows per request regardless of history length. P95 537ms → 531ms.

**Fix (V6)**: Composite indexes `idx_msg_sender_ts (sender_id, timestamp DESC)` and `idx_msg_receiver_ts (receiver_id, timestamp DESC)`. Both paginated queries now walk a pre-ordered index instead of scanning+sorting. `get_conversation` P95 499ms → 462ms (−7.4%). Overall P95 504ms @ 200u / 499ms @ 500u.

### Bottleneck #4: HTTP Polling Overhead (FIXED in V7)

**The problem**: Every browser tab polled:
- `/api/messages/conversations` every 3 seconds
- `/api/messages/conversation/{id}` every 2 seconds

That\u2019s **~0.83 req/s per user just for polling**, even when nothing changed. At 200 users: ~166 background req/s of pure noise.

**The fix (V7)**: WebSocket (STOMP). Clients open one persistent connection on load. `send_message` posts via STOMP `SEND /app/chat.send`. Server pushes `MessageResponse` to both sender and receiver via `SimpMessagingTemplate.convertAndSendToUser`. Polling intervals removed from frontend.

**Result**: send_message P95 574ms \u2192 102ms at 200u. Overall P95 504ms \u2192 105ms. Background polling traffic eliminated.

### Bottleneck #5: User Lookup on Every Request (FIXED in V4)

**The problem (V1-V3)**: `JwtAuthFilter` ran `SELECT * FROM users WHERE username=?` on **every authenticated request** to populate the `SecurityContext`.

**The fix (V4)**: `@Cacheable(value="users", key="#username")` on `findByUsername`. Spring's built-in `ConcurrentHashMap` cache (no Redis, no extra dependency). `@CacheEvict` on register ensures new users are immediately visible.

**Result**: One DB query eliminated per request. P50 improved 194ms→150ms at 200 users. However, this freed up more throughput which caused faster message accumulation — P95 revealed the pagination bottleneck more clearly.

**Lesson**: Even small repeated DB hits matter at scale. Every authenticated endpoint was paying this tax.

### Bottleneck #6: Single-Instance Architecture (LOW for V1)

**The problem**: One JVM handles all requests. No horizontal scaling.

- Tomcat default: 200 threads (OK for now, but ceiling is near)
- Single point of failure
- Cannot scale API and DB independently

**Fix options for V2+**:
- Multiple backend instances behind a load balancer (nginx/HAProxy)
- Sticky sessions or stateless JWT (already stateless, good)
- Read replicas for PostgreSQL

### Bottleneck #7: Frontend `id: 0` dedup bug (FIXED in V8)

**The bug**: In V8, `sendMessage()` no longer writes to the DB before responding. The `MessageResponse` is built with `id: 0L` because no database-assigned ID exists yet — the INSERT happens asynchronously via Kafka. The frontend's dedup check in `chat.js` was:

```javascript
if (prev.some((m) => m.id === msg.id)) return prev; // dedup
```

With every message having `id: 0`, `0 === 0` was always true after the first message. Every subsequent WS push was **silently dropped** — messages appeared only after a page refresh (which loaded them from the DB via HTTP GET).

**The fix**: Dedup by a composite key instead of the database ID:

```javascript
const isDup = prev.some(
  (m) => m.senderId === msg.senderId && m.content === msg.content && m.timestamp === msg.timestamp
);
```

**Impact on load test**: None — the load test client uses its own echo detection (`senderId == me.id` in `processFrame()`) and never runs the React dedup logic. The bug only affected the browser UI.

**Lesson**: When you move a DB write to an async path, audit every consumer of the response for assumptions about database-generated fields (`id`, `createdAt`, etc.). The `id: 0` broke a seemingly unrelated dedup check in the frontend.

---

## Kafka Internals — Deep Dive

### Why Kafka, not a queue / thread pool?

A simple `@Async` call or an in-process `LinkedBlockingQueue` would also decouple the DB write from the hot path. Kafka was chosen because:

1. **Back-pressure is explicit** — if the consumer falls behind, Kafka offsets grow and that lag is visible in monitoring (`kafka-consumer-groups.sh`). An in-memory queue would silently drop messages on JVM restart.
2. **Durability** — messages are persisted on disk in the Kafka log. A JVM crash between a WS push and a DB write means the message is not lost; it sits in Kafka and the consumer catches up on restart.
3. **Partitioned parallelism** — a topic with N partitions allows N independent consumer threads, each working a disjoint key space. Zero lock contention between threads on `saveAll()`.
4. **Batch semantics** — Kafka consumers naturally accumulate records between polls. A single `consumer.poll()` call returns up to `max.poll.records` events (default 500), which are then flushed to DB in one `saveAll()`. This is the cheapest possible batch primitive.

---

### KRaft mode — no ZooKeeper

Before Kafka 3.3, Apache Kafka required Apache ZooKeeper to manage metadata (broker registration, topic/partition assignments, controller election). KRaft (Kafka Raft) embeds a Raft-based metadata quorum directly inside the Kafka broker, eliminating ZooKeeper entirely.

In this project, a single node acts as both **broker** and **controller**:

```
KAFKA_NODE_ID=1
KAFKA_PROCESS_ROLES=broker,controller
```

The controller runs the Raft log on port 9093 (`CONTROLLER` listener) and the broker serves clients on port 9092 (`PLAINTEXT` listener):

```
KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093
```

`KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092` is what clients (producers/consumers) use to connect. Docker's internal DNS resolves `kafka` to the container IP. This is distinct from `KAFKA_LISTENERS` (bind address) — the advertised address is what the broker announces to clients in `Metadata` responses.

```
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1
```

`__consumer_offsets` — the internal topic where consumer group progress is stored — has replication factor 1 (single broker). In production with multiple brokers you'd set this to 3.

```
KAFKA_LOG_DIRS=/tmp/kraft-combined-logs
```

All Kafka data (topic partitions, controller Raft log, `__consumer_offsets`) lives here on the container filesystem. It's ephemeral (not volume-mounted) — messages are lost on `docker compose down -v`. For production, mount a named volume.

---

### Topic: `chat-messages`

Declared in `KafkaConfig.java`:

```java
@Bean
public NewTopic chatMessagesTopic() {
    return TopicBuilder.name("chat-messages").partitions(3).replicas(1).build();
}
```

3 partitions, replication factor 1. Spring auto-creates this topic on application startup if it does not exist (controlled by `spring.kafka.admin.auto-create=true` which is the default when `KafkaAdmin` is on the classpath).

**Partition routing**: The producer key is `sender.getId().toString()`. Kafka's default partitioner hashes the key with MurmurHash2 and maps it to `partition = hash(key) mod numPartitions`. This ensures all messages sent by the same user always land in the same partition, preserving ordering per sender.

---

### Producer path (MessageService)

```java
kafkaTemplate.send("chat-messages", sender.getId().toString(), event);
```

`kafkaTemplate.send()` is **fire-and-forget** from the caller's perspective. Internally it:

1. Calls `RecordAccumulator.append()` — the record is added to an in-memory `ProducerBatch` for the target partition.
2. The `Sender` background thread (started by `KafkaProducer`) wakes up and checks batches that are either full (`batch.size=16384` bytes default) or have been waiting longer than `linger.ms=0` (default = send immediately).
3. A `ProduceRequest` is sent over the TCP connection to the broker leader for that partition.
4. The broker appends the record to its log segment, fsync's the index (controlled by `log.flush.interval.messages`), and returns an `Ack`.
5. The `CompletableFuture<SendResult>` returned by `kafkaTemplate.send()` completes. We don't await it — the WS push already returned to the client.

**Serialisation**: key = `StringSerializer`, value = `JsonSerializer` (Spring's Jackson-based serialiser). The JSON payload for `ChatMessageEvent` looks like:

```json
{"senderId":12,"senderUsername":"alice","receiverId":47,"receiverUsername":"bob","content":"hey!"}
```

---

### Consumer path (MessagePersistenceConsumer)

`application.yml` configures Spring Boot's auto-configured container factory:

```yaml
spring.kafka.listener:
  type: batch        # enables List<> parameter in @KafkaListener
  concurrency: 3     # 3 consumer threads = 1 per partition
```

`ConcurrentMessageListenerContainer` spawns 3 child `KafkaMessageListenerContainer` instances. Each child manages one `ConsumerRecord` polling loop on its own thread (`ntainer#0-0-C-1`, `ntainer#0-1-C-1`, `ntainer#0-2-C-1` — visible in logs). The group coordinator assigns one partition to each thread via the `range` assignor (default). This is a **static 1:1 partition/thread mapping** — no rebalancing once partitions are assigned.

The polling loop per thread:

```
while (running) {
    ConsumerRecords<String, ChatMessageEvent> records = consumer.poll(Duration.ofMillis(5000));
    if (!records.isEmpty()) {
        listener.onMessage(records);   // calls persistMessages(List<ChatMessageEvent>)
        consumer.commitSync();         // commit offsets after successful save
    }
}
```

`max.poll.records` defaults to 500 in Kafka, capping how many records `poll()` returns per call. At ~900 messages/second across 3 partitions, each partition receives ~300 msg/s. The consumer easily keeps up — `saveAll()` for a batch finishes in under 100ms.

---

### Batch INSERT mechanics

`persistMessages()`:

```java
List<Message> messages = events.stream().map(event -> {
    User sender   = userRepository.getReferenceById(event.senderId());
    User receiver = userRepository.getReferenceById(event.receiverId());
    return Message.builder().sender(sender).receiver(receiver).content(event.content()).build();
}).toList();
messageRepository.saveAll(messages);
```

`getReferenceById()` returns a **Hibernate proxy** — no SQL is executed. The proxy carries the ID and becomes a foreign-key reference in the `INSERT`. No extra `SELECT` per record.

`saveAll()` calls `persist()` on each new entity (all IDs are null — they're new inserts). Hibernate accumulates them in the `ActionQueue`. At flush time (end of transaction, which is `saveAll()`'s `@Transactional`), the queue is sorted by entity type (`order_inserts=true`), then executed in batches of `hibernate.jdbc.batch_size=50`:

```
application.yml:
  spring.jpa.properties.hibernate.jdbc.batch_size: 50
  spring.jpa.properties.hibernate.order_inserts: true
```

For a batch of 500 records:
- **10 JDBC statements** (`INSERT INTO messages ... VALUES (?,?,?,?)` × 50 rows each)
- Only **10 database round-trips** instead of 500
- PostgreSQL processes each statement as a multi-row insert, writing all 50 rows in one WAL operation

`order_inserts=true` ensures all `Message` rows are grouped together before `order_updates` (irrelevant here since no updates) — preventing Hibernate from interleaving INSERT and UPDATE statements across entity types, which would break JDBC batching.

---

### When exactly do messages hit the database?

```
t=0ms     Client sends STOMP SEND /app/chat.send
t=~3ms    MessageService receives event, pushes WS to both parties
t=~5ms    kafkaTemplate.send() — record in ProducerBatch (in memory)
t=~6ms    Sender thread flushes ProduceRequest to Kafka broker (linger.ms=0)
t=~7ms    Broker appends to log, ACKs producer
t=~8ms    (WS echo arrives at client — load test timer stops here)

-- async from here --

t=8ms–∞   Consumer thread is polling on its 5s poll interval
           When poll() returns, it may have this record plus up to 499 others

t = (consumer's next poll)
           records = consumer.poll(5000ms)      // returns up to 500 records
           persistMessages(records)              // begins saveAll() transaction
             - getReferenceById (no SQL)
             - flush ActionQueue (batch INSERT every 50 rows)
           consumer.commitSync()                 // offset committed → message "processed"
```

The typical consumer lag is **sub-second** at low load (the consumer polls frequently). Under heavy load (500 users, ~900 msg/s), a consumer thread may accumulate a full batch in under 600ms, triggering a `saveAll()` every cycle.

**Key trade-off**: the `id: 0` in the `MessageResponse` sent over WebSocket is not the real database-assigned ID, because the INSERT hasn't happened yet. The load test uses `senderId` for echo detection, not `id`, so correctness is unaffected. In a production system you would either (a) assign a UUID in the service layer before publishing, or (b) accept the deferred ID and refresh on next page load.

---

## Kafka Debug & Observability

### View Kafka broker logs

```bash
# Live stream — see partition assignments, leader elections, consumer group joins
docker compose logs -f kafka

# Last 100 lines
docker compose logs --tail=100 kafka

# Search for errors only
docker compose logs kafka 2>&1 | grep -i "error\|warn\|exception"
```

### List topics and partition metadata

```bash
# Shell into the Kafka container
docker compose exec kafka bash

# List all topics
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list

# Describe chat-messages topic (leaders, replicas, offsets)
/opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic chat-messages
```

Example output:
```
Topic: chat-messages  TopicId: XYZ  PartitionCount: 3  ReplicationFactor: 1
  Topic: chat-messages  Partition: 0  Leader: 1  Replicas: 1  Isr: 1
  Topic: chat-messages  Partition: 1  Leader: 1  Replicas: 1  Isr: 1
  Topic: chat-messages  Partition: 2  Leader: 1  Replicas: 1  Isr: 1
```

### Check consumer group lag

Consumer lag is the number of messages written to a partition partition minus the number the consumer group has committed. A rising lag means the consumer is falling behind.

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group chatapp-persistence
```

Example output during a load test:
```
GROUP                TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID
chatapp-persistence  chat-messages  0          4250            4312            62   consumer-chatapp-persistence-1-...
chatapp-persistence  chat-messages  1          4190            4289            99   consumer-chatapp-persistence-2-...
chatapp-persistence  chat-messages  2          4201            4271            70   consumer-chatapp-persistence-3-...
```

LAG=0 after the load test ends means all messages have been persisted to DB.

### Inspect raw messages on a topic

```bash
# Read all messages from the beginning (useful to verify serialization)
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic chat-messages \
  --from-beginning \
  --max-messages 10

# Read only new messages (live tail)
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic chat-messages
```

Note: The value is JSON (`JsonSerializer`), so output will look like:
```
{"senderId":1,"senderUsername":"user_1","receiverId":2,"receiverUsername":"user_2","content":"hello"}
```

### Check partition offsets (high watermark)

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --bootstrap-server localhost:9092 \
  --topic chat-messages
```

Output: `chat-messages:0:4312` (partition 0 has 4,312 committed records).

### Delete a topic (for clean retest)

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --delete \
  --topic chat-messages
```

Spring's `KafkaAdmin` will recreate the topic on next backend startup.

---

## PostgreSQL Inspection

### Connect to the database

```bash
docker compose exec postgres psql -U chatapp -d chatapp
```

You are now at the `psql` interactive prompt.

### List tables

```
\dt
```

Output:
```
        List of relations
 Schema |   Name   | Type  |  Owner
--------+----------+-------+---------
 public | messages | table | chatapp
 public | users    | table | chatapp
```

### Count rows — verify Kafka consumer flushed

```sql
-- Total messages in DB (should equal total WS sends by load test minus inflight)
SELECT COUNT(*) FROM messages;

-- Messages per sender (useful after load test to verify distribution)
SELECT sender_id, COUNT(*) AS msgs
FROM messages
GROUP BY sender_id
ORDER BY msgs DESC
LIMIT 10;
```

### Check for consumer lag in DB (cross-check with Kafka lag)

During a load test, query the message count every few seconds:

```sql
-- In one psql session, repeatedly run:
SELECT COUNT(*) FROM messages;
-- Count should rise in jumps of ~50-500 (the batch sizes)
```

### Explain query plans — verify indexes are used

```sql
-- Conversation query (should show Index Scan on idx_msg_sender_ts or idx_msg_receiver_ts)
EXPLAIN ANALYZE
SELECT * FROM messages
WHERE sender_id = 1 OR receiver_id = 1
ORDER BY timestamp DESC
LIMIT 15;
```

Look for `Bitmap Index Scan` on `idx_msg_sender_ts` and `idx_msg_receiver_ts` — this confirms the composite indexes are being used for sorted pagination.

### Inspect indexes

```sql
\di messages*
```

Output:
```
                  List of indexes
 Schema |         Name          | Type  |  Table   |   Columns
--------+-----------------------+-------+----------+-----------------
 public | idx_msg_receiver_ts   | btree | messages | receiver_id, timestamp
 public | idx_msg_sender_ts     | btree | messages | sender_id, timestamp
 public | messages_pkey         | btree | messages | id
```

### Check Hibernate batch inserts are firing (PostgreSQL statement statistics)

```sql
-- Enable statement tracking (requires pg_stat_statements extension — may not be available in default image)
SELECT query, calls, rows, total_exec_time::bigint AS total_ms
FROM pg_stat_statements
WHERE query LIKE 'insert into messages%'
ORDER BY calls DESC
LIMIT 5;
```

If `pg_stat_statements` is not enabled, watch `docker compose logs backend` for Hibernate SQL logging (enable `spring.jpa.show-sql=true` temporarily):

```
INFO  [ntainer#0-0-C-1] SQL: insert into messages (content,receiver_id,sender_id,timestamp,is_read) values (?,?,?,?,?),(?,?,?,?,?)...
```

A single statement with 50 `(?,?,?,?,?)` placeholders confirms batching.

### Exit psql

```
\q
```

---

## Scaling Roadmap

| Step | Change                                        | Status      | Result / Impact                                                               |
|------|-----------------------------------------------|-------------|-------------------------------------------------------------------------------|
| 1    | HikariCP pool 10 → 50                        | **DONE V2** | DB endpoints 1.5-2.9x faster; revealed BCrypt saturation                     |
| 2    | Fix load test: login once per user            | **DONE V3** | True baseline: P95=586ms, 693 req/s at 200 users                             |
| 3    | Cache user lookups (`@Cacheable`)             | **DONE V4** | P50 improved; revealed pagination as next bottleneck                          |
| 4    | Pagination LIMIT 15 per page + infinite scroll | **DONE V5** | P95 537ms @ 200u / 531ms @ 500u; 3.6x less data; P95 converges across scales |
| 5    | Composite DB index on messages                | **DONE V6** | P95 504ms @ 200u / 499ms @ 500u; read queries use pre-ordered index scans     |
| 6    | WebSockets / SSE for messaging                | **DONE V7** | send_message P95 574ms \u2192 102ms; polling eliminated; overall P95 ~105ms        |
| 7    | Kafka async batch persistence                 | **DONE V8** | DB INSERT removed from hot path; batch writes via saveAll()                   |
| 8    | Horizontal scaling (2+ instances)             | Planned     | Linear throughput increase                                                    |
| 9    | Read replicas for PostgreSQL                  | Future      | Separate read/write workloads                                                 |

**Current baseline (V8)**: send_message P95 **26ms @ 200u / 70ms @ 500u** — 78% / 29% lower than V7 Run 2 (116ms / 99ms). DB INSERT fully removed from the hot path. Zero errors at both scales.
**Next target**: Horizontal scaling (multiple backend instances) — Stateless JWT is already in place; adding a load balancer + second instance + Redis pub/sub for WS routing is the natural next step.

---

*Last updated: April 6, 2026. Tests run on Docker Desktop; production numbers will differ.*
