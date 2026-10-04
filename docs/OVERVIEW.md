# ChatApp — Project Overview

A WhatsApp-like real-time chat application built iteratively as a **performance engineering exercise**. The goal was to start with the simplest possible architecture, measure it under load, identify bottlenecks, and fix them one version at a time with data.

**Stack**: Spring Boot 3.2 · PostgreSQL 16 · Apache Kafka 3.7.2 · AWS S3 · Vanilla JS · Docker Compose

---

## 1. What the App Does

Users register, log in, search for other users, and exchange messages in real time. Users can also send images — uploaded directly to AWS S3 via presigned URLs and displayed inline in the chat. The conversation list and message history are paginated. All session state is held in `localStorage` via a JWT token — there are no server-side sessions.

---

## 2. Evolution: V1 → V9

Each version fixed one specific bottleneck. Load tests before and after validated every change.

| Version | What Changed | Key Result |
|---------|-------------|------------|
| V1 | HTTP polling baseline | P95 = 2,086ms @ 200 users |
| V2 | HikariCP pool 10 → 50 | Freed DB connections; exposed BCrypt CPU bottleneck |
| V3 | Fixed load test (login once, not per request) | True P95 = 586ms @ 200 users |
| V4 | `@Cacheable` on user lookup | Removed a DB hit on every authenticated request |
| V5 | Pagination (LIMIT 15) + infinite scroll | P95 stable across 200u and 500u |
| V6 | Composite DB indexes | Ordered scans without post-sort step |
| V7 | WebSocket (STOMP) — replaced HTTP polling | P95 = 105ms @ 200u (5.6× faster than V6) |
| V8 | Kafka async batch persistence | P95 = 26ms @ 200u — DB write removed from hot path |
| V9 | Kafka circuit breaker + vanilla JS migration | Zero message loss when Kafka goes down |
| V10 | Image messaging via S3 presigned URLs | New image-service microservice, nginx proxy, direct S3 upload, 7-day URL expiry, browser caching via Cache-Control, auto-refresh on expired URLs |

---

## 3. HTTP Polling (V1–V6)

In the original version the browser polled two endpoints on a timer:
- `GET /api/messages/conversations` every 3 seconds
- `GET /api/messages/conversation/{id}` every 2 seconds

At 200 users this generated ~160 background requests per second even when nothing was happening. The server was constantly burning DB queries on no-op reads. This was replaced completely in V7.

---

## 4. WebSockets / STOMP (V7+)

On page load the browser opens a single **WebSocket** connection (using the STOMP protocol over SockJS). All message sending and receiving happens over this persistent connection — no polling.

**Send flow**:
1. Browser sends a STOMP `SEND /app/chat.send` frame with `{receiverId, content, clientId}`
2. Spring's `@MessageMapping` handler receives it, pushes a `MessageResponse` to **both** sender and receiver via `SimpMessagingTemplate.convertAndSendToUser`
3. Both users see the message appear instantly (3–8ms round-trip)

The timer-based polling loops were removed entirely. Background request noise dropped to zero.

---

## 5. User Lookup Caching (V4)

Every authenticated HTTP request (and WebSocket message) runs through `JwtAuthFilter`, which extracts the username from the JWT and loads the `User` entity from the database to populate the `SecurityContext`.

That was one `SELECT * FROM users WHERE username = ?` per request — even for a message send that never needs the full user object.

**Fix**: `@Cacheable(value="users", key="#username")` on `UserRepository.findByUsername`. Spring stores the result in an in-memory `ConcurrentHashMap`. Subsequent requests for the same user skip the DB entirely. `@CacheEvict` on register ensures new users are immediately visible.

No Redis, no extra dependency — just Spring's built-in cache abstraction.

---

## 6. Pagination + Infinite Scroll (V5+V6)

Without pagination, `GET /api/messages/conversation/{id}` returned **all** messages between two users. Early in a load test that's 5 rows; 2 minutes in with 200 users, it's hundreds of rows per request. Latency grew 49× over the course of a test.

**Fix (V5)**: Both message and conversation queries now use `PageRequest.of(page, 15)` — 15 rows max. The frontend uses infinite scroll:
- Scroll up in chat → loads older messages (page 1, 2, …)
- Scroll down in the sidebar → loads more conversations (page 1, 2, …)

**Fix (V6)**: Composite DB indexes `(sender_id, timestamp DESC)` and `(receiver_id, timestamp DESC)`. PostgreSQL can now walk a pre-ordered index to satisfy the `ORDER BY timestamp DESC LIMIT 15` instead of scanning all rows and sorting. Queries use a `BitmapOr` of the two indexes.

---

## 7. Kafka Async Batch Persistence (V8)

