import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * Zero-dependency Java load test for the ChatApp API.
 * Simulates concurrent users registering, logging in, sending messages, and fetching conversations.
 * Prints p50/p90/p95/p99 latency per endpoint at the end.
 *
 * Usage:
 *   javac LoadTest.java
 *   java LoadTest [BASE_URL] [NUM_USERS] [DURATION_SECONDS]
 *
 * Defaults: http://localhost:8080  100 users  60 seconds
 */
public class LoadTest {

    static String BASE_URL = "http://localhost:8080";
    static int NUM_USERS = 100;
    static int DURATION_SECONDS = 60;

    // ── Metrics collection ──
    static final ConcurrentHashMap<String, CopyOnWriteArrayList<Long>> latencies = new ConcurrentHashMap<>();
    static final AtomicInteger totalRequests = new AtomicInteger();
    static final AtomicInteger totalErrors = new AtomicInteger();
    static final AtomicLong totalBytes = new AtomicLong();

    // ── Shared state ──
    static final List<UserInfo> users = Collections.synchronizedList(new ArrayList<>());

    record UserInfo(int id, String username, String token) {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 1) BASE_URL = args[0];
        if (args.length >= 2) NUM_USERS = Integer.parseInt(args[1]);
        if (args.length >= 3) DURATION_SECONDS = Integer.parseInt(args[2]);

        System.out.println("+==================================================+");
        System.out.println("|         CHATAPP JAVA LOAD TEST                   |");
        System.out.println("+==================================================+");
        System.out.printf("|  Target:     %s%n", BASE_URL);
        System.out.printf("|  Users:      %d%n", NUM_USERS);
        System.out.printf("|  Duration:   %d seconds%n", DURATION_SECONDS);
        System.out.println("+==================================================+");
        System.out.println();

        // Initialize metric buckets
        for (String ep : List.of("register", "login", "send_message", "get_conversation", "get_conversations")) {
            latencies.put(ep, new CopyOnWriteArrayList<>());
        }

        // ── Phase 1: Register users ──
        System.out.println("[Phase 1] Registering " + NUM_USERS + " users...");
        long regStart = System.currentTimeMillis();

        HttpClient setupClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String timestamp = String.valueOf(System.currentTimeMillis());
        for (int i = 0; i < NUM_USERS; i++) {
            String username = "loaduser_" + i + "_" + timestamp;
            String body = String.format(
                "{\"username\":\"%s\",\"email\":\"%s@test.com\",\"password\":\"password123\"}",
                username, username);

            long start = System.currentTimeMillis();
            try {
                HttpResponse<String> res = setupClient.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/auth/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());

                long dur = System.currentTimeMillis() - start;
                record("register", dur);

                if (res.statusCode() == 200) {
                    // Quick JSON parse without dependencies
                    String json = res.body();
                    int userId = extractInt(json, "userId");
                    String token = extractString(json, "token");
                    users.add(new UserInfo(userId, username, token));
                } else {
                    totalErrors.incrementAndGet();
                    System.err.printf("  Register failed for user %d: %d%n", i, res.statusCode());
                }
            } catch (Exception e) {
                totalErrors.incrementAndGet();
                System.err.printf("  Register exception for user %d: %s%n", i, e.getMessage());
            }

