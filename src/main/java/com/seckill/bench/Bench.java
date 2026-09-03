package com.seckill.bench;

import com.seckill.mapper.LocalMessageMapper;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.VoucherMapper;
import com.seckill.seckill.VoucherService;
import com.seckill.seckill.PayService;
import com.seckill.seckill.SeckillService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 压测入口：--bench=cache|seckill [--threads=8] [--users=50000] [--rate=500000]
 * cache：商铺多级缓存 vs 直连 DB（MyBatis mapper）；seckill：限流+Lua+Kafka+本地消息表+延迟取消全链路。
 */
@Component
public class Bench implements ApplicationRunner {

    static final int SHOPS = 1000; // 券总数（去商铺模型）
    static final long HOT = 1;

    private final VoucherService voucherService;
    private final com.seckill.cache.VoucherCacheService voucherCacheService;
    private final SeckillService seckill;
    private final PayService pay;
    private final VoucherMapper voucherMapper;
    private final OrderMapper orderMapper;
    private final LocalMessageMapper localMessageMapper;
    private final JdbcTemplate jdbc;
    private final JedisPool master;

    public Bench(VoucherService voucherService, com.seckill.cache.VoucherCacheService voucherCacheService,
                 SeckillService seckill,
                 PayService pay, VoucherMapper voucherMapper, OrderMapper orderMapper,
                 LocalMessageMapper localMessageMapper, JdbcTemplate jdbc, @org.springframework.beans.factory.annotation.Qualifier("masterPool") JedisPool master) {
        this.voucherService = voucherService;
        this.voucherCacheService = voucherCacheService;
        this.seckill = seckill;
        this.pay = pay;
        this.voucherMapper = voucherMapper;
        this.orderMapper = orderMapper;
        this.localMessageMapper = localMessageMapper;
        this.jdbc = jdbc;
        this.master = master;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> bench = args.getOptionValues("bench");
        if (bench == null || bench.isEmpty()) return; // 无参数则正常启动 web 服务

        int threads = intArg(args, "threads", 8);
        try (var j = master.getResource()) { j.flushAll(); }

        switch (bench.get(0)) {
            case "seckill" -> benchSeckill(args, threads, intArg(args, "users", 50000));
            case "mixed" -> benchMixed(args, threads, intArg(args, "duration", 30));
            default -> {}
        }
        System.exit(0);
    }

    static int intArg(ApplicationArguments args, String name, int def) {
        List<String> v = args.getOptionValues(name);
        return v == null || v.isEmpty() ? def : Integer.parseInt(v.get(0));
    }

    // ---------- 秒杀压测 ----------
    void benchSeckill(ApplicationArguments args, int threads, int users) throws Exception {
        int rate = intArg(args, "rate", 500_000);
        seckill.rateLimiter.reconfigure(rate, rate); // 压主链路时限流不设瓶颈
        Thread.sleep(1500); // 等 consumer 就绪

        // —— 基线：纯 DB ——
        jdbc.update("UPDATE tb_seckill_voucher SET available = 1000, locked = 0, sold = 0, version = version + 1 WHERE voucher_id = " + HOT);
        seckill.resetRedis(HOT, 1000);
        jdbc.execute("TRUNCATE tb_voucher_order");
        long t0 = System.nanoTime();
        AtomicInteger okDb = new AtomicInteger();
        runBurst(threads, users, u -> seckill.seckillDb(HOT, u) && okDb.incrementAndGet() > -1);
        long dbMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf(Locale.ROOT,
                "  [db]    %d 用户抢 1000 库存：成功 %d，DB 订单 %d，耗时 %dms，吞吐 %.1f万/s%n",
                users, okDb.get(), orderMapper.countAll(), dbMs, users / (dbMs / 1000.0) / 10000);

        // —— 优化全链路 ——
        jdbc.update("UPDATE tb_seckill_voucher SET available = 1000, locked = 0, sold = 0, version = version + 1 WHERE voucher_id = " + HOT);
        seckill.resetRedis(HOT, 1000);
        jdbc.execute("TRUNCATE tb_voucher_order");
        jdbc.execute("TRUNCATE tb_local_message");
        t0 = System.nanoTime();
        AtomicInteger okAsync = new AtomicInteger();
        runBurst(threads, users, u ->
                seckill.seckill(HOT, u) == SeckillService.Result.SUCCESS && okAsync.incrementAndGet() > -1);
        long asyncMs = (System.nanoTime() - t0) / 1_000_000;

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && orderMapper.countAll() < okAsync.get()) {
            Thread.sleep(100); // 等订单全部落库（含本地消息表补偿路径）
        }
        System.out.printf(Locale.ROOT,
                "  [async] %d 用户抢 1000 库存：判定成功 %d，耗时 %dms，吞吐 %.1f万/s，本地消息表 pending=%d%n",
                users, okAsync.get(), asyncMs, users / (asyncMs / 1000.0) / 10000,
                localMessageMapper.countPending());

