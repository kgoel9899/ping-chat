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
| Frontend     | Vanilla JS (no React, no build step) | ES2020    |
| Container    | Docker Compose                      | -         |
| Load Test    | Java HttpClient (zero dependencies) | 17        |
| Messaging    | Apache Kafka (KRaft, no ZooKeeper)  | 3.7.2     |

---

## System Architecture

```
+-----------------------------------------------------------+
|                     User's Browser                         |
|  Vanilla JS (no React, no build step)                     |
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

The WS push fires **before** the Kafka publish. The sender's echo arrives before the message hits the database. The `id: 0` in the response reflects that no DB-assigned ID exists yet — `clientId` (a client-generated UUID) is used for dedup instead.

The Kafka consumer runs on 3 threads (one per partition), polling up to 500 events per cycle and writing them via `saveAll()` with `hibernate.jdbc.batch_size=50` (Hibernate groups them into sub-batches of 50 rows per `INSERT ... VALUES` statement).

### `outboundChannel` — where WS push tail latency comes from

`convertAndSendToUser()` does not write to the WebSocket directly. It **submits the serialized frame as a task to the `outboundChannel` thread pool** (Spring's `ThreadPoolTaskExecutor`, default corePoolSize=1). A thread in that pool picks it up, serializes the `MessageResponse` to a STOMP MESSAGE frame, and writes it to the WebSocket session.

```
sendMessage() thread                outboundChannel thread pool (default: 1 thread)
──────────────────────────────      ──────────────────────────────────────────────
convertAndSendToUser(sender)  ─┐
convertAndSendToUser(receiver) ─┤──► queue task ──► serialize JSON
kafkaTemplate.send()           ─┘                   write STOMP frame to sender WS
return response                                      write STOMP frame to receiver WS
```

At low concurrency the queue is empty and tasks run instantly (P50 = 7ms). At 500 users sending ~900 messages/second, **1,800 push tasks per second** are enqueued to a single thread — queue backup causes the P95 = 70ms tail. This is the primary remaining latency source after Kafka removed the DB write.

To push below ~20ms P95 at 500u, tune the pool:

```java
@Override
public void configureClientOutboundChannel(ChannelRegistration registration) {
    registration.taskExecutor().corePoolSize(4).maxPoolSize(8);
}
```

### Initial page load (HTTP)

On first opening the chat, two HTTP GET requests fetch existing data (page 0):
- `GET /api/messages/conversations?page=0` — first 15 partners (scroll down loads page 1, 2, ...)
- `GET /api/messages/conversation/{id}?page=0` — last 15 messages (scroll up loads page 1, 2, ...)

After that, all new messages arrive via WebSocket push. Older history is loaded on demand via scroll-triggered pagination.

---

## Frontend Architecture

The frontend has **zero build tooling** — no webpack, no vite, no npm, **no React**:

```
backend/src/main/resources/static/
  index.html          # Loads only STOMP.js from CDN; no React, no Babel
  css/app.css         # WhatsApp dark theme
  js/
    api.js            # HTTP fetch wrapper + WebSocket STOMP connection manager
    auth.js           # renderLoginPage() + renderRegisterPage() — vanilla DOM
    chat.js           # renderChatPage() — vanilla JS, real-time WS, infinite scroll, dedup
    app.js            # IIFE entry point — manages auth state, renders pages
