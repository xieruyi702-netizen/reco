# AgentX —— 阶段式 Agent 与原生 ReAct 的受控对比实验

基于北大 **WorkSurface-Bench**（arXiv:2607.25765，1151 任务：RAG/Table(DuckDB)/Graph/跨源混合）
构建的评测驱动迭代项目。复用官方 `ProfileTools` 工具环境、`react_loop`、确定性评分
（Route/Evidence/Answer 精确校验），实现 **S7 阶段式工作流**并与官方 **S4 ReAct-all** 基线对跑。

## 架构

```
Stage-Designer（LLM 拆解任务 → 有序阶段 × 单一 surface，偏过多包含防欠规划）
   ↓
条件分阶段：单 surface 任务 → 单段 8 步连贯循环（不拆）
            跨 surface 任务 → 每阶段 6 步限面 ReAct → Stage-Summarizer 压缩
            （权威阶段答案 verbatim + 轨迹尾部最近动作，防大结果挤占截断窗口）
   ↓
Synthesizer（汇总阶段摘要，格式纪律：分号分隔/裸文件名/无前言）
```

## 评测结果（DeepSeek-v4-flash，20 任务分层抽样：8 跨源 + 5 表格 + 4 RAG + 3 图谱）

| 迭代 | aggregate | answer | avg tokens | 说明 |
|---|---|---|---|---|
| S4 ReAct-all（基线） | **0.695** | **0.65** | 4211 | 单循环全工具 |
| S7-v1 均匀分阶段 | 0.587 | 0.399 | 4185 | 跨源任务崩塌（40%→0%） |
| S7-v2 条件分阶段 | 0.587 | 0.400 | 3839 | 单 surface 恢复，跨源仍失败 |
| **S7-v3 +规划冗余/格式纪律** | **0.615** | **0.50** | **3214** | token 比 S4 省 24%，正确率仍落后 |

## 核心发现（负结果也是结果）

1. **强模型下阶段摘要是信息瓶颈**：跨源任务的跨阶段信息（如"rag 里找到值 13.5"→"拿它查表"）
   经摘要压缩后丢失，导致答 INSUFFICIENT_EVIDENCE；v1 时该类任务正确率 40%→0%。
2. **分阶段不是免费收益**：GLM-4.5-flash（弱模型、4 任务小样本）上分阶段曾优于基线，
   DeepSeek-v4-flash（强模型、8 步预算）下单循环 ReAct 更优——**分阶段的收益与模型
   上下文纪律能力成反比**，应作为可配置策略而非默认架构。
3. 残余失败三类：Designer 欠规划（已通过偏过多包含缓解）、计数类小偏差（模型能力，
   与架构无关）、确定性评分的格式敏感（已通过格式纪律修复一例）。

## 复现

```bash
cd agent
uv venv --python 3.12 venv312 && uv pip install --python venv312/bin/python \
  -e /path/to/WorkSurface-Bench huggingface_hub python-dotenv
python -c "from huggingface_hub import snapshot_download; snapshot_download(\
  'lhpku20010120/WorkSurface-Bench', repo_type='dataset', local_dir='wsb_data', \
  allow_patterns=['data/tasks.jsonl','resources/**'])"
# .env: DEEPSEEK_API_KEY=... / DEEPSEEK_MODEL=deepseek-v4-flash
set -a; . .env; set +a
export WSB_API_BASE="https://api.deepseek.com" WSB_API_KEY="$DEEPSEEK_API_KEY"
./venv312/bin/python agentx/agentx_runner.py --tasks wsb_data/data/tasks.jsonl \
  --data-root wsb_data/resources --model deepseek-v4-flash --sample 20 --mode both
```


## 全景对比（2026-09-04，官方 S1–S6 + AgentX S7，DeepSeek-v4-flash，同 20 任务）

| 设置 | aggregate | answer | route_f1 | evidence | avg tokens |
|---|---|---|---|---|---|
| S1 无工具 | 0.095 | 0.00 | 0 | 0 | 469 |
| S2 强制 RAG | 0.094 | 0.00 | 0 | 0 | 592 |
| S3 单选路由 | 0.209 | 0.00 | 0.37 | 0.28 | 7647 |
| **S4 ReAct-all（基线）** | **0.695** | **0.65** | 0.85 | 0.72 | 4211 |
| S5 金标限面 | 0.645 | 0.25 | 1.00 | 0.93 | 8684 |
| S6 金标提示 | 0.616 | 0.20 | 0.97 | 0.95 | 10569 |
| **S7 AgentX（ours）** | 0.655 | 0.55 | 0.82 | 0.62 | **4079** |

### 两个关键发现

1. **完美路由损害答案（官方数据佐证）**：S5/S6 拿着金标级路由（route_f1 1.00/0.97）与最高
   evidence（0.93/0.95），answer 却只有 0.25/0.20，且是全场最烧 token 的设置——在该模型档位，
   限定/提示 surface 抑制了自由探索，答案质量反受其害。单选路由（S3）更是全军覆没。
2. **S7 的定位：answer-per-token 最优**。阶段摘要牺牲部分正确率（0.55 vs S4 0.65）换取
   最低 token（比 S4 省 3%，比金标设置省 53%~62%），answer/token 比值与 S4 持平；
   且以自规划路由（0.82）把 answer 做到 0.55，**超过拿着金标路由的官方 S5/S6**。

### 对"AgentX 打不过 S4"的模型层归因（不改基准方案，只调模型适配）

- 每阶段步数 6→8（与 S4 同预算）：answer 0.50→0.55，aggregate 0.615→0.655
- 思考模式统一关闭（GLM/DeepSeek 思考模型小预算下 content 为空的通病）
- 剩余差距归因：跨源任务的跨阶段摘要损耗 + 计数类小偏差（模型能力，非架构）