        // —— 支付 60% + 3s 超时延迟取消 ——
        long paid = 0;
        for (long u = 1; u <= users && paid < okAsync.get() * 0.6; u++) {
            if (pay.pay(HOT, u)) paid++;
        }
        System.out.printf("  [pay] 主动支付 %d 单；等待 3s 超时 + 延迟取消回收...%n", paid);
        Thread.sleep(6000);
        while (pay.unpaidQueueSize() > 0) Thread.sleep(500);

        List<java.util.Map<String, Object>> byStatus = orderMapper.countGroupByStatus();
        long redisStock = seckill.redisStock(HOT);
        Integer dbStock = jdbc.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = " + HOT, Integer.class);
        System.out.printf(Locale.ROOT,
                "  [final] 订单状态分布 %s | Redis余量=%d DB余量=%d | 待支付队列=%d | 总订单=%d 超卖=%b%n",
                byStatus, redisStock, dbStock, pay.unpaidQueueSize(),
                orderMapper.countAll(), orderMapper.countAll() > 1000);

        // —— 限流验证：桶 200 容量 / 2000 每秒，灌 50 万请求 ——
        seckill.rateLimiter.reconfigure(200, 2000);
        seckill.rateLimiter.resetStats();
        t0 = System.nanoTime();
        runBurst(threads, 500_000, u -> seckill.rateLimiter.tryAcquire());
        long secs = (System.nanoTime() - t0) / 1_000_000_000;
        System.out.printf(Locale.ROOT,
                "  [rate-limit] 容量200/补充2000每秒：50万请求 %d 秒，通过=%d 拒绝=%d（拒绝全部 fail-fast 无阻塞）%n",
                secs, seckill.rateLimiter.passed(), seckill.rateLimiter.rejected());
    }

    // ---------- 读写混合压测 ----------
    // 读 80%：券详情（多级缓存）；写 20%：抢券（一人一券一次）
    void benchMixed(ApplicationArguments args, int threads, int durationSec) throws Exception {
        int readPercent = intArg(args, "read", 80);
        int rate = intArg(args, "rate", 500_000);
        seckill.rateLimiter.reconfigure(rate, rate);

        voucherMapper.resetAllStock(1000);
        try (var j = master.getResource()) {
            var pipe = j.pipelined();
            for (long v = 1; v <= SHOPS; v++) {
                pipe.set("seckill:stock:" + v, "1000");
                pipe.del("seckill:users:" + v);
            }
            pipe.sync();
        }
        jdbc.execute("TRUNCATE tb_voucher_order");
        Thread.sleep(1500);

        System.out.printf("压测开始: mixed read=%d%% threads=%d duration=%ds%n", readPercent, threads, durationSec);
        long[] readLat = new long[2_000_000];
        long[] writeLat = new long[500_000];
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger(), writeOk = new AtomicInteger();
        AtomicLong userIds = new AtomicLong(1);
        CountDownLatch latch = new CountDownLatch(threads);
        long start = System.nanoTime();
        long endNanos = start + durationSec * 1_000_000_000L;

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (System.nanoTime() < endNanos) {
                    long v = 1 + rnd.nextLong(SHOPS);
                    if (rnd.nextInt(100) < readPercent) {
                        long s = System.nanoTime();
                        voucherCacheService.queryDetail(v);
                        int idx = reads.incrementAndGet() - 1;
                        if (idx < readLat.length) readLat[idx] = (System.nanoTime() - s) / 1000;
                    } else {
                        long u = userIds.getAndIncrement();
                        long s = System.nanoTime();
                        boolean ok = seckill.seckill(v, u) == SeckillService.Result.SUCCESS;
                        int idx = writes.incrementAndGet() - 1;
                        if (idx < writeLat.length) writeLat[idx] = (System.nanoTime() - s) / 1000;
                        if (ok) writeOk.incrementAndGet();
                    }
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        double sec = (System.nanoTime() - start) / 1e9;
        int rn = Math.min(reads.get(), readLat.length);
        int wn = Math.min(writes.get(), writeLat.length);
        long[] rc = Arrays.copyOf(readLat, rn); Arrays.sort(rc);
        long[] wc = Arrays.copyOf(writeLat, wn); Arrays.sort(wc);
        System.out.printf(Locale.ROOT,
                "RESULT mixed: 总QPS=%.0f | 读QPS=%.0f(P99=%dus) | 写QPS=%.0f(P99=%dus) | 抢券成功=%d%n",
                (reads.get() + writes.get()) / sec, reads.get() / sec, rn > 0 ? rc[(int) (rn * 0.99)] : -1,
                writes.get() / sec, wn > 0 ? wc[(int) (wn * 0.99)] : -1, writeOk.get());

        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && orderMapper.countAll() < writeOk.get()) Thread.sleep(200);
        System.out.printf(Locale.ROOT,
                "  [check] 总订单=%d（判定成功 %d）| 本地消息表pending=%d%n",
                orderMapper.countAll(), writeOk.get(), localMessageMapper.countPending());
    }

    interface Task { boolean run(long u); }

    void runBurst(int threads, long users, Task task) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong ids = new AtomicLong(1);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    long u;
                    while ((u = ids.getAndIncrement()) <= users) task.run(u);
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        latch.await();
    }
}
