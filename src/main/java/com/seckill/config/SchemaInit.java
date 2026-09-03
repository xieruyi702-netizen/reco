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
        // 三段式库存: available 可抢 / locked 待支付占用 / sold 已支付售出, 任意时刻三段之和 = 初始 + 累计加量
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_seckill_voucher (" +
                "voucher_id BIGINT PRIMARY KEY, available INT NOT NULL DEFAULT 0, " +
                "locked INT NOT NULL DEFAULT 0, sold INT NOT NULL DEFAULT 0, " +
                "version BIGINT NOT NULL DEFAULT 0, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.execute("DROP TABLE IF EXISTS tb_shop");
        // 券详情：低频变更数据，挂多级缓存；余量高频变化不缓存（读链路分流）
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_voucher_detail (" +
                "voucher_id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL, description VARCHAR(255))");
        jdbc.batchUpdate("INSERT INTO tb_voucher_detail(voucher_id, name, description) VALUES(?,?,?) " +
                        "ON DUPLICATE KEY UPDATE voucher_id = voucher_id",
                java.util.stream.IntStream.rangeClosed(1, 1000)
                        .mapToObj(i -> new Object[]{(long) i, "优惠券-" + i, "满100减10，全场通用，每券限量1000张"})
                        .toList());
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_voucher_order (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, order_no BIGINT NOT NULL UNIQUE, " +
                "voucher_id BIGINT NOT NULL, user_id BIGINT NOT NULL, " +
                "status TINYINT NOT NULL DEFAULT 0, " +
                "pay_time DATETIME NULL, cancel_time DATETIME NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "UNIQUE KEY uk_voucher_user (voucher_id, user_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS tb_local_message (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY, msg_id VARCHAR(64) NOT NULL UNIQUE, " +
                "body VARCHAR(128) NOT NULL, status TINYINT NOT NULL DEFAULT 0, " +
                "retry INT NOT NULL DEFAULT 0, sent_at DATETIME NULL, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        jdbc.batchUpdate("INSERT INTO tb_seckill_voucher(voucher_id, available) " +
                        "VALUES(?, 1000) ON DUPLICATE KEY UPDATE available = 1000, locked = 0, sold = 0, version = version + 1",
                java.util.stream.IntStream.rangeClosed(1, 1000)
                        .mapToObj(i -> new Object[]{(long) i})
                        .toList());
        jdbc.execute("TRUNCATE tb_voucher_order");
        jdbc.execute("TRUNCATE tb_local_message");
    }
}
