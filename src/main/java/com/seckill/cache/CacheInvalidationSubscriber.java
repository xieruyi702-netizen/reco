package com.seckill.cache;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import org.springframework.stereotype.Component;

/**
 * L1 失效广播订阅：每个实例持有一条专用 Redis 连接订阅 cache:invalidate，
 * 收到消息立刻踢本地 Caffeine——多实例 L1 的最终一致从"等 5s TTL"提速到"秒级广播"。
 * （pub/sub 至多一次，丢消息时退化为 TTL 兜底，两层配合）
 */
@Component
public class CacheInvalidationSubscriber {

    public CacheInvalidationSubscriber(@org.springframework.beans.factory.annotation.Qualifier("masterPool") JedisPool master,
                                       VoucherCacheService cacheService) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis j = master.getResource()) {
                    j.subscribe(new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            cacheService.evictLocal(Long.parseLong(message));
                        }
                    }, "cache:invalidate");
                } catch (Exception e) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; } // 断线重连
                }
            }
        }, "cache-invalidation-sub");
        t.setDaemon(true);
        t.start();
    }
}
