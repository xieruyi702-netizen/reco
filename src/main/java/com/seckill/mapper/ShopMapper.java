package com.seckill.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ShopMapper {

    @Select("SELECT name FROM tb_shop WHERE id = #{id}")
    String selectName(long id);
}
