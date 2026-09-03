package com.seckill.controller;

import com.seckill.seckill.SeckillService;
import com.seckill.seckill.VoucherService;
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
    private final VoucherService voucherService;
    private final int voucherCount;

    public AdminController(JdbcTemplate jdbc, SeckillService seckillService, VoucherService voucherService,
                           @Value("${seckill.voucher-count}") int voucherCount) {
        this.jdbc = jdbc;
        this.seckillService = seckillService;
        this.voucherService = voucherService;
        this.voucherCount = voucherCount;
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
        voucherService.loadWindows(); // 重载时间窗缓存
        return Map.of("code", 0, "msg", "reset to stock=" + stock);
    }

    /**
     * 三段式交叉对账：
     *   ① redis available == db available（Redis 与 DB 同步）
     *   ② db locked == 待支付订单数(status=0)（占用一致）
     *   ③ db sold == 已支付订单数(status=1)（售出一致）
     *   ④ available + locked + sold 之和与上轮 audit 差值 == 期间 addStock 总量（防隐性丢失）
     */
    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "1000") int stock) {
        long mismatch = 0;
        Map<String, Object> sample = new LinkedHashMap<>();

        // 每券订单状态计数
        Map<Long, long[]> orderCounts = new HashMap<>(); // [待支付, 已支付]
        jdbc.query("SELECT voucher_id, status, COUNT(*) AS cnt FROM tb_voucher_order GROUP BY voucher_id, status",
                rs -> {
                    long v = rs.getLong(1);
                    int st = rs.getInt(2);
                    long cnt = rs.getLong(3);
                    long[] arr = orderCounts.computeIfAbsent(v, k -> new long[2]);
                    if (st == 0) arr[0] = cnt;
                    if (st == 1) arr[1] = cnt;
                });

        for (var row : jdbc.queryForList("SELECT voucher_id, available, locked, sold FROM tb_seckill_voucher")) {
            long v = ((Number) row.get("voucher_id")).longValue();
            long dbAvail = ((Number) row.get("available")).longValue();
            long dbLocked = ((Number) row.get("locked")).longValue();
            long dbSold = ((Number) row.get("sold")).longValue();
            long redisAvail = seckillService.redisStock(v);
            long[] oc = orderCounts.getOrDefault(v, new long[2]);

            String problem = null;
            if (redisAvail != dbAvail) problem = "redis_available=" + redisAvail + "!=db_available=" + dbAvail;
            else if (dbLocked != oc[0]) problem = "db_locked=" + dbLocked + "!=unpaid_orders=" + oc[0];
            else if (dbSold != oc[1]) problem = "db_sold=" + dbSold + "!=paid_orders=" + oc[1];
            else if (dbAvail + dbLocked + dbSold < stock) problem = "sum=" + (dbAvail + dbLocked + dbSold) + "<" + stock + "(丢失)";
            else if (dbAvail + dbLocked + dbSold > stock) problem = "sum>" + stock + "(疑似有加量,核对addStock)";

            if (problem != null) {
                mismatch++;
                if (sample.size() < 5) sample.put("voucher_" + v, problem);
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
