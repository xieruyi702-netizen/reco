# Seckill — 商铺秒杀系统（Spring Boot + Docker 五容器）

1000 家商铺每家一张秒杀券。两条互通链路：商铺详情查询（读）与券秒杀下单（写）。
技术栈：Spring Boot 3 + MyBatis、Redis 一主两从（读写分离）、Kafka、Caffeine、布隆过滤器、本地消息表、令牌桶限流、ZSet 延迟队列。

## Docker 拓扑（docker/docker-compose.yml）

```
├── nginx       8080 入口，轮询负载均衡到 app1/app2（max_fails 摘除 + next_upstream 重试）
├── app1/app2   Spring Boot 双实例（docker profile 用容器服务名连中间件）
├── mysql:8.4   端口 3306，启动自动建库造数（1000 商铺 / 每铺一券库存1000 / 订单表 / 本地消息表）
├── redis-master        6379（写）
├── redis-replica-1/2   6380/6381（读，轮询 + 失败回退主库）
└── kafka       KRaft 单节点 9092（seckill-orders topic 2 分区，两实例分组并行消费）
```

多实例语义：延迟取消扫描经 Redis 分布式锁互斥；消息表中继重复投递由消费端幂等吸收；令牌桶为实例内存态（集群总限流 = 单实例配置 × 实例数）。

```bash
cd docker
docker compose up -d --build     # 五容器全起
mvn package -DskipTests && docker compose build app   # app 用本地 jar 构建更快
```

## 核心设计

- **多级缓存**：L1 Caffeine(5s TTL) → L2 Redis 从库(30min) → MySQL；布隆过滤器本地快照防穿透；SETNX 互斥锁重建 + 空值缓存防击穿
- **秒杀**：令牌桶限流（惰性补充，fail-fast）→ Redis Lua 原子「库存判断 + 一人一单去重 + 扣减」→ Kafka 异步落库
- **最终一致性**：先落本地消息表再发 Kafka（Outbox，无崩溃丢消息窗口），@Scheduled 中继重投（≤10 次）；Kafka 消费端手动 commit + `earliest` 重放，INSERT IGNORE + 唯一索引幂等
- **支付状态机**：订单 0 待支付 → 1 已支付 / 2 超时取消；未支付订单挂 Redis ZSet 延迟队列，到期扫描 CAS 取消 + DB/Redis 双回补库存（Lua 幂等，取消后可重新抢）

## 压测复现（中间件全部容器化，宿主机发起）

### JMeter 端到端压测（走 Nginx → 双实例全链路）

```bash
# 每轮压测前重置状态
curl -X POST "http://localhost:8080/admin/reset?stock=1000"
# 压测后逐券对账（mismatch=0 即通过）
curl "http://localhost:8080/admin/audit?stock=1000"
```

| JMeter 线程组 | 请求 |
|---|---|
| 读 | `GET http://localhost:8080/shop/${__Random(1,1000)}` |
| 写 | `POST http://localhost:8080/voucher/1/seckill?userId=${__Random(1,50000)}` |

### 进程内组件压测（Bench，绕过 HTTP 栈隔离组件能力）

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


## HTTP 全链路压测（2026-09-03，双实例 + Nginx 加固版）

压测路径：HttpBench(宿主机) → Nginx:8080(轮询) → app1/app2 双实例 → 容器化中间件。
读写混合 80/20，64 线程 30s：

| 指标 | 结果 |
|---|---|
| 总 QPS | **5,989**（读 4,783 / 写 1,206） |
| 抢券成功 | 36,201 单，零错误 |
| 对账 | 消费积压排空后 `/admin/audit` **mismatch=0，1000/1000 通过** |

> 口径说明：数字远低于进程内压测（3 万+），因为这是**完整生产形态链路**——HTTP 解析 + Nginx 转发 + Docker NAT + Outbox 先落账（热路径多一次 DB 写）+ Kafka acks=all。这正是"过网络的真实数字"。

