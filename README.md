# ChatApp — WhatsApp-like Chat Application

A full-stack chat application built with **Spring Boot**, **React (CDN)**, **PostgreSQL**, and **Kafka**, containerized with **Docker**. Built as an iterative performance project — starting from a simple polling baseline (V1) and optimized through 8 versions to a real-time architecture with async persistence.

**Current version: V8** — Kafka async batch persistence, WebSocket (STOMP) messaging, composite DB indexes, user-lookup caching, pagination. send_message P95 **26ms @ 200u / 70ms @ 500u** (-78/-29% vs V7).

## Architecture (V8 — Current)

```
┌────────────────────────────────────┐       ┌────────────┐
│         Spring Boot :8080          │       │            │
│  ┌────────────┐  ┌──────────────┐ │       │ PostgreSQL │
│  │  Static    │  │   REST API   │ │ JDBC  │  :5432     │
│  │  HTML/JS   │  │  /api/*      │─┼──────▶│            │
│  │  React CDN │  │  /ws (STOMP) │ │       │  Indexes:  │
│  └────────────┘  └──────────────┘ │       │  sender_id │
└─────────────────┬──────────────────┘       │  +timestamp│
         │        │                          └────────────┘
         │  WS    │  Kafka producer                ▲
         │  push  │  (fire-and-forget)              │
         │        ▼                                 │
         │  ┌──────────────┐   batch saveAll()      │
         │  │  Kafka :9092 │──────────────────────────
         │  │  3 partitions│   (3 consumer threads,
         │  └──────────────┘    batch_size=50,
         │                      max_poll=500)
         ▼
      Real-time push. DB write is async via Kafka.
```

### What's been optimized (V1 → V8)
- ✅ **HikariCP pool** 10 → 50 (V2)
- ✅ **User lookup caching** `@Cacheable` on `findByUsername` (V4)
- ✅ **Pagination** LIMIT 15 per page with infinite scroll — scroll up for older messages, scroll down for more conversations (V5 + V7)
- ✅ **Composite DB indexes** `(sender_id, timestamp DESC)` and `(receiver_id, timestamp DESC)` (V6)
- ✅ **WebSocket (STOMP)** — HTTP polling replaced with persistent WS connection (V7)
- ✅ **Kafka async batch persistence** — DB INSERT removed from hot path; messages published to Kafka, consumed in batches of up to 500 by 3 parallel threads, flushed via `saveAll()` with `hibernate.jdbc.batch_size=50` (V8)
- **No build tooling** — React via CDN with Babel in-browser transform (intentional — keeps it simple)

## Tech Stack

| Layer     | Technology                        |
|-----------|----------------------------------|
| Frontend  | React 18 via CDN (plain JS, no build) |
| Backend   | Spring Boot 3.2, Java 17         |
| Auth      | JWT (jjwt)                        |
| Database  | PostgreSQL 16                     |
| Messaging | Apache Kafka 3.7.2 (KRaft mode)  |
| Container | Docker Compose                    |
| Load Test | Java (zero dependencies)           |

## Quick Start

### Prerequisites
- Docker & Docker Compose installed
- Java 17+ (for load testing — already in the backend Docker image)

### 1. Start the application

```bash
docker compose up --build
```

This starts 3 containers:
- **postgres** — Database on port 5432
- **kafka** — Apache Kafka (KRaft mode, no ZooKeeper) on port 9092
- **backend** — Spring Boot API + static frontend on port 8080

Wait until you see `Started ChatAppApplication` in the logs, then open **http://localhost:8080** in your browser.

### 2. Use the app

1. Go to http://localhost:8080
2. Click **Register** and create two accounts (use two browser tabs/profiles)
3. In one tab, search for the other user's username
4. Click on the user to start a conversation
5. Send messages — they appear instantly in the other tab via WebSocket push

## Docker Commands

### Start (foreground — see all logs live)
```bash
docker compose up --build
```

### Start (background)
```bash
docker compose up --build -d
```

### Check running containers
```bash
docker compose ps
```

### View all logs
```bash
docker compose logs
```

### View backend logs (follow mode — live)
```bash
docker compose logs -f backend
```

### View last 100 lines of backend logs
```bash
docker compose logs --tail=100 backend
```

### Watch API request logs
The backend logs every API request with method, path, auth status, response code, and duration:
```bash
docker compose logs -f backend | grep -E ">>>|<<<"
```
Example output:
```
>>> POST /api/auth/login (anon) from 172.18.0.1
<<< POST /api/auth/login 200 145ms
>>> POST /api/messages (auth) from 172.18.0.1
<<< POST /api/messages 200 12ms
```

