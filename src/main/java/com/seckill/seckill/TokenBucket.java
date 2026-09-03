package com.seckill.seckill;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流：容量 capacity，匀速补充 refillPerSec。
 * syncaddToken 计算自上次补充至今应补令牌数（惰性补充，无需后台线程）。
 */
public class TokenBucket {

    private long capacity;
    private double refillPerNano;
    private double tokens;
    private long lastRefillNanos;
    private final AtomicLong passed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public TokenBucket(long capacity, double refillPerSec) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSec / 1e9;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** 尝试获取 1 个令牌；拿不到立即拒绝（fail-fast，不排队） */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            passed.incrementAndGet();
            return true;
        }
        rejected.incrementAndGet();
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double add = (now - lastRefillNanos) * refillPerNano;
        if (add > 0) {
            tokens = Math.min(capacity, tokens + add);
            lastRefillNanos = now;
        }
    }

    public long passed() { return passed.get(); }
    public long rejected() { return rejected.get(); }

    /** 压测用：清零计数 */
    public void resetStats() { passed.set(0); rejected.set(0); }

    /** 压测/运维用：运行时调整桶参数 */
    public synchronized void reconfigure(long newCapacity, double refillPerSec) {
        this.tokens = Math.min(this.tokens, newCapacity);
        this.capacity = newCapacity;
        this.refillPerNano = refillPerSec / 1e9;
    }
}
