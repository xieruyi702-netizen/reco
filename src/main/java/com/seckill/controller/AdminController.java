package com.seckill.controller;

import com.seckill.seckill.SeckillService;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压测辅助接口（仅演示环境，生产必须鉴权/隔离）：
 * - reset：一轮压测前把库存/订单/消息表/延迟队列恢复初始态
 * - audit：三段式库存交叉对账（redis↔db available、db locked↔待支付订单、db sold↔已支付订单）
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final JdbcTemplate jdbc;
    private final SeckillService seckillService;
    private final Producer<Long, String> producer;
    @Value("${seckill.kafka.topic}") private String topic;
    private final int voucherCount;

    public AdminController(JdbcTemplate jdbc, SeckillService seckillService,
                           Producer<Long, String> producer,
                           @Value("${seckill.voucher-count}") int voucherCount) {
        this.jdbc = jdbc;
        this.seckillService = seckillService;
        this.producer = producer;
        this.voucherCount = voucherCount;
    }

    /** 演示用：向主题注入一条毒消息（格式非法），验证 DLT 转运与主流不阻塞 */
    @PostMapping("/poison")
    public Map<String, Object> poison() throws Exception {
        producer.send(new ProducerRecord<>(topic, "poison:not-a-number:xxx")).get();
        return Map.of("code", 0, "msg", "poison injected, watch DLT topic " + topic + "-dlt");
    }

    /** 恢复初始态：三段库存重置、清订单/本地消息表、清延迟队列与 Redis 库存 */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "1000") int stock) {
        jdbc.update("UPDATE tb_seckill_voucher SET available = ?, locked = 0, sold = 0, version = version + 1", stock);
        jdbc.execute("TRUNCATE tb_voucher_order");
        jdbc.execute("TRUNCATE tb_local_message");
        for (long v = 1; v <= voucherCount; v++) {
            seckillService.resetRedis(v, stock);
        }
        seckillService.clearUnpaidQueue();
        seckillService.nextEpoch(); // 代际栅栏：reset 后旧 Kafka 消息（重放/在途）一律作废，防孤儿订单
        return Map.of("code", 0, "msg", "reset to stock=" + stock);
    }

}
