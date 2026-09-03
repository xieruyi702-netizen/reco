package com.seckill.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VoucherDetailMapper {

    @Select("SELECT CONCAT(name, ' | ', description) FROM tb_voucher_detail WHERE voucher_id = #{voucherId}")
    String selectDetail(long voucherId);

    @org.apache.ibatis.annotations.Update("UPDATE tb_voucher_detail SET name = #{name}, description = #{description} " +
            "WHERE voucher_id = #{voucherId}")
    int updateDetail(@org.apache.ibatis.annotations.Param("voucherId") long voucherId,
                     @org.apache.ibatis.annotations.Param("name") String name,
                     @org.apache.ibatis.annotations.Param("description") String description);
}
