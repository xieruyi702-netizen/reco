# Seckill Ops Agent —— 秒杀系统多智能体运维（LangGraph + GLM）

给秒杀系统加一个自然语言运维入口：多 agent 协作完成查询、补货、巡检，工具即真实系统的 REST 接口。

## 架构（LangGraph Supervisor 模式）

```
用户自然语言任务
   │
┌──▼──────────┐   条件边分发    ┌────────────┐
│  supervisor │───────────────▶│ query_agent│ 券余量/详情（只读）
│  总调度 LLM  │◀───────────────└────────────┘
│  结构化路由  │   结果回环（决定继续分派或收工）
│  防环护栏8跳 │   ┌────────────┐
│             │──▶│ ops_agent  │ 补货（写操作，工具内额度护栏）
│             │   └────────────┘
│             │   ┌────────────┐
│             │──▶│patrol_agent│ 订单分布/Outbox积压/死信堆积巡检
└─────────────┘   └────────────┘
   │ FINISH
   ▼
最终总结报告（综合全部专员结论）
```

参考：LangGraph 官方 supervisor 模式（分发-回环）、OpenHands 事件留痕（轨迹可见）。
选 supervisor 而非群聊式（AutoGen）或 SOP 流水线（MetaGPT）：运维编排需要**受控的分发-汇总环**，控制流显式可预测。

## 工具层（真实系统 REST，非 mock）

| 工具 | 调用 | 说明 |
|---|---|---|
| query_stock | GET /voucher/{id}/stock | 余量直读 Redis 主库 |
| query_detail | GET /voucher/{id}/detail | 详情走多级缓存 |
| restock | POST /voucher/{id}/addStock | 写操作；工具内校验额度 1~10000；底层 version CAS |
| patrol_stats | GET /admin/stats | 订单分布/Outbox 待投递/死信堆积（Kafka AdminClient） |

## 运行

```bash
cd agent
python3 -m venv venv && ./venv/bin/pip install -r requirements.txt
# .env: GLM_API_KEY=xxx / GLM_MODEL=glm-4.5-flash（充值后可切 glm-5.3-flash，改一行）
./venv/bin/python main.py "巡检系统状态，然后看看券9余量，低于990就补100"
```

实测输出（真实运行）：

- 巡检专员识别死信堆积 1 条并标记 ⚠️
- 查询专员确认券 9 余量 1000
- 条件补货决策正确：1000 ≥ 990，**不触发**补货（agent 遵守了指令中的条件）

## 已处理的工程问题（面试素材）

1. **supervisor 死循环**：专员已返回结论仍重复分派 → 提示词加"绝不重复分派已完成的子任务" + 图级 8 跳防环护栏（双层防护）
2. **收工不总结**：FINISH 后直接回传专员原话 → report 节点改为 LLM 综合所有结论生成报告
3. **系统代理劫持 localhost**：http_proxy 环境变量导致工具请求超时 → requests 显式 proxies 直连
4. **容器内 Kafka 地址**：AdminClient 硬编码 localhost:9092 在容器内不可达 → 注入 seckill.kafka.bootstrap 配置

## 已知边界

- GLM pub/sub 式审批未做：restock 是写操作，当前靠工具内额度护栏 + 指令约束；生产应加"危险操作人工确认"中断点（LangGraph interrupt）
- 模型 glm-4.5-flash（免费）；glm-5.3-flash 需账户充值后改 GLM_MODEL 一行
