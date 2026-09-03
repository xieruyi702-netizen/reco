package com.seckill.controller;

import com.seckill.seckill.PayService;
import com.seckill.seckill.SeckillService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/voucher")
public class SeckillController {

    private final SeckillService seckillService;
    private final PayService payService;

    public SeckillController(SeckillService seckillService, PayService payService) {
        this.seckillService = seckillService;
        this.payService = payService;
    }

    /** 秒杀下单（令牌桶限流 + Lua 原子判定 + Kafka 异步落库） */
    @PostMapping("/{voucherId}/seckill")
    public Map<String, Object> seckill(@PathVariable long voucherId, long userId) {
        SeckillService.Result r = seckillService.seckill(voucherId, userId);
        return Map.of("code", r.ordinal(), "msg", r.name());
    }

    /** 支付订单 */
    @PostMapping("/order/{voucherId}/{userId}/pay")
    public Map<String, Object> pay(@PathVariable long voucherId, @PathVariable long userId) {
        boolean ok = payService.pay(voucherId, userId);
        return Map.of("code", ok ? 0 : 1, "msg", ok ? "paid" : "order not payable");
    }
}
