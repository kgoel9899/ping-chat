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
13. [Scaling Roadmap](#scaling-roadmap)

> All load test results are in [docs/LOAD_TEST_RESULTS.md](LOAD_TEST_RESULTS.md).

---

## Overview

ChatApp is a WhatsApp-like real-time messaging application built as a **deliberately unoptimized V1 baseline**. The goal is to measure how a simple architecture performs under load, identify bottlenecks, and then iteratively improve scaling in future versions.

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

---

## System Architecture

```
+-----------------------------------------------------------+
|                     User's Browser                         |
|  React 18 (CDN) + Babel in-browser JSX transform          |
|  Polls:  /api/messages/conversations  every 3 seconds     |
|          /api/messages/conversation/X  every 2 seconds     |
+-------------------------------+---------------------------+
                                |
                          HTTP (port 8080)
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
|  |  POST /auth/register |  |  POST /messages           |   |
|  |  POST /auth/login    |  |  GET  /messages/conv/{id} |   |
|  +---------------------+  |  GET  /messages/convs      |   |
|                            +---------------------------+   |
|  +---------------------+                                   |
|  | UserController       |  +---------------------------+   |
|  |  GET /users/search   |  | Service Layer              |   |
|  +---------------------+  |  AuthService (BCrypt)       |   |
|                            |  MessageService (JPA)       |   |
|  +---------------------+  +---------------------------+   |
|  | JPA / Hibernate      |                                  |
|  | HikariCP Pool: 10    |                                  |
|  +----------+-----------+                                  |
+--------------+--------------------------------------------+
               |
         JDBC (port 5432)
               |
+--------------v--------------------------------------------+
|                    PostgreSQL 16                           |
|  Database: chatapp                                        |
|  Tables: users, messages                                  |
|  Indexes: sender_id, receiver_id, timestamp               |
+---------------------------------------------------------+
```

Key points:
- **Everything is in 2 Docker containers** (backend + postgres). No reverse proxy, no cache, no message broker.
- **Frontend is served from Spring Boot's static resources** — no separate web server.
- **No WebSockets** — the frontend uses HTTP polling at fixed intervals.
- **No connection pooling tuning** — HikariCP default max pool size of 10.

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

### Sending a message

```
Client                          Backend                         Database
  |                                |                               |
  |  POST /api/messages            |                               |
  |  Authorization: Bearer <jwt>   |                               |
  |  {receiverId, content}         |                               |
  |------------------------------->|                               |
  |                                |  JwtAuthFilter validates token |
  |                                |  Load sender from SecurityCtx  |
  |                                |  Load receiver by ID           |
  |                                |------------------------------>|
  |                                |  INSERT INTO messages          |
  |                                |  (sender, receiver, content,   |
  |                                |   timestamp=NOW, read=false)   |
  |                                |------------------------------>|
  |  {id, senderId, receiverId,    |                               |
  |   senderName, content,         |                               |
  |   timestamp, read}             |                               |
  |<-------------------------------|                               |
```

### Loading conversations (polling)

The frontend has **two polling loops** running simultaneously:

1. **Conversation list** — every **3 seconds**:
```
GET /api/messages/conversations
  -> Native SQL: SELECT DISTINCT CASE WHEN sender_id = :userId
                   THEN receiver_id ELSE sender_id END
                 FROM messages
                 WHERE sender_id = :userId OR receiver_id = :userId
  -> Returns List<Long> partner IDs
  -> Looks up User entities with findAllById(partnerIds)
  -> Returns List<UserResponse>
```

2. **Messages in selected chat** — every **2 seconds**:
```
GET /api/messages/conversation/{partnerId}
  -> JPQL: SELECT m FROM Message m
           WHERE (m.sender.id = :me AND m.receiver.id = :them)
              OR (m.sender.id = :them AND m.receiver.id = :me)
           ORDER BY m.timestamp ASC
  -> Returns all messages in the conversation (no pagination!)
```

**Why this matters for performance**: Every active browser tab generates ~0.8 requests/second just from polling, even when idle. With 1000 users online, that's 800 requests/second of background noise.

---

## Frontend Architecture

The frontend has **zero build tooling** — no webpack, no vite, no npm:

```
backend/src/main/resources/static/
  index.html          # Loads React 18 + Babel from CDN (unpkg.com)
  css/app.css         # WhatsApp dark theme
  js/
    api.js            # Fetch wrapper with console timing logs
    auth.js           # LoginPage + RegisterPage components
    chat.js           # ChatPage with polling, search, send
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
Phase 1: SETUP
  For each of N users:
    POST /api/auth/register -> store {userId, username, token}

Phase 2: LOAD (runs for D seconds)
  Each user runs in its own thread:
    loop until deadline:
      1. POST /api/auth/login          (re-authenticate)
      2. POST /api/messages             (send to random partner)
      3. GET  /api/messages/conv/{id}   (read conversation)
      4. GET  /api/messages/convs       (list all conversations)
      sleep(100-300ms random)

Phase 3: REPORT
  Aggregate all latencies per endpoint
  Calculate p50, p90, p95, p99 percentiles
  Print formatted summary table
```

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
docker run ... | Tee-Object -FilePath "loadtest\results\v6-index-200users-120s.txt"
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

**Fix (V5)**: `PageRequest.of(0, 15)` on `findConversation`; SQL `LIMIT 15` subquery on `findConversationPartnerIds`. Payloads now bounded to 15 rows regardless of history length. P95 537ms → 531ms.

**Fix (V6)**: Composite indexes `idx_msg_sender_ts (sender_id, timestamp DESC)` and `idx_msg_receiver_ts (receiver_id, timestamp DESC)`. Both paginated queries now walk a pre-ordered index instead of scanning+sorting. `get_conversation` P95 499ms → 462ms (−7.4%). Overall P95 504ms @ 200u / 499ms @ 500u.

### Bottleneck #4: HTTP Polling Overhead (MODERATE)

**The problem**: Every browser tab polls:
- `/api/messages/conversations` every 3 seconds
- `/api/messages/conversation/{id}` every 2 seconds

That's **~0.83 requests/second per user just for polling**, even when nothing has changed.

**At 1000 users**: 830 useless requests/second of background noise.

**Fix options for V2**:
- WebSockets (push instead of poll)
- Server-Sent Events (SSE) — simpler than WebSockets
- Long-polling with ETag/Last-Modified headers
- Increase poll interval when chat is idle

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

---

## Scaling Roadmap

| Step | Change                                        | Status      | Result / Impact                                                               |
|------|-----------------------------------------------|-------------|-------------------------------------------------------------------------------|
| 1    | HikariCP pool 10 → 50                        | **DONE V2** | DB endpoints 1.5-2.9x faster; revealed BCrypt saturation                     |
| 2    | Fix load test: login once per user            | **DONE V3** | True baseline: P95=586ms, 693 req/s at 200 users                             |
| 3    | Cache user lookups (`@Cacheable`)             | **DONE V4** | P50 improved; revealed pagination as next bottleneck                          |
| 4    | Pagination LIMIT 15 (messages + partner list) | **DONE V5** | P95 537ms @ 200u / 531ms @ 500u; 3.6x less data; P95 converges across scales |
| 5    | Composite DB index on messages                | **DONE V6** | P95 504ms @ 200u / 499ms @ 500u; read queries use pre-ordered index scans     |
| 6    | WebSockets / SSE for messaging                | Planned     | Remove polling (~0.83 req/s per idle user)                                    |
| 7    | Horizontal scaling (2+ instances)             | Future      | Linear throughput increase                                                    |
| 8    | Read replicas for PostgreSQL                  | Future      | Separate read/write workloads                                                 |

**Current baseline (V6)**: P95=504ms @ 200 users, P95=499ms @ 500 users. Throughput ~808–813 req/s.
**Next target**: WebSockets/SSE to eliminate polling overhead and address `send_message` write contention as the current dominant bottleneck.

---

*Last updated: April 6, 2026. Tests run on Docker Desktop; production numbers will differ.*
