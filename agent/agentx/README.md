# AgentX —— 三架构受控对比：ReAct / 阶段式 / Plan-and-Execute

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

## 迭代全记录（含负结果，DeepSeek-v4-flash 同 20 任务）

| 配置 | aggregate | answer | avg tok | 备注 |
|---|---|---|---|---|
| S4 基线 | 0.695 | 0.65 | 4211 | 单循环全工具 |
| **S7-v4：8 步/阶段 + 条件分阶段** | **0.655** | **0.55** | **4079** | **最终采用** |
| S7-v5：+阶段目标注入内层/战术提示（已回退） | 0.592 | 0.45 | 2832 | 目标注入使内层探索变窄，负优化 |
| S7-v6：v4 + 答案规则化归一化 | 0.645 | 0.55 | 4093 | 与 v4 差异在运行方差内（±1pp） |

消融结论：目标注入是负优化（内层 agent 被阶段 goal 收窄探索面）；答案归一化中性
（保留，对格式变体更稳健）。同配置重复运行方差约 ±1 个百分点（20 任务样本量下）。
最终配置收敛于 v4/v6：**answer 0.55 vs S4 0.65，token -3%，且超过金标路由的 S5/S6**。


## 全量评测：S4 ReAct-all × 1151 任务（官方 sweep，DeepSeek-v4-flash）

官方 `runner.sweep`（并发 100 + `retry_errors` 补跑，最终 0 错误），并生成论文同款
`runs_full/tables/table3_main_results.md`：

| 指标 | 论文 DeepSeek-V4-Pro | 本次 flash 全量 | 差异解读 |
|---|---|---|---|
| route_f1 | 79.1 | **76.9** | 接近——flash 路由能力与 pro 相当 |
| evidence | 77.2 | **76.3** | 几乎一致——证据获取不是短板 |
| answer | 67.9 | 54.2 | **-13.7——合成/精度是 flash 的短板** |
| efficiency | 27.2 | 13.4 | flash 轨迹更长（弱模型多试错） |
| aggregate | 69.4 | **62.4** | 主要被 answer/efficiency 拖累 |

结论：**路由与证据获取能力上 flash ≈ pro（差异 <3 分），全部差距集中在答案合成与
效率**——这为"外挂确定性校验/合成增强"（Verify-Act 方向）提供了量化依据：如果把
answer 从 54 提到 67（pro 水平），flash 配置就能以零模型成本追平论文基线。


## S7 = Plan-and-Execute（2026-09-05）：第三种架构入场

ReAct 是"思考→行动→观察→再思考"的交错循环；S7 改为 **Plan-and-Execute**：
Planner 一次性产出完整工具调用计划（≤6 步，具体到参数）→ 执行器【无 LLM】按序执行
→ Synthesizer 一次汇总。全程仅 2 次 LLM 调用 + 1 次可选 repair 重规划。

| 迭代 | aggregate | answer | evidence | avg tok | 说明 |
|---|---|---|---|---|---|
| PE-v1 盲计划 | 0.375 | 0.24 | 0.10 | 1117 | 规划器猜表名/节点名，证据命中极低 |
| **PE-v2 + 环境快照** | **0.602** | 0.40 | **0.725** | 1173 | 注入真实表清单 + 图谱节点约定 |

### 三架构同任务对比（DeepSeek-v4-flash，20 任务）

| 架构 | aggregate | answer | evidence | avg tok | answer/token |
|---|---|---|---|---|---|
| S4 ReAct-all | 0.695 | 0.65 | 0.72 | 4211 | 1.5e-4 |
| S7 阶段式 | 0.645 | 0.55 | 0.68 | 4093 | 1.3e-4 |
| **S7 Plan-and-Execute** | 0.602 | 0.40 | **0.725** | **1173** | **3.4e-4** |

结论：三种架构构成清晰的**成本-精度权衡谱**。PE 的 evidence 反超 S4（0.725 vs 0.72）
——环境快照让计划直击正确证据面；answer 差距来自盲执行无中间自适应。适用建议：
短时程任务（≤6 步可解）选 PE 省 73% token；长时程/强探索任务选 ReAct；
阶段式介于两者但两头不占优（在本基准上）。

PE 工程要点：环境快照是确定性的（table_list + 图谱节点约定，无 LLM）；repair 重规划
仅在全空/报错时触发一次。

### 合成环节的负结果（v3，已回退）

尝试"最近调用优先预算 + 计数结果权威 + 答案在后两条"的材料重排/截断策略，
answer 0.40→0.25 反向恶化：跨源任务的**决定性证据常在早期步骤**（graph_neighbors
找到文件名，后续才查表），尾部优先的截断把它切掉了。结论：**短时程盲执行任务里，
简单按时间序的物化（v2）优于任何"聪明"的重排启发式**——启发式假设与任务的信息
分布假设冲突时，损失是致命的。最终 PE 配置回退为 v2。
