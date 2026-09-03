package com.seckill.controller;

import com.seckill.seckill.PayService;
import com.seckill.seckill.SeckillService;
import com.seckill.seckill.VoucherService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 券接口（去商铺模型：用户 × 券）：
 * - GET  /voucher/{id}/stock        查余量（读）
 * - POST /voucher/{id}/seckill      抢券（写，一人一券一次）
 * - POST /voucher/{id}/addStock     加库存（写操作增加券量）
 */
@RestController
@RequestMapping("/voucher")
public class SeckillController {

    private final SeckillService seckillService;
    private final PayService payService;
    private final VoucherService voucherService;

    public SeckillController(SeckillService seckillService, PayService payService,
                             VoucherService voucherService) {
        this.seckillService = seckillService;
        this.payService = payService;
        this.voucherService = voucherService;
    }

    /** 券余量（布隆防穿透 + Redis 主库直读） */
    @GetMapping("/{voucherId}/stock")
    public Map<String, Object> stock(@PathVariable long voucherId) {
        Long stock = voucherService.queryStock(voucherId);
        return Map.of("code", stock == null ? 404 : 0, "data", stock == null ? "voucher not found" : stock);
    }

    /** 秒杀下单（令牌桶限流 + Lua 原子判定 + Kafka 异步落库） */
    @PostMapping("/{voucherId}/seckill")
    public Map<String, Object> seckill(@PathVariable long voucherId, @RequestParam long userId) {
        SeckillService.Result r = seckillService.seckill(voucherId, userId);
        return Map.of("code", r.ordinal(), "msg", r.name());
    }

    /** 加库存 */
    @PostMapping("/{voucherId}/addStock")
    public Map<String, Object> addStock(@PathVariable long voucherId, @RequestParam int amount) {
        long stock = voucherService.addStock(voucherId, amount);
        return Map.of("code", stock < 0 ? 404 : 0, "msg", stock < 0 ? "voucher not found" : "stock=" + stock);
    }

    /** 支付订单 */
    @PostMapping("/order/{voucherId}/{userId}/pay")
    public Map<String, Object> pay(@PathVariable long voucherId, @PathVariable long userId) {
        boolean ok = payService.pay(voucherId, userId);
        return Map.of("code", ok ? 0 : 1, "msg", ok ? "paid" : "order not payable");
    }
}
