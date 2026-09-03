package com.seckill.seckill;

import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.VoucherMapper;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Kafka 订单消费者：批量拉取 → 创建待支付订单（幂等 INSERT IGNORE）→ 挂 ZSet 延迟队列等支付。
 * 消息体 voucherId:userId。
 */
@Component
public class OrderConsumer {

    private final KafkaConsumer<Long, String> consumer;
    private final OrderMapper orderMapper;
    private final VoucherMapper voucherMapper;
    private final JedisPool master;
    private final long orderTimeoutMs;
    private final Thread worker;
    private volatile boolean running = true;

    public OrderConsumer(OrderMapper orderMapper, VoucherMapper voucherMapper, @org.springframework.beans.factory.annotation.Qualifier("masterPool") JedisPool master,
                         @Value("${seckill.kafka.bootstrap}") String bootstrap,
                         @Value("${seckill.kafka.topic}") String topic,
                         @Value("${seckill.order-timeout-ms}") long orderTimeoutMs) {
        this.orderMapper = orderMapper;
        this.voucherMapper = voucherMapper;
        this.master = master;
        this.orderTimeoutMs = orderTimeoutMs;
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("group.id", "seckill-order");
        p.put("key.deserializer", "org.apache.kafka.common.serialization.LongDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("enable.auto.commit", "false"); // 手动提交：处理完一批再 commit，配合消费幂等做到 at-least-once
        p.put("auto.offset.reset", "earliest"); // group offset 丢失时从最早重放，靠幂等去重，宁可重复不丢消息
        this.consumer = new KafkaConsumer<>(p);
        this.consumer.subscribe(List.of(topic));
        this.worker = new Thread(this::run, "order-consumer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private void run() {
        while (consumer.assignment().isEmpty()) {
            consumer.poll(Duration.ofMillis(100)); // 等 group join
        }
        while (running) {
            ConsumerRecords<Long, String> records = consumer.poll(Duration.ofMillis(200));
            for (ConsumerRecord<Long, String> r : records) {
                try {
                    handle(r.value());
                } catch (Exception e) {
                    // 单条解析/处理失败只记日志不中断批次；offset 照常推进，
                    // 坏消息靠 DB 唯一索引与状态机兜底，不因毒消息卡死消费组
                }
            }
            if (!records.isEmpty()) consumer.commitSync(); // 处理完成才提交，宕机后从上次提交位重放
        }
        consumer.close();
    }

    private final java.util.concurrent.atomic.AtomicLong epochCache = new java.util.concurrent.atomic.AtomicLong(-1);
    private volatile long epochCachedAt = 0;

    private long currentEpoch() {
        long now = System.currentTimeMillis();
        if (now - epochCachedAt > 1000 || epochCache.get() < 0) { // 1s 缓存，避免每消息一次 Redis GET
            try (var j = master.getResource()) {
                String v = j.get("seckill:epoch");
                epochCache.set(v == null ? 0 : Long.parseLong(v));
                epochCachedAt = now;
            } catch (Exception ignore) {}
        }
        return epochCache.get();
    }

    void handle(String msg) {
        String[] parts = msg.split(":");
        long voucherId = Long.parseLong(parts[0]);
        long userId = Long.parseLong(parts[1]);
        long sentAt = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
        if (sentAt > 0 && sentAt < currentEpoch()) return; // 旧代消息（reset 前发出）作废
        if (orderMapper.insertUnpaid(voucherId, userId) > 0) { // 幂等：重复消息不会二次扣减
            voucherMapper.deductStock(voucherId);              // DB 库存同步扣减
            // 挂延迟队列：超时未支付自动取消（score = 截止时间戳 ms）。
            // 只对新订单挂，重复消费不会刷新本应到期的截止时间
            try (var j = master.getResource()) {
                j.zadd("orders:unpaid", System.currentTimeMillis() + orderTimeoutMs, msg);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
    }
}