            // Small stagger to avoid overwhelming during setup
            if (i % 50 == 0 && i > 0) {
                System.out.printf("  ...registered %d/%d%n", i, NUM_USERS);
            }
        }
        System.out.printf("[Phase 1] Done. %d users registered in %.1fs%n%n",
                users.size(), (System.currentTimeMillis() - regStart) / 1000.0);

        if (users.size() < 2) {
            System.err.println("Not enough users registered. Aborting.");
            System.exit(1);
        }

        // ── Phase 2: Load test ──
        System.out.printf("[Phase 2] Running load test for %d seconds with %d concurrent users...%n",
                DURATION_SECONDS, users.size());

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(users.size(), 200));
        CountDownLatch latch = new CountDownLatch(users.size());
        long deadline = System.currentTimeMillis() + (DURATION_SECONDS * 1000L);

        AtomicInteger iterCount = new AtomicInteger();

        for (int i = 0; i < users.size(); i++) {
            final int userIdx = i;
            pool.submit(() -> {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                UserInfo me = users.get(userIdx);
                Random rng = new Random(userIdx);

                try {
                    while (System.currentTimeMillis() < deadline) {
                        // Pick a random partner
                        UserInfo partner;
                        do {
                            partner = users.get(rng.nextInt(users.size()));
                        } while (partner.id == me.id);

                        // 1. Login
                        doLogin(client, me);

                        // 2. Send message
                        doSendMessage(client, me, partner);

                        // 3. Get conversation
                        doGetConversation(client, me, partner);

                        // 4. Get conversations list
                        doGetConversations(client, me);

                        int iters = iterCount.incrementAndGet();
                        if (iters % 500 == 0) {
                            double elapsed = (System.currentTimeMillis() - (deadline - DURATION_SECONDS * 1000L)) / 1000.0;
                            System.out.printf("  [%.0fs] %d iterations, %d requests, %d errors%n",
                                    elapsed, iters, totalRequests.get(), totalErrors.get());
                        }

                        // Small pause between iterations
                        Thread.sleep(100 + rng.nextInt(200));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        // ── Phase 3: Report ──
        System.out.println();
        printReport();
    }

    // ── HTTP Helpers ──

    static void doLogin(HttpClient client, UserInfo user) {
        String body = String.format(
            "{\"username\":\"%s\",\"password\":\"password123\"}", user.username);
        timed("login", () -> post(client, "/api/auth/login", body, null));
    }

    static void doSendMessage(HttpClient client, UserInfo sender, UserInfo receiver) {
        String body = String.format(
            "{\"receiverId\":%d,\"content\":\"Load test msg at %d\"}", receiver.id, System.currentTimeMillis());
        timed("send_message", () -> post(client, "/api/messages", body, sender.token));
    }

    static void doGetConversation(HttpClient client, UserInfo user, UserInfo partner) {
        timed("get_conversation", () -> get(client, "/api/messages/conversation/" + partner.id, user.token));
    }

    static void doGetConversations(HttpClient client, UserInfo user) {
        timed("get_conversations", () -> get(client, "/api/messages/conversations", user.token));
    }

    static int post(HttpClient client, String path, String body, String token) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (token != null) builder.header("Authorization", "Bearer " + token);

            HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            totalRequests.incrementAndGet();
            totalBytes.addAndGet(res.body().length());
            if (res.statusCode() != 200) totalErrors.incrementAndGet();
            return res.statusCode();
        } catch (Exception e) {
            totalRequests.incrementAndGet();
            totalErrors.incrementAndGet();
            return -1;
        }
    }

    static int get(HttpClient client, String path, String token) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + path))
                    .GET();
            if (token != null) builder.header("Authorization", "Bearer " + token);

            HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            totalRequests.incrementAndGet();
            totalBytes.addAndGet(res.body().length());
            if (res.statusCode() != 200) totalErrors.incrementAndGet();
            return res.statusCode();
        } catch (Exception e) {
            totalRequests.incrementAndGet();
            totalErrors.incrementAndGet();
            return -1;
        }
    }

    static void timed(String endpoint, Runnable task) {
        long start = System.currentTimeMillis();
        task.run();
        long duration = System.currentTimeMillis() - start;
        record(endpoint, duration);
    }

    static void record(String endpoint, long durationMs) {
        latencies.get(endpoint).add(durationMs);
    }

    // ── Reporting ──

    static void printReport() {
        // Aggregate all latencies
        long[] all = latencies.values().stream()
                .flatMapToLong(list -> list.stream().mapToLong(Long::longValue))
                .sorted().toArray();

        System.out.println("+==============================================================+");
        System.out.println("|              LOAD TEST RESULTS SUMMARY                       |");
        System.out.println("+==============================================================+");
        System.out.printf("|  Total Requests:    %,d%n", totalRequests.get());
        System.out.printf("|  Total Errors:      %,d (%.2f%%)%n", totalErrors.get(),
                totalRequests.get() > 0 ? (totalErrors.get() * 100.0 / totalRequests.get()) : 0);
        System.out.printf("|  Data Transferred:  %,.1f KB%n", totalBytes.get() / 1024.0);
        System.out.println("|");
        if (all.length > 0) {
            System.out.printf("|  Overall Latency:%n");
            System.out.printf("|    Avg:   %,.1f ms%n", LongStream.of(all).average().orElse(0));
            System.out.printf("|    P50:   %,d ms%n", percentile(all, 50));
            System.out.printf("|    P90:   %,d ms%n", percentile(all, 90));
            System.out.printf("|    P95:   %,d ms%n", percentile(all, 95));
            System.out.printf("|    P99:   %,d ms%n", percentile(all, 99));
            System.out.printf("|    Max:   %,d ms%n", all[all.length - 1]);
        }
        System.out.println("+==============================================================+");
        System.out.println("|  Per-Endpoint Breakdown                                      |");
        System.out.println("+==============================================================+");

        for (var entry : latencies.entrySet()) {
            long[] sorted = entry.getValue().stream().mapToLong(Long::longValue).sorted().toArray();
            if (sorted.length == 0) continue;
            double avg = LongStream.of(sorted).average().orElse(0);
            System.out.printf("|  %-20s  count=%-6d  avg=%-6.0f  p50=%-5d  p95=%-5d  p99=%-5d ms%n",
                    entry.getKey(), sorted.length, avg,
                    percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99));
        }

        System.out.println("+==============================================================+");
    }

    static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    // ── Minimal JSON parsing (no dependencies) ──

    static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return "";
        start += search.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    static int extractInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return -1;
        start += search.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Integer.parseInt(json.substring(start, end));
    }
}
