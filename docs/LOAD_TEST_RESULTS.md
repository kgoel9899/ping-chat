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

*Last updated: April 6, 2026. Tests run on Docker Desktop; production numbers will differ.*