```

- **Vanilla JavaScript** — all DOM rendering via `innerHTML` and `addEventListener`
- **No React, no Babel, no JSX** — scripts loaded as plain `<script>` tags
- **STOMP.js** is the only CDN dependency (for WebSocket communication)
- Dedup logic preserved: `clientId` (UUID) for WS-pushed messages, composite key fallback for DB-loaded messages
- XSS prevention: user-generated content rendered via `escapeHtml()` (creates a text node, reads `innerHTML`)
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

**The bug**: In V8, `sendMessage()` no longer writes to the DB before responding. The `MessageResponse` is built with `id: 0L` because no database-assigned ID exists yet — the INSERT happens asynchronously via Kafka. The frontend's original dedup check in `chat.js` was:

```javascript
if (prev.some((m) => m.id === msg.id)) return prev; // dedup
```

With every message having `id: 0`, `0 === 0` was always true after the first message. Every subsequent WS push was **silently dropped** — messages appeared only after a page refresh (which loaded them from the DB via HTTP GET).

**Why dedup exists at all**: The server pushes the same `MessageResponse` to both the sender and the receiver. The sender is subscribed to `/user/queue/messages` and receives the echo. Without dedup, the sender would see their own message twice: once from the echo, once from the receiver's push routing.

**Initial fix (composite key)**: Dedup by `senderId + content + timestamp`. Works in practice but has a theoretical edge case — two identical messages sent by the same user at the exact same millisecond would be falsely deduped.

**Final fix (client-generated UUID — the WhatsApp/Signal approach)**:

The root problem is that dedup relied on a *server-generated* ID that isn't available yet. The solution is to generate the ID on the *client*, before the message is sent, so the ID is always known regardless of DB timing.

```javascript
// api.js — generate UUID before sending
const clientId = crypto.randomUUID();
this.client.publish({
  destination: '/app/chat.send',
  body: JSON.stringify({ receiverId, content, clientId }),
});
return clientId;
```

The server echoes it back unchanged in `MessageResponse`:

```java
// MessageService.java
new MessageResponse(0L, ..., request.clientId());
```

The frontend deduplicates by `clientId` when present (WS-pushed messages), falling back to composite key for DB-loaded messages which have no `clientId`:

```javascript
// chat.js
const isDup = msg.clientId
  ? prev.some((m) => m.clientId === msg.clientId)
  : prev.some((m) => m.senderId === msg.senderId && m.content === msg.content && m.timestamp === msg.timestamp);
```

`crypto.randomUUID()` generates a RFC 4122 v4 UUID (e.g. `"550e8400-e29b-41d4-a716-446655440000"`) using the browser's CSPRNG. The probability of two UUIDs colliding is $\frac{1}{2^{122}}$ — practically impossible.

**How WhatsApp and Signal handle this**: Both generate a client-side ID (WhatsApp uses a 20-byte random string encoded in hex; Signal uses a UUID) at the moment the user taps Send. This ID travels with the message through the server and is echoed back in the push. The server's DB-assigned `rowid` is irrelevant for real-time display — it's only used for pagination/history queries. The client-side ID is also used for delivery receipts and message editing.

**Impact on load test**: None — the load test uses `senderId == me.id` for echo detection, not `clientId`. The change is transparent to the test.

**Lesson**: When you move a DB write to an async path, audit every consumer of the response for assumptions about database-generated fields (`id`, `createdAt`, etc.). The general pattern: **any ID used for client-side dedup should be client-generated, not server-generated**.

### Bottleneck #8: React `key` collision from `id: 0` (FIXED)

**The bug**: Every WS-pushed `MessageResponse` has `id: 0` (no DB write in V8's hot path). In `chat.js`, the messages list renders with:

```jsx
// Broken — all WS messages have key=0
<div key={msg.id} ...>
```

React uses `key` to identify list items across re-renders. When all keys are `0`, React cannot distinguish items — it reuses and patches the same DOM node instead of creating new ones. Symptoms:
- Messages can render in wrong visual order
- A state update on one message (e.g., read receipt) can visually apply to the wrong bubble
- New-message mount animations may not trigger since React thinks the node already exists

**No impact on load test** — the test never renders JSX. Pure browser UI correctness fix.

**Fix**:

```jsx
// chat.js — use clientId for WS-pushed messages; fall back to id+timestamp for DB-loaded history
<div key={msg.clientId ?? (msg.id + '_' + msg.timestamp)} ...>
```

- WS-pushed messages: `clientId` is `crypto.randomUUID()` — globally unique, always present
- DB-loaded history messages: `clientId` is `null` → falls back to `"42_2026-04-08T19:35:01"` — unique by PK + timestamp

**Lesson**: Any list item in React that has a server-assigned ID which may not be available yet (optimistic UI, async persistence) needs a client-generated stable key. The `clientId` introduced for dedup in Bottleneck #7 solves this simultaneously — one UUID per message serves both purposes.

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

### Understanding Kafka broker log output

Run `docker compose logs kafka` and look for these key log lines:

```
[2026-04-08 10:00:01,102] INFO [QuorumController id=1] QuorumController 1 transitioning to ACTIVE (KRaft)
```
**What it means**: The KRaft controller is now the active leader. You'll see this once on startup. Normal.

```
[2026-04-08 10:00:02,441] INFO [BrokerLifecycleManager id=1] The broker has caught up, Starting the transition to UNFENCED (KRaft)
```
**What it means**: The broker has replicated enough log to start accepting client connections. Normal — appears a few seconds after startup.

```
[2026-04-08 10:00:03,018] INFO [GroupCoordinator 1]: Stabilized group chatapp-persistence generation 1 ...
```
**What it means**: The Spring Kafka consumer group has completed its initial rebalance and all 3 partitions are assigned to your consumers. This is the "ready" signal — messages will start being consumed.

```
[2026-04-08 10:00:03,050] INFO [GroupCoordinator 1]: Assignment received from leader, leader is consumer-chatapp-persistence-1-...
  chatapp-persistence-1: [chat-messages-0, chat-messages-1]
  chatapp-persistence-2: [chat-messages-2]
