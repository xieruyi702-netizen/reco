package com.seckill.seckill;

import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.VoucherMapper;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
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
    private final KafkaProducer<Long, String> deadProducer;
    private final String dltTopic;
    private static final int MAX_RETRY = 3;

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

        // 死信生产者：毒消息重试耗尽后转运到 {topic}-dlt，放行 offset 不卡分区
        Properties dp = new Properties();
        dp.put("bootstrap.servers", bootstrap);
        dp.put("key.serializer", "org.apache.kafka.common.serialization.LongSerializer");
        dp.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        dp.put("acks", "all");
        this.deadProducer = new KafkaProducer<>(dp);
        this.dltTopic = topic + "-dlt";

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
                handleWithDlt(r.value());
            }
            if (!records.isEmpty()) consumer.commitSync(); // 处理完成才提交，宕机后从上次提交位重放
        }
        consumer.close();
        deadProducer.close();
    }

    /**
     * 死信队列：单条失败原地重试 3 次（多为瞬时故障：DB 抖动/连接池耗尽），
     * 仍失败则投递 {topic}-dlt 并放行 offset——毒消息（格式错误等永久故障）不阻塞分区、不静默丢弃，可回溯可告警。
     */
    void handleWithDlt(String msg) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                handle(msg);
                return;
            } catch (Exception e) {
                if (attempt == MAX_RETRY) {
                    try {
                        deadProducer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(
                                dltTopic, 0L, msg)).get(5, java.util.concurrent.TimeUnit.SECONDS);
                        System.err.println("[DLT] 重试 " + MAX_RETRY + " 次仍失败, 已转运死信主题 " + dltTopic + ": " + msg + " / " + e);
                    } catch (Exception sendFail) {
                        System.err.println("[DLT] 死信投递也失败(需告警人工介入): " + msg + " / " + sendFail);
                    }
                } else {
                    try { Thread.sleep(50L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }

    private final java.util.concurrent.atomic.AtomicLong epochCache = new java.util.concurrent.atomic.AtomicLong(-1);

    /** 业务订单号：时间戳左移 + 进程内序列，不依赖自增 id（不外露防遍历） */
    private final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();

    long nextOrderNo() {
        return (System.currentTimeMillis() << 20) | (seq.incrementAndGet() & 0xFFFFF);
    }
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
        if (orderMapper.insertUnpaid(nextOrderNo(), voucherId, userId) > 0) { // 幂等：重复消息不会二次扣减
            voucherMapper.lockStock(voucherId);                // 三段式：可用 -> 锁定
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
