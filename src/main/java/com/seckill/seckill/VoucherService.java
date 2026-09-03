package com.seckill.seckill;

import com.seckill.cache.RedisBloomFilter;
import com.seckill.mapper.VoucherMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * 券余量查询与加库存。
 * - 查余量：布隆过滤器（本地快照）拦无效券 id → 直读 Redis 主库（余量高频变化，不做缓存）
 * - 加库存：DB 与 Redis 双侧同步增加，保持「redis余量 + 生效订单 = 初始 + 累计加量」对账不变式
 */
@Service
public class VoucherService {

    private final JedisPool master;
    private final VoucherMapper voucherMapper;
    private final RedisBloomFilter bloom;

    public VoucherService(@org.springframework.beans.factory.annotation.Qualifier("masterPool") JedisPool master,
                          VoucherMapper voucherMapper,
                          @Value("${seckill.voucher-count}") int voucherCount) {
        this.master = master;
        this.voucherMapper = voucherMapper;
        this.bloom = new RedisBloomFilter(master, master, "bloom:voucher", voucherCount, 0.01);
        for (long id = 1; id <= voucherCount; id++) bloom.add(id);
    }

    /** 表结构由 SchemaInit 就绪后加载（构造期表可能尚未创建） */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onReady() {
        loadWindows();
    }

    /** 券余量；null = 券不存在（布隆拦截） */
    public Long queryStock(long voucherId) {
        if (!bloom.mightContain(voucherId)) return null;
        try (Jedis j = master.getResource()) {
            String s = j.get("seckill:stock:" + voucherId);
            return s == null ? null : Long.parseLong(s);
        }
    }

    /** 加库存（写操作增加券量）：version CAS 乐观锁 + 时间窗校验，DB 与 Redis 同增 */
    public long addStock(long voucherId, int amount) {
        if (!bloom.mightContain(voucherId)) return -1;
        if (!inWindow(voucherId)) return -2; // 未开始/已结束的券不允许加量

        for (int i = 0; i < 3; i++) { // CAS 重试：version 冲突说明并发更新，重读再试
            Long version = voucherMapper.selectVersion(voucherId);
            if (version == null) return -1;
            if (voucherMapper.addStockCas(voucherId, amount, version) > 0) {
                try (Jedis j = master.getResource()) {
                    return j.incrBy("seckill:stock:" + voucherId, amount);
                }
            }
        }
        return -3; // CAS 重试耗尽
    }

    /** 券时间窗（开抢/结束）：启动时全量加载内存，免每请求查库 */
    private final java.util.Map<Long, long[]> windows = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean inWindow(long voucherId) {
        long[] w = windows.get(voucherId);
        if (w == null) return false;
        long now = System.currentTimeMillis();
        return now >= w[0] && now <= w[1];
    }

    public void loadWindows() {
        for (var row : voucherMapper.selectAllWindows()) {
            windows.put(((Number) row.get("voucher_id")).longValue(),
                    new long[]{((Number) row.get("startMs")).longValue(), ((Number) row.get("endMs")).longValue()});
        }
    }
}
