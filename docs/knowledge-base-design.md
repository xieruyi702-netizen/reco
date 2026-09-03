# SQL 知识库构建方案：向量层与文档层的设计

> 面试支撑材料：对应简历中"构建 SQL 知识库供 Agent 写 SQL"条目。
> 核心思路：**md 文档是事实源（source of truth），向量库只是它的检索索引**。

---

## 一、md 文档怎么存放

### 目录结构（Git 仓库管理，事实源）

```
knowledge-base/
├── tables/                    # 每张表一份文档（一份文档 ≈ 一张表）
│   ├── dws_voice_match_di.md
│   ├── dim_user_profile.md
│   └── ads_experiment_metrics.md
├── metrics/                   # 业务口径文档（一个口径一份）
│   ├── 秒挂率口径.md
│   └── 次均价口径.md
└── sql-recipes/               # 样例 SQL（一类问题一份）
    └── 实验对比查询模板.md
```

### 单份文档格式：YAML frontmatter（元数据）+ Markdown 正文

```markdown
---
id: dws_voice_match_di          # 全局唯一，= 文件名
type: table                     # table | metric | recipe
title: 语音匹配天表
summary: 语音匹配明细天表，一行 = 一次匹配发起，含主被叫用户、卡片类型、
         距离档位、是否付费、接通结果；写 SQL 查匹配量/接通率/秒挂率优先用这张表。
tags: [voice_match, 匹配, 接通率]   # 关键词兜底检索用
updated: 2026-08-28
---

## DDL / 字段说明
| 字段 | 类型 | 说明 |
|---|---|---|
| match_id | bigint | 匹配会话 ID |
| caller_gender | int | 主叫性别 0男1女 |
| ...

## 业务口径
- 秒挂率 = 通话时长 < 5s 的次数 / 接通次数
- 一个 match_id 只有一行成功记录

## 样例 SQL
```sql
select ... from dws_voice_match_di where ds = '${ds}' ...
```
```

**要点**：`summary` 由项目内的 LLM（GLM，备用 DeepSeek）按模板归纳后写回文档 frontmatter 固化——摘要不是向量库里的附属品，而是文档元数据的一部分，**文档与索引天然同源**。

## 二、向量库怎么构建

### 选型（按规模降级，面试要能说出为什么）

| 规模 | 选型 | 理由 |
|---|---|---|
| < 几千份文档（本场景） | **本地持久化：ChromaDB 或 SQLite + numpy 余弦** | 单机工具平台，无需部署独立向量服务；一个 json/sqlite 文件就是全库，随 Git 仓库走 |
| 万级 | FAISS + 元数据存 SQLite | 需要 ANN 索引时才值得 |
| 多服务共享 / 百万级 | Milvus / ES（dense_vector） | 只有分布式检索需求才上 |

### 索引记录结构

```
向量库一条记录 =
  embedding: float[1024]        # summary 文本的向量
  payload:
    id: dws_voice_match_di      # → knowledge-base/tables/dws_voice_match_di.md
    type: table
    title: 语音匹配天表
    doc_path: tables/dws_voice_match_di.md
    summary_hash: sha1(summary) # 增量更新判断
```

### 建库流程（系统上线后自动运行，零人工）

```
触发：应用启动全量对账 + 低频定时增量（10min）+ 管理端手动触发
   ↓
1. 扫描 knowledge-base/**/*.md，解析 frontmatter，计算 summary_hash
2. 与 SQLite 比对：新文档/变更 → 进入归纳；未变 → 跳过；已删除 → 清向量
3. GLM 归纳：按模板提炼 summary（表粒度/关键字段/业务口径/适用问题）
   - GLM 失败自动切 DeepSeek（复用现有 LLM 主备降级）
   - 归纳结果写回 md frontmatter（事实源同步固化）
4. RPC 调内部向量服务：embedding(summary) → upsert SQLite（doc_id 主键幂等）
```

**容错要点**：LLM 和向量服务都是外部依赖——GLM/DeepSeek 都失败则该文档本轮跳过（下轮重试），向量服务不可用则沿用旧索引 + tags 关键词兜底，**构建是旁路任务，任何一环挂掉不阻塞查询主链路、不阻塞系统启动**。摘要质量兜底：模板强约束 + 上线初期抽样人工校验（校准模板，而非长期人工审）。

Embedding 服务：内部统一向量服务（RPC 接口），模型维度由平台决定（如 BGE 系列 1024 维）；**建库与查询必须用同一服务同一版本**，模型升级时全库重嵌。

## 三、在线查询流程（Agent 写 SQL 前）

```
用户需求: "拉最近7天各距离档位的接通率"
   ↓ embedding(需求文本)
向量检索 top-k（k=3~5，余弦相似度）
   ↓ 命中: dws_voice_match_di.md, 秒挂率口径.md, ...
按 doc_path 加载完整 md → 组装 prompt:
   [系统提示: 你是 Hive SQL 专家，只用给出的表]
   [检索到的文档全文]
   [用户需求]
   ↓ LLM 生成 SQL
直查 Soda Coca：先 EXPLAIN 校验字段/表存在，再 LIMIT 试跑
   ↓ 失败 → 错误信息回填 prompt 重试一次；仍失败 → 不采信，交人工
```

**兜底**：检索分数低于阈值时叠加表名关键词匹配（frontmatter 的 tags）；两者都无命中 → 明确告诉用户"知识库无相关表"，拒绝编造。

## 四、面试追问速答

| 追问 | 答案 |
|---|---|
| 为什么向量库存摘要不存全文向量？ | 向量只负责"找对文档"，内容由命中后加载全文承载；文档更新只重嵌摘要，索引重建成本 O(变更文档数) |
| 为什么按文档切而不按 512 字符切块？ | 一份文档≈一张表/一个口径，天然语义完整单元；切块会混口径 |
| 摘要谁写的？ | 项目自己的 LLM（GLM 主备）按固定模板归纳，写回 md frontmatter；模板强约束格式，上线初期抽样人工校准模板，之后全自动 |
| 向量库数据量多大？ | 几百份文档 × 1024 维 float ≈ 1MB 级，单文件即可，无分布式需求 |
| 检索不准怎么办？ | top-k 调大 + tags 关键词兜底 + 试跑验证不过不采信 |
| 怎么防止模型编表名？ | prompt 明示"只用给出的表"；EXPLAIN 先行校验；知识库无命中时明确拒绝 |
