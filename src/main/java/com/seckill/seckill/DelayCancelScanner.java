package com.seckill.seckill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * 超时取消调度：每 500ms 扫一次 ZSet 延迟队列。
 * 多实例部署：SET NX 抢分布式锁，同一时刻只有一个实例扫描，
 * 避免两实例重复拉取同一批到期订单（DB CAS 也能兜住正确性，但会重复回补与扫描）。
 */
@Component
public class DelayCancelScanner {

    private static final String LOCK_KEY = "lock:delay-cancel";

    private final PayService payService;
    private final JedisPool master;

    @Value("${seckill.scan-lock-ms:400}")
    private long lockMs;

    public DelayCancelScanner(PayService payService, JedisPool master) {
        this.payService = payService;
        this.master = master;
    }

    @Scheduled(fixedDelay = 500)
    public void scan() {
        // 锁短于扫描周期：实例崩溃后锁自动过期，另一实例 500ms 内接管
        try (Jedis j = master.getResource()) {
            if (!"OK".equals(j.set(LOCK_KEY, "1", SetParams.setParams().nx().px(lockMs)))) {
                return; // 别的实例正在扫
            }
        }
        payService.cancelExpired(200);
    }
}
