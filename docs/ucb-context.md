# 免费男性延迟匹配 UCB 策略——详细上下文

> 对应简历 bullet：**免费男性延迟匹配 UCB 策略支撑**（负责策略的后端工程化）。
> 代码依据：`user-commlzn-reco` 的 `service/ucb/`、`model/ucb/`、`dao/impl/UcbRedisDaoImpl.java`、`config/UcbDelayConfig.java` 及配套测试类。

---

## 一、业务背景：免费男延迟匹配为什么难

语音匹配的供需天然不平衡：免费男性用户发起匹配后，如果立即从女性候选池里选人推送，接通率很低（候选还在等待早期、可选面窄）。业务上的做法是让免费男**延迟一段时间再匹配**——等得更久，池子里积累的可推候选更多、质量更高。

但"延迟多久"是个两难：延迟太短，候选少、匹配质量差；延迟太长，用户等不及直接退出（流失）。不同付费等级的用户耐心不同（VIP 等得起，纯免费用户等不了），**不存在一个全群体最优的固定延迟**。

这就是一个典型的**多臂老虎机（MAB）问题**：把候选的延迟时长离散成档位（臂），20 / 25 / 30 / 35 / 40 / 45 / 50 秒共 7 个臂（`UcbDelayConfig.delays`），系统在线上持续试错，学出"哪一档延迟在该用户群体上收益最高"。

## 二、策略设计（算法同学定义，后端工程化落地）

### 2.1 用户分桶：按付费等级分段隔离探索

```
VIP / SVIP → HIGH 段
NP（N 月付费）→ MID 段
其他/未付费 → LOW 段        （UcbSegment.fromPaidClass）
```

每段独立维护一套臂统计（Redis Hash 按 `modelVersion + segment` 分桶）。**段间隔离的原因**：HIGH 段用户付费意愿强，任何延迟档位接通都不错，如果和 LOW 段混在同一个统计里，LOW 段的探索噪声会拉混 HIGH 段的均值，导致 HIGH 用户被分配到次优延迟。分段后各段各学各的曲线。

### 2.2 选臂：UCB1 + 冷启动优先

`UcbAlgorithm.select(arms, stats, coldStartCount, alpha)`：

```
1. 冷启动优先：任何臂的尝试次数 < coldStartCount(=3) → 直接选该臂
   （保证每个延迟档位都获得足够的初始曝光，统计才有意义）
2. 否则 UCB1 打分：score = mean(arm) + alpha × sqrt(ln(totalCount) / count(arm))
   → 取分数最高的臂
```

UCB1 的第二项是**探索bonus**——被尝试次数少的臂，bonus 大，会优先被补试。alpha（默认 1.2）调节"利用已知好臂"和"探索未知臂"的比例，经实验参数下发可调。

纯函数设计：`select()` 是无副作用的静态方法，输入候选臂 + 统计快照、输出选中臂——**策略与存储彻底分离**，所以有独立的 `UcbAlgorithmTest` 可以离线单测公式行为（冷启动、平分时的稳定性等）。

### 2.3 reward 定义：用户最终愿意为什么付钱

延迟策略的成败最终由用户行为投票。reward 取**该次匹配最终成交的卡片总价**：

```
reward = ln(totalPrice + 1)          （UcbRewardCalculator.calculate）
 totalPrice = Σ(用户购买卡片的价格表单价)
```

- 用对数压缩量纲：买 39 币和买 13 币的差异被压平到合理比例，避免天价卡主导梯度；
- 特判：10+11 两张卡组合定价 15（业务组合价）；价格表里查不到的卡型打 `hasUnknownCard` 标记（价格表配置缺失的可观测信号）；
- **结算时机是关键**：用户购买发生在匹配之后很久（秒~分钟级），选臂那一刻 reward 是未知的。所以采用"**先挂账、后结算**"：选臂时在 Redis 写一条 pending 记录，支付回调到达时异步结算。

## 三、后端工程化：这套策略能在线上跑起来依赖的机制

### 3.1 结算的原子性：Lua 脚本

