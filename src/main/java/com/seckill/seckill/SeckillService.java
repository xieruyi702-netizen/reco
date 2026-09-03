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

    public enum Result { SUCCESS, RATE_LIMITED, SOLD_OUT, DUPLICATED, DB_ERROR }

    /** 秒杀入口：限流 → Lua 判定 → 落本地消息表（失败则整体回滚）→ Kafka */
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

        try {
            sendReliably(voucherId, userId);
        } catch (Exception e) {
            // 消息落库失败 = 账没记上，这单不成立：幂等回滚 Redis（库存+1、去重集合移除），用户可重试
            rollback(voucherId, userId);
            return Result.DB_ERROR;
        }
        return Result.SUCCESS;
    }

    private final java.util.concurrent.atomic.AtomicLong dbSeq = new java.util.concurrent.atomic.AtomicLong();

    /** 基线：纯 DB 乐观扣减（对比用）。唯一索引冲突视为失败（重复下单）。 */
    public boolean seckillDb(long voucherId, long userId) {
        if (voucherMapper.deductStock(voucherId) == 0) return false;
        try {
            orderMapper.insertUnpaid((System.currentTimeMillis() << 20) | (dbSeq.incrementAndGet() & 0xFFFFF),
                    voucherId, userId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 可靠投递（Write-Through Outbox）：先落本地消息表（INSERT IGNORE 幂等），再发 Kafka。
     * 先落库保证「Redis 已扣减但进程崩溃」时消息仍在，由中继补投，不存在丢消息窗口；
     * 发送成功即标记 SENT，避免中继重复扫描。
     */
    /** 代际栅栏：记录 reset 时刻，早于该时刻发出的消息作废（防 reset 后重放消息生成孤儿订单） */
    public long nextEpoch() {
        long now = System.currentTimeMillis();
        try (Jedis j = master.getResource()) {
            j.set("seckill:epoch", String.valueOf(now));
        }
        return now;
    }

    void sendReliably(long voucherId, long userId) throws Exception {
        String body = voucherId + ":" + userId + ":" + System.currentTimeMillis();
        String msgId = voucherId + ":" + userId;
        localMessageMapper.insert(msgId, body); // 落账失败向上抛，seckill 会整体回滚 Redis
        // 异步投递：消息已落账，可靠性由中继保证，热路径不等 ack（同步 get 会把 P99 拖到几十 ms）
        producer.send(new ProducerRecord<>(topic, userId, body), (recordMetadata, e) -> {
            if (e == null) {
                try { localMessageMapper.markSent(msgId); }
                catch (Exception ignored) { /* markSent 失败：消息已发，中继重投被消费端幂等吸收 */ }
            }
            // 发送失败：消息保持 PENDING，由 LocalMessageRelay 定时重投
        });
    }

    /** 重置 Redis 库存与去重集合 */
    public void resetRedis(long voucherId, int stock) {
        try (Jedis j = master.getResource()) {
            j.set("seckill:stock:" + voucherId, String.valueOf(stock));
            j.del("seckill:users:" + voucherId);
        }
    }

    /** 清空延迟取消队列（压测重置用） */
    public void clearUnpaidQueue() {
        try (Jedis j = master.getResource()) {
            j.del("orders:unpaid");
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
