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

    /** 券余量；null = 券不存在（布隆拦截） */
    public Long queryStock(long voucherId) {
        if (!bloom.mightContain(voucherId)) return null;
        try (Jedis j = master.getResource()) {
            String s = j.get("seckill:stock:" + voucherId);
            return s == null ? null : Long.parseLong(s);
        }
    }

    /** 加库存（写操作增加券量）：DB 与 Redis 同增，加完后之前抢失败的用户可再抢 */
    public long addStock(long voucherId, int amount) {
        if (!bloom.mightContain(voucherId)) return -1;
        voucherMapper.addStock(voucherId, amount);
        try (Jedis j = master.getResource()) {
            return j.incrBy("seckill:stock:" + voucherId, amount);
        }
    }
}
