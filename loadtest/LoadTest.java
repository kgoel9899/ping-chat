import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * Zero-dependency Java load test for the ChatApp API.
 * V7: Uses WebSocket (STOMP) for sending messages and receiving push notifications.
 * HTTP is still used for register, login, and initial data loads.
 *
 * Usage:
 *   java LoadTest.java [BASE_URL] [NUM_USERS] [DURATION_SECONDS]
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
    static final AtomicInteger totalWsReceived = new AtomicInteger();

    // ── Shared state ──
    static final List<UserInfo> users = Collections.synchronizedList(new ArrayList<>());

    record UserInfo(int id, String username, String token) {}

    public static void main(String[] args) throws Exception {
        if (args.length >= 1) BASE_URL = args[0];
        if (args.length >= 2) NUM_USERS = Integer.parseInt(args[1]);
        if (args.length >= 3) DURATION_SECONDS = Integer.parseInt(args[2]);

        System.out.println("+==================================================+");
        System.out.println("|         CHATAPP JAVA LOAD TEST (WebSocket)       |");
        System.out.println("+==================================================+");
        System.out.printf("|  Target:     %s%n", BASE_URL);
        System.out.printf("|  Users:      %d%n", NUM_USERS);
        System.out.printf("|  Duration:   %d seconds%n", DURATION_SECONDS);
        System.out.println("+==================================================+");
        System.out.println();

        // Initialize metric buckets
        for (String ep : List.of("register", "login", "ws_connect", "send_message",
                                  "get_conversation", "get_conversations")) {
            latencies.put(ep, new CopyOnWriteArrayList<>());
        }

        // ── Phase 1: Register users (HTTP) ──
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

        // ── Phase 1b: Login each user once (HTTP) ──
        System.out.println("[Phase 1b] Logging in " + users.size() + " users once...");
        long loginStart = System.currentTimeMillis();
        for (int i = 0; i < users.size(); i++) {
            UserInfo u = users.get(i);
            String loginBody = String.format(
                "{\"username\":\"%s\",\"password\":\"password123\"}", u.username);
            long start = System.currentTimeMillis();
            try {
                HttpResponse<String> res = setupClient.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
                long dur = System.currentTimeMillis() - start;
                record("login", dur);
                if (res.statusCode() == 200) {
                    String freshToken = extractString(res.body(), "token");
                    users.set(i, new UserInfo(u.id, u.username, freshToken));
                } else {
                    System.err.printf("  Login failed for %s: %d%n", u.username, res.statusCode());
                }
            } catch (Exception e) {
                System.err.printf("  Login exception for %s: %s%n", u.username, e.getMessage());
            }
            if (i % 50 == 0 && i > 0) {
                System.out.printf("  ...logged in %d/%d%n", i, users.size());
            }
        }
        System.out.printf("[Phase 1b] Done. Logged in %d users in %.1fs%n%n",
                users.size(), (System.currentTimeMillis() - loginStart) / 1000.0);

        // ── Phase 2: Connect WebSockets ──
        System.out.println("[Phase 2] Connecting " + users.size() + " WebSocket clients...");
        long wsStart = System.currentTimeMillis();

        String wsUrl = BASE_URL.replace("http://", "ws://").replace("https://", "wss://") + "/ws";
        List<WsClient> wsClients = Collections.synchronizedList(new ArrayList<>());

        ExecutorService connectPool = Executors.newFixedThreadPool(Math.min(users.size(), 50));
        CountDownLatch connectLatch = new CountDownLatch(users.size());

        for (int i = 0; i < users.size(); i++) {
            final int idx = i;
            connectPool.submit(() -> {
                try {
                    UserInfo me = users.get(idx);
                    WsClient wsc = new WsClient(me, wsUrl);
                    wsc.connect();
                    wsClients.add(wsc);
                } catch (Exception e) {
                    System.err.printf("  WS connect failed for user %d: %s%n", idx, e.getMessage());
                    totalErrors.incrementAndGet();
                } finally {
                    connectLatch.countDown();
                }
                if (idx % 50 == 0 && idx > 0) {
                    System.out.printf("  ...connected %d/%d%n", idx, users.size());
                }
            });
        }
        connectLatch.await(60, TimeUnit.SECONDS);
        connectPool.shutdown();
        System.out.printf("[Phase 2] Done. %d WebSocket clients connected in %.1fs%n%n",
                wsClients.size(), (System.currentTimeMillis() - wsStart) / 1000.0);

        // ── Phase 3: Load test — send via WS, receive via push ──
        System.out.printf("[Phase 3] Running load test for %d seconds with %d concurrent users...%n",
                DURATION_SECONDS, wsClients.size());

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(wsClients.size(), 200));
        CountDownLatch latch = new CountDownLatch(wsClients.size());
        long deadline = System.currentTimeMillis() + (DURATION_SECONDS * 1000L);
        long testStart = System.currentTimeMillis();

        AtomicInteger iterCount = new AtomicInteger();

        for (int i = 0; i < wsClients.size(); i++) {
            final int userIdx = i;
            pool.submit(() -> {
                WsClient wsc = wsClients.get(userIdx);
                Random rng = new Random(userIdx);

                // Initial HTTP load (like opening the app)
                UserInfo partner = pickPartner(wsc.me, rng);
                doGetConversations(setupClient, wsc.me);
                doGetConversation(setupClient, wsc.me, partner);

                try {
                    while (System.currentTimeMillis() < deadline) {
                        partner = pickPartner(wsc.me, rng);

                        // Send message via WebSocket STOMP
                        long start = System.currentTimeMillis();
                        String content = "Load test msg at " + start;
                        String sendBody = String.format(
                            "{\"receiverId\":%d,\"content\":\"%s\"}", partner.id, content);

                        wsc.stompSend("/app/chat.send", sendBody);
                        totalRequests.incrementAndGet();

                        // Wait for our own message echo on subscription
                        String echo = wsc.receiveQueue.poll(5, TimeUnit.SECONDS);
                        long dur = System.currentTimeMillis() - start;

                        if (echo != null) {
                            record("send_message", dur);
                            totalBytes.addAndGet(echo.length());
                        } else {
                            totalErrors.incrementAndGet();
                        }

                        int iters = iterCount.incrementAndGet();
                        if (iters % 500 == 0) {
                            double elapsed = (System.currentTimeMillis() - testStart) / 1000.0;
                            System.out.printf("  [%.0fs] %d iterations, %d requests, %d errors, %d ws_pushes%n",
                                    elapsed, iters, totalRequests.get(), totalErrors.get(),
                                    totalWsReceived.get());
                        }

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

        // Close all WebSockets
        for (WsClient wsc : wsClients) {
            try { wsc.close(); } catch (Exception ignored) {}
        }

        System.out.println();
        printReport();
    }

    // ── WebSocket STOMP Client (zero dependencies) ──

    static class WsClient {
        final UserInfo me;
        final String wsUrl;
        WebSocket ws;
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final BlockingQueue<String> receiveQueue = new LinkedBlockingQueue<>();
        final StringBuilder frameBuffer = new StringBuilder();

        WsClient(UserInfo me, String wsUrl) {
            this.me = me;
            this.wsUrl = wsUrl;
        }

        void connect() throws Exception {
            long start = System.currentTimeMillis();

            ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            frameBuffer.append(data);
                            if (last) {
                                processFrame(frameBuffer.toString());
                                frameBuffer.setLength(0);
                            }
                            webSocket.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            totalErrors.incrementAndGet();
                        }
                    }).join();

            // STOMP CONNECT
            ws.sendText(stompFrame("CONNECT", Map.of(
                    "accept-version", "1.2",
                    "Authorization", "Bearer " + me.token
            ), null), true);

            if (!connectedLatch.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("STOMP CONNECT timeout for " + me.username);
            }

            // Subscribe to personal message queue
            ws.sendText(stompFrame("SUBSCRIBE", Map.of(
                    "id", "sub-0",
                    "destination", "/user/queue/messages"
            ), null), true);

            record("ws_connect", System.currentTimeMillis() - start);
        }

        void processFrame(String raw) {
            // STOMP frames may have leading newlines (heartbeats)
            String frame = raw.stripLeading();
            if (frame.isEmpty() || frame.equals("\n")) return;

            if (frame.startsWith("CONNECTED")) {
                connectedLatch.countDown();
            } else if (frame.startsWith("MESSAGE")) {
                int bodyStart = frame.indexOf("\n\n");
                if (bodyStart >= 0) {
                    String body = frame.substring(bodyStart + 2);
                    if (body.endsWith("\0")) body = body.substring(0, body.length() - 1);
                    body = body.trim();

                    totalWsReceived.incrementAndGet();

                    // If it's our own message echo, put in receiveQueue for round-trip timing
                    int senderId = extractInt(body, "senderId");
                    if (senderId == me.id) {
                        receiveQueue.offer(body);
                    }
                }
            } else if (frame.startsWith("ERROR")) {
                totalErrors.incrementAndGet();
            }
        }

        void stompSend(String destination, String body) {
            ws.sendText(stompFrame("SEND", Map.of(
                    "destination", destination,
                    "content-type", "application/json"
            ), body), true);
        }

        void close() throws Exception {
            if (ws != null) {
                ws.sendText(stompFrame("DISCONNECT", Map.of(), null), true);
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
        }
    }

    // ── STOMP frame builder ──

    static String stompFrame(String command, Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(command).append('\n');
        for (var e : headers.entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue()).append('\n');
        }
        sb.append('\n');
        if (body != null) sb.append(body);
        sb.append('\0');
        return sb.toString();
    }

    // ── HTTP Helpers (used for initial loads + setup) ──

    static UserInfo pickPartner(UserInfo me, Random rng) {
        UserInfo partner;
        do {
            partner = users.get(rng.nextInt(users.size()));
        } while (partner.id == me.id);
        return partner;
    }

    static void doGetConversation(HttpClient client, UserInfo user, UserInfo partner) {
        timed("get_conversation", () -> get(client, "/api/messages/conversation/" + partner.id, user.token));
    }

    static void doGetConversations(HttpClient client, UserInfo user) {
        timed("get_conversations", () -> get(client, "/api/messages/conversations", user.token));
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
        // Aggregate send_message + get_conversation + get_conversations for "overall" latency
        long[] all = latencies.entrySet().stream()
                .filter(e -> !e.getKey().equals("register") && !e.getKey().equals("login")
                          && !e.getKey().equals("ws_connect"))
                .flatMapToLong(e -> e.getValue().stream().mapToLong(Long::longValue))
                .sorted().toArray();

        System.out.println("+==============================================================+");
        System.out.println("|              LOAD TEST RESULTS SUMMARY (WebSocket)           |");
        System.out.println("+==============================================================+");
        System.out.printf("|  Total Requests:    %,d%n", totalRequests.get());
        System.out.printf("|  Total Errors:      %,d (%.2f%%)%n", totalErrors.get(),
                totalRequests.get() > 0 ? (totalErrors.get() * 100.0 / totalRequests.get()) : 0);
        System.out.printf("|  WS Pushes Recv'd:  %,d%n", totalWsReceived.get());
        System.out.printf("|  Data Transferred:  %,.1f KB%n", totalBytes.get() / 1024.0);
        System.out.println("|");
        if (all.length > 0) {
            System.out.printf("|  Overall Latency (send_message round-trip):%n");
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