### 本轮修复（都可作面试素材）

1. **Nginx 400**：默认 `proxy_set_header` 把上游组名 `seckill_app`（含下划线）作为 Host 传给 Tomcat，被 RFC 7230 校验拒绝 → 显式 `proxy_set_header Host $host`
2. **孤儿订单**：`/admin/reset` 截断订单表后，Kafka 在途/重放消息被重新消费，凭空重建订单（DB 库存 999 + Redis 1000 不一致）→ **代际栅栏**：reset 时写时间戳 epoch，消息体带发送时间，早于 epoch 的消息消费端直接作废
3. **对账口径**：audit 必须只统计生效订单（status 0/1），已取消订单库存已回补
4. **Outbox 同步发送拖垮写 P99**（52ms）：消息已落账后没必要同步等 Kafka ack → 改异步回调 markSent，失败交给中继，P99 降约 40%


## JMeter 标准压测（2026-09-03）

测试计划 `bench/seckill-mixed.jmx`（可复现）：64 线程 / 30s / 80% 读 + 20% 写，走 Nginx 双实例。

```bash
curl -X POST "http://localhost:8080/admin/reset?stock=1000"
cd bench && rm -rf results.jtl report
jmeter -n -t seckill-mixed.jmx -l results.jtl -e -o report   # HTML 报告在 bench/report/
curl "http://localhost:8080/admin/audit?stock=1000"           # 压后对账
```

| 采样器 | 吞吐 | P50 | P99 | MAX |
|---|---|---|---|---|
| GET /shop/{id} | 3,856/s | 11ms | 25ms | 79ms |
| POST /voucher/{id}/seckill | 960/s | 18ms | 44ms | 96ms |
| **合计** | **4,816/s** | | | 零错误 |

对账：`mismatch=0`，1000 张券全部一致。

> 踩坑：JMeter POST 的 Parameters 会放进请求体而非 query string（userId 缺失 400）；采样器 path 中嵌套 `${__LongSum(x,${__counter})}` 不展开导致 URISyntaxException——userId 直接用 `${__counter(FALSE,)}`（全局唯一即满足一人一券）。
> 自研 HttpBench 同场景 5,989/s 略高于 JMeter（4,816/s），差值是 JMeter 采样统计开销。


## 模型简化（2026-09-03）：去商铺，用户 × 券

应用户要求简化业务模型：移除商铺表/商铺缓存链路，核心实体只剩 **用户 × 券**。

- 读：`GET /voucher/{id}/stock` —— 布隆过滤器（本地快照）拦无效券 id，直读 Redis 主库（余量高频变化不做缓存）
- 写：`POST /voucher/{id}/seckill?userId=u` —— 一人一券一次（Lua SISMEMBER + DB 唯一索引双层保证）
- **加库存**：`POST /voucher/{id}/addStock?amount=n` —— 写操作增加券量，DB 与 Redis 同增，加量后此前抢失败的用户可再抢

保留：令牌桶限流、Lua 原子扣减、Outbox 本地消息表、Kafka 异步落库、订单状态机、ZSet 延迟取消、epoch 栅栏、/admin 对账。

### 简化后 JMeter 复测（64 线程 / 30s / 80读20写）

| 采样器 | 吞吐 | P50 | P99 |
|---|---|---|---|
| GET /voucher/{id}/stock | 3,812/s | ~11ms | ~25ms |
| POST /voucher/{id}/seckill | 960/s | 19ms | 53ms |
| **合计** | **4,768/s，零错误** | | |

对账（积压排空后）：`mismatch=0`，1000 张券全部一致。

> 移除多级缓存（Caffeine L1）后读链路少了本地命中层——原 592 万 QPS 的缓存数字随商铺模型一并移除，当前读链路以 Redis 直读为上限。


## 字段升级（2026-09-03）：三段式库存 + 订单证据链 + 投递可观测