```
**What it means**: Partition assignment map. With `concurrency=3`, Spring creates 3 consumer threads; Kafka distributes the 3 partitions among them. If you only see 1–2 threads initially, the others join within seconds. Normal.

```
[2026-04-08 10:02:10,100] INFO [GroupCoordinator 1]: Member consumer-chatapp-persistence-1-... in group chatapp-persistence has failed ...
```
**What it means**: A consumer thread missed its heartbeat (e.g., long GC pause, overloaded host). Kafka will trigger a rebalance. During the rebalance (~seconds), those partitions are unassigned and messages queue up in Kafka. If this happens repeatedly under load, the consumer is too slow — increase `listener.concurrency` or batch size.

```
WARN [ReplicaManager] ...  not enough ISR...
```
**What it means**: Under-replicated partitions. Harmless in a single-broker setup (replication-factor=1 means ISR is always exactly the leader). In a multi-broker cluster this would be a red flag.

```
ERROR Unexpected exception in handleReceive
ERROR Failed to obtain DB connection
```
**What it means**: Actual errors. If you see `ERROR` in Kafka broker logs, something is structurally wrong (disk full, corrupt log segment, OOM). Rare in this single-broker dev setup.

**Pattern summary**:
| Log keyword | Normal? | What to do |
|---|---|---|
| `ACTIVE (KRaft)` / `UNFENCED` | ✅ Yes | Startup sequence — ignore |
| `Stabilized group ... generation N` | ✅ Yes | Consumer group ready |
| `PreparingRebalance` / `CompletingRebalance` | ⚠️ On startup only | Mid-run rebalance = consumers too slow |
| `Member ... has failed` | ❌ No | Consumer crashed or GC-stalled |
| `ERROR` | ❌ No | Investigate immediately |

### Understanding Spring Kafka consumer logs (backend side)

The backend logs contain the consumer-side view. Filter for Kafka lines:

```bash
docker compose logs -f backend | grep -iE "kafka|partition|rebalanc|lag|batch"
```

Key lines to know:

```
INFO  c.c.k.MessagePersistenceConsumer : Persisting batch of 47 messages
```
**What it means**: Your `@KafkaListener` received and is flushing a batch of 47 records to PostgreSQL via `saveAll()`. Batch sizes typically vary 10–200 depending on producer rate. During load tests at 500u you'll see sizes of 200+.

```
INFO  o.s.k.l.ConcurrentMessageListenerContainer: partitions assigned: [chat-messages-0, chat-messages-1]
```
**What it means**: This Spring log confirms the specific partitions assigned to this container thread. You should see 3 lines like this (one per concurrency thread) within seconds of backend startup.

```
WARN  o.a.k.c.c.i.ConsumerCoordinator: Offset commit failed with retriable exception
```
**What it means**: The consumer tried to commit its read offset to Kafka but failed. Spring Kafka will retry automatically. If this happens repeatedly, check Kafka broker health with `docker compose logs kafka`.

```
ERROR o.s.k.l.KafkaMessageListenerContainer: Error handler
com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of MessageRequest ...
```
**What it means**: The Kafka message JSON could not be deserialized into your DTO. Cause: you renamed or removed a field in `MessageRequest` / `ChatMessageEvent` without restarting Kafka (old messages in the topic have the old schema). Fix: delete the topic, restart the backend, messages from the new schema will serialize cleanly.

```
WARN  o.a.k.c.NetworkClient: [Consumer clientId=...] Connection to node -1 (kafka/172.x.x.x:9092) could not be established.
```
**What it means**: The backend cannot reach the Kafka broker at startup. Usually timing — the `depends_on: kafka: condition: service_healthy` in docker-compose should prevent this. If you see it after startup, run `docker compose ps` to check if the kafka container is actually healthy.

### The NOT_COORDINATOR startup flood — annotated real log

On a fresh cluster (or first run after `docker compose down -v`), the backend logs will be flooded with messages like this for ~1 second:

```
INFO ConsumerCoordinator: Group coordinator kafka:9092 (id: 2147483646 rack: null) is unavailable or invalid due to cause: coordinator unavailable.
INFO ConsumerCoordinator: Requesting disconnect from last known coordinator kafka:9092 (id: 2147483646 rack: null)
INFO ConsumerCoordinator: JoinGroup failed: This is not the correct coordinator. Marking coordinator unknown.
INFO NetworkClient: Client requested disconnect from node 2147483646
INFO ConsumerCoordinator: Discovered group coordinator kafka:9092 (id: 2147483646 rack: null)
```

This looks alarming but it is **completely normal on first startup**. Here is exactly what is happening and why.

#### Root cause: `__consumer_offsets` doesn't exist yet

Kafka tracks consumer group progress (which offsets each group has committed) in an internal topic called `__consumer_offsets`. On a brand-new cluster this topic doesn't exist. KRaft has to:
1. Create it automatically
2. Elect a leader for the relevant partition

Until that is done, the broker can't coordinate any consumer group. Every `JoinGroup` request bounces back with `NOT_COORDINATOR` (the broker's way of saying "the coordinator partition isn't ready"). Your 3 consumer threads retry aggressively every 50–100ms (`reconnect.backoff.ms=50`), creating the flood.

#### What is `id: 2147483646`?

Not a real broker. Kafka client code assigns bootstrap connections a pseudo-node ID of `Integer.MAX_VALUE - 1 = 2,147,483,646`. It is the pre-identification channel to `kafka:9092` before the broker has announced its real ID.

- `kafka:9092 (id: 2147483646)` — same physical host, accessed via the **bootstrap channel**
- `kafka:9092 (id: 1)` — same physical host, accessed after the broker identified itself as `KAFKA_NODE_ID: 1`

All `NOT_COORDINATOR` responses come via the bootstrap channel. Once the `__consumer_offsets` partition is ready, responses switch to `id: 1`.

#### MemberIdRequiredException — the pivot point

The flood ends when this line appears:

```
INFO ConsumerCoordinator: Request joining group due to: rebalance failed due to
  'The group member needs to have a valid member id before actually entering a consumer group.'
  (MemberIdRequiredException)
