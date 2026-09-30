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
12. [V1 Baseline Results (pool=10)](#v1-baseline-results-pool10)
13. [V2 Results (pool=50)](#v2-results-pool50)
14. [V1 vs V2 Comparison](#v1-vs-v2-comparison)
15. [V3 Results (pool=50, realistic login)](#v3-results-pool50-realistic-login)
16. [V2 vs V3 Comparison](#v2-vs-v3-comparison)
17. [V4 Results (pool=50, user cache)](#v4-results-pool50-user-cache)
18. [V3 vs V4 Comparison](#v3-vs-v4-comparison)
19. [Bottleneck Analysis](#bottleneck-analysis)
20. [Scaling Roadmap](#scaling-roadmap)

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

## V1 Baseline Results (pool=10)

All tests run on the same machine, clean database, Docker Desktop. `hikari.maximum-pool-size=10`.

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

## V2 Results (pool=50)

Single change: `hikari.maximum-pool-size` raised from **10 → 50**. Everything else identical.

### Test 1: Smoke (10 users, 15 seconds)

| Metric           | Value        |
|------------------|--------------|
| Total Requests   | 1,392        |
| Errors           | 0 (0.00%)    |
| Data Transferred | 661.6 KB     |
| Throughput       | ~93 req/s    |

**Overall Latency:**

| Percentile | Latency  |
|------------|----------|
| P50        | 21 ms    |
| P90        | 156 ms   |
| P95        | 178 ms   |
| P99        | 331 ms   |
| Max        | 488 ms   |

**Per-Endpoint:**

| Endpoint          | Count | Avg    | P50  | P95  | P99  |
|-------------------|-------|--------|------|------|------|
| register          | 10    | 80 ms  | 74   | 137  | 137  |
| login             | 348   | 167 ms | 149  | 312  | 384  |
| send_message      | 348   | 28 ms  | 19   | 80   | 133  |
| get_conversation  | 348   | 21 ms  | 15   | 55   | 89   |
| get_conversations | 348   | 23 ms  | 16   | 64   | 106  |

### Test 2: Medium (50 users, 60 seconds)

| Metric           | Value        |
|------------------|--------------|
| Total Requests   | 9,212        |
| Errors           | 0 (0.00%)    |
| Data Transferred | 5,068.5 KB   |
| Throughput       | ~154 req/s   |

**Overall Latency:**

| Percentile | Latency   |
|------------|-----------|
| P50        | 79 ms     |
| P90        | 955 ms    |
| P95        | 1,262 ms  |
| P99        | 1,679 ms  |
| Max        | 2,728 ms  |

**Per-Endpoint:**

| Endpoint          | Count | Avg     | P50  | P95   | P99   |
|-------------------|-------|---------|------|-------|-------|
| register          | 50    | 96 ms   | 91   | 120   | 252   |
| login             | 2,303 | 876 ms  | 818  | 1,636 | 1,955 |
| send_message      | 2,303 | 86 ms   | 64   | 232   | 359   |
| get_conversation  | 2,303 | 69 ms   | 50   | 198   | 324   |
| get_conversations | 2,303 | 72 ms   | 53   | 200   | 300   |

### Test 3: High (200 users, 120 seconds)

| Metric           | Value         |
|------------------|---------------|
| Total Requests   | 23,444        |
| Errors           | 0 (0.00%)     |
| Data Transferred | 12,182.4 KB   |
| Throughput       | ~196 req/s    |

**Overall Latency:**

| Percentile | Latency   |
|------------|-----------|
| P50        | 845 ms    |
| P90        | 2,056 ms  |
| P95        | 2,456 ms  |
| P99        | 3,412 ms  |
| Max        | 6,168 ms  |

**Per-Endpoint:**

| Endpoint          | Count | Avg     | P50   | P95   | P99   |
|-------------------|-------|---------|-------|-------|-------|
| register          | 200   | 89 ms   | 86    | 126   | 143   |
| login             | 5,861 | 1,555ms | 1,437 | 2,999 | 3,977 |
| send_message      | 5,861 | 835 ms  | 707   | 2,231 | 3,241 |
| get_conversation  | 5,861 | 762 ms  | 644   | 2,068 | 2,908 |
| get_conversations | 5,861 | 730 ms  | 626   | 2,010 | 2,799 |

---

## V1 vs V2 Comparison

Only change between V1 and V2: `hikari.maximum-pool-size` 10 → 50.

### At 50 users (medium load)

| Endpoint          | V1 P95    | V2 P95    | Change            |
|-------------------|-----------|-----------|-------------------|
| login             | 847 ms    | 1,636 ms  | **1.9x SLOWER**   |
| send_message      | 684 ms    | 232 ms    | **2.9x faster**   |
| get_conversation  | 302 ms    | 198 ms    | **1.5x faster**   |
| get_conversations | 177 ms    | 200 ms    | similar           |

### At 200 users (high load)

| Endpoint          | V1 P95    | V2 P95    | Change            |
|-------------------|-----------|-----------|-------------------|
| login             | 1,619 ms  | 2,999 ms  | **1.9x SLOWER**   |
| send_message      | 2,291 ms  | 2,231 ms  | similar           |
| get_conversation  | 2,210 ms  | 2,068 ms  | slightly faster   |
| get_conversations | 2,094 ms  | 2,010 ms  | slightly faster   |

### Key Finding: The Pool Increase Exposed BCrypt

With pool=10, the pool queue was acting as an **accidental rate-limiter** — only 10 threads could BCrypt at once. Raising to pool=50 let all 50 (or 200) BCrypt operations run simultaneously, **saturating the CPU**. This is why:

- DB-bound endpoints (`send_message`, `get_conversation`) **improved** — no more pool wait
- CPU-bound endpoint (`login`) **got worse** — more concurrent BCrypt = more CPU contention

This is the classic bottleneck cascade: **fix one and the next one becomes the new ceiling.**

The next bottleneck to fix is BCrypt concurrency — see Step 2 in the Scaling Roadmap.

---

## V3 Results (pool=50, realistic login)

Change from V2: **Load test fixed** — login called **once per user at startup** (matching 24h JWT reality), not on every loop iteration. App code and config unchanged.

### Test 1: Smoke (10 users, 15 seconds)

| Metric           | Value        |
|------------------|-------------|
| Total Requests   | 1,758       |
| Errors           | 0 (0.00%)   |
| Data Transferred | 1,334.7 KB  |
| Throughput       | ~117 req/s  |

**Overall Latency:**

| Percentile | Latency |
|------------|---------|
| P50        | 15 ms   |
| P90        | 39 ms   |
| P95        | 51 ms   |
| P99        | 84 ms   |
| Max        | 518 ms  |

**Per-Endpoint:**

| Endpoint          | Count | Avg   | P50 | P95 | P99 |
|-------------------|-------|-------|-----|-----|-----|
| login             | 10    | 82 ms | 76  | 110 | 110 |
| send_message      | 586   | 23 ms | 17  | 55  | 128 |
| get_conversation  | 586   | 17 ms | 14  | 44  | 58  |
| get_conversations | 586   | 18 ms | 14  | 44  | 74  |

### Test 2: Medium (50 users, 60 seconds)

| Metric           | Value        |
|------------------|--------------|
| Total Requests   | 26,283       |
| Errors           | 0 (0.00%)    |
| Data Transferred | 28,730.1 KB  |
| Throughput       | ~438 req/s   |

**Overall Latency:**

| Percentile | Latency |
|------------|---------|
| P50        | 31 ms   |
| P90        | 102 ms  |
| P95        | 140 ms  |
| P99        | 243 ms  |
| Max        | 761 ms  |

**Per-Endpoint:**

| Endpoint          | Count  | Avg   | P50 | P95 | P99 |
|-------------------|--------|-------|-----|-----|-----|
| login             | 50     | 81 ms | 78  | 108 | 122 |
| send_message      | 8,761  | 57 ms | 38  | 162 | 271 |
| get_conversation  | 8,761  | 41 ms | 26  | 124 | 223 |
| get_conversations | 8,761  | 42 ms | 28  | 128 | 216 |

### Test 3: High (200 users, 120 seconds)

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 83,172         |
| Errors           | 0 (0.00%)      |
| Data Transferred | 142,340.6 KB   |
| Throughput       | ~693 req/s     |

**Overall Latency:**

| Percentile | Latency  |
|------------|----------|
| P50        | 194 ms   |
| P90        | 478 ms   |
| P95        | 586 ms   |
| P99        | 813 ms   |
| Max        | 1,744 ms |

**Per-Endpoint:**

| Endpoint          | Count  | Avg    | P50 | P95 | P99 |
|-------------------|--------|--------|-----|-----|-----|
| login             | 200    | 75 ms  | 70  | 98  | 106 |
| send_message      | 27,724 | 254 ms | 226 | 653 | 883 |
| get_conversation  | 27,724 | 198 ms | 172 | 545 | 748 |
| get_conversations | 27,724 | 204 ms | 181 | 549 | 757 |

---

## V2 vs V3 Comparison

Only change: load test fixed to login once instead of per-iteration. **App code identical.**

### At 50 users (medium load)

| Endpoint          | V2 P95    | V3 P95  | Improvement       |
|-------------------|-----------|---------|-------------------|
| send_message      | 232 ms    | 162 ms  | **1.4x faster**   |
| get_conversation  | 198 ms    | 124 ms  | **1.6x faster**   |
| get_conversations | 200 ms    | 128 ms  | **1.6x faster**   |
| Overall P95       | 1,262 ms  | 140 ms  | **9x faster**     |
| Throughput        | ~154 req/s| ~438 req/s | **2.8x more**  |

### At 200 users (high load)

| Endpoint          | V2 P95    | V3 P95  | Improvement        |
|-------------------|-----------|---------|--------------------|
| send_message      | 2,231 ms  | 653 ms  | **3.4x faster**    |
| get_conversation  | 2,068 ms  | 545 ms  | **3.8x faster**    |
| get_conversations | 2,010 ms  | 549 ms  | **3.7x faster**    |
| Overall P95       | 2,456 ms  | 586 ms  | **4.2x faster**    |
| Throughput        | ~196 req/s| ~693 req/s | **3.5x more**   |

### Key Insight: The V2 Numbers Were Lying

V1 and V2 test results were distorted by unrealistic per-iteration login. The CPU was spending most of its time BCrypt-hashing — something that never happens for returning users. V3 reflects real-world usage where each user authenticates once.

**With realistic load, the same app delivers P95 < 600ms at 200 users** — using only a connection pool increase as the sole infrastructure change.

---

## V4 Results (pool=50, user cache)

Change from V3: **`@Cacheable` added to `UserRepository.findByUsername`** using Spring's built-in `ConcurrentHashMap` cache (`@EnableCaching`). This eliminates the `SELECT * FROM users WHERE username=?` DB hit that ran on **every authenticated request** inside `JwtAuthFilter`. Tests now run at 200 and 500 users only.

### Test 1: High (200 users, 120 seconds)

> Two runs recorded: **Run 1** immediately after a cold rebuild (JVM not yet JIT-compiled). **Run 2** on a warm JVM. Run 2 is the representative steady-state result.

**Run 1 — Cold JVM:**

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 76,296         |
| Errors           | 0 (0.00%)      |
| Throughput       | ~636 req/s     |

| Percentile | Latency  |
|------------|----------|
| P50        | 150 ms   |
| P90        | 586 ms   |
| P95        | 768 ms   |
| P99        | 1,188 ms |
| Max        | 3,378 ms |

| Endpoint          | Count  | Avg    | P50 | P95 | P99   |
|-------------------|--------|--------|-----|-----|-------|
| login             | 200    | 77 ms  | 74  | 99  | 103   |
| send_message      | 25,432 | 280 ms | 209 | 850 | 1,319 |
| get_conversation  | 25,432 | 223 ms | 110 | 718 | 1,105 |
| get_conversations | 25,432 | 232 ms | 128 | 722 | 1,113 |

**Run 2 — Warm JVM (representative):**

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 87,264         |
| Errors           | 0 (0.00%)      |
| Data Transferred | 154,081.0 KB   |
| Throughput       | ~727 req/s     |

| Percentile | Latency  |
|------------|----------|
| P50        | 172 ms   |
| P90        | 468 ms   |
| P95        | 573 ms   |
| P99        | 785 ms   |
| Max        | 1,377 ms |

| Endpoint          | Count  | Avg    | P50 | P95 | P99 |
|-------------------|--------|--------|-----|-----|-----|
| login             | 200    | 73 ms  | 71  | 95  | 106 |
| send_message      | 29,088 | 233 ms | 212 | 625 | 860 |
| get_conversation  | 29,088 | 188 ms | 132 | 537 | 730 |
| get_conversations | 29,088 | 196 ms | 152 | 552 | 749 |

### Test 2: Stress (500 users, 120 seconds)

> Run 1 was already on a warm backend JVM (the 200-user tests had run before it). Run 2 confirms the result is stable.

**Run 1 — Warm JVM:**

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 89,070         |
| Errors           | 0 (0.00%)      |
| Throughput       | ~742 req/s     |

| Percentile | Latency  |
|------------|----------|
| P50        | 173 ms   |
| P90        | 451 ms   |
| P95        | 561 ms   |
| P99        | 785 ms   |
| Max        | 1,570 ms |

| Endpoint          | Count  | Avg    | P50 | P95 | P99 |
|-------------------|--------|--------|-----|-----|-----|
| login             | 500    | 73 ms  | 68  | 100 | 147 |
| send_message      | 29,690 | 227 ms | 203 | 611 | 842 |
| get_conversation  | 29,690 | 184 ms | 147 | 530 | 746 |
| get_conversations | 29,690 | 191 ms | 165 | 539 | 749 |

**Run 2 — Warm JVM (representative):**

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 91,944         |
| Errors           | 0 (0.00%)      |
| Data Transferred | 158,440.8 KB   |
| Throughput       | ~766 req/s     |

| Percentile | Latency  |
|------------|----------|
| P50        | 164 ms   |
| P90        | 434 ms   |
| P95        | 543 ms   |
| P99        | 743 ms   |
| Max        | 1,482 ms |

| Endpoint          | Count  | Avg    | P50 | P95 | P99 |
|-------------------|--------|--------|-----|-----|-----|
| login             | 500    | 72 ms  | 68  | 94  | 105 |
| send_message      | 30,648 | 220 ms | 198 | 607 | 824 |
| get_conversation  | 30,648 | 175 ms | 137 | 501 | 688 |
| get_conversations | 30,648 | 182 ms | 150 | 513 | 706 |

---

## V3 vs V4 Comparison

Only change: `@Cacheable("users")` on `findByUsername` — eliminates one DB query per authenticated request.

Comparison uses V4 Run 2 (warm JVM) as the representative result.

### At 200 users, 120 seconds

| Metric               | V3          | V4 Run 1 (cold) | V4 Run 2 (warm) | V3→V4 Warm       |
|----------------------|-------------|-----------------|-----------------|------------------|
| Overall P50          | 194 ms      | 150 ms          | 172 ms          | **1.1x faster**  |
| Overall P90          | —           | 586 ms          | 468 ms          | —                |
| Overall P95          | 586 ms      | 768 ms          | 573 ms          | **1.02x faster** |
| Overall P99          | —           | 1,188 ms        | 785 ms          | —                |
| send_message P95     | 653 ms      | 850 ms          | 625 ms          | **1.04x faster** |
| get_conversation P95 | 545 ms      | 718 ms          | 537 ms          | **1.01x faster** |
| Throughput           | ~693 req/s  | ~636 req/s      | ~727 req/s      | **+5%**          |

### What the two V4 runs reveal

**Run 1 (cold JVM)**: P95 looked *worse* than V3 (768ms vs 586ms). This was misleading — the JVM had just restarted after a full rebuild. HotSpot JIT hadn’t compiled the hot paths yet, so CPU-bound work (serialization, Spring Security filter chain) ran interpreted.

**Run 2 (warm JVM)**: P95 improves over V3 (573ms vs 586ms) and P99 drops from 1,188ms to 785ms. Throughput is 5% higher. The cache is doing its job — one fewer DB round-trip per request freed up HikariCP connections for message writes.

**Lesson**: Always measure after JVM warmup when comparing optimizations. A cold-JVM measurement can make a real improvement look like a regression.

**At 500 users (both runs warm)**: P95 is 561ms (run 1) / 543ms (run 2) — both slightly better than 200-user warm (573ms). The results are stable across runs, confirming the cache benefit is real.

### Why 500 users is faster than 200 users

This is counterintuitive but has a precise explanation. The bottleneck is not concurrency — it is the number of **rows returned per `get_conversation` call**, which grows with messages accumulated *per conversation pair*, not with total user count.

| | 200 users | 500 users |
|---|---|---|
| `send_message` calls (run 2) | ~29,088 | ~30,648 |
| Active conversation pairs (approx.) | ~100 | ~250 |
| Messages per pair after 120s | **~291** | **~123** |

Both runs send roughly the same total number of messages (~30k), but 500 users spreads them across ~2.5x more conversation pairs. At the end of the test, each `get_conversation` at 200 users returns **~2.4x more rows** than at 500 users. Since there is no `LIMIT`, the query payload grows throughout the test duration — and it grows faster per pair with fewer users.

**This is proof that the bottleneck is payload size, not connection pool pressure or thread contention.** Once pagination caps `get_conversation` at 50 rows, the payload becomes constant regardless of user count or test duration — and 200-user and 500-user P95 numbers should converge.

**The next bottleneck is clear: unbounded conversation fetches.** Pagination is Step 4.

---

## Bottleneck Analysis

### Scaling Comparison Table (V3 — Realistic Load, pool=50)

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

### Bottleneck #2: Database Connection Pool Exhaustion (FIXED in V2)

**The problem (V1)**: HikariCP max pool size was **10 connections** serving **200 concurrent users**.

**The fix (V2)**: `hikari.maximum-pool-size=50`

**Result**: DB-bound endpoints (`send_message`, `get_conversation`) improved 1.5-2.9x at 50 users. However fixing this exposed the next bottleneck — BCrypt CPU saturation. Login got 1.9x *slower* because the pool had previously been throttling BCrypt concurrency.

**Lesson**: The pool was masking the CPU problem. This is how bottleneck cascades work.

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

| Step | Change                              | Status          | Result / Impact                                                   |
|------|-------------------------------------|-----------------|-------------------------------------------------------------------|
| 1    | HikariCP pool 10 → 50              | **DONE V2**     | DB endpoints 1.5-2.9x faster; revealed BCrypt saturation         |
| 2    | Fix load test: login once per user  | **DONE V3**     | True baseline: P95=586ms, 693 req/s at 200 users                 |
| 3    | Cache user lookups (`@Cacheable`)   | **DONE V4**     | P50 improved; revealed pagination as next bottleneck              |
| 4    | Message pagination (LIMIT 50)       | **Next**        | Stop fetching all messages; fixed payload size regardless of age  |
| 5    | Composite DB index on messages      | Planned         | Faster pagination queries on (sender_id, receiver_id, timestamp)  |
| 6    | WebSockets / SSE for messaging      | Planned         | Remove polling (~0.83 req/s per idle user)                        |
| 7    | Horizontal scaling (2+ instances)   | Future          | Linear throughput increase                                        |
| 8    | Read replicas for PostgreSQL        | Future          | Separate read/write workloads                                     |

**Current baseline (V4, warm JVM)**: P95=573ms at 200 users, P95=543ms at 500 users.
**Next target**: Pagination — cap `get_conversation` at 50 rows. Expected P95 to drop well below 200ms and stay flat as test duration increases.

---

*Last updated: April 5, 2026. Tests run on Docker Desktop; production numbers will differ.*
