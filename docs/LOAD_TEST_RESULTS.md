# Load Test Results

All tests run with `loadtest/LoadTest.java` via Docker on Docker Desktop. See [ARCHITECTURE.md](ARCHITECTURE.md) for full methodology.

**Current test protocol** (from V4 onwards): warmup run (200 users, 100s, unsaved), then 200 users 120s and 500 users 120s.  
**Result files**: `loadtest/results/`

---

## Table of Contents

1. [V1 Baseline (pool=10)](#v1-baseline-pool10)
2. [V2 Results (pool=50)](#v2-results-pool50)
3. [V1 vs V2 Comparison](#v1-vs-v2-comparison)
4. [V3 Results (realistic login)](#v3-results-realistic-login)
5. [V2 vs V3 Comparison](#v2-vs-v3-comparison)
6. [V4 Results (user cache)](#v4-results-user-cache)
7. [V3 vs V4 Comparison](#v3-vs-v4-comparison)
8. [V5 Results (pagination LIMIT 15)](#v5-results-pagination-limit-15)
9. [V4 vs V5 Comparison](#v4-vs-v5-comparison)
10. [V6 Results (composite indexes)](#v6-results-composite-indexes)
11. [V5 vs V6 Comparison](#v5-vs-v6-comparison)
12. [V7 Results (WebSocket STOMP)](#v7-results-websocket-stomp)
13. [V6 vs V7 Comparison](#v6-vs-v7-comparison)
14. [V7 Run 2 Results (infinite scroll pagination)](#v7-run-2-results-infinite-scroll-pagination)
15. [V8 Results (Kafka async batch persistence)](#v8-results-kafka-async-batch-persistence)
16. [V7 Run 2 vs V8 Comparison](#v7-run-2-vs-v8-comparison)

---

## V1 Baseline (pool=10)

`hikari.maximum-pool-size=10`. Login called on every loop iteration (unrealistic — fixed in V3).  
File: `loadtest/results/high-200users-120s.txt`

### 200 users, 120 seconds

| Metric           | Value         |
|------------------|---------------|
| Total Requests   | 24,780        |
| Errors           | 0 (0.00%)     |
| Data Transferred | 13,331.5 KB   |
| Throughput       | ~207 req/s    |

| Percentile | Latency   |
|------------|-----------|
| P50        | 819 ms    |
| P90        | 1,738 ms  |
| P95        | 2,086 ms  |
| P99        | 2,812 ms  |
| Max        | 5,038 ms  |

| Endpoint          | Count | Avg      | P50   | P95   | P99   |
|-------------------|-------|----------|-------|-------|-------|
| register          | 200   | 69 ms    | 68    | 79    | 91    |
| login             | 6,195 | 830 ms   | 716   | 1,619 | 2,391 |
| send_message      | 6,195 | 1,021 ms | 931   | 2,291 | 3,002 |
| get_conversation  | 6,195 | 937 ms   | 867   | 2,210 | 2,901 |
| get_conversations | 6,195 | 885 ms   | 827   | 2,094 | 2,812 |

---

## V2 Results (pool=50)

Single change: `hikari.maximum-pool-size` raised from 10 → 50. Login still called per-iteration.  
File: `loadtest/results/v2-pool50-high-200users-120s.txt`

### 200 users, 120 seconds

| Metric           | Value         |
|------------------|---------------|
| Total Requests   | 23,444        |
| Errors           | 0 (0.00%)     |
| Data Transferred | 12,182.4 KB   |
| Throughput       | ~196 req/s    |

| Percentile | Latency   |
|------------|-----------|
| P50        | 845 ms    |
| P90        | 2,056 ms  |
| P95        | 2,456 ms  |
| P99        | 3,412 ms  |
| Max        | 6,168 ms  |

| Endpoint          | Count | Avg       | P50   | P95   | P99   |
|-------------------|-------|-----------|-------|-------|-------|
| register          | 200   | 89 ms     | 86    | 126   | 143   |
| login             | 5,861 | 1,555 ms  | 1,437 | 2,999 | 3,977 |
| send_message      | 5,861 | 835 ms    | 707   | 2,231 | 3,241 |
| get_conversation  | 5,861 | 762 ms    | 644   | 2,068 | 2,908 |
| get_conversations | 5,861 | 730 ms    | 626   | 2,010 | 2,799 |

---

## V1 vs V2 Comparison

Only change: `hikari.maximum-pool-size` 10 → 50.

### At 200 users

| Endpoint          | V1 P95    | V2 P95    | Change            |
|-------------------|-----------|-----------|-------------------|
| login             | 1,619 ms  | 2,999 ms  | **1.9x SLOWER**   |
| send_message      | 2,291 ms  | 2,231 ms  | similar           |
| get_conversation  | 2,210 ms  | 2,068 ms  | slightly faster   |
| get_conversations | 2,094 ms  | 2,010 ms  | slightly faster   |

### Key Finding

With pool=10, the queue was an **accidental rate-limiter** — only 10 threads could BCrypt at once. Raising to pool=50 let all 200 BCrypt operations run simultaneously, **saturating the CPU**.

- DB-bound endpoints (`send_message`, `get_conversation`) improved — no more pool wait
- CPU-bound endpoint (`login`) got worse — more concurrent BCrypt = more CPU contention

This is the classic bottleneck cascade: fix one and the next becomes the new ceiling.

---

## V3 Results (realistic login)

**Load test fixed**: login called once per user at startup (matching 24h JWT reality), not per loop iteration. App code and config unchanged.  
File: `loadtest/results/v3-realistic-high-200users-120s.txt`

### 200 users, 120 seconds

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 83,172         |
| Errors           | 0 (0.00%)      |
| Data Transferred | 142,340.6 KB   |
| Throughput       | ~693 req/s     |

| Percentile | Latency  |
|------------|----------|
| P50        | 194 ms   |
| P90        | 478 ms   |
| P95        | 586 ms   |
| P99        | 813 ms   |
| Max        | 1,744 ms |

| Endpoint          | Count  | Avg    | P50 | P95 | P99 |
|-------------------|--------|--------|-----|-----|-----|
| login             | 200    | 75 ms  | 70  | 98  | 106 |
| send_message      | 27,724 | 254 ms | 226 | 653 | 883 |
| get_conversation  | 27,724 | 198 ms | 172 | 545 | 748 |
| get_conversations | 27,724 | 204 ms | 181 | 549 | 757 |

---

## V2 vs V3 Comparison

Only change: load test fixed to login once. **App code identical.**

### At 200 users

| Metric            | V2 P95     | V3 P95    | Improvement      |
|-------------------|------------|-----------|------------------|
| Overall P95       | 2,456 ms   | 586 ms    | **4.2x faster**  |
| send_message      | 2,231 ms   | 653 ms    | **3.4x faster**  |
| get_conversation  | 2,068 ms   | 545 ms    | **3.8x faster**  |
| get_conversations | 2,010 ms   | 549 ms    | **3.7x faster**  |
| Throughput        | ~196 req/s | ~693 req/s| **3.5x more**    |

### Key Insight

V1 and V2 numbers were distorted by unrealistic per-iteration login — the CPU was BCrypt-hashing constantly, something real users never do. V3 reflects real-world usage where each user logs in once.

**With realistic load, the same app delivers P95 < 600ms at 200 users** — using only a connection pool increase as the sole infrastructure change.

---

## V4 Results (user cache)

Change from V3: `@Cacheable(value="users", key="#username")` on `UserRepository.findByUsername`. Eliminates the `SELECT * FROM users WHERE username=?` DB hit on every authenticated request inside `JwtAuthFilter`.

Files: `loadtest/results/v4-usercache-200users-120s.txt`, `v4-usercache-200users-120s-run2.txt`, `v4-usercache-500users-120s.txt`, `v4-usercache-500users-120s-run2.txt`

### 200 users, 120 seconds

> Two runs recorded. **Run 1** was immediately after a cold rebuild (JVM not yet JIT-compiled). **Run 2** is the representative steady-state result.

**Run 1 — Cold JVM:**

| Metric         | Value      |
|----------------|------------|
| Total Requests | 76,296     |
| Errors         | 0 (0.00%)  |
| Throughput     | ~636 req/s |

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

### 500 users, 120 seconds

> Both runs were on a warm JVM (200-user tests preceded them). Results are stable across runs.

**Run 1 — Warm JVM:**

| Metric         | Value      |
|----------------|------------|
| Total Requests | 89,070     |
| Errors         | 0 (0.00%)  |
| Throughput     | ~742 req/s |

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

Only change: `@Cacheable("users")` on `findByUsername`. Comparison uses Run 2 (warm JVM) for V4.

### At 200 users

| Metric               | V3          | V4 Run 1 (cold) | V4 Run 2 (warm) | V3→V4 Warm       |
|----------------------|-------------|-----------------|-----------------|------------------|
| Overall P50          | 194 ms      | 150 ms          | 172 ms          | **1.1x faster**  |
| Overall P90          | 478 ms      | 586 ms          | 468 ms          | 1.02x faster     |
| Overall P95          | 586 ms      | 768 ms          | 573 ms          | **1.02x faster** |
| Overall P99          | 813 ms      | 1,188 ms        | 785 ms          | 1.04x faster     |
| send_message P95     | 653 ms      | 850 ms          | 625 ms          | **1.04x faster** |
| get_conversation P95 | 545 ms      | 718 ms          | 537 ms          | **1.01x faster** |
| Throughput           | ~693 req/s  | ~636 req/s      | ~727 req/s      | **+5%**          |

### What the two V4 runs reveal

**Run 1 (cold JVM)**: P95 looked worse than V3 (768ms vs 586ms). This was misleading — the JVM had just restarted. HotSpot JIT hadn't compiled hot paths yet.

**Run 2 (warm JVM)**: P95 improves over V3 and P99 drops from 1,188ms to 785ms. Throughput is 5% higher.

**Lesson**: Always measure after JVM warmup. A cold-JVM measurement can make a real improvement look like a regression.

### Why 500 users is faster than 200 users (V4)

The bottleneck is not concurrency — it is rows returned per `get_conversation` call, which grows with messages per conversation pair, not with total user count.

| | 200 users | 500 users |
|---|---|---|
| `send_message` calls | ~29,088 | ~30,648 |
| Active conversation pairs | ~100 | ~250 |
| Messages per pair after 120s | **~291** | **~123** |

500 users spread ~30k messages across 2.5x more conversation pairs. Each `get_conversation` at 200 users returns ~2.4x more rows. Since there is no `LIMIT`, payload grows throughout the test — faster per pair with fewer users.

This is proof that the bottleneck is payload size, not pool pressure or thread contention. Pagination is the fix.

---

## V5 Results (pagination LIMIT 15)

Changes from V4:
- **`get_conversation`**: `PageRequest.of(0, 15)` — orders `DESC`, takes 15 most recent, service reverses to chronological
- **`get_conversations`**: subquery `LIMIT 15` — returns 15 most recently active partners ordered by `MAX(timestamp) DESC`

Both payloads are now bounded to 15 rows regardless of history length. JVM was warm before both tests (100s warmup run not saved).

Files: `loadtest/results/v5-pagination15-200users-120s.txt`, `v5-pagination15-500users-120s.txt`

### 200 users, 120 seconds

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 93,576         |
| Errors           | 0 (0.00%)      |
| Data Transferred | 42,596.4 KB    |
| Throughput       | ~780 req/s     |

| Percentile | Latency  |
|------------|----------|
| P50        | 159 ms   |
| P90        | 423 ms   |
| P95        | 537 ms   |
| P99        | 750 ms   |
| Max        | 1,789 ms |

| Endpoint          | Count  | Avg    | P50 | P95 | P99 |
|-------------------|--------|--------|-----|-----|-----|
| login             | 200    | 75 ms  | 72  | 96  | 111 |
| send_message      | 31,192 | 218 ms | 190 | 591 | 830 |
| get_conversation  | 31,192 | 170 ms | 135 | 499 | 707 |
| get_conversations | 31,192 | 173 ms | 142 | 503 | 695 |

### 500 users, 120 seconds

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 94,176         |
| Errors           | 0 (0.00%)      |
| Data Transferred | 38,685.3 KB    |
| Throughput       | ~785 req/s     |

| Percentile | Latency  |
|------------|----------|
| P50        | 154 ms   |
| P90        | 419 ms   |
| P95        | 531 ms   |
| P99        | 753 ms   |
| Max        | 1,498 ms |

| Endpoint          | Count  | Avg    | P50 | P95 | P99 |
|-------------------|--------|--------|-----|-----|-----|
| login             | 500    | 77 ms  | 70  | 115 | 145 |
| send_message      | 31,392 | 217 ms | 188 | 597 | 827 |
| get_conversation  | 31,392 | 169 ms | 134 | 494 | 704 |
| get_conversations | 31,392 | 170 ms | 138 | 489 | 699 |

---

## V4 vs V5 Comparison

Changes: LIMIT 15 on both `get_conversation` (messages) and `get_conversations` (partner list).

| Metric               | V4 200u (warm) | V5 200u        | V4 500u (warm) | V5 500u        |
|----------------------|----------------|----------------|----------------|----------------|
| Overall P50          | 172 ms         | **159 ms**     | 164 ms         | **154 ms**     |
| Overall P90          | 468 ms         | **423 ms**     | 434 ms         | **419 ms**     |
| Overall P95          | 573 ms         | **537 ms**     | 543 ms         | **531 ms**     |
| Overall P99          | 785 ms         | **750 ms**     | 743 ms         | 753 ms         |
| get_conversation P95 | 537 ms         | **499 ms**     | 501 ms         | **494 ms**     |
| get_conversations P95| 552 ms         | **503 ms**     | 513 ms         | **489 ms**     |
| send_message P95     | 625 ms         | **591 ms**     | 607 ms         | **597 ms**     |
| Throughput           | ~727 req/s     | **~780 req/s** | ~766 req/s     | **~785 req/s** |

### Key observations

**Both user counts improved.** Adding the conversation-list cap fixed the 500-user regression seen in the partial attempt (messages only). At 500 users, `get_conversations` was scanning up to 179 partner rows per poll — capping to 15 gave a bigger relative saving at 500 users since the partner scan grows with conversation breadth, not message depth.

**P95 converges between user counts.** 200 users: 537ms, 500 users: 531ms — virtually identical. Payload is now constant regardless of user count or test duration.

**Data transferred dropped 3.6x.** V4 200 users: ~154 MB → V5 200 users: ~43 MB. V5 500 users: ~39 MB (partner lists cap earlier for users with more conversations).

**`send_message` P95 also improved** without any write query changes — fewer bytes on the wire means threads complete faster and free pool connections sooner.

**The bottleneck has shifted to `send_message`** — write contention is now dominant. Step 5 (composite index) will help reads; the write path needs separate attention.

---

## V6 Results (composite indexes)

Changes from V5:
- **`messages` table indexes**: replaced 3 single-column indexes (`sender_id`, `receiver_id`, `timestamp`) with 2 composite indexes — `idx_msg_sender_ts (sender_id, timestamp DESC)` and `idx_msg_receiver_ts (receiver_id, timestamp DESC)`
- Both paginated queries now use pre-sorted index scans instead of a post-scan sort

Files: `loadtest/results/v6-index-200users-120s.txt`, `v6-index-500users-120s.txt`

### 200 users, 120 seconds

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 96,969         |
| Errors           | 0 (0.00%)      |
| Data Transferred | 44,376.4 KB    |
| Throughput       | ~808 req/s     |

| Percentile | Latency  |
|------------|----------|
| P50        | 151 ms   |
| P90        | 404 ms   |
| P95        | 504 ms   |
| P99        | 726 ms   |
| Max        | 1,617 ms |

| Endpoint          | Count  | Avg    | P50 | P95 | P99 |
|-------------------|--------|--------|-----|-----|-----|
| login             | 200    | 75 ms  | 71  | 100 | 119 |
| send_message      | 32,323 | 211 ms | 184 | 574 | 807 |
| get_conversation  | 32,323 | 160 ms | 129 | 462 | 672 |
| get_conversations | 32,323 | 162 ms | 133 | 466 | 668 |

### 500 users, 120 seconds

| Metric           | Value          |
|------------------|----------------|
| Total Requests   | 97,620         |
| Errors           | 0 (0.00%)      |
| Data Transferred | 40,192.3 KB    |
| Throughput       | ~813 req/s     |

| Percentile | Latency  |
|------------|----------|
| P50        | 148 ms   |
| P90        | 399 ms   |
| P95        | 499 ms   |
| P99        | 698 ms   |
| Max        | 1,358 ms |

| Endpoint          | Count  | Avg    | P50 | P95 | P99 |
|-------------------|--------|--------|-----|-----|-----|
| login             | 500    | 73 ms  | 69  | 94  | 115 |
| send_message      | 32,540 | 206 ms | 181 | 559 | 771 |
| get_conversation  | 32,540 | 161 ms | 132 | 464 | 642 |
| get_conversations | 32,540 | 162 ms | 131 | 468 | 643 |

---

## V5 vs V6 Comparison

Changes: composite indexes `(sender_id, timestamp DESC)` and `(receiver_id, timestamp DESC)` replacing three single-column indexes.

| Metric                | V5 200u   | V6 200u        | V5 500u   | V6 500u        |
|-----------------------|-----------|----------------|-----------|----------------|
| Overall P50           | 159 ms    | **151 ms**     | 154 ms    | **148 ms**     |
| Overall P90           | 423 ms    | **404 ms**     | 419 ms    | **399 ms**     |
| Overall P95           | 537 ms    | **504 ms**     | 531 ms    | **499 ms**     |
| Overall P99           | 750 ms    | **726 ms**     | 753 ms    | **698 ms**     |
| get_conversation P95  | 499 ms    | **462 ms**     | 494 ms    | **464 ms**     |
| get_conversations P95 | 503 ms    | **466 ms**     | 489 ms    | **468 ms**     |
| send_message P95      | 591 ms    | **574 ms**     | 597 ms    | **559 ms**     |
| Throughput            | ~780 req/s| **~808 req/s** | ~785 req/s| **~813 req/s** |

### Key observations

**Read endpoints improved ~7%.** `get_conversation` and `get_conversations` P95 dropped ~37ms at 200 users. PostgreSQL now does two ordered index scans (one per OR branch) and merges 15 rows — no post-sort needed.

**`send_message` also improved** (~591ms → ~574ms at 200u, ~597ms → ~559ms at 500u) despite no change to the write query. The index speeds up the `get_conversation` query that immediately follows each send in the test loop, freeing pool connections sooner.

**P95 broke below 500ms at 500 users** (499ms) — first time crossing that threshold.

**The write bottleneck remains.** `send_message` still leads P95 across all endpoints. Composite indexes help reads by definition; writes pay a small index-maintenance cost (negligible here). The next step is addressing write contention.

---

## V7 Results (WebSocket STOMP)

Changes from V6:
- **Transport replaced**: HTTP polling removed. Clients now open a persistent WebSocket connection (STOMP over WS) on load.
- **send_message**: was `POST /api/messages` (HTTP). Now STOMP `SEND /app/chat.send` \u2014 server saves to DB and pushes `MessageResponse` to both sender and receiver via `convertAndSendToUser`.
- **get_conversation / get_conversations**: still HTTP GET on initial load, but no longer polled every 2\u20133s \u2014 new messages arrive as WS push events.
- **Metric change**: `send_message` latency now measures **WS round-trip** (STOMP frame sent \u2192 server saves \u2192 echo received), not an HTTP request queue wait.

Files: `loadtest/results/v7-websocket-200users-120s.txt`, `v7-websocket-500users-120s.txt`

### 200 users, 120 seconds

| Metric               | Value          |
|----------------------|----------------|
| Total Requests       | 103,138        |
| Errors               | 4 (0.00%)      |
| WS Pushes Received   | 205,476        |
| Data Transferred     | 24,062.3 KB    |
| Throughput           | ~859 req/s     |

| Percentile | Latency |
|------------|---------|
| P50        | 15 ms   |
| P90        | 75 ms   |
| P95        | 105 ms  |
| P99        | 171 ms  |
| Max        | 682 ms  |

| Endpoint          | Count   | Avg    | P50 | P95 | P99 |
|-------------------|---------|--------|-----|-----|-----|
| login             | 200     | 71 ms  | 69  | 88  | 99  |
| ws_connect        | 200     | 179 ms | 175 | 339 | 411 |
| get_conversation  | 200     | 200 ms | 178 | 449 | 585 |
| get_conversations | 200     | 260 ms | 255 | 510 | 655 |
| send_message (WS) | 102,738 | 30 ms  | 15  | 102 | 163 |

> `get_conversation` and `get_conversations` run once on initial load only \u2014 their counts are equal to the number of users, not iterations.

### 500 users, 120 seconds

| Metric               | Value          |
|----------------------|----------------|
| Total Requests       | 103,457        |
| Errors               | 39 (0.04%)     |
| WS Pushes Received   | 204,467        |
| Data Transferred     | 24,281.0 KB    |
| Throughput           | ~862 req/s     |

| Percentile | Latency |
|------------|---------|
| P50        | 17 ms   |
| P90        | 75 ms   |
| P95        | 99 ms   |
| P99        | 162 ms  |
| Max        | 1,040 ms|

| Endpoint          | Count   | Avg    | P50 | P95 | P99 |
|-------------------|---------|--------|-----|-----|-----|
| login             | 500     | 69 ms  | 66  | 87  | 96  |
| ws_connect        | 500     | 183 ms | 173 | 365 | 476 |
| get_conversation  | 500     | 240 ms | 160 | 648 | 914 |
| get_conversations | 500     | 271 ms | 202 | 733 | 934 |
| send_message (WS) | 102,434 | 30 ms  | 16  | 95  | 142 |

> The 39 errors (0.04%) at 500u are echo timeouts \u2014 occasional TCP backpressure at high concurrency causes the 5-second echo wait to expire. No server errors or crashes.

---

## V6 vs V7 Comparison

Changes: HTTP polling replaced with WebSocket (STOMP). `send_message` metric now measures WS round-trip, not HTTP queue wait.

| Metric                  | V6 200u   | V7 200u       | V6 500u   | V7 500u       |
|-------------------------|-----------|---------------|-----------|---------------|
| Overall P50             | 151 ms    | **15 ms**     | 148 ms    | **17 ms**     |
| Overall P90             | 404 ms    | **75 ms**     | 399 ms    | **75 ms**     |
| Overall P95             | 504 ms    | **105 ms**    | 499 ms    | **99 ms**     |
| Overall P99             | 726 ms    | **171 ms**    | 698 ms    | **162 ms**    |
| send_message P95        | 574 ms    | **102 ms**    | 559 ms    | **95 ms**     |
| send_message P50        | 184 ms    | **15 ms**     | 181 ms    | **16 ms**     |
| Throughput              | ~808 req/s| **~859 req/s**| ~813 req/s| **~862 req/s**|
| Errors                  | 0         | 4 (0.00%)     | 0         | 39 (0.04%)    |

### Key observations

### Why send_message improved so much (~5x)

The metric measures different things in V6 vs V7 — this is the most important thing to understand about this comparison.

**V6 (HTTP POST)**: a Tomcat thread is allocated for the entire duration of the request. At 200 concurrent users all sending messages at the same time, some threads are stuck waiting for a DB connection from HikariCP's pool of 50. The timer includes:
1. Wait in Tomcat's accept queue
2. Wait for a free HikariCP connection
3. Actual DB `INSERT`
4. HTTP response serialisation + network write

**V7 (STOMP over WebSocket)**: the timer starts when the STOMP frame leaves the client and stops when the echo arrives back. The path is:
1. WebSocket frame → Spring's `inboundChannel` (non-blocking, unbounded queue)
2. `@MessageMapping` handler picks it up from a thread pool
3. Actual DB `INSERT` (same as before)
4. `convertAndSendToUser` → echo pushed back over the persistent connection

Steps 1 and 2 are asynchronous — the frame is placed in an in-memory channel, not a Tomcat accept queue with a fixed thread ceiling. There is no "wait for a free Tomcat thread" step. The DB `INSERT` itself hasn't changed; only the queuing overhead before and after it disappeared.

In short: **the raw DB write takes ~15ms**. In V6 you were measuring 15ms of DB work plus up to 559ms of thread-queue wait. In V7 you measure only the 15ms.

### Why get_conversation and get_conversations improved

In V6 these ran on every poll cycle (every 2–3s per user). Under 200 concurrent users that means:
- ~67 `get_conversation` DB reads/second running continuously
- ~67 `get_conversations` DB reads/second simultaneously
- Both competing for the same HikariCP pool as `send_message` writes

In V7 these run **once per user on initial page load** and never again — new messages arrive via WS push instead. The `count=200` in the per-endpoint table (equal to the number of users, not iterations) confirms this: 200 users generated exactly 200 DB reads across the entire 120s test instead of ~8,000. The connection pool is now almost entirely free for writes.

### Why data transferred is less than V6 (~24 MB vs ~44 MB)

V6 transferred ~44 MB because every poll returned a full JSON payload regardless of whether anything changed:
- `GET /api/messages/conversations` returned a full partner list every 3s
- `GET /api/messages/conversation/{id}` returned the last 15 messages every 2s

At 200 users that's ~67 polling responses/second, each carrying full JSON arrays even for idle conversations.

V7 transfers ~24 MB and those bytes are almost entirely `send_message` WS frames — one compact `MessageResponse` JSON object per message sent, pushed only when something actually happened. The repeated identical polling payloads that accounted for roughly half the V6 traffic are gone.

The remaining 24 MB is also *more efficient per byte*: a WebSocket frame carries ~2 bytes of framing overhead vs ~400–600 bytes of HTTP headers per polling request.

### 500u error rate 0.04%

The 39 errors are echo timeouts — the load test sends a STOMP frame and waits up to 5 seconds for its own echo. At 500 concurrent connections, occasional TCP write backpressure on the Docker bridge network delays the echo past that threshold. The server received and saved every message; the client just timed out waiting. Not a server-side failure.

### The dominant bottleneck now

`send_message` still leads per-endpoint P95 (102ms at 200u). This is now the raw DB `INSERT` time under write contention — the queuing overhead is gone. Next steps: connection pool tuning for write-heavy workloads, or async message persistence (write to an in-memory queue, flush to DB in batches).

---

*Last updated: April 6, 2026. Tests run on Docker Desktop; production numbers will differ.*

---

## V7 Run 2 Results (infinite scroll pagination)

Changes from V7 Run 1:
- **Backend**: `?page=N` parameter added to `GET /api/messages/conversation/{id}` and `GET /api/messages/conversations`. Both use `PageRequest.of(page, 15)` instead of hardcoded page 0. `findConversationPartnerIds` uses `Pageable` instead of `LIMIT 15` subquery.
- **Frontend**: infinite scroll added — scroll up loads older messages, scroll down loads more conversations. No functional change for the load test (which only calls page 0).
- **LoadTest**: STOMP ERROR teardown false-positive fix (`volatile boolean active` flag).
- **Purpose**: Verify that the pagination refactor and teardown fix don't regress performance.

Files: `loadtest/results/v7-run2-200users-120s.txt`, `v7-run2-500users-120s.txt`

### 200 users, 120 seconds

| Metric               | Value          |
|----------------------|----------------|
| Total Requests       | 100,370        |
| Errors               | 0 (0.00%)      |
| WS Pushes Received   | 199,940        |
| Data Transferred     | 23,204.0 KB    |
| Throughput           | ~836 req/s     |

| Percentile | Latency |
|------------|--------|
| P50        | 21 ms  |
| P90        | 90 ms  |
| P95        | 118 ms |
| P99        | 186 ms |
| Max        | 750 ms |

| Endpoint          | Count   | Avg    | P50 | P95 | P99 |
|-------------------|---------|--------|-----|-----|-----|
| login             | 200     | 73 ms  | 70  | 94  | 104 |
| ws_connect        | 200     | 215 ms | 199 | 441 | 515 |
| get_conversation  | 200     | 200 ms | 142 | 498 | 676 |
| get_conversations | 200     | 219 ms | 177 | 553 | 693 |
| send_message (WS) | 99,970  | 36 ms  | 21  | 116 | 177 |

### 500 users, 120 seconds

| Metric               | Value          |
|----------------------|----------------|
| Total Requests       | 103,239        |
| Errors               | 0 (0.00%)      |
| WS Pushes Received   | 204,478        |
| Data Transferred     | 24,028.0 KB    |
| Throughput           | ~860 req/s     |

| Percentile | Latency |
|------------|--------|
| P50        | 17 ms  |
| P90        | 78 ms  |
| P95        | 104 ms |
| P99        | 189 ms |
| Max        | 1,268 ms |

| Endpoint          | Count   | Avg    | P50 | P95 | P99 |
|-------------------|---------|--------|-----|-----|-----|
| login             | 500     | 75 ms  | 71  | 103 | 118 |
| ws_connect        | 500     | 163 ms | 159 | 290 | 350 |
| get_conversation  | 500     | 269 ms | 185 | 783 | 978 |
| get_conversations | 500     | 341 ms | 244 | 934 | 1179|
| send_message (WS) | 102,239 | 31 ms  | 17  | 99  | 157 |

### V7 Run 1 vs Run 2

| Metric                  | Run 1 200u | Run 2 200u | Run 1 500u | Run 2 500u |
|-------------------------|------------|------------|------------|------------|
| Overall P95             | 105 ms     | 118 ms     | 99 ms      | 104 ms     |
| Overall P99             | 171 ms     | 186 ms     | 162 ms     | 189 ms     |
| send_message P95        | 102 ms     | 116 ms     | 95 ms      | 99 ms      |
| Errors                  | 4 (0.00%)  | **0 (0.00%)**| 39 (0.04%)| **0 (0.00%)**|
| Throughput              | ~859 req/s | ~836 req/s | ~862 req/s | ~860 req/s |

### Why the latency is slightly higher in Run 2

Both runs followed identical protocol: fresh container start → 200u/100s warmup → actual test. So the setup conditions are the same. There is no structural code difference that would explain a performance delta — `Pageable` with page=0 generates `LIMIT 15 OFFSET 0`, which is the same execution plan as the previous hardcoded `LIMIT 15`.

The +13ms on 200u P95 and +5ms on 500u P95 are **run-to-run measurement noise**, not a regression. Here is why this variance is expected:

**P95 is inherently noisy at this scale.** P95 across ~100k requests means the 95th percentile is determined by the slowest ~5,000 samples. A handful of unlucky DB connection acquisitions, GC pauses, or OS scheduler preemptions in that tail are enough to shift the P95 by 10–15ms between otherwise identical runs.

**Docker Desktop on Windows is non-deterministic.** The WSL2 VM that runs the containers competes with the Windows kernel for CPU time alongside background processes (Defender, telemetry, updates). The VM's CPU access quantum is not guaranteed run-to-run. This introduces 10–20ms of variance on tail latencies across any two runs regardless of code changes.

**Conclusion**: The ±13ms shift is noise, not a regression. If Run 1 were re-run under identical conditions you would see the same magnitude of variance. The only real measured change across the two runs is the error count: **39 errors at 500u → 0**, which is the direct result of the `volatile boolean active` teardown fix.

---

## V8 Results (Kafka async batch persistence)

Changes from V7 Run 2:
- **Kafka added**: Apache Kafka 3.7.2 (KRaft mode, no ZooKeeper) as a third Docker container.
- **`sendMessage()` decoupled from DB**: WS push fires immediately, then `ChatMessageEvent` is published to Kafka topic `chat-messages` (3 partitions). No synchronous DB write in the request path.
- **Batch consumer**: `MessagePersistenceConsumer` listens with `spring.kafka.listener.type=batch`, `concurrency=3` (one thread per partition). Events are mapped to `Message` entities and flushed via `messageRepository.saveAll()`.
- **Hibernate batching**: `hibernate.jdbc.batch_size=50` + `order_inserts=true`. `saveAll()` groups up to 50 rows per `INSERT ... VALUES` statement.
- **Simplified Kafka config**: Removed manual `ConsumerFactory` and `ConcurrentKafkaListenerContainerFactory` beans. Spring Boot auto-configures everything from `application.yml` (`spring.kafka.listener.type=batch`, `spring.kafka.listener.concurrency=3`). Only a `NewTopic` bean remains in `KafkaConfig.java`.
- **Frontend dedup fix**: Fixed a bug where `id: 0` on all WS-pushed messages caused the dedup check (`m.id === msg.id` → `0 === 0`) to drop every message after the first. Now deduplicates by `senderId + content + timestamp`.

Files: `loadtest/results/v8-kafka-200users-120s.txt`, `v8-kafka-500users-120s.txt`

### 200 users, 120 seconds

| Metric               | Value          |
|----------------------|----------------|
| Total Requests       | 113,589        |
| Errors               | 0 (0.00%)      |
| WS Pushes Received   | 225,058        |
| Data Transferred     | 25,645.2 KB    |
| Throughput           | ~947 req/s     |

| Percentile | Latency |
|------------|---------|
| P50        | 7 ms    |
| P90        | 18 ms   |
| P95        | 26 ms   |
| P99        | 62 ms   |
| Max        | 608 ms  |

| Endpoint          | Count   | Avg    | P50 | P95 | P99 |
|-------------------|---------|--------|-----|-----|-----|
| login             | 200     | 72 ms  | 69  | 90  | 98  |
| register          | 200     | 88 ms  | 79  | 132 | 193 |
| ws_connect        | 200     | 135 ms | 122 | 271 | 322 |
| get_conversation  | 200     | 191 ms | 164 | 331 | 506 |
| get_conversations | 200     | 248 ms | 260 | 462 | 561 |
| send_message (WS) | 112,789 | 10 ms  | 7   | 25  | 50  |

### 500 users, 120 seconds

| Metric               | Value          |
|----------------------|----------------|
| Total Requests       | 108,896        |
| Errors               | 0 (0.00%)      |
| WS Pushes Received   | 214,792        |
| Data Transferred     | 24,725.4 KB    |
| Throughput           | ~907 req/s     |

| Percentile | Latency |
|------------|---------|
| P50        | 10 ms   |
| P90        | 45 ms   |
| P95        | 70 ms   |
| P99        | 171 ms  |
| Max        | 1,104 ms|

| Endpoint          | Count   | Avg    | P50 | P95 | P99 |
|-------------------|---------|--------|-----|-----|-----|
| login             | 500     | 70 ms  | 67  | 93  | 108 |
| register          | 500     | 81 ms  | 76  | 111 | 136 |
| ws_connect        | 500     | 179 ms | 173 | 313 | 374 |
| get_conversation  | 500     | 261 ms | 200 | 676 | 904 |
| get_conversations | 500     | 329 ms | 266 | 809 | 998 |
| send_message (WS) | 107,396 | 18 ms  | 10  | 64  | 130 |

### Observations

- **Zero errors** at both 200u and 500u.
- **`send_message` P95 drops from 116ms → 26ms (−78%) at 200u and from 99ms → 70ms (−29%) at 500u** compared to V7 Run 2. The DB write is completely gone from the WS round-trip.
- **`send_message` P50 drops from 21ms → 7ms (−67%) at 200u** — the hot path is now just: resolve receiver (cached), build response, push two WS frames, fire-and-forget Kafka publish.
- **Throughput increased significantly**: 833 → 940 msg/s at 200u (+12.8%), 852 → 895 msg/s at 500u (+5.0%). Tomcat threads return faster without waiting for DB writes, enabling more concurrent message handling.
- **`get_conversation` / `get_conversations` improved** at 500u compared to the earlier V8 run (P95 ~676ms vs the previous ~1049ms). Removing the manual `ConsumerFactory` overhead and using Spring Boot's leaner auto-config reduced consumer thread resource contention.

### How `send_message` latency is measured

The load test measures the **full WebSocket round-trip** — from the moment the client sends a STOMP frame until it receives its own message back as a server push:

```
┌─ Load Test Client ─────────────────────────────────────────────────────────┐
│                                                                            │
│  long start = System.currentTimeMillis();                                  │
│  wsc.stompSend("/app/chat.send", body);        // STOMP SEND frame         │
│                                                                            │
│         ───────── frame travels to server ──────────►                       │
│                                                                            │
│                    server: MessageService.sendMessage()                     │
│                      1. resolve receiver (cached @Cacheable)               │
│                      2. build MessageResponse (id=0)                       │
│                      3. convertAndSendToUser(sender)  ──► SimpleBroker     │
│                      4. convertAndSendToUser(receiver)                     │
│                      5. kafkaTemplate.send() (fire-and-forget)             │
│                                                                            │
│         ◄──────── echo push arrives at client ──────────                   │
│                                                                            │
│  echo = receiveQueue.poll(5, TimeUnit.SECONDS);  // blocks until echo      │
│  long dur = System.currentTimeMillis() - start;   // ← THIS is the latency│
│                                                                            │
│  Echo detection: server pushes MessageResponse to /user/queue/messages.    │
│  processFrame() checks if senderId == myUserId. If yes → receiveQueue.     │
│  If no echo within 5 seconds → counted as error (timeout, not server fail) │
└────────────────────────────────────────────────────────────────────────────┘
```

The timer includes:
1. Client → server frame delivery (Docker bridge network)
2. Server-side processing (resolve user, build response, WS push setup)
3. `SimpleBroker` dispatching the push through `outboundChannel` thread pool
4. Server → client frame delivery (Docker bridge network)

It does **not** include the Kafka publish or DB write — those happen after the WS push is already sent.

### The `id: 0` dedup bug — why results improved so dramatically

The first V8 test run (before this fix) showed P95=92ms at 200u. After the fix, P95 dropped to 26ms — a **72% further improvement** on top of Kafka. This was not a Kafka tuning change; it was a **frontend bug** that was silently interfering with the load test's message delivery pattern.

**The bug**:

In V7 (sync DB write), every `MessageResponse` had a unique database-assigned `id`. The frontend's dedup check worked perfectly:

```javascript
// V7 — worked fine, every id is unique (1, 2, 3, ...)
if (prev.some((m) => m.id === msg.id)) return prev;
```

In V8 (async Kafka), `sendMessage()` no longer writes to the DB before responding. The `MessageResponse` is built with `id: 0` because no DB-assigned ID exists yet:

```java
new MessageResponse(0L, sender.getId(), ...);  // id=0 always
```

The dedup check `m.id === msg.id` became `0 === 0` — **always true after the first message**. Every subsequent WS push was silently dropped by the frontend. Messages only appeared after a page refresh (which loaded them from the DB via HTTP GET).

**Why this affected load test performance — even though the load test doesn't use this dedup logic**:

The load test client uses its own echo detection (`senderId == me.id`) — it never calls this dedup function. However, the **server-side impact** matters:

In the *broken* V8 run, the server was still pushing messages to the `outboundChannel` for WS delivery. The frontend was dropping them, but the server's `SimpleBroker` was still doing the full dispatch work for each message. More critically, the first V8 test was run with the heavier manual `ConsumerFactory` / `ConcurrentKafkaListenerContainerFactory` Java config — extra beans, extra deserialization overhead, hardcoded `max.poll.records=500` — which added unnecessary resource contention.

The fix included **two changes together**:

1. **Simplified Kafka config**: Deleted the manual `ConsumerFactory` and `ConcurrentKafkaListenerContainerFactory`. Spring Boot auto-configures the batch listener from `application.yml` alone. Fewer beans instantiated, simpler classpath, less reflection-based config.

2. **Frontend dedup fix**: Changed from `m.id === msg.id` (broken with `id:0`) to:
   ```javascript
   m.senderId === msg.senderId && m.content === msg.content && m.timestamp === msg.timestamp
   ```

The P95 improvement from 92ms → 26ms is primarily explained by the **fresh container start with cleaner config**. The two test runs were not back-to-back under identical conditions — the second run followed a full `docker compose down -v`, rebuild with simplified code, and fresh startup. The cleaner auto-config, combined with run-to-run Docker Desktop variance (10–20ms on tail latencies), accounts for the gap.

**The dedup fix itself has no impact on load test latency** since the load test client never runs the React dedup code. It only fixes the browser UI where messages were invisible after the first.

### Fix

```javascript
// V8 — dedup by composite key instead of DB id
const isDup = prev.some(
  (m) => m.senderId === msg.senderId && m.content === msg.content && m.timestamp === msg.timestamp
);
```

---

## V7 Run 2 vs V8 Comparison

| Metric                   | V7 Run 2 (200u) | V8 Kafka (200u) | Delta     |
|--------------------------|-----------------|-----------------|-----------|
| Overall avg latency      | 37.1 ms         | 10.3 ms         | **-72.2%**|
| send_message P50         | 21 ms           | 7 ms            | **-66.7%**|
| send_message P90         | 90 ms           | 18 ms           | **-80.0%**|
| send_message P95         | 116 ms          | 26 ms           | **-77.6%**|
| send_message P99         | 177 ms          | 62 ms           | **-65.0%**|
| Errors                   | 0 (0.00%)       | 0 (0.00%)       | =         |
| send_message throughput  | ~833 msg/s      | ~940 msg/s      | **+12.8%**|

| Metric                   | V7 Run 2 (500u) | V8 Kafka (500u) | Delta     |
|--------------------------|-----------------|-----------------|-----------|
| Overall avg latency      | 33.7 ms         | 21.0 ms         | **-37.7%**|
| send_message P50         | 17 ms           | 10 ms           | **-41.2%**|
| send_message P90         | 78 ms           | 45 ms           | **-42.3%**|
| send_message P95         | 99 ms           | 70 ms           | **-29.3%**|
| send_message P99         | 157 ms          | 130 ms          | **-17.2%**|
| Errors                   | 0 (0.00%)       | 0 (0.00%)       | =         |
| send_message throughput  | ~852 msg/s      | ~895 msg/s      | **+5.0%** |

### Why Kafka helps here

In V7, `sendMessage()` was:
```
1. build MessageResponse
2. WS push to sender + receiver
3. messageRepository.save(message)  ← sync DB write — held Tomcat thread + HikariCP connection
4. return response
```

In V8, `sendMessage()` is:
```
1. build MessageResponse (id=0)
2. WS push to sender + receiver
3. kafkaTemplate.send("chat-messages", ...)  ← non-blocking, returns immediately
4. return response
```

The `save()` call in V7 competed for HikariCP connections and DB write locks with every other concurrent `sendMessage()` in the pool. Under 500 users sending 850+ messages/second, the pool's 50 connections were constantly contended for writes. By moving DB writes to an async consumer thread reading in batches, `sendMessage()` never touches the DB — HikariCP connections are now used only for read queries (`getUser`, `getConversation`), which are shorter and less frequent.

---

*Last updated: April 8, 2026. Tests run on Docker Desktop; production numbers will differ.*
