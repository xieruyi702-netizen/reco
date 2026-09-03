# 简历第一项目（Soul 实习）技术点合集

> 组成：① 新提炼——social-feature Common Link 性能优化（来源：`技术/social-feature-CommonLink性能优化.md`，xry 分支 3 个 commit，代码可查）；② 已有——并发治理 / 性能优化 / 延迟队列迭代 / 工具平台。
> 用途：从下面挑选组合成简历"实习经历"的最终 bullet。

---

## 一、新提炼：Common Link 消费与查询性能优化（social-feature）

背景一句话：内部特征调试平台，消费 Common Link 推荐链路 Kafka 消息落库 MySQL，并提供模拟/全链路可视化 HTTP API。针对**消费吞吐、查询延迟、消费可靠性**三类瓶颈做了 12 项优化，不改业务语义。

### 可上简历的点（按含金量排序）

**1. Kafka 消费假批量改真批量 + Hive 回写异步化**
- 改前：每条 Kafka 消息单独 INSERT + 同步逐条发 Hive topic；
- 改后：同一 poll 批内合并 entity 按 500 条/块批量 INSERT；Hive 回写移出消费线程（线程池异步 + 200 条批量 send + linger.ms=5 聚合）；
- 简历句式："优化 Kafka 消费落库链路：单条 INSERT 改批量合并落库（500/块），Hive 回写异步化 + 批量发送，消费吞吐提升数倍"。

**2. 消费可靠性：子批失败不 ack + 运维开关 pause/resume**
- 改前：部分落库失败仍提交 offset（丢数）；运维锁开启时 ack 但不落库（静默丢数）；
- 改后：子批异常向上抛、整批不 ack，消息重投（DuplicateKeyException 视为幂等成功）；运维开关改为 pause 消费容器 + 不 ack，关锁后从未提交 offset 继续——**故障止血不丢数据**；批处理等待超时对齐 max.poll.interval.ms；
- 简历句式："重构 Kafka 消费可靠性：子批失败整批不 ack 重投、幂等键去重，运维开关由'跳过落库'改为 pause/resume 消费容器，消除静默丢数"。

**3. 查询接口 N+1 与阻塞治理**
- 历史列表每页 10 条各查 2 次 DB → 2 次 IN 批量查询 + 内存组装；
- simulate/泛化接口去掉服务端 15s 轮询（最多 30 次无效 DB 查询），Dubbo 调用后立即返回 requestId，数据就绪由前端短轮询加载；
- 可视化计划改由 Kafka 落库事件触发，删除 500ms×30 次的 DB 轮询；
- 双重 JSON 序列化（toJSONString 再 parse）改直接对象转换；
- 简历句式："治理查询接口：N+1 改批量 IN 查询、删除服务端 15s 轮询改事件触发/前端短轮询、消除双重 JSON 序列化，接口 P99 显著下降"。

**4. Hive 异步发送重试**：最多 3 次间隔递增，失败留离线补偿——一句话带过。

### 面试亮点

- "pause/resume vs 跳过落库"的对比：跳过落库 = ack 了但数据没了；pause = offset 不动，lag 上涨是预期现象——**宁可堆积不丢数据**，和 reco 秒杀项目里"手动 commit + earliest 重放"是同一设计哲学；
- "两层 batch 的区分"：Apollo batchSizeThreshold（Kafka 消息批）vs 500 条/块的 SQL chunk——能分清这两层的面试官会高看一眼；
- 每项优化有 commit hash 可指认，是真实做过的地方。

---

## 二、已有技术点（当前简历第一项目内容）

### 2.1 匹配服务并发正确性治理（老系统迁移专项）
- super match 分布式锁：setnx 与 expire 分离 → SET NX EX 原子加锁 + 释放校验锁归属（`LockRedisDaoImpl`）；
- 延迟队列消费改 Lua 原子弹出（ZRANGEBYSCORE + ZREM 单次往返），按 member 删除规避同分误删（`dequeue.lua`）。

### 2.2 在线链路性能优化
- 串行批量 Redis 查询算子 → 分批并行（batchSize 切分 + CompletableFuture + 合并，common-link `RedisRpcServiceImpl` 模式）；
- 插件超时熔断：组内并行插件独立超时（Apollo），慢插件降级不中断链路（`PluginManageImpl`）。

### 2.3 延迟队列链路迭代
- 批量弹出 + 独立线程池并发消费，批量/开关 Apollo 动态下发；
- 付费续次卡延迟主叫策略接 AB 实验参数（`BreakServiceImpl`）；
- 实验分桶信息本地缓存（5 万容量 / 5min TTL），实验平台 RPC 降一个量级（`CacheUtils`）。

### 2.4 策略实验工具平台（social-hive）
- 双线程池分工（跑批 Abort / SQL 队列 CallerRuns）；
- 多线程批量跑 SQL + 异步写飞书（端到端 -89%）；
- 定时任务动态调度（cron 存 MySQL + ThreadPoolTaskScheduler 动态注册/重载 + 防重入 + catch-up）；
- SQL 知识库：文档摘要向量化（GLM 归纳 + RPC 向量服务 + SQLite），Agent 检索 top-k 文档生成 SQL 直查 Hive 验证。

---

## 三、合并后的简历"核心工作"建议版（四块）

- **匹配服务并发正确性治理**（老系统迁移专项）：分布式锁 setnx/expire 分离缺陷修复（SET NX EX 原子化 + 锁归属校验）；延迟队列消费改 Lua 原子弹出，解决多实例重复消费与同分误删；
- **在线链路性能优化**：串行批量 Redis 查询算子分批并行化（CompletableFuture + 独立线程池），插件单插件超时熔断，保障在线接口 P99 与耗时预算；
- **调试平台 Kafka 链路优化**：消费落库单条 INSERT 改 500/块批量合并，Hive 回写异步化 + 批量发送；消费可靠性重构——子批失败整批不 ack 重投、运维开关改 pause/resume 消费容器消除静默丢数；查询接口 N+1 改批量 IN、服务端 15s 轮询改事件触发；
- **策略实验工具平台**：（保持原文——双线程池、动态调度、-89%、SQL 知识库 + Agent）。

> 延迟队列链路迭代三条（动态并发消费/延迟主叫/本地缓存）如版面超限可压缩合并进第一条，优先级低于上面四块。
