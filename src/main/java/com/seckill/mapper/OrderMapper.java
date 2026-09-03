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

    @Insert("INSERT IGNORE INTO tb_voucher_order(order_no, voucher_id, user_id, status) " +
            "VALUES(#{orderNo}, #{voucherId}, #{userId}, 0)")
    int insertUnpaid(@Param("orderNo") long orderNo, @Param("voucherId") long voucherId, @Param("userId") long userId);

    @Select("SELECT status FROM tb_voucher_order WHERE voucher_id = #{voucherId} AND user_id = #{userId}")
    Integer selectStatus(@Param("voucherId") long voucherId, @Param("userId") long userId);

    /** 支付：仅待支付状态可流转，CAS 语义，留痕 pay_time */
    @Update("UPDATE tb_voucher_order SET status = 1, pay_time = NOW() " +
            "WHERE voucher_id = #{voucherId} AND user_id = #{userId} AND status = 0")
    int pay(@Param("voucherId") long voucherId, @Param("userId") long userId);

    /** 超时取消：仅待支付状态可流转，留痕 cancel_time（对账可校验 cancel_time-created_at≈超时阈值） */
    @Update("UPDATE tb_voucher_order SET status = 2, cancel_time = NOW() " +
            "WHERE voucher_id = #{voucherId} AND user_id = #{userId} AND status = 0")
    int cancel(@Param("voucherId") long voucherId, @Param("userId") long userId);

    /** 批量状态预查（取消扫描用）：返回 (voucherId,userId)->status */
    @Select("<script>SELECT voucher_id, user_id, status FROM tb_voucher_order WHERE " +
            "(voucher_id, user_id) IN " +
            "<foreach collection='keys' item='k' open='(' separator=',' close=')'>(#{k[0]},#{k[1]})</foreach>" +
            "</script>")
    java.util.List<Map<String, Object>> selectStatuses(@Param("keys") java.util.List<long[]> keys);

    /** 批量 CAS 取消：仅待支付状态流转，回填 cancel_time */
    @Update("<script>UPDATE tb_voucher_order SET status = 2, cancel_time = NOW() WHERE status = 0 AND " +
            "(voucher_id, user_id) IN " +
            "<foreach collection='keys' item='k' open='(' separator=',' close=')'>(#{k[0]},#{k[1]})</foreach>" +
            "</script>")
    int cancelBatch(@Param("keys") java.util.List<long[]> keys);

    @Select("SELECT COUNT(*) FROM tb_voucher_order")
    long countAll();

    @Select("SELECT COUNT(*) FROM tb_voucher_order WHERE status = #{status}")
    long countByStatus(int status);

    @Select("SELECT status, COUNT(*) AS cnt FROM tb_voucher_order GROUP BY status")
    List<Map<String, Object>> countGroupByStatus();
}