### Stop everything
```bash
docker compose down
```

### Stop and remove volumes (wipes database)
```bash
docker compose down -v
```

### Rebuild from scratch
```bash
docker compose down -v
docker compose up --build
```

### Enter running containers
```bash
docker compose exec backend sh
docker compose exec postgres psql -U chatapp -d chatapp
```

## API Endpoints

### Auth (public)
| Method | Endpoint             | Description     |
|--------|---------------------|-----------------|
| POST   | `/api/auth/register` | Register user   |
| POST   | `/api/auth/login`    | Login, get JWT  |

### Messages (authenticated — `Authorization: Bearer <token>`)
| Method | Endpoint                          | Description              |
|--------|----------------------------------|--------------------------|
| POST   | `/api/messages`                   | Send a message           |
| GET    | `/api/messages/conversations`     | List conversation partners |
| GET    | `/api/messages/conversation/{id}` | Get messages with user   |

### Users (authenticated)
| Method | Endpoint                  | Description     |
|--------|--------------------------|-----------------|
| GET    | `/api/users/search?q=...` | Search users    |

### Example: curl usage
```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@test.com","password":"password123"}'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'

# Send message (use token from login response)
curl -X POST http://localhost:8080/api/messages \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"receiverId":2,"content":"Hello!"}'

# Get conversation
curl http://localhost:8080/api/messages/conversation/2 \
  -H "Authorization: Bearer <token>"
```

## Load Testing

The load test is a single Java file (`loadtest/LoadTest.java`) with **zero dependencies** — uses `java.net.http.HttpClient` (built into Java 17). No k6, no Gatling, no Maven needed.

### Run the load test

Make sure the app is running first (`docker compose up -d`), then run via Docker (uses the maven image already pulled during build):

```bash
# Warmup (200 users, 100s — not saved, warms JVM + DB page cache)
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 200 100

# High (200 users, 120s)
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 200 120

# Stress (500 users, 120s)
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 500 120
```

Arguments: `BASE_URL  NUM_USERS  DURATION_SECONDS` (defaults: `http://localhost:8080  100  60`)

> **If you have Java 17+ installed locally**, you can also run directly:
> ```bash
> cd loadtest && javac LoadTest.java && java LoadTest http://localhost:8080 50 60
> ```

### What the load test does

1. **Phase 1 — Register**: Creates N users via `POST /api/auth/register`
2. **Phase 1b — Login**: Each user logs in once via `POST /api/auth/login`, stores the JWT
3. **Phase 2 — Connect**: Each user opens a WebSocket connection and sends a STOMP `CONNECT` frame with their JWT, then subscribes to `/user/queue/messages`
4. **Phase 3 — Load** (runs for D seconds): Each user loops:
   - Sends a STOMP `SEND /app/chat.send` frame with a random partner's ID
   - Waits up to **5 seconds** for its own echo back on the WS subscription (the server pushes `MessageResponse` to both sender and receiver immediately — before the DB write, which happens async via Kafka)
   - If the echo doesn't arrive within 5s → counted as an error (echo timeout, not a server crash)
   - `get_conversation` and `get_conversations` run once on initial load only — not in the loop
5. **Phase 4 — Report**: Prints per-endpoint P50/P90/P95/P99 latencies, error rate, WS push count, throughput

