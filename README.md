# Seckill — 商铺秒杀系统（Spring Boot + Docker 五容器）

1000 家商铺每家一张秒杀券。两条互通链路：商铺详情查询（读）与券秒杀下单（写）。
技术栈：Spring Boot 3 + MyBatis、Redis 一主两从（读写分离）、Kafka、Caffeine、布隆过滤器、本地消息表、令牌桶限流、ZSet 延迟队列。

## Docker 拓扑（docker/docker-compose.yml）

```
├── app        Spring Boot（宿主机 8080，docker profile 用容器服务名连中间件）
├── mysql:8.4  端口 3306，启动自动建库造数（1000 商铺 / 每铺一券 / 订单表 / 本地消息表）
├── redis-master        6379（写）
├── redis-replica-1/2   6380/6381（读，轮询 + 失败回退主库）
└── kafka       KRaft 单节点 9092
```

```bash
cd docker
docker compose up -d --build     # 五容器全起
mvn package -DskipTests && docker compose build app   # app 用本地 jar 构建更快
```

## 核心设计

- **多级缓存**：L1 Caffeine(5s TTL) → L2 Redis 从库(30min) → MySQL；布隆过滤器本地快照防穿透；SETNX 互斥锁重建 + 空值缓存防击穿
- **秒杀**：令牌桶限流（惰性补充，fail-fast）→ Redis Lua 原子「库存判断 + 一人一单去重 + 扣减」→ Kafka 异步落库
- **最终一致性**：Kafka 发送失败落本地消息表，@Scheduled 中继重投（≤10 次），消费端 INSERT IGNORE + 唯一索引幂等
- **支付状态机**：订单 0 待支付 → 1 已支付 / 2 超时取消；未支付订单挂 Redis ZSet 延迟队列，到期扫描 CAS 取消 + DB/Redis 双回补库存（Lua 幂等，取消后可重新抢）

## 压测复现（中间件全部容器化，宿主机发起）

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp target/classes:$(cat target/cp.txt) com.seckill.SeckillApplication --bench=cache   --threads=8
java -cp target/classes:$(cat target/cp.txt) com.seckill.SeckillApplication --bench=seckill --threads=100 --users=50000
java -cp target/classes:$(cat target/cp.txt) com.seckill.SeckillApplication --bench=mixed   --threads=50 --duration=30
```

## 实测指标（2026-09-02，Apple M4，Docker 容器化中间件，宿主机压测）

### 商铺缓存查询（8 线程 20s，含 10% 恶意 id）

| 方案 | QPS | P99 |
|---|---|---|
| 直连 MySQL（MyBatis，容器 NAT） | 27,661 | 698µs |
| **L1 Caffeine + L2 Redis 从库 + 布隆** | **5,928,530**（L1 命中主导） | **1µs** |

布隆本地快照拦截全部恶意 id，DB 零穿透。（纯 Redis L2 命中约 7 万 QPS 量级，见历史演进）

### 优惠券秒杀（5 万用户抢 1000 库存，100 线程）

| 方案 | 耗时 | 判定吞吐 |
|---|---|---|
| 纯 DB 乐观扣减 | 3,936ms | 1.3 万/s |
| **令牌桶 + Lua + Kafka** | **1,581ms** | **3.2 万/s（+146%）** |

订单状态机验证：600 单已支付 + 400 单 3s 超时自动取消，取消后 Redis 余量 = DB 余量 = 400 精确一致，零超卖、零重复。

### 读写混合（80% 读券余量 + 20% 抢券，50 线程 30s）

总 QPS **30,315**（读 24,236 / 写 6,079），抢券成功 **182,412 单**，全部落库。
一致性校验（逐券）：`redis余量 + 生效订单数 = 1000` 与 `db余量 + 生效订单数 = 1000` **1000/1000 全部通过**，超卖 0，本地消息表 pending=0。

### 令牌桶限流（容量 200 / 补充 2000每秒，50 万突发）

通过 361（= 200 突发 + 灌注窗口内补充），拒绝 499,639，全部 fail-fast 无阻塞。

## 踩坑记录（面试素材）

1. 布隆过滤器逐 bit 查询 7 次网络往返 → pipeline 合并 → 本地快照，三级演进
2. Kafka consumer `seekToEnd` 跳过未消费消息导致订单丢失 → 改依赖 group 已提交 offset 断点续传
3. 异步链路早期只写订单不扣 DB 库存，一致性校验暴露 → 消费者「INSERT IGNORE 成功才扣减」
4. KafkaConsumer 非线程安全；group rebalance 一次性 2-3s 需从链路耗时中剔除
5. Spring 注入两个 JedisPool 需要 @Primary/@Qualifier（无 -parameters 编译时按名注入失效）