`settleLatestPaid(userId, now, earlyPaidSeconds, reward)` 走 `SETTLE_LATEST_SCRIPT` Lua 原子执行，一次往返完成：查用户最新 pending → 判断是否提前支付（`earlyPaidSeconds=20s` 内付款有额外语义）→ 累加进对应臂的统计 Hash → 清 pending。**原子化的原因**：支付回调可能和过期清理任务并发碰同一个 pending 记录，两步式会双结算或漏结算。

### 3.2 脏数据清理：过期 pending 批量结算

用户等了延迟但始终没付费，pending 记录会永久残留——**残留的 pending 会让统计窗口存有假数据**。`settleExpired(now, batchSize=100)` 定时批量清理过期 pending（pendingExpireSeconds=1 小时），保证臂统计只反映真实成交。

### 3.3 配置全量外置（UcbDelayConfig）

modelVersion（统计分桶版本号，改版本 = 统计重置）、臂列表、alpha、coldStartCount、earlyPaidSeconds、pendingExpire、卡片价格表——**全部 Apollo/实验参数下发**。后端不做任何决策，只按配置执行；策略迭代（换 reward 公式、调延迟档位）= 改配置，不动代码。

### 3.4 存储拓扑

独立 Redis DB（`VOICE_MATCH_UCB_REDIS_DB`），与匹配锁、延迟队列的 Redis 物理隔离——UCB 的统计写入频率高（每次匹配都写 pending/结算），隔离避免影响主链路。

## 四、我的职责边界（面试表述）

> "UCB 的公式和 reward 定义是算法同学定的；我负责把它工程化：选臂接口做成纯函数可离线单测（UcbAlgorithmTest 三组用例覆盖冷启动/平局/参数校验），结算链路用 Lua 保证原子、过期 pending 定时批量清理保证统计干净，全部参数收进 Apollo/实验配置支持在线调参与回滚。另外我治理过同模块的配套基础设施——匹配分布式锁和延迟队列的原子消费。"

## 五、一次用户到来的完整决策流程（代码级）

入口：UcbDelayServiceImpl.selectDelayAndCreatePending(recoContext)。

0. 资格检查：免费男 + 实验开关，不满足走默认延迟（UCB 是可选策略不是必经之路）
1. 配置兜底：Apollo 拉臂列表/α/coldStart，先验合法性，非法降级 + 打点 invalid_config
2. 定分段：读付费等级属性 → HIGH/MID/LOW
3. 读统计：HGETALL 该分段臂统计，Redis 失败则降级
4. UCB1 选臂：count<3 冷启动臂优先；否则 均值 + α·sqrt(ln N / count) 取最高
5. 创建 pending 归因凭证：Lua 原子写入（arm/matchTime/modelVersion/segment，1h 过期）
6. 透传决策：ucbArmDelaySeconds 等参数注入请求，下游据此设 ZSet score

收益回流：支付回调 → calculate 算 reward → settleLatestPaid 原子结算；挂断回调 → 通话时长分档（≥60s=1.0，否则 0.5）；均未发生 → 定时批量冲销归 0。
兜底链条：四个失败点（资格/配置/读/写）全部降级默认延迟并分别打点——策略是实验性的，稳定性是无条件的。

---

## 六、追问预案

| 追问 | 应答 |
|---|---|
| 为什么 UCB 不是 ε-greedy | ε-greedy 均匀随机探索，浪费探索预算在明显差的臂上；UCB 的 bonus 项让探索偏向"试得少"的臂，收敛更快；Thompson 采样需要维护 Beta 分布，实现成本高，UCB 用计数+均值就够了 |
| alpha=1.2 怎么定的 | 初始经验值 + 实验参数可调；alpha 越大越保守（多探索），调参看各臂次数分布是否收敛到少数几个臂 |
| reward 为什么取 log | 价格是重尾分布（几十到几百），log 压缩后单笔大额不会主导均值；+1 避免 ln(0) |
| 结算冲突怎么处理 | 支付回调 vs 过期清理并发碰同一 pending——Lua 脚本内先判 pending 状态再结算，原子保证只结一次 |
| 模型版本怎么灰度 | modelVersion 分桶天然支持：新版统计写新桶，实验开关按用户路由到新旧版本，对比后再切流 |
| 统计被清了怎么办 | modelVersion 重置 = 全臂重新冷启动（3 次探索很快补上）；UCB 对统计丢失的自愈能力本身就很强，这是相对其他策略的工程优势 |