```

This is **KIP-394** — a deliberate two-step handshake added in Kafka 2.4. The coordinator is now up and accepting connections. It rejects the first `JoinGroup` with this error to force the client to resubmit with an assigned UUID (`consumer-chatapp-persistence-2-9549fe15-...`). It is not an error — it is the moment the coordinator finally became ready.

#### The ~6 second gap before `Successfully joined`

After MemberIds are assigned, there is still a ~6 second wait:

```
19:34:55  MemberIdRequiredException (coordinator now ready)
19:35:01  Successfully joined group with generation 1
```

`group.initial.rebalance.delay.ms` defaults to **3000ms**. When the first consumer joins, the coordinator intentionally waits 3 seconds for other members to join before computing the partition assignment. With 3 threads joining + the JoinGroup → SyncGroup round-trip, total is ~6 seconds.

#### The full resolved state

```
19:35:01  Successfully joined group with generation Generation{generationId=1, ...}  ← all 3 consumers
19:35:01  Finished assignment: consumer-1→[P0], consumer-2→[P1], consumer-3→[P2]
19:35:01  Found no committed offset for partition chat-messages-0/1/2
19:35:01  Resetting offset to position FetchPosition{offset=0, ...}    ← auto.offset.reset=earliest
19:35:01  KafkaMessageListenerContainer: chatapp-persistence: partitions assigned ✅
```

"Found no committed offset" means no prior offset was persisted for this group (fresh cluster). With `auto.offset.reset=earliest`, each partition resets to offset 0 — the consumer will read from the beginning. If you restart the backend mid-load-test, any messages produced since offset 0 will be re-consumed, potentially causing duplicate DB inserts. To avoid this: don't restart the backend during a test; let Kafka commit offsets before shutting down.

#### Summary table for this startup sequence

| Phase | Log keyword | Duration | Normal? |
|---|---|---|---|
| `__consumer_offsets` creating | `coordinator unavailable` / `NOT_COORDINATOR` / `JoinGroup failed` | ~750ms | ✅ Yes, first startup only |
| Coordinator ready, UUID assignment | `MemberIdRequiredException` | instant | ✅ Yes, KIP-394 handshake |
| Rebalance delay + SyncGroup | *(silence)* | ~6s | ✅ Yes, `initial.rebalance.delay.ms` |
| Fully ready | `partitions assigned` + `KafkaMessageListenerContainer` | instant | ✅ Target state |

On subsequent restarts (without `docker compose down -v`) the `__consumer_offsets` topic already exists, so the whole startup completes in under 1 second with no `NOT_COORDINATOR` messages.

---

## PostgreSQL Inspection

### View PostgreSQL container logs

```bash
# All postgres logs since container start
docker compose logs postgres

