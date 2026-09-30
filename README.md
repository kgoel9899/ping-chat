# ChatApp — WhatsApp-like Chat Application

A full-stack chat application built with **Spring Boot**, **React (CDN)**, and **PostgreSQL**, containerized with **Docker**. This is the **v1 baseline** — intentionally simple (HTTP polling, no caching, single instance) to establish a performance baseline that will be improved in subsequent iterations.

## Architecture (v1 — Baseline)

```
┌────────────────────────────────────┐       ┌────────────┐
│         Spring Boot :8080          │       │            │
│  ┌────────────┐  ┌──────────────┐ │       │ PostgreSQL │
│  │  Static    │  │   REST API   │ │ JDBC  │  :5432     │
│  │  HTML/JS   │  │  /api/*      │─┼──────▶│            │
│  │  React CDN │  │              │ │       │            │
│  └────────────┘  └──────────────┘ │       └────────────┘
└────────────────────────────────────┘
         │                │
         │  Polling every │  No caching
         │  2-3 seconds   │  No connection pool tuning
         │                │  Single instance
         ▼                ▼
      Known bottlenecks for future optimization
```

### What's intentionally NOT optimized (v1)
- **HTTP polling** instead of WebSockets — high latency, wasted bandwidth
- **No caching** (Redis) — every request hits the database
- **No message queue** (Kafka) — synchronous writes only
- **Single backend instance** — no horizontal scaling
- **No pagination** — loads all messages in a conversation
- **Default connection pool** — HikariCP with 10 connections
- **No build tooling** — React via CDN with Babel in-browser transform

## Tech Stack

| Layer     | Technology                        |
|-----------|----------------------------------|
| Frontend  | React 18 via CDN (plain JS, no build) |
| Backend   | Spring Boot 3.2, Java 17         |
| Auth      | JWT (jjwt)                        |
| Database  | PostgreSQL 16                     |
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

This starts 2 containers:
- **postgres** — Database on port 5432
- **backend** — Spring Boot API + static frontend on port 8080

Wait until you see `Started ChatAppApplication` in the logs, then open **http://localhost:8080** in your browser.

### 2. Use the app

1. Go to http://localhost:8080
2. Click **Register** and create two accounts (use two browser tabs/profiles)
3. In one tab, search for the other user's username
4. Click on the user to start a conversation
5. Send messages — they appear in the other tab within 2-3 seconds (polling)

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
# Smoke test — 5 users, 10 seconds
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 5 10

# Medium load — 50 users, 60 seconds
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 50 60

# High load — 200 users, 120 seconds
docker run --rm --network chat-app_default -v ./loadtest:/loadtest -w /loadtest maven:3.9-eclipse-temurin-17 java LoadTest.java http://backend:8080 200 120
```

Arguments: `BASE_URL  NUM_USERS  DURATION_SECONDS` (defaults: `http://localhost:8080  100  60`)

> **If you have Java 17+ installed locally**, you can also run directly:
> ```bash
> cd loadtest && javac LoadTest.java && java LoadTest http://localhost:8080 50 60
> ```

### What the load test does

1. **Phase 1 (Setup)**: Registers N users via the API
2. **Phase 2 (Load)**: Each user runs in its own thread, repeatedly:
   - Logs in
   - Sends a message to a random partner
   - Fetches the conversation
   - Lists all conversations
3. **Phase 3 (Report)**: Prints per-endpoint p50/p90/p95/p99 latencies, error rates, throughput

### Understanding the results

| Metric | What it means |
|--------|--------------|
| **P50** | Median latency — what a typical user experiences |
| **P90** | 90th percentile — only 10% of requests are slower |
| **P95** | 95th percentile — the target for most SLAs |
| **P99** | 99th percentile — worst-case (excluding outliers) |
| **Error Rate** | % of requests that returned non-200 status |

## Project Structure

```
chat-app/
├── backend/
│   ├── src/main/java/com/chatapp/
│   │   ├── ChatAppApplication.java          # Entry point
│   │   ├── config/
│   │   │   ├── SecurityConfig.java          # JWT security config
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
│   │   │   └── MessageService.java          # Message business logic (with logging)
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
│   │           ├── chat.js                  # Chat UI with polling
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

| Version | Changes | Expected Impact |
|---------|---------|----------------|
| **v1 (current)** | Baseline: polling, single instance, no cache | Establish baseline p95/p99 |
| **v2** | Add database indexes, pagination, connection pool tuning | 2-5x improvement on reads |
| **v3** | Add Redis caching for conversations and sessions | 10x improvement on reads |
| **v4** | WebSocket for real-time messaging (replace polling) | Sub-100ms message delivery |
| **v5** | Kafka for async message processing | Better write throughput |
| **v6** | Horizontal scaling (multiple backend instances + load balancer) | Linear scalability |
| **v7** | Database read replicas, sharding | Handle millions of users |

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
