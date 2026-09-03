package com.seckill.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP 层压测：客户端 → Nginx(8080, 轮询) → app1/app2 双实例 → 容器化中间件。
 * 读 = GET /shop/{id}（多级缓存 + 券余量）；写 = POST /voucher/{id}/seckill?userId=u（一人一券一次）。
 *
 * 用法：HttpBench [baseUrl] [threads] [durationSec] [readPercent]
 */
public class HttpBench {

    static final int SHOPS = 1000;

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        int readPercent = args.length > 3 ? Integer.parseInt(args[3]) : 80;

        // 重置
        post(base + "/admin/reset?stock=1000");

        List<Long> readLat = Collections.synchronizedList(new ArrayList<>(1 << 21));
        List<Long> writeLat = Collections.synchronizedList(new ArrayList<>(1 << 19));
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        AtomicInteger writeOk = new AtomicInteger(), errors = new AtomicInteger();
        AtomicLong userIds = new AtomicLong(900_000_000);
        CountDownLatch latch = new CountDownLatch(threads);
        long start = System.nanoTime();
        long endNanos = start + durationSec * 1_000_000_000L;

        System.out.printf("HTTP 压测开始: %s threads=%d duration=%ds read=%d%%%n", base, threads, durationSec, readPercent);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build(); // 每线程一个 client，复用连接池
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (System.nanoTime() < endNanos) {
                    long id = 1 + rnd.nextLong(SHOPS);
                    try {
                        long s = System.nanoTime();
                        int status;
                        String body;
                        if (rnd.nextInt(100) < readPercent) {
                            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/shop/" + id)).GET().build();
                            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                            status = resp.statusCode(); body = resp.body();
                            int idx = reads.incrementAndGet() - 1;
                            if (idx < 2_000_000) readLat.add((System.nanoTime() - s) / 1000);
                        } else {
                            long u = userIds.getAndIncrement();
                            HttpRequest req = HttpRequest.newBuilder(URI.create(
                                    base + "/voucher/" + id + "/seckill?userId=" + u)).POST(HttpRequest.BodyPublishers.noBody()).build();
                            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                            status = resp.statusCode(); body = resp.body();
                            int idx = writes.incrementAndGet() - 1;
                            if (idx < 500_000) writeLat.add((System.nanoTime() - s) / 1000);
                            if (body.contains("SUCCESS")) writeOk.incrementAndGet();
                        }
                        if (status != 200) errors.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        double sec = (System.nanoTime() - start) / 1e9;

        long[] rc = readLat.stream().mapToLong(Long::longValue).sorted().toArray();
        long[] wc = writeLat.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf(Locale.ROOT,
                "RESULT http-mixed: 总QPS=%.0f | 读QPS=%.0f(P50=%dus P99=%dus) | 写QPS=%.0f(P99=%dus) | 抢券成功=%d 错误=%d%n",
                (reads.get() + writes.get()) / sec, reads.get() / sec,
                rc.length > 0 ? rc[rc.length / 2] : -1, rc.length > 0 ? rc[(int) (rc.length * 0.99)] : -1,
                writes.get() / sec, wc.length > 0 ? wc[(int) (wc.length * 0.99)] : -1,
                writeOk.get(), errors.get());

        // 等消费落库 + 延迟取消回收，对账轮询至收敛（上限 180s）
        for (int i = 0; i < 90; i++) {
            Thread.sleep(2000);
            String audit = get(base + "/admin/audit?stock=1000");
            if (audit.contains("passed\":true")) {
                System.out.println("对账: " + audit);
                return;
            }
            if (i == 89 || i % 15 == 14) System.out.println("对账(进行中): " + audit);
        }
        System.exit(0);
    }

    static void post(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    }
}