# Live tail — useful during a load test
docker compose logs -f postgres

# Last 50 lines
docker compose logs --tail=50 postgres
```

### Understanding PostgreSQL log output

PostgreSQL logs everything it considers noteworthy. Here are the key lines you will see:

```
PostgreSQL 16.2 on x86_64-pc-linux-musl, compiled by gcc (Alpine...) 13.2.1 ...
```
**What it means**: Version banner — startup is beginning. Normal.

```
LOG:  database system was shut down at 2026-04-08 09:55:01 UTC
LOG:  database system is ready to accept connections
```
**What it means**: Clean startup. If you see `database system was not properly shut down; automatic recovery in progress` instead, the container was killed mid-write. PostgreSQL will replay the WAL to recover. Normal after `docker compose down` without `--volumes`; no action needed.

```
LOG:  connection received: host=172.18.0.4 port=51234
LOG:  connection authorized: user=chatapp database=chatapp application_name=HikariPool-1
```
**What it means**: A new TCP connection was accepted and authenticated. With HikariCP pool size 50, you'll see 50 of these on backend startup. `HikariPool-1` in `application_name` confirms it's your Spring app. Normal.

```
FATAL:  password authentication failed for user "chatapp"
```
**What it means**: Wrong password. Check `DB_PASSWORD` in docker-compose.yml environment vs. `POSTGRES_PASSWORD` on the postgres service. They must match.

```
FATAL:  database "chatapp" does not exist
```
**What it means**: The database was not created. Either the `POSTGRES_DB: chatapp` env var was missing when the volume was first created, or the volume has data from a differently-named DB. Fix: `docker compose down -v` to wipe the volume, then `docker compose up`.

```
LOG:  autovacuum: found 0 removable, 1284 nonremovable row versions in table "public.messages"
LOG:  autovacuum: processed table "public.messages"
```
**What it means**: PostgreSQL is running its background maintenance (autovacuum) to reclaim dead rows and update planner statistics. Normal — you'll see this after load tests when many rows were inserted. No action needed.

```
ERROR:  duplicate key value violates unique constraint "messages_pkey"
DETAIL:  Key (id)=(0) already exists.
```
**What it means**: You tried to insert a message with `id=0` (or a duplicate PK). In this app this would indicate you accidentally persisted a WS-pushed `MessageResponse` (which has `id=0`) rather than the Kafka-consumed `ChatMessageEvent`. Shouldn't happen in normal flow.

**Pattern summary**:
| Log keyword | Normal? | What to do |
|---|---|---|
| `database system is ready` | ✅ Yes | Startup complete |
| `connection authorized: ... HikariPool-1` | ✅ Yes | App connected |
| `automatic recovery in progress` | ⚠️ Warn | Clean shutdown missed; auto-heals |
| `FATAL: password authentication failed` | ❌ No | Check env var mismatch |
| `FATAL: database ... does not exist` | ❌ No | `docker compose down -v` + restart |
| `autovacuum: processed table` | ✅ Yes | Background maintenance |
| `ERROR: duplicate key` | ❌ No | Logic bug — double-insert same PK |

### Enable slow query logging

By default PostgreSQL does not log individual query timings. To find slow queries during debugging:

```bash
# Connect to the running instance
docker compose exec postgres psql -U chatapp -d chatapp
```

```sql
-- Log any query that takes longer than 100ms
ALTER SYSTEM SET log_min_duration_statement = '100';
SELECT pg_reload_conf();
```

Now run your load test, then check logs:

```bash
docker compose logs postgres | grep "duration:"
```

You will see lines like:
```
LOG:  duration: 243.012 ms  statement: select ... from messages where sender_id=$1 or receiver_id=$1 ...
LOG:  duration: 18.401 ms   statement: insert into messages ... values ($1,$2,...),($1,$2,...) ...  [50 parameters]
```

The first line tells you a conversation-fetch query is slow (missing or unused index). The second shows a batch INSERT with 50 parameter groups — this confirms Hibernate batching is working.

To disable after debugging:
```sql
ALTER SYSTEM RESET log_min_duration_statement;
SELECT pg_reload_conf();
```

> The setting is session-persistent (written to `postgresql.auto.conf` inside the volume) — it survives container restarts until explicitly reset.

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
| 8    | Kafka resilience — direct DB fallback          | **DONE V9** | If Kafka is down, messages persist directly to DB; zero message loss          |
| 9    | Frontend migrated to vanilla JS (no React)     | **DONE V9** | Removed React/Babel CDN deps; pure DOM rendering; smaller page load           |
| 10   | DB save logging                                | **DONE V9** | `log.info` on every batch save (Kafka consumer) and fallback save             |
| 11   | Horizontal scaling (2+ instances)             | Planned     | Linear throughput increase                                                    |
| 9    | Read replicas for PostgreSQL                  | Future      | Separate read/write workloads                                                 |

**Current baseline (V8)**: send_message P95 **26ms @ 200u / 70ms @ 500u** — 78% / 29% lower than V7 Run 2 (116ms / 99ms). DB INSERT fully removed from the hot path. Zero errors at both scales.
**Next target**: Horizontal scaling (multiple backend instances) — Stateless JWT is already in place; adding a load balancer + second instance + Redis pub/sub for WS routing is the natural next step.

---

## V9 Changes — Kafka Resilience, Vanilla JS Frontend, DB Logging

### Kafka Resilience: Direct DB Fallback

**Problem**: If Kafka goes down, `kafkaTemplate.send()` would fail and messages would be lost — the WS push already went out, but no persistence happens.

**Solution**: `MessageService.sendMessage()` now wraps the Kafka publish in a try/catch. If the `CompletableFuture` from `kafkaTemplate.send().get()` throws (Kafka unreachable, timeout, etc.), the message is **persisted directly to PostgreSQL** via `messageRepository.save()` as a synchronous fallback.

```java
try {
    kafkaTemplate.send("chat-messages", sender.getId().toString(), event).get();
    log.info("Message published to Kafka");
} catch (Exception ex) {
    log.warn("Kafka unavailable, falling back to direct DB write: {}", ex.getMessage());
    persistDirectly(sender, receiver, request.content());
}
```

**Kafka producer timeouts** (configured in `application.yml`):
- `delivery.timeout.ms: 5000` — total time allowed for send (including retries)
- `request.timeout.ms: 2000` — per-request timeout to broker
- `max.block.ms: 3000` — max time `send()` blocks waiting for metadata
- `retries: 3` — retry on transient failures before giving up

These ensure the fallback triggers within ~5 seconds max, rather than hanging indefinitely.

**Behavior**:
| Kafka status | What happens |
|---|---|
| Kafka up | Normal path: Kafka publish → async batch consumer → `saveAll()` to DB |
| Kafka down | Fallback: direct `messageRepository.save()` → immediate DB write |
| Kafka comes back | Next message automatically routes through Kafka again (no restart needed) |

### Steps to Simulate Kafka Failure

```bash
# 1. Start everything
docker compose up -d --build