> **Why 5 seconds?** The echo wait is a measurement deadline. In normal operation the echo arrives in ~15ms (P50). The 5s threshold only triggers under extreme TCP backpressure (seen at 0.04% rate with 500 users on Docker's bridge network). In production this would be negligible.

### Understanding the results

| Metric | What it means |
|--------|--------------|
| **P50** | Median latency — what a typical user experiences |
| **P90** | 90th percentile — only 10% of requests are slower |
| **P95** | 95th percentile — the target for most SLAs |
| **P99** | 99th percentile — worst-case (excluding outliers) |
| **Error Rate** | % of requests that timed out or returned non-200 |
| **WS Pushes** | Total WebSocket push frames received by all clients |

## Project Structure

```
chat-app/
├── backend/
│   ├── src/main/java/com/chatapp/
│   │   ├── ChatAppApplication.java          # Entry point
│   │   ├── config/
│   │   │   ├── SecurityConfig.java          # JWT security config
│   │   │   ├── WebSocketConfig.java         # STOMP WebSocket config
│   │   │   ├── KafkaConfig.java             # Kafka topic + batch consumer factory
│   │   │   └── RequestLoggingFilter.java    # Request/response logger
│   │   ├── security/
│   │   │   ├── JwtUtil.java                 # Token generation/validation
│   │   │   └── JwtAuthFilter.java           # JWT auth filter
│   │   ├── controller/
│   │   │   ├── AuthController.java          # Register/Login
│   │   │   ├── MessageController.java       # Send/receive messages
│   │   │   └── UserController.java          # User search
│   │   ├── service/
│   │   │   ├── AuthService.java             # Auth business logic (with logging)
│   │   │   └── MessageService.java          # WS push + Kafka publish (no sync DB write)
│   │   ├── kafka/
│   │   │   └── MessagePersistenceConsumer.java # Batch consumer — saveAll() from Kafka
│   │   ├── model/                           # JPA entities
│   │   ├── dto/                             # Request/Response records
│   │   ├── repository/                      # Spring Data repos
│   │   └── exception/                       # Global error handler
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── static/                          # Frontend (served by Spring Boot)
│   │       ├── index.html
│   │       ├── css/app.css
│   │       └── js/
│   │           ├── api.js                   # HTTP client with console logging
│   │           ├── auth.js                  # Login/Register components
│   │           ├── chat.js                  # Chat UI — real-time via WebSocket (no polling)
│   │           └── app.js                   # Main React app
│   ├── Dockerfile
│   └── pom.xml
├── loadtest/
│   └── LoadTest.java                        # Java load test (zero dependencies)
├── docker-compose.yml
├── .gitignore
└── README.md
```

## Debugging

### Backend logs
Both the request logging filter and service-layer logs are visible in Docker:
```bash
# All backend logs (live)
docker compose logs -f backend

# Just API requests
docker compose logs -f backend 2>&1 | findstr ">>>"

# Just service-level info (auth, messages)
docker compose logs -f backend 2>&1 | findstr "INFO"
```

### Frontend logs
Open browser DevTools (F12) → Console tab. The JS API client logs every request:
```
[API] POST /api/auth/login {username: "alice", password: "..."}
[API] 200 /api/auth/login (145ms) ok
[API] GET /api/messages/conversations
[API] 200 /api/messages/conversations (23ms) [3 items]
```

### Database queries
```bash
docker compose exec postgres psql -U chatapp -d chatapp

# Check tables
\dt

# Count messages
SELECT count(*) FROM messages;

# See recent messages
SELECT m.id, s.username as sender, r.username as receiver, m.content, m.timestamp
FROM messages m
JOIN users s ON m.sender_id = s.id
JOIN users r ON m.receiver_id = r.id
ORDER BY m.timestamp DESC LIMIT 10;
```

## Evolution Roadmap

| Version | Changes | Result |
|---------|---------|--------|
| **V1** | Baseline: HTTP polling, pool=10, no cache, no pagination | P95=2,086ms @ 200u |
| **V2** | HikariCP pool 10 → 50 | P95 improved on DB endpoints; revealed BCrypt CPU bottleneck |
| **V3** | Fixed load test: login once per user | True baseline P95=586ms @ 200u |
| **V4** | `@Cacheable` on user lookup | P50 improved; revealed pagination bottleneck |
| **V5** | LIMIT 15 on messages + partner list | P95=537ms @ 200u / 531ms @ 500u; 3.6x less data |
| **V6** | Composite indexes `(sender_id, timestamp DESC)` etc. | P95=504ms @ 200u / 499ms @ 500u |
| **V7** | WebSocket (STOMP) — replaced HTTP polling | P95=105ms @ 200u / 99ms @ 500u; polling eliminated |
| **V8 (current)** | Kafka async batch persistence — DB write removed from hot path | P95=26ms @ 200u / 70ms @ 500u (-78/-29%) |
| **V9 (planned)** | Horizontal scaling (2+ instances + load balancer) | Linear throughput increase |

Each version will include load test results to demonstrate the measurable improvement.

## Troubleshooting

**Backend won't start?**
```bash
docker compose logs backend
# Look for connection errors — postgres might still be starting
docker compose restart backend
```

**Database issues?**
```bash
docker compose exec postgres psql -U chatapp -d chatapp
\dt
SELECT count(*) FROM messages;
```

**Rebuild from scratch?**
```bash
docker compose down -v
docker compose up --build
```
