package com.seckill.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VoucherDetailMapper {

    @Select("SELECT CONCAT(name, ' | ', description) FROM tb_voucher_detail WHERE voucher_id = #{voucherId}")
    String selectDetail(long voucherId);
}
