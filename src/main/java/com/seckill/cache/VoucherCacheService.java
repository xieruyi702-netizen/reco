package com.seckill.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seckill.mapper.VoucherDetailMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 券详情多级缓存（低频变更数据的正确缓存对象）：
 * L1 Caffeine（本地, TTL 5s, 容量 10000）→ L2 Redis 从库（TTL 30min, 读轮询从库/失败回退主库）
 * → MySQL。防护：布隆过滤器（本地快照）防穿透；SETNX 互斥锁重建 + 空值缓存防击穿。
 * 注意：券【余量】不走此链路（高频变化，直读主库），一个系统里"该缓存的缓存、不该缓存的直读"。
 */
@Service
public class VoucherCacheService {

    private static final String NULL_VALUE = "\u0000NULL";

    private final JedisPool master;
    private final com.seckill.config.RedisConfig.ReplicaPools replicas;
    private final VoucherDetailMapper detailMapper;
    private final RedisBloomFilter bloom;
    private final AtomicInteger rr = new AtomicInteger();

    private final Cache<Long, String> l1 = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build();

    public VoucherCacheService(@org.springframework.beans.factory.annotation.Qualifier("masterPool") JedisPool master,
                               @org.springframework.beans.factory.annotation.Qualifier("replicaPools") com.seckill.config.RedisConfig.ReplicaPools replicas,
                               VoucherDetailMapper detailMapper,
                               @Value("${seckill.voucher-count}") int voucherCount) {
        this.master = master;
        this.replicas = replicas;
        this.detailMapper = detailMapper;
        this.bloom = new RedisBloomFilter(master, replicas.pools.get(0), "bloom:voucher", voucherCount, 0.01);
        for (long id = 1; id <= voucherCount; id++) bloom.add(id);
    }

    /** 券详情；null = 不存在 */
    public String queryDetail(long voucherId) {
        if (!bloom.mightContain(voucherId)) return null; // 穿透防护（本地快照零网络）

        String l1Hit = l1.getIfPresent(voucherId);      // L1：本地内存
        if (l1Hit != null) return NULL_VALUE.equals(l1Hit) ? null : l1Hit;

        String cached = redisGet("voucher:detail:" + voucherId); // L2：从库轮询读
        if (cached != null) {
            l1.put(voucherId, cached);
            return NULL_VALUE.equals(cached) ? null : cached;
        }

        // 互斥锁重建（防击穿）：抢到锁的查库回写，其他自旋等缓存
        String lockKey = "lock:voucher:" + voucherId;
        for (int i = 0; i < 50; i++) {
            try (Jedis j = master.getResource()) {
                if ("OK".equals(j.set(lockKey, "1", SetParams.setParams().nx().px(3000)))) {
                    try {
                        String detail = detailMapper.selectDetail(voucherId);
                        // TTL 加随机抖动（±10%）：避免同批预热/同时重建的 key 同时过期引发缓存雪崩
                        int ttl = 1800 + ThreadLocalRandom.current().nextInt(-180, 180);
                        if (detail != null) j.setex("voucher:detail:" + voucherId, ttl, detail);
                        else j.setex("voucher:detail:" + voucherId, 60, NULL_VALUE); // 空值缓存 60s（短，刻意不加抖动）
                        l1.put(voucherId, detail == null ? NULL_VALUE : detail);
                        return detail;
                    } finally {
                        j.del(lockKey);
                    }
                }
            }
            cached = redisGet("voucher:detail:" + voucherId);
            if (cached != null) {
                l1.put(voucherId, cached);
                return NULL_VALUE.equals(cached) ? null : cached;
            }
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return detailMapper.selectDetail(voucherId);
    }

    /** 更新详情：先更 DB → 删 L2 → 发布失效广播（所有实例踢 L1，跨实例最终一致秒级收敛） */
    public boolean updateDetail(long voucherId, String name, String description) {
        if (!bloom.mightContain(voucherId)) return false;
        boolean updated = detailMapper.updateDetail(voucherId, name, description) > 0;
        if (updated) {
            try (Jedis j = master.getResource()) {
                j.del("voucher:detail:" + voucherId);                    // 删 L2（Cache Aside）
                j.publish("cache:invalidate", String.valueOf(voucherId)); // 广播踢各实例 L1
            }
            l1.invalidate(voucherId); // 自己的 L1 也踢
        }
        return updated;
    }

    /** 收到其他实例的失效广播时回调 */
    public void evictLocal(long voucherId) {
        l1.invalidate(voucherId);
    }

    private String redisGet(String key) {
        int idx = Math.floorMod(rr.getAndIncrement(), replicas.pools.size());
        try (Jedis j = replicas.pools.get(idx).getResource()) {
            return j.get(key);
        } catch (Exception e) {
            try (Jedis j = master.getResource()) {
                return j.get(key);
            }
        }
    }
}
