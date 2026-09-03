package com.seckill.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 订单状态机：0 待支付 / 1 已支付 / 2 超时取消。幂等依赖 (voucher_id, user_id) 唯一索引。 */
@Mapper
public interface OrderMapper {

    @Insert("INSERT IGNORE INTO tb_voucher_order(voucher_id, user_id, status) VALUES(#{voucherId}, #{userId}, 0)")
    int insertUnpaid(@Param("voucherId") long voucherId, @Param("userId") long userId);

    @Select("SELECT status FROM tb_voucher_order WHERE voucher_id = #{voucherId} AND user_id = #{userId}")
    Integer selectStatus(@Param("voucherId") long voucherId, @Param("userId") long userId);

    /** 支付：仅待支付状态可流转，CAS 语义 */
    @Update("UPDATE tb_voucher_order SET status = 1 WHERE voucher_id = #{voucherId} AND user_id = #{userId} AND status = 0")
    int pay(@Param("voucherId") long voucherId, @Param("userId") long userId);

    /** 超时取消：仅待支付状态可流转 */
    @Update("UPDATE tb_voucher_order SET status = 2 WHERE voucher_id = #{voucherId} AND user_id = #{userId} AND status = 0")
    int cancel(@Param("voucherId") long voucherId, @Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM tb_voucher_order")
    long countAll();

    @Select("SELECT COUNT(*) FROM tb_voucher_order WHERE status = #{status}")
    long countByStatus(int status);

    @Select("SELECT status, COUNT(*) AS cnt FROM tb_voucher_order GROUP BY status")
    List<Map<String, Object>> countGroupByStatus();
}
