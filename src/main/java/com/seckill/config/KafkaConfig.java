package com.seckill.config;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
public class KafkaConfig {

    @Bean(destroyMethod = "close")
    public Producer<Long, String> kafkaProducer(@Value("${seckill.kafka.bootstrap}") String bootstrap) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("key.serializer", "org.apache.kafka.common.serialization.LongSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("acks", "1");
        p.put("linger.ms", "5");
        p.put("request.timeout.ms", "3000");
        p.put("delivery.timeout.ms", "5000");
        return new KafkaProducer<>(p);
    }
}
