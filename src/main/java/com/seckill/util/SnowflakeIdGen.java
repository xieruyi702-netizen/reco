package com.seckill.util;

/**
 * 雪花算法分布式 ID（替换"时间戳+进程序列"方案——双实例下后者会撞号）：
 * 64bit = 1 符号位 + 41 时间戳(毫秒,约69年) + 5 datacenterId + 5 workerId + 12 序列(每毫秒4096个)
 * workerId 来自环境变量（compose 中 app1=1 / app2=2），保证多实例不冲突；时钟回拨直接抛异常拒绝发号。
 */
public class SnowflakeIdGen {

    private static final long TWEPOCH = 1735689600000L; // 2025-01-01 起算，延长可用年限
    private static final long WORKER_BITS = 5L, DATACENTER_BITS = 5L, SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER = ~(-1L << WORKER_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long workerId, datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGen(long workerId, long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("workerId must be in [0," + MAX_WORKER + "]: " + workerId);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long nextId() {
        long ts = System.currentTimeMillis();
        if (ts < lastTimestamp) {
            throw new IllegalStateException("Clock moved backwards by " + (lastTimestamp - ts) + "ms, refuse to issue id");
        }
        if (ts == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) { // 当前毫秒 4096 个号用尽，自旋到下一毫秒
                while ((ts = System.currentTimeMillis()) <= lastTimestamp) { /* spin */ }
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = ts;
        return ((ts - TWEPOCH) << (WORKER_BITS + DATACENTER_BITS + SEQUENCE_BITS))
                | (datacenterId << (WORKER_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
    }
}
