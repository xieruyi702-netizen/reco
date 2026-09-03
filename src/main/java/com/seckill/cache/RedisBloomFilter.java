package com.seckill.cache;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.BitSet;

/**
 * Redis Bitmap 布隆过滤器（Murmur3 finalizer + 双哈希），查询走本地快照（零网络往返）。
 * 快照从从库加载（读写分离），写入走主库 pipeline。
 */
public class RedisBloomFilter {

    private final JedisPool master;
    private final JedisPool replica;
    private final String name;
    private final long bits;
    private final int hashes;
    private final BitSet localSnapshot;

    public RedisBloomFilter(JedisPool master, @org.springframework.beans.factory.annotation.Qualifier("replicaPool") JedisPool replica, String name,
                            long expectedInsertions, double fpp) {
        this.master = master;
        this.replica = replica;
        this.name = name;
        this.bits = optimalBits(expectedInsertions, fpp);
        this.hashes = optimalHashes(bits, expectedInsertions);
        try (Jedis j = replica.getResource()) {
            byte[] raw = j.get(name.getBytes());
            this.localSnapshot = fromBytes(raw == null ? new byte[0] : raw);
        }
    }

    public void add(long key) {
        long h = hash(key);
        long h1 = h, h2 = (h >>> 32) | 1;
        try (Jedis j = master.getResource()) {
            var pipe = j.pipelined();
            for (int i = 0; i < hashes; i++) {
                pipe.setbit(name, Math.floorMod(h1 + (long) i * h2, bits), true);
            }
            pipe.sync();
        }
        for (int i = 0; i < hashes; i++) {
            localSnapshot.set((int) Math.floorMod(h1 + (long) i * h2, bits));
        }
    }

    /** 本地快照直接查内存：零网络往返 */
    public boolean mightContain(long key) {
        long h = hash(key);
        long h1 = h, h2 = (h >>> 32) | 1;
        for (int i = 0; i < hashes; i++) {
            if (!localSnapshot.get((int) Math.floorMod(h1 + (long) i * h2, bits))) return false;
        }
        return true;
    }

    private static long hash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        return key;
    }

    private static long optimalBits(long n, double p) {
        return Math.max(64, (long) (-n * Math.log(p) / (Math.log(2) * Math.log(2))));
    }

    private static int optimalHashes(long m, long n) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    private static BitSet fromBytes(byte[] raw) {
        BitSet set = new BitSet();
        for (int b = 0; b < raw.length; b++) {
            for (int bit = 0; bit < 8; bit++) {
                if ((raw[b] & (1 << bit)) != 0) set.set(b * 8 + bit);
            }
        }
        return set;
    }
}
