package com.seckill.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 一主两从：写走主库；读请求在从库池间轮询，任一从库不可用回退主库。
 * seckill.redis.master / seckill.redis.replicas 均为 host:port 列表（逗号分隔）。
 */
@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "close")
    @Primary
    public JedisPool masterPool(@Value("${seckill.redis.master}") String master) {
        String[] hp = master.split(":");
        return new JedisPool(hp[0], Integer.parseInt(hp[1]));
    }

    @Bean(destroyMethod = "close")
    public ReplicaPools replicaPools(@Value("${seckill.redis.replicas}") String replicas) {
        List<JedisPool> pools = new ArrayList<>();
        for (String hp : replicas.split(",")) {
            String[] parts = hp.trim().split(":");
            pools.add(new JedisPool(parts[0], Integer.parseInt(parts[1])));
        }
        return new ReplicaPools(pools);
    }

    /** 从库池集合：统一关闭 */
    public static class ReplicaPools implements AutoCloseable {
        public final List<JedisPool> pools;
        public ReplicaPools(List<JedisPool> pools) { this.pools = pools; }
        @Override public void close() { pools.forEach(JedisPool::close); }
    }
}