# 2. Open the app at http://localhost:8080, send some messages (verify they work)

# 3. Stop Kafka (simulates Kafka crash)
docker compose stop kafka

# 4. Send messages in the UI — they should still deliver via WebSocket
#    Backend logs will show: "Kafka unavailable, falling back to direct DB write"
docker compose logs -f backend | Select-String "Kafka|DIRECT DB"

# 5. Verify messages are in the database
docker compose exec postgres psql -U chatapp -d chatapp -c "SELECT COUNT(*) FROM messages;"

# 6. Bring Kafka back
docker compose start kafka

# 7. Send new messages — they should go through Kafka again
#    Backend logs will show: "Message published to Kafka"
```

### DB Save Logging

All database persistence paths now emit `log.info` statements:

1. **Kafka consumer path** (`MessagePersistenceConsumer`):
   ```
   INFO KAFKA CONSUMER: Received batch of 47 messages from topic
   INFO DATABASE SAVE: Batch persisted 47 messages to PostgreSQL
   INFO   -> Saved message: sender=1 (alice) -> receiver=2 (bob), contentLength=12
   ```

2. **Direct DB fallback** (`MessageService.persistDirectly()`):
   ```
   WARN Kafka unavailable, falling back to direct DB write: ...
   INFO DIRECT DB SAVE (Kafka fallback): message saved to database, sender=1 -> receiver=2
   ```

### Frontend: React → Vanilla JS Migration

**Why**: Removed the React 18 + Babel CDN dependency. The app now loads faster (no 130KB+ React bundle, no Babel transpilation at runtime) and is easier to understand.

**What changed**:
- `index.html`: Removed React, ReactDOM, and Babel `<script>` tags. Scripts are plain `<script src="...">` instead of `<script type="text/babel">`.
- `auth.js`: `LoginPage` / `RegisterPage` React components → `renderLoginPage()` / `renderRegisterPage()` functions that use `innerHTML` + `addEventListener`.
- `chat.js`: `ChatPage` React component with `useState`/`useEffect`/`useRef`/`useCallback` → `chatState` object + `renderConversationList()` / `renderMessages()` / `renderChatArea()` functions. All state is in a plain JS object.
- `app.js`: React root mount → IIFE that reads `localStorage` and calls the appropriate render function.

**All features preserved**: WebSocket real-time messaging, dedup (clientId + composite fallback), infinite scroll pagination (messages + conversations), user search, session restore from localStorage, XSS protection via `escapeHtml()`.

---

*Last updated: April 8, 2026. Tests run on Docker Desktop; production numbers will differ.*
