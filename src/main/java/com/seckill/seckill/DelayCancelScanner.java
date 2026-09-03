package com.seckill.seckill;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 超时取消调度：每 500ms 扫一次 ZSet 延迟队列。 */
@Component
public class DelayCancelScanner {

    private final PayService payService;

    public DelayCancelScanner(PayService payService) {
        this.payService = payService;
    }

    @Scheduled(fixedDelay = 500)
    public void scan() {
        payService.cancelExpired(200);
    }
}