Before V8, every `sendMessage()` call did a synchronous `messageRepository.save()` — the WebSocket response couldn't go out until the DB INSERT finished. At 200 users that was the dominant latency source.

**Fix**: The DB write was moved off the hot path entirely using Kafka.

**Send path (V8+)**:
```
Browser → WebSocket STOMP SEND
  → MessageService:
      1. Push MessageResponse to sender + receiver via WS (instant, ~3ms)
      2. kafkaTemplate.send() — record lands in Kafka (fire-and-forget, ~5ms)
      3. Return — user's message thread is done

  (async, in background)
  → MessagePersistenceConsumer (3 threads, one per Kafka partition):
      poll() returns a batch of up to 500 events
      messageRepository.saveAll(batch)  ← one DB round-trip for up to 50 rows per statement
      commitSync()
```

The send_message P95 dropped from 102ms (V7) to 26ms (V8) at 200 users — the DB write is now invisible to the user.

**Why Kafka instead of a simple thread pool?**
- Messages survive a JVM crash (stored on disk in Kafka's log)
- Kafka consumer lag is measurable — you can see if the consumer falls behind
- 3 partitions = 3 consumer threads with zero lock contention on `saveAll()`

**Batch mechanics**: `fetch.min.bytes: 10000` tells the broker to wait until 10KB of data is ready before responding. `fetch.max.wait.ms: 5000` is the fallback timer. Combined: 5 messages sent quickly arrive in one `poll()` → one `saveAll()` → one DB round-trip. Hibernate's `jdbc.batch_size: 50` groups the INSERTs into 50-row multi-value statements.

---

## 8. Message Deduplication

When the server pushes a `MessageResponse`, it sends it to **both** the sender and the receiver. The sender is subscribed to `/user/queue/messages` and receives an echo of their own message. Without dedup, the sender sees every message twice.

**Why the original fix broke in V8**: The old dedup checked `msg.id`. In V8, the DB write is async — no ID exists yet when the WS push fires. Every message had `id: 0`, so `0 === 0` deduplicated everything after the first message. Messages looked missing until page refresh.

**Fix**: A `clientId` (RFC 4122 UUID generated by `crypto.randomUUID()`) is created on the client before sending. It travels with the message through the server and is echoed back in the response. Dedup checks `clientId` for WS-pushed messages, and falls back to a `(senderId, content, timestamp)` composite key for DB-loaded history (which has no `clientId`).

This is the same approach used by WhatsApp and Signal.

---

## 9. Kafka Circuit Breaker (V9)

**Problem**: If Kafka goes down (crash, restart, network partition), `kafkaTemplate.send().get()` hangs for up to `delivery.timeout.ms` (5s) on every message. That 5s wait is on the user's message-sending thread — messages are visually delayed even though WS delivery already worked.

**Solution**: A circuit breaker pattern with a background health probe.

**State machine**:

```
CLOSED (default)
  kafkaDown = false
  User message → try Kafka
  If send fails → kafkaDown = true → direct DB save → circuit OPEN

OPEN
  kafkaDown = true
  User message → direct messageRepository.save() immediately (no Kafka wait)
  Background @Scheduled thread probes Kafka every 10s

CLOSED again
  Probe succeeds → kafkaDown = false → Kafka resumes
```

**Key design**: The `@Scheduled(fixedDelay = 10_000)` probe runs on Spring's scheduling thread — completely separate from the user's request thread. When the circuit is OPEN, user messages hit the DB in <5ms with no Kafka involvement at all. The 5s probe timeout never touches the user.

Probe messages use `senderUsername = "__probe__"` and are filtered out in `MessagePersistenceConsumer` before `saveAll()` so they don't pollute the database.

**Logging suppression**: When Kafka is down, the Kafka client library logs a `WARN` or `ERROR` every `reconnect.backoff.ms` for each of the 3 consumer threads. This floods the log. Two loggers are suppressed to `ERROR` level: `org.apache.kafka.clients.NetworkClient` and `org.apache.kafka.common.network.Selector`. Application-level logs (`com.chatapp`) remain at `INFO`.

---

## 10. Frontend: Vanilla JS (V9)

The frontend was originally written with React 18 loaded from CDN, with Babel doing in-browser JSX transpilation. This added ~130KB+ of JavaScript that had to be parsed before the page could render, and Babel's runtime transform added CPU overhead on every page load.

**Migration**: All four JS files were rewritten to plain DOM manipulation:
- `auth.js` → `renderLoginPage()` / `renderRegisterPage()` functions using `innerHTML` + `addEventListener`
- `chat.js` → `chatState` plain object + `renderChatArea()` / `renderMessages()` / `renderConversationList()` functions
- `app.js` → IIFE that reads `localStorage` and calls the right render function
- `index.html` → React, ReactDOM, and Babel `<script>` tags removed; STOMP.js is the only CDN dependency

All features preserved: real-time WS, dedup, infinite scroll pagination, user search, XSS protection via `escapeHtml()`.

---

## 11. Load Testing

A **zero-dependency Java 17 load tester** (`loadtest/LoadTest.java`) using `java.net.http.HttpClient` and raw STOMP over WebSocket.

**Test protocol**:
1. Register N users
2. Each user logs in once (JWT valid 24h — matches real usage)
3. All N users connect WebSockets simultaneously
4. Each user thread loops for 120 seconds: pick a random partner → send STOMP message → wait for echo → record latency
5. Print P50 / P90 / P95 / P99 per endpoint

**Two scales**: 200 users (high load) and 500 users (stress). A 50-second warmup at 200 users is run first (unsaved).

**The 5-second echo timeout**: the load test waits up to 5s for its own echo before counting an error. In normal operation the echo arrives in ~15ms (P50). The 5s threshold only fires under extreme TCP backpressure.

**Results summary**:

| Version | P95 @ 200u | P95 @ 500u | Key change |
|---------|-----------|-----------|-----------|
| V1 | 2,086ms | — | HTTP polling, pool=10 |
| V3 | 586ms | — | Realistic login fixed |
| V4 | 573ms | 543ms | User cache |
| V6 | 504ms | 499ms | Composite indexes |
| V7 | 105ms | 99ms | WebSocket — polling gone |
| V8 | 26ms | 70ms | Kafka async — DB off hot path |

Full results per endpoint and per version: [LOAD_TEST_RESULTS.md](LOAD_TEST_RESULTS.md).

---

## 12. Docker Infrastructure

Five containers, one `docker-compose.yml`:

| Container | Image | Role |
|-----------|-------|------|
| `postgres` | `postgres:16-alpine` | Persistent database (named volume `pgdata`) |
| `kafka` | `apache/kafka:3.7.2` | KRaft mode (no ZooKeeper), topic `chat-messages` with 3 partitions |
| `backend` | Multi-stage build | Maven compile → JRE 17 runtime image |
| `image-service` | Multi-stage build | S3 presigned URL generation, JWT auth (shared secret) |
| `nginx` | `nginx:alpine` | Reverse proxy: routes `/api/images/*` to image-service, everything else to backend |

`backend` depends on `postgres: condition: service_healthy`. Kafka has no volume mount — `docker compose down` loses uncommitted Kafka messages (intentional for dev simplicity; in production mount a named volume).

---

## 13. Image Messaging (V10)

Users can send images in chat. The flow uses **presigned S3 URLs** so image data never touches the backend:

1. Client calls `POST /api/images/presign/upload` (image-service) with filename + content type
2. Image-service generates a presigned PUT URL (upload) and GET URL (download) from AWS S3
3. Client uploads the image directly to S3 via the presigned PUT URL
4. Client sends a WebSocket message with `imageUrl` (presigned GET) and `imageKey` (S3 key)
5. Both users receive the message with the image URL — rendered as an inline `<img>` tag

**Supported formats**: JPEG, PNG, GIF, WebP (max 10MB).

**URL expiration**: Upload URLs expire in 15 minutes; download URLs expire in **7 days** (10,080 minutes — the maximum for IAM user credentials). When a download URL expires, the `<img>` tag's `onerror` handler automatically calls `GET /api/images/presign/download?imageKey=...` to get a fresh URL — no user action needed.

**Browser caching**: Presigned GET URLs include a `response-cache-control` parameter (`public, max-age=604800, immutable`). S3 returns this as the `Cache-Control` header, so browsers serve images from disk cache on subsequent page loads instead of re-downloading from S3.

**Logging**: The image-service includes structured logging at the controller, filter, and exception handler levels — request/response logging on both endpoints, JWT auth debug/warn logging, and error stack traces.

**Architecture**: The image-service is a separate Spring Boot microservice with its own Dockerfile. It shares the JWT secret with the backend for token validation. Nginx routes `/api/images/*` requests to it.

---

## 14. Security Notes

- Passwords hashed with **BCrypt** (cost factor 10, ~80–150ms per hash — intentionally slow)
- Auth via **JWT** (HMAC-SHA384, 24h expiry) — stateless, no server-side sessions
- CSRF disabled (stateless API, no cookie-based auth)
- Public routes: `/`, `/index.html`, `/css/**`, `/js/**`, `/api/auth/**`
- XSS: all user content rendered via `escapeHtml()` (text node extraction), never via `innerHTML` with raw input
- SQL injection: all queries use Spring Data JPA with parameterized `@Query` — no string concatenation

---

*Full technical deep-dives: [ARCHITECTURE.md](ARCHITECTURE.md) · Load test data: [LOAD_TEST_RESULTS.md](LOAD_TEST_RESULTS.md)*
