package com.seckill.controller;

import com.seckill.seckill.SeckillService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 压测辅助接口（仅演示环境，生产必须鉴权/隔离）：
 * - reset：一轮压测前把库存/订单/消息表/延迟队列恢复初始态
 * - stats：压测后经 HTTP 拿对账数据（JMeter BeanShell/JSR223 断言可直接消费）
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final JdbcTemplate jdbc;
    private final SeckillService seckillService;
    private final int voucherCount;

    public AdminController(JdbcTemplate jdbc, SeckillService seckillService,
                           @Value("${seckill.voucher-count}") int voucherCount) {
        this.jdbc = jdbc;
        this.seckillService = seckillService;
        this.voucherCount = voucherCount;
    }

    /** 恢复初始态：券库存重置、清订单/本地消息表、清延迟队列与 Redis 库存 */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "1000") int stock) {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = ?", stock);
        jdbc.execute("TRUNCATE tb_voucher_order");
        jdbc.execute("TRUNCATE tb_local_message");
        for (long v = 1; v <= voucherCount; v++) {
            seckillService.resetRedis(v, stock);
        }
        seckillService.clearUnpaidQueue();
        seckillService.nextEpoch(); // 代际栅栏：reset 后旧 Kafka 消息（重放/在途）一律作废，防孤儿订单
        return Map.of("code", 0, "msg", "reset to stock=" + stock);
    }

    /** 对账数据：逐券校验 redis余量 + 生效订单数 = 初始库存（JMeter 压测后断言用） */
    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "1000") int stock) {
        long mismatch = 0;
        Map<String, Object> sample = new LinkedHashMap<>();
        for (long v = 1; v <= voucherCount; v++) {
            long redisStock = seckillService.redisStock(v);
            Integer orders = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ? AND status IN (0,1)", Integer.class, v); // 仅生效订单(待支付/已支付)，已取消的库存已回补
            long effective = orders == null ? 0 : orders;
            if (redisStock + effective != stock) {
                mismatch++;
                if (sample.size() < 5) sample.put("voucher_" + v, redisStock + "+" + effective);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", mismatch == 0 ? 0 : 1);
        r.put("total_vouchers", voucherCount);
        r.put("mismatch", mismatch);
        r.put("passed", mismatch == 0);
        r.put("mismatch_samples", sample);
        return r;
    }
}
