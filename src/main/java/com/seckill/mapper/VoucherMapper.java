package com.seckill.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface VoucherMapper {

    /** 乐观扣库存基线 */
    @Update("UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE voucher_id = #{voucherId} AND stock > 0")
    int deductStock(@Param("voucherId") long voucherId);

    /** 超时取消：回补 DB 库存 */
    @Update("UPDATE tb_seckill_voucher SET stock = stock + 1 WHERE voucher_id = #{voucherId}")
    int restoreStock(@Param("voucherId") long voucherId);

    @Update("UPDATE tb_seckill_voucher SET stock = #{stock} WHERE voucher_id = #{voucherId}")
    int resetStock(@Param("voucherId") long voucherId, @Param("stock") int stock);

    @Update("UPDATE tb_seckill_voucher SET stock = stock + #{amount} WHERE voucher_id = #{voucherId}")
    int addStock(@Param("voucherId") long voucherId, @Param("amount") int amount);

    @Update("UPDATE tb_seckill_voucher SET stock = #{stock}")
    int resetAllStock(@Param("stock") int stock);
}
