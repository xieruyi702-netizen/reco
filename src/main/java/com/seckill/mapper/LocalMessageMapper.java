package com.seckill.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 本地消息表：Kafka 发送失败时落库，定时任务中继重投，保证最终一致性。 */
@Mapper
public interface LocalMessageMapper {

    @Insert("INSERT IGNORE INTO tb_local_message(msg_id, body) VALUES(#{msgId}, #{body})")
    int insert(@Param("msgId") String msgId, @Param("body") String body);

    /** 中继扫描：未确认的消息（含发送失败重试） */
    @Select("SELECT msg_id AS msgId, body FROM tb_local_message WHERE status = 0 AND retry < 10 ORDER BY id LIMIT 500")
    List<Map<String, Object>> selectPending();

    @Update("UPDATE tb_local_message SET status = 1, sent_at = NOW() WHERE msg_id = #{msgId}")
    int markSent(@Param("msgId") String msgId);

    @Update("UPDATE tb_local_message SET retry = retry + 1 WHERE msg_id = #{msgId}")
    int incRetry(@Param("msgId") String msgId);

    @Select("SELECT COUNT(*) FROM tb_local_message WHERE status = 0")
    long countPending();
}
