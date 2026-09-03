package com.seckill.seckill;

import com.seckill.mapper.OrderMapper;
import com.seckill.mapper.VoucherMapper;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Jedis;

import java.util.List;

/**
 * 支付与超时取消：
 * - pay：订单状态 0(待支付) → 1(已支付)，CAS 更新
 * - 延迟取消：扫描 ZSet orders:unpaid 中到期的订单，未支付则
 *   DB 订单置 2(取消) + DB 库存回补 + Redis Lua 幂等回补（库存 + 去重集合移除）
 */
@Service
public class PayService {

    private final OrderMapper orderMapper;
    private final VoucherMapper voucherMapper;
    private final SeckillService seckillService;
    private final JedisPool master;

    public PayService(OrderMapper orderMapper, VoucherMapper voucherMapper,
                      SeckillService seckillService, @org.springframework.beans.factory.annotation.Qualifier("masterPool") JedisPool master) {
        this.orderMapper = orderMapper;
        this.voucherMapper = voucherMapper;
        this.seckillService = seckillService;
        this.master = master;
    }

    /** 支付：仅待支付状态可流转（CAS）。返回 false = 订单不存在/已支付/已取消 */
    public boolean pay(long voucherId, long userId) {
        boolean ok = orderMapper.pay(voucherId, userId) > 0;
        if (ok) {
            // 支付成功立即移出延迟队列，避免占用扫描批次直到过期才被清掉
            try (Jedis j = master.getResource()) {
                j.zrem("orders:unpaid", voucherId + ":" + userId);
            }
        }
        return ok;
    }

    /** 定时扫描超时订单（每 500ms），批量处理到期未支付订单 */
    // 注：@Scheduled 放在 DelayCancelScanner，这里提供单批处理逻辑
    public int cancelExpired(int batchSize) {
        long now = System.currentTimeMillis();
        List<String> due;
        try (Jedis j = master.getResource()) {
            due = j.zrangeByScore("orders:unpaid", 0, now, 0, batchSize);
        }
        int cancelled = 0;
        for (String member : due) {
            String[] parts = member.split(":");
            long voucherId = Long.parseLong(parts[0]);
            long userId = Long.parseLong(parts[1]);

            // DB CAS 取消（只有待支付能取消；已支付则直接移出延迟队列）
            int updated = orderMapper.cancel(voucherId, userId);
            if (updated > 0) {
                voucherMapper.restoreStock(voucherId);        // DB 库存回补
                seckillService.rollback(voucherId, userId);   // Redis 幂等回补（库存+1、去重集合移除，可重新抢）
                cancelled++;
            }
            try (Jedis j = master.getResource()) {
                j.zrem("orders:unpaid", member);
            }
        }
        return cancelled;
    }

    public long unpaidQueueSize() {
        try (Jedis j = master.getResource()) {
            return j.zcard("orders:unpaid");
        }
    }
}
