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
12. [Load Test Results](#load-test-results)
13. [Bottleneck Analysis](#bottleneck-analysis)
14. [Scaling Roadmap](#scaling-roadmap)

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

| Index Name       | Column      | Purpose                        |
|------------------|-------------|--------------------------------|
| idx_msg_sender   | sender_id   | Fast lookup by sender          |
| idx_msg_receiver | receiver_id | Fast lookup by receiver        |
| idx_msg_timestamp| timestamp   | Ordering messages              |

**What's missing** (intentionally, for V1):
- No composite index on `(sender_id, receiver_id, timestamp)` — would speed up conversation queries
- No pagination — all messages are loaded every poll
- No `updated_at` / `last_message_at` for efficient conversation sorting

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
# Smoke (light)
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest \
  maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 10 15

# Medium
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest \
  maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 50 60

# High
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest \
  maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 200 120
```

---

## Load Test Results

All tests run on the same machine, clean database, Docker Desktop.

### Test 1: Smoke (10 users, 15 seconds)

| Metric           | Value        |
|------------------|-------------|
| Total Requests   | 1,468       |
| Errors           | 0 (0.00%)   |
| Data Transferred | 717.5 KB    |
| Throughput       | ~98 req/s   |

**Overall Latency:**

| Percentile | Latency  |
|------------|----------|
| P50        | 21 ms    |
| P90        | 154 ms   |
| P95        | 168 ms   |
| P99        | 241 ms   |
| Max        | 320 ms   |

**Per-Endpoint:**

| Endpoint          | Count | Avg   | P50  | P95  | P99  |
|-------------------|-------|-------|------|------|------|
| register          | 10    | 86 ms | 79   | 159  | 159  |
| login             | 367   | 153ms | 147  | 237  | 285  |
| send_message      | 367   | 24 ms | 19   | 53   | 86   |
| get_conversation  | 367   | 19 ms | 15   | 43   | 88   |
| get_conversations | 367   | 20 ms | 16   | 49   | 67   |

### Test 2: Medium (50 users, 60 seconds)

| Metric           | Value        |
|------------------|-------------|
| Total Requests   | 10,728      |
| Errors           | 0 (0.00%)   |
| Data Transferred | 6,276.0 KB  |
| Throughput       | ~179 req/s  |

**Overall Latency:**

| Percentile | Latency  |
|------------|----------|
| P50        | 143 ms   |
| P90        | 555 ms   |
| P95        | 673 ms   |
| P99        | 930 ms   |
| Max        | 1,724 ms |

**Per-Endpoint:**

| Endpoint          | Count | Avg    | P50  | P95  | P99   |
|-------------------|-------|--------|------|------|-------|
| register          | 50    | 72 ms  | 71   | 77   | 138   |
| login             | 2,682 | 436 ms | 409  | 847  | 1,096 |
| send_message      | 2,682 | 311 ms | 288  | 684  | 909   |
| get_conversation  | 2,682 | 105 ms | 77   | 302  | 559   |
| get_conversations | 2,682 | 65 ms  | 45   | 177  | 389   |

### Test 3: High (200 users, 120 seconds)

| Metric           | Value         |
|------------------|--------------|
| Total Requests   | 24,780       |
| Errors           | 0 (0.00%)    |
| Data Transferred | 13,331.5 KB  |
| Throughput       | ~207 req/s   |

**Overall Latency:**

| Percentile | Latency   |
|------------|-----------|
| P50        | 819 ms    |
| P90        | 1,738 ms  |
| P95        | 2,086 ms  |
| P99        | 2,812 ms  |
| Max        | 5,038 ms  |

**Per-Endpoint:**

| Endpoint          | Count | Avg     | P50  | P95   | P99   |
|-------------------|-------|---------|------|-------|-------|
| register          | 200   | 69 ms   | 68   | 79    | 91    |
| login             | 6,195 | 830 ms  | 716  | 1,619 | 2,391 |
| send_message      | 6,195 | 1,021ms | 931  | 2,291 | 3,002 |
| get_conversation  | 6,195 | 937 ms  | 867  | 2,210 | 2,901 |
| get_conversations | 6,195 | 885 ms  | 827  | 2,094 | 2,812 |

---

## Bottleneck Analysis

### Scaling Comparison Table

| Metric               | 10 users | 50 users | 200 users | Degradation (10->200) |
|----------------------|----------|----------|-----------|----------------------|
| P50 overall          | 21 ms    | 143 ms   | 819 ms    | **39x worse**        |
| P95 overall          | 168 ms   | 673 ms   | 2,086 ms  | **12x worse**        |
| P99 overall          | 241 ms   | 930 ms   | 2,812 ms  | **12x worse**        |
| P95 login            | 237 ms   | 847 ms   | 1,619 ms  | **7x worse**         |
| P95 send_message     | 53 ms    | 684 ms   | 2,291 ms  | **43x worse**        |
| P95 get_conversation | 43 ms    | 302 ms   | 2,210 ms  | **51x worse**        |
| Throughput (req/s)   | ~98      | ~179     | ~207      | 2x (plateaued)       |

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

### Bottleneck #2: Database Connection Pool Exhaustion (CRITICAL)

**The problem**: HikariCP max pool size is **10 connections** serving **200 concurrent users**.

At 200 users, every request needs a DB connection. With only 10 available:
- 190 threads wait in the HikariCP queue
- Wait time compounds — each thread holds a connection for the entire request
- This explains why ALL endpoints degrade at 200 users, not just login

**Evidence**: At 50 users, `get_conversations` avg = 65ms. At 200 users, avg = 885ms. The query itself hasn't changed — the threads are just waiting for connections.

**Fix options for V2**:
- Increase `hikari.maximum-pool-size` to 50-100
- Add connection pool monitoring (HikariCP metrics endpoint)
- Use reactive/non-blocking DB drivers (R2DBC)

### Bottleneck #3: No Pagination — Growing Query Payload (MODERATE)

**The problem**: `GET /api/messages/conversation/{id}` returns **ALL messages** between two users. No limit, no pagination.

- Early in the test: returns 1-5 messages → fast
- Late in test (200 users, 120s): could return hundreds of messages per pair → slow, large payload

**Evidence**: `get_conversation` avg goes from 19ms (10 users) to 937ms (200 users) — 49x degradation, the worst of any endpoint.

**Fix options for V2**:
- Add `LIMIT 50 OFFSET ?` pagination
- Add cursor-based pagination using `timestamp`
- Only fetch messages since last-seen timestamp

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

### Bottleneck #5: No Caching (LOW-MEDIUM)

**The problem**: Every request hits the database. There is no caching layer.

- User lookups during JWT validation hit DB every time
- Conversation partner lists are recalculated from scratch every 3s
- Same data is served repeatedly without any cache

**Fix options for V2**:
- Add Redis for session/token caching
- Spring Cache annotations on conversation list queries
- HTTP response caching headers for rarely-changing data

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

Based on the bottleneck analysis, here's the priority order for V2 improvements:

| Priority | Change                              | Expected Impact on P95         |
|----------|-------------------------------------|-------------------------------|
| 1        | Increase HikariCP pool to 50        | 3-5x improvement at high load |
| 2        | Add message pagination (LIMIT 50)   | 2-3x improvement, less I/O   |
| 3        | Cache JWT user lookups (Redis)      | Eliminate redundant DB reads  |
| 4        | WebSockets for messaging            | Eliminate polling overhead     |
| 5        | Token caching (avoid re-login)      | Remove BCrypt from hot path   |
| 6        | Composite DB index                  | Faster conversation queries   |
| 7        | Horizontal scaling (2+ instances)   | Linear throughput increase    |
| 8        | Read replicas                       | Separate read/write workloads |

**Target for V2**: P95 < 200ms at 200 concurrent users (currently 2,086ms — need 10x improvement).

---

*Document generated from load tests run on April 5, 2026. Results are specific to the test machine (Docker Desktop, shared resources). Production numbers will vary.*
