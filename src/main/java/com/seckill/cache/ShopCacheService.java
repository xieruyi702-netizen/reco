package com.seckill.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seckill.mapper.ShopMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;

/**
 * 商铺多级缓存查询：
 * L1 Caffeine（本地，TTL 5s，容量 10000）→ L2 Redis（从库读，TTL 30min）→ MySQL。
 * 防护：布隆过滤器（本地快照）拦截不存在 id；互斥锁重建 + 空值缓存防击穿/穿透兜底。
 * 读走从库、写走主库（主从读写分离）；L1 短 TTL 容忍主从/缓存间短暂不一致（展示型数据可接受）。
 */
@Service
public class ShopCacheService {

    private static final String NULL_VALUE = "\u0000NULL";

    private final JedisPool master;
    private final com.seckill.config.RedisConfig.ReplicaPools replicas;
    private final ShopMapper shopMapper;
    private final RedisBloomFilter bloom;
    private final boolean cacheEnabled;
    private final java.util.concurrent.atomic.AtomicInteger rr = new java.util.concurrent.atomic.AtomicInteger();

    /** L1 本地缓存：热 key 不再走网络 */
    private final Cache<Long, String> l1 = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build();

    public ShopCacheService(JedisPool master,
                            @org.springframework.beans.factory.annotation.Qualifier("replicaPools") com.seckill.config.RedisConfig.ReplicaPools replicas,
                            ShopMapper shopMapper,
                            @Value("${seckill.shop-count}") int shopCount,
                            @Value("${seckill.cache-enabled:true}") boolean cacheEnabled) {
        this.master = master;
        this.replicas = replicas;
        this.shopMapper = shopMapper;
        this.cacheEnabled = cacheEnabled;
        this.bloom = new RedisBloomFilter(master, replicas.pools.get(0), "bloom:shop", shopCount, 0.01);
        if (cacheEnabled) {
            for (long id = 1; id <= shopCount; id++) bloom.add(id);
        }
    }

    /** 返回商铺名；null = 不存在 */
    public String queryShop(long id) {
        if (!cacheEnabled) return shopMapper.selectName(id);

        if (!bloom.mightContain(id)) return null; // 穿透防护（本地快照）

        String l1Hit = l1.getIfPresent(id);
        if (l1Hit != null) return NULL_VALUE.equals(l1Hit) ? null : l1Hit;

        // L2：读走从库
        String cached = redisGet("shop:" + id);
        if (cached != null) {
            l1.put(id, cached);
            return NULL_VALUE.equals(cached) ? null : cached;
        }

        // 互斥锁重建（防击穿）：拿不到锁短暂自旋等对方写完
        String lockKey = "lock:shop:" + id;
        for (int i = 0; i < 50; i++) {
            try (Jedis j = master.getResource()) {
                if ("OK".equals(j.set(lockKey, "1", SetParams.setParams().nx().px(3000)))) {
                    try {
                        String name = shopMapper.selectName(id);
                        if (name != null) j.setex("shop:" + id, 1800, name);
                        else j.setex("shop:" + id, 60, NULL_VALUE); // 空值缓存
                        l1.put(id, name == null ? NULL_VALUE : name);
                        return name;
                    } finally {
                        j.del(lockKey);
                    }
                }
            }
            cached = redisGet("shop:" + id);
            if (cached != null) {
                l1.put(id, cached);
                return NULL_VALUE.equals(cached) ? null : cached;
            }
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return shopMapper.selectName(id);
    }

    /** 从库轮询读，失败回退主库 */
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

    /** 商铺详情：商铺名 + 本店券实时余量（读主库库存 key，秒杀进行中余量高频变化） */
    public String queryShopDetail(long shopId) {
        String name = queryShop(shopId);
        if (name == null) return null;
        try (Jedis j = master.getResource()) {
            String stock = j.get("seckill:stock:" + shopId);
            return name + " | 券余量: " + (stock == null ? "无活动" : stock);
        }
    }
}
