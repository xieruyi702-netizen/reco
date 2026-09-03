package com.seckill.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
public class KafkaConfig {

    /**
     * 显式建 topic：分区数 = 预期 app 实例数（消费者组按分区分配，
     * 自动创建默认 1 分区会导致多实例只有一个能消费）。
     */
    @Bean
    public NewTopic seckillOrdersTopic(@Value("${seckill.kafka.topic}") String topic,
                                       @Value("${seckill.kafka.partitions:2}") int partitions) {
        return new NewTopic(topic, partitions, (short) 1);
    }

    @Bean(destroyMethod = "close")
    public Producer<Long, String> kafkaProducer(@Value("${seckill.kafka.bootstrap}") String bootstrap) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("key.serializer", "org.apache.kafka.common.serialization.LongSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("acks", "all");
        p.put("enable.idempotence", "true");
        p.put("linger.ms", "5");
        p.put("request.timeout.ms", "3000");
        p.put("delivery.timeout.ms", "5000");
        return new KafkaProducer<>(p);
    }
}
