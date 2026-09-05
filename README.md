# AgentX —— 企业知识 Multi-Agent：自研 ReAct / 阶段式 / Plan-and-Execute 三架构受控评测

面向企业知识路由场景的 Agent 系统与评测工程。基于北大 **WorkSurface-Bench**
（arXiv:2607.25765，1151 任务：文档检索 / DuckDB 表格 / 依赖图谱 / 跨源混合，确定性评分）
实现三种 agent 架构并与论文官方 6 种基线设置（S1–S6）受控对跑。

> 前身项目为高并发秒杀系统（Java + Redis + Kafka，Docker 六容器，JMeter 4.8k req/s），
> 本仓库重构后聚焦 Agent；历史代码见 git 提交记录。

## 三架构与定位

| 架构 | LLM 触达方式 | 定位 | 实测（flash，20 任务） |
|---|---|---|---|
| S4 ReAct（基线） | 每步交错"思考→行动→观察" | 精度优先 | agg 0.695 / answer 0.65 / 4211 tok |
| S7 阶段式 | 拆解→分段限面 ReAct→摘要接力 | 介于两者 | agg 0.645 / answer 0.55 / 4093 tok |
| **S7 Plan-and-Execute** | 计划一次→无 LLM 确定性执行→汇总 | **效率优先** | agg 0.602 / answer 0.40 / **1173 tok** |

核心发现：
- **完美路由损害答案**：官方 S5/S6 拿金标路由 answer 仅 0.25/0.20（S4=0.65）——强模型下
  限定/提示 surface 抑制探索；S7-PE 以自规划路由（0.82）answer 超过金标路由设置
- **全量复现追平 Pro 级**：flash 模型 S4 全量 1151 任务，route_f1 76.9 / evidence 76.3，
  与论文 DeepSeek-V4-Pro（79.1 / 77.2）差距 <3 分；差距集中在 answer（54.2 vs 67.9），
  指向 Verify-Act 合成增强方向
- **两个带归因的负结果**：均匀分阶段致跨源任务 40%→0%（摘要瓶颈，已迭代条件分阶段）；
  合成端"聪明重排"致 answer 0.40→0.25（已回退简单物化）

## 结构

```
agent/
├── agentx/            # AgentX 框架
│   ├── agentx_runner.py   # S7 阶段式 / Plan-and-Execute runner + 评测 CLI（分层抽样、双拆分协议）
│   └── plan_execute.py    # Plan-and-Execute（Planner→无 LLM 确定性执行→一次 repair→Synthesizer）
├── agents.py          # 多 agent 编排（supervisor + 查询/补货/巡检 专员）
├── tools.py           # 工具层（REST 封装）
├── main.py            # CLI 入口
├── wsb_data/          # WorkSurface-Bench 数据（gitignored，HF 下载）
├── runs_full/         # 全量 1151 任务轨迹 + 论文同款 table3/table4
├── runs_official|pro|final|dev  # 各轮评测产物
└── docs/              # 设计文档与历史归档
```

## 复现

```bash
uv venv --python 3.12 venv312
uv pip install --python venv312/bin/python -e /path/to/WorkSurface-Bench \
  langchain langchain-openai langgraph requests python-dotenv huggingface_hub
# .env: GLM_API_KEY / GLM_MODEL / DEEPSEEK_API_KEY / DEEPSEEK_MODEL
python -c "from huggingface_hub import snapshot_download; snapshot_download(\
  'lhpku20010120/WorkSurface-Bench', repo_type='dataset', local_dir='wsb_data', \
  allow_patterns=['data/tasks.jsonl','resources/**'])"
# 全量 S4
set -a; . .env; set +a
export WSB_API_BASE="https://api.deepseek.com" WSB_API_KEY="$DEEPSEEK_API_KEY"
python -m runner.sweep --model deepseek-v4-flash --settings S4 \
  --tasks wsb_data/data/tasks.jsonl --data-root wsb_data/resources \
  --runs-dir runs_full --resume
python -m scoring.score_run --tasks wsb_data/data/tasks.jsonl \
  --traces runs_full/S4_deepseek-v4-flash.jsonl \
  --out runs_full/S4_deepseek-v4-flash.scored.json
```

## 文档

- [agentx/README.md](agentx/README.md) —— 三架构对比、S1–S7 全景、迭代全记录（含负结果）
- [docs/legacy/README-seckill.md](docs/legacy/README-seckill.md) —— 前身秒杀系统归档