### 券表 tb_seckill_voucher

| 字段 | 作用 |
|---|---|
| `available / locked / sold` | **三段式库存**：下单(消费端) available→locked；支付 locked→sold；超时取消 locked→available 回池。不变式「三段之和 = 初始 + 累计加量」任意时刻可审计 |
| `version` | 乐观锁，addStock 等多字段更新 CAS 重试（3 次） |
| `start_time / end_time` | 券活动时间窗，启动加载至内存，抢券/加量入口校验 |
| `updated_at` | ON UPDATE 自动维护，排障时间线 |

### 订单表 tb_voucher_order

| 字段 | 作用 |
|---|---|
| `order_no` | 业务订单号（时间戳<<20 + 进程序列），自增 id 不外露防遍历 |
| `pay_time / cancel_time` | 状态机时间戳证据链：可校验「cancel_time - created_at ≈ 超时阈值」验证延迟队列健康度 |

### 本地消息表 tb_local_message

`sent_at`——Outbox 投递时间可观测，能计算中继投递延迟（实测积压期最长 124s，正常毫秒级）。

### 对账升级（/admin/audit）

从单一等式升级为**分段交叉对账**：① redis.available == db.available ② db.locked == 待支付订单数 ③ db.sold == 已支付订单数 ④ 三段之和 ≥ 初始量。实时可审，不必等延迟取消跑完。

### JMeter 复测（64 线程 / 30s / 80读20写）

**4,526 req/s 零错误**，对账（积压排空后）1000/1000 通过；实测 21,000 单超时取消、`min/max cancel 时延 = 14s/133s`（扫描批 200×2 实例/500ms 的排空速度，即消费积压期的取消延迟，健康期应秒级）。


## 取消扫描批量化优化（2026-09-03）

**瓶颈定位**：逐单取消 = 每单 3 次 DB（cancel/restore/zrem 状态）+ 2 次 Redis 往返，积压 2.7 万单时取消延迟最长 **133s**。

**优化**（`PayService.cancelExpired` 重写）：
1. 一次 `IN` 预查整批订单状态（已支付/已取消的只移出队列）
2. `status=0` 的**一条 UPDATE 批量 CAS 取消**（`(voucher_id,user_id) IN (...)` + `cancel_time=NOW()`）
3. **按券聚合回补**：同券 N 单合并为一条 `locked-N, available+N`
4. Redis **pipeline** 幂等回滚（SREM 成功才 INCR）+ 批量 ZREM
5. 扫描批量 200 → 2000

**效果**（JMeter 同场景回归，4,529 req/s 零错误不变）：

| 指标 | 逐单版 | 批量版 |
|---|---|---|
| 取消延迟 max | 133s | **33s** |
| 取消延迟 min | 14s | 7s |
| 队列排空时间 | ~2min | **~40s** |

> 进一步优化路径：扫描锁当前保证单实例执行，可改为按 voucherId 分片多实例并行；或 ZSet 换时间轮。留作演进项。


## 移除时间窗 + 故障场景问答（2026-09-03）

应用户要求移除券活动时间窗（start_time/end_time 及入口校验），抢券入口回归：令牌桶 → Lua → Outbox → Kafka。

四个故障场景的处理与缺口详见仓库外文档，速答：

| 场景 | 方案 | 缺口 |
|---|---|---|
| Lua 成功、落账失败 | 同步补偿回滚 Redis（幂等 SREM+INCR），用户可重试 | 回滚自身失败无重试表 |
| Lua 内部失败 | 脚本先判后写、无中途失败点（Lua 是原子性非回滚） | AOF 丢秒级数据靠对账暴露 |
| 落账成功、生产失败 | Outbox PENDING + 中继每秒重投 | retry 超限无死信告警 |
| 生产成功、消费失败 | 手动提交 offset 重放 + INSERT IGNORE 幂等 | **缺死信队列，毒消息会卡分区**（下一步最值得补）
