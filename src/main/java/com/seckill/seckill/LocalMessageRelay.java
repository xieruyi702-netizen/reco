package com.seckill.seckill;

import com.seckill.mapper.LocalMessageMapper;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 本地消息表中继：定时扫描发送失败/未确认的消息重投 Kafka（最多重试 10 次），
 * 投递成功标记 SENT。配合消费端幂等（INSERT IGNORE + 唯一索引）实现最终一致性。
 */
@Component
public class LocalMessageRelay {

    private final LocalMessageMapper mapper;
    private final Producer<Long, String> producer;
    private final String topic;

    public LocalMessageRelay(LocalMessageMapper mapper, Producer<Long, String> producer,
                             @Value("${seckill.kafka.topic}") String topic) {
        this.mapper = mapper;
        this.producer = producer;
        this.topic = topic;
    }

    @Scheduled(fixedDelay = 1000)
    public void relay() {
        var pending = mapper.selectPending();
        for (var m : pending) {
            String msgId = (String) m.get("msgId");
            String body = (String) m.get("body");
            try {
                producer.send(new ProducerRecord<>(topic, 0L, body)).get(2, TimeUnit.SECONDS);
                mapper.markSent(msgId);
            } catch (Exception e) {
                mapper.incRetry(msgId);
            }
        }
    }
}
