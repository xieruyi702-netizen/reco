package com.seckill.controller;

import com.seckill.seckill.SeckillService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
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
import java.util.List;
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
    @Value("${seckill.kafka.bootstrap}") private String kafkaBootstrap;
    private final int voucherCount;

    public AdminController(JdbcTemplate jdbc, SeckillService seckillService,
                           Producer<Long, String> producer,
                           @Value("${seckill.voucher-count}") int voucherCount) {
        this.jdbc = jdbc;
        this.seckillService = seckillService;
        this.producer = producer;
        this.voucherCount = voucherCount;
    }

    /** 巡检数据源（Agent patrol 工具用）：订单分布 / 待投递消息 / DLT 死信堆积 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> byStatus = jdbc.queryForList(
                "SELECT status, COUNT(*) AS cnt FROM tb_voucher_order GROUP BY status");
        long unpaid = 0, paid = 0, canceled = 0;
        for (var row : byStatus) {
            int st = ((Number) row.get("status")).intValue();
            long cnt = ((Number) row.get("cnt")).longValue();
            if (st == 0) unpaid = cnt; else if (st == 1) paid = cnt; else canceled = cnt;
        }
        r.put("orders", Map.of("unpaid", unpaid, "paid", paid, "canceled", canceled));
        r.put("outbox_pending", jdbc.queryForObject("SELECT COUNT(*) FROM tb_local_message WHERE status = 0", Long.class));

        long dltLag = -1;
        try (AdminClient admin = AdminClient.create(
                Map.of("bootstrap.servers", kafkaBootstrap))) {
            var tp = new TopicPartition("seckill-orders-dlt", 0);
            ListOffsetsResult.ListOffsetsResultInfo info = admin.listOffsets(Map.of(tp, OffsetSpec.latest()))
                    .all().get(3, java.util.concurrent.TimeUnit.SECONDS).get(tp);
            dltLag = info.offset(); // 单分区死信主题：latest offset 即历史死信总量
        } catch (Exception ignored) {}
        r.put("dlt_total", dltLag);
        return r;
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
