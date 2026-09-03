package com.seckill.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 启动时建表 + 造数（幂等）：1000 商铺、每铺一券（库存 1000）、订单表、本地消息表。 */
@Component
public class SchemaInit {

    private final JdbcTemplate jdbc;

    public SchemaInit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_seckill_voucher (" +
                "voucher_id BIGINT PRIMARY KEY, stock INT NOT NULL)");
        jdbc.execute("DROP TABLE IF EXISTS tb_shop");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_voucher_order (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, voucher_id BIGINT NOT NULL, " +
                "user_id BIGINT NOT NULL, status TINYINT NOT NULL DEFAULT 0, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "UNIQUE KEY uk_voucher_user (voucher_id, user_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_local_message (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, msg_id VARCHAR(64) NOT NULL UNIQUE, " +
                "body VARCHAR(128) NOT NULL, status TINYINT NOT NULL DEFAULT 0, " +
                "retry INT NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        jdbc.batchUpdate("INSERT INTO tb_seckill_voucher(voucher_id, stock) VALUES(?,1000) " +
                        "ON DUPLICATE KEY UPDATE stock = 1000",
                java.util.stream.IntStream.rangeClosed(1, 1000)
                        .mapToObj(i -> new Object[]{(long) i})
                        .toList());
        jdbc.execute("TRUNCATE tb_voucher_order");
        jdbc.execute("TRUNCATE tb_local_message");
    }
}
