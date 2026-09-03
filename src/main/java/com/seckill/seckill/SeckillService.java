package com.seckill.seckill;

import com.seckill.mapper.LocalMessageMapper;
import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.VoucherMapper;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.TimeUnit;

/**
 * 优惠券秒杀：令牌桶限流 → Redis Lua 原子判定 → Kafka 异步落库。
 * 最终一致性：Kafka 发送失败时消息落本地消息表，定时中继重投（LocalMessageRelay），消费端幂等。
 */
@Service
public class SeckillService {

    /** Lua：库存>0 且未下过单 → 扣库存 + 记用户 + 返回 1；否则 0。单次往返原子完成。 */
    static final String LUA_SECKILL = """
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
                return 0
            end
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil or stock <= 0 then
                return 0
            end
            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            return 1
            """;

    /** 超时取消回滚：只有去重集合里确有该用户时才回补（幂等，重复调用不会多加库存） */
    static final String LUA_ROLLBACK = """
            if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
                redis.call('INCR', KEYS[1])
                return 1
            end
            return 0
            """;

    private final JedisPool master;
    private final VoucherMapper voucherMapper;
    private final OrderMapper orderMapper;
    private final LocalMessageMapper localMessageMapper;
    private final Producer<Long, String> producer;
    private final String topic;
    public final TokenBucket rateLimiter;

    public SeckillService(@org.springframework.beans.factory.annotation.Qualifier("masterPool") JedisPool master,
                          VoucherMapper voucherMapper,
                          OrderMapper orderMapper,
                          LocalMessageMapper localMessageMapper,
                          Producer<Long, String> producer,
                          @Value("${seckill.kafka.topic}") String topic,
                          @Value("${seckill.rate.capacity}") long capacity,
                          @Value("${seckill.rate.refill-per-sec}") double refillPerSec) {
        this.master = master;
        this.voucherMapper = voucherMapper;
        this.orderMapper = orderMapper;
        this.localMessageMapper = localMessageMapper;
        this.producer = producer;
        this.topic = topic;
        this.rateLimiter = new TokenBucket(capacity, refillPerSec);
    }

    public enum Result { SUCCESS, RATE_LIMITED, SOLD_OUT, DUPLICATED }

    /** 秒杀入口：限流 → Lua 判定 → Kafka（失败落本地消息表） */
    public Result seckill(long voucherId, long userId) {
        if (!rateLimiter.tryAcquire()) return Result.RATE_LIMITED;

        boolean luaOk;
        try (Jedis j = master.getResource()) {
            Object r = j.eval(LUA_SECKILL, 2,
                    "seckill:stock:" + voucherId, "seckill:users:" + voucherId, String.valueOf(userId));
            luaOk = ((Long) r) == 1;
        }
        if (!luaOk) {
            // 库存不足或重复下单（压测口径统一返回 SOLD_OUT，DUPLICATED 需另查集合）
            return Result.SOLD_OUT;
        }

        sendReliably(voucherId, userId);
        return Result.SUCCESS;
    }

    /** 基线：纯 DB 乐观扣减（对比用）。唯一索引冲突视为失败（重复下单）。 */
    public boolean seckillDb(long voucherId, long userId) {
        if (voucherMapper.deductStock(voucherId) == 0) return false;
        try {
            orderMapper.insertUnpaid(voucherId, userId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 可靠投递：同步等 Kafka ack，失败落本地消息表由中继补偿 */
    void sendReliably(long voucherId, long userId) {
        String msgId = voucherId + ":" + userId;
        try {
            producer.send(new ProducerRecord<>(topic, userId, msgId)).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            localMessageMapper.insert(msgId, msgId); // 兜底：最终一致性
        }
    }

    /** 重置 Redis 库存与去重集合 */
    public void resetRedis(long voucherId, int stock) {
        try (Jedis j = master.getResource()) {
            j.set("seckill:stock:" + voucherId, String.valueOf(stock));
            j.del("seckill:users:" + voucherId);
        }
    }

    public long redisStock(long voucherId) {
        try (Jedis j = master.getResource()) {
            String s = j.get("seckill:stock:" + voucherId);
            return s == null ? 0 : Long.parseLong(s);
        }
    }

    /** 超时取消：DB 订单置取消 + Redis 幂等回补库存 */
    public boolean rollback(long voucherId, long userId) {
        try (Jedis j = master.getResource()) {
            Object r = j.eval(LUA_ROLLBACK, 2,
                    "seckill:stock:" + voucherId, "seckill:users:" + voucherId, String.valueOf(userId));
            return ((Long) r) == 1;
        }
    }
}
