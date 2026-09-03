package com.seckill.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 三段式库存（available 可抢 / locked 待支付 / sold 已售出）：
 * - 下单：available-1, locked+1（消费端）
 * - 支付：locked-1, sold+1
 * - 超时取消：locked-1, available+1（库存回池可重抢）
 * 不变式：available + locked + sold = 初始 + 累计加量，任意时刻可审计。
 */
@Mapper
public interface VoucherMapper {

    /** 消费端下单占用：可用转锁定 */
    @Update("UPDATE tb_seckill_voucher SET available = available - 1, locked = locked + 1, version = version + 1 " +
            "WHERE voucher_id = #{voucherId} AND available > 0")
    int lockStock(@Param("voucherId") long voucherId);

    /** 支付：锁定转售出 */
    @Update("UPDATE tb_seckill_voucher SET locked = locked - 1, sold = sold + 1, version = version + 1 " +
            "WHERE voucher_id = #{voucherId}")
    int payConvert(@Param("voucherId") long voucherId);

    /** 超时取消：锁定还回可用（库存回池） */
    @Update("UPDATE tb_seckill_voucher SET locked = locked - 1, available = available + 1, version = version + 1 " +
            "WHERE voucher_id = #{voucherId}")
    int cancelRestore(@Param("voucherId") long voucherId);

    /** 基线：纯 DB 乐观扣减 */
    @Update("UPDATE tb_seckill_voucher SET available = available - 1, locked = locked + 1 " +
            "WHERE voucher_id = #{voucherId} AND available > 0")
    int deductStock(@Param("voucherId") long voucherId);

    /** 取消回池（批量聚合）：locked-N, available+N，一条 SQL 完成一批同券回补 */
    @Update("UPDATE tb_seckill_voucher SET locked = locked - #{n}, available = available + #{n}, version = version + 1 " +
            "WHERE voucher_id = #{voucherId}")
    int restoreBatch(@Param("voucherId") long voucherId, @Param("n") int n);

    /** 加库存：DB 与 Redis 同增；version 乐观锁防与其他多字段更新并发丢失 */
    @Update("UPDATE tb_seckill_voucher SET available = available + #{amount}, version = version + 1 " +
            "WHERE voucher_id = #{voucherId} AND version = #{version}")
    int addStockCas(@Param("voucherId") long voucherId, @Param("amount") int amount, @Param("version") long version);

    @Select("SELECT version FROM tb_seckill_voucher WHERE voucher_id = #{voucherId}")
    Long selectVersion(@Param("voucherId") long voucherId);

    @Update("UPDATE tb_seckill_voucher SET available = #{stock}, locked = 0, sold = 0, version = version + 1")
    int resetAllStock(@Param("stock") int stock);

    /** audit 用：分段快照 */
    @Select("SELECT voucher_id, available, locked, sold FROM tb_seckill_voucher")
    List<Map<String, Object>> selectAllSegments();
}
