"""AgentX：阶段式 Agent 工作流（S7），对标官方 S4 ReAct-all 基线。

三段式架构（对比原生 ReAct 的单循环全工具）：
  1. Stage-Designer   一次 LLM 调用，把任务拆解为有序阶段，每阶段绑定单一
                      knowledge surface（rag/table/graph）+ 阶段目标
  2. 阶段执行         每阶段独立 ReAct 循环，仅暴露该阶段 surface 的工具，
                      步数上限 4（上下文天然裁剪）
  3. Stage-Summarizer 每阶段结束把轨迹压缩为结构化摘要（事实+实体引用），
                      阶段间只传摘要不传完整轨迹 —— token 优化的核心
  4. Synthesizer      汇总全部阶段摘要生成最终答案（不再调工具）

评分复用官方 scoring（Route/Evidence/Answer 确定性校验），token 计数复用
backbone.cum_usage，与 S4 基线口径完全一致。

用法：
  python agentx_runner.py --tasks ../../wsb_data/data/tasks.jsonl \
      --data-root ../../wsb_data/resources --sample 20 --out runs_s7.jsonl --score
"""
from __future__ import annotations

import argparse
import json
import random
import time
from collections import defaultdict

from runner.backbone import APIBackbone
from runner.react import react_loop
from runner.tools import ProfileTools
from worksurface.common import persona_slug

SURFACES = ["rag", "table", "graph"]


class RetryBackbone(APIBackbone):
    """GLM 适配层：429/5xx 指数退避 + 关闭思考模式（GLM-4.5 为思考模型，
    小 max_tokens 下思维链会吃光预算导致 content 为空；关闭后 S4/S7 对比
    口径也更公平）+ reasoning_content 兜底。"""

    def _post(self, messages, max_tokens):
        import urllib.request
        body = json.dumps({
            "model": self.name,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": 0,
            "thinking": {"type": "disabled"},  # GLM 混合思考模型开关
        }).encode()
        req = urllib.request.Request(
            self.api_base.rstrip("/") + "/chat/completions",
            data=body,
            headers={"Authorization": f"Bearer {self.api_key}",
                     "Content-Type": "application/json"},
        )
        for attempt in range(7):
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    data = json.load(resp)
                usage = data.get("usage", {})
                self.last_usage = {"input": usage.get("prompt_tokens", 0),
                                   "output": usage.get("completion_tokens", 0)}
                self.cum_usage["input"] += self.last_usage["input"]
                self.cum_usage["output"] += self.last_usage["output"]
                msg = data["choices"][0]["message"]
                content = msg.get("content") or msg.get("reasoning_content") or ""
                return content
            except Exception as e:  # noqa: BLE001
                if attempt == 6:
                    raise
                wait = min(60, 5 * (2 ** attempt)) if "429" in repr(e) else 5
                print(f"    [backoff {wait}s] {repr(e)[:80]}")
                time.sleep(wait)


# ---------------- Stage 1: Stage-Designer ----------------

DESIGNER_SYSTEM = (
    "You are a task planner for an enterprise data agent with three knowledge "
    "surfaces: rag (document search), table (DuckDB SQL over spreadsheets), "
    "graph (dependency graph of files/tasks). Decompose the user question into "
    "1-3 ordered stages. Each stage binds ONE surface and states what evidence "
    "to extract. Only include surfaces the question actually needs, in the "
    "order they should be queried. "
    'Output strict JSON: {"stages":[{"surface":"rag|table|graph","goal":"..."}]}'
)


def stage_design(task: dict, backbone) -> list[dict]:
    raw = backbone.chat(DESIGNER_SYSTEM, task["question"], max_tokens=400)
    try:
        stages = json.loads(raw[raw.index("{"): raw.rindex("}") + 1])["stages"]
        stages = [s for s in stages if s.get("surface") in SURFACES][:3]
        if stages:
            return stages
    except Exception:
        pass
    # 兜底：拆解失败退化为单阶段全工具（保证可用性，损失部分 token 优势）
    return [{"surface": task["required_surfaces"][0], "goal": task["question"]}]


# ---------------- Stage 2: per-stage execution ----------------

TACTIC_HINTS = {
    "table": "Tactic: for counting questions ALWAYS use SQL aggregation "
             "(SELECT COUNT(*), COUNT(DISTINCT col)) instead of manually counting rows.",
    "graph": "Tactic: for counting questions, traverse then count node ids from the returned lists programmatically.",
    "rag": "Tactic: quote the exact sentence that contains the answer before finalizing.",
}


def run_stage(task: dict, backbone, tools: ProfileTools, stage: dict):
    marker = len(tools.trace)
    stage_answer = react_loop(task, backbone, tools,
                              allowed_surfaces=[stage["surface"]], max_steps=8)
    # 只取轨迹尾部最近 6 个动作（前面的探索动作已过时，且防止 table_list 之类
    # 大结果把截断窗口塞满、挤掉真正携带答案的 table_query）
    tail = json.dumps(tools.trace[marker:][-6:], ensure_ascii=False)[:4000]
    return stage_answer, tail


# ---------------- Stage 3: Stage-Summarizer ----------------

SUMMARIZER_SYSTEM = (
    "Compress this tool-call trajectory into a fact summary for a downstream "
    "agent that CANNOT see the trajectory. The Stage Answer is authoritative: "
    "include it VERBATIM in the first bullet. Also keep file names, table/view "
    "names, node ids, numbers. Drop failed attempts and boilerplate. Max 100 words."
)


def summarize_stage(task: dict, backbone, stage: dict, trajectory: str, stage_answer: str) -> str:
    user = (f"Stage goal: {stage['goal']}\n"
            f"AUTHORITATIVE stage answer (must appear verbatim): {stage_answer}\n"
            f"Recent tool calls: {trajectory}")
    return backbone.chat(SUMMARIZER_SYSTEM, user, max_tokens=300)


# ---------------- Stage 4: Synthesizer ----------------

def synthesize(task: dict, backbone, summaries: list[dict]) -> str:
    body = "\n".join(f"[Stage {s['idx']} · {s['surface']}] {s['summary']}" for s in summaries)
    user = (f"Question: {task['question']}\n\nStage summaries:\n{body}\n\n"
            "Combine the stage facts into the final answer. Rules: output the "
            "bare value(s) only, no preamble; if the answer has multiple parts, "
            "separate them with '; '; for file names output the bare filename.")
    return backbone.chat(
        "You are an enterprise data agent. Answer strictly from the provided "
        "stage summaries. Output the final answer value only.", user, max_tokens=400)


# ---------------- S7 主流程 ----------------

def run_s7_agentx(task: dict, backbone, tools: ProfileTools) -> dict:
    stages = stage_design(task, backbone)
    # 条件分阶段：单 surface 任务不拆阶段（整段 8 步连贯探索，避免分段丢上下文），
    # 仅跨 surface 任务走多阶段 + 摘要接力 —— 分阶段是手段不是目的
    if len({s["surface"] for s in stages}) <= 1:
        answer = react_loop(task, backbone, tools,
                            allowed_surfaces=[stages[0]["surface"]], max_steps=8)
        return {
            "chosen_surfaces": sorted(tools.surfaces_used),
            "rag_files": sorted(tools.rag_files),
            "tables": sorted(tools.tables_used),
            "graph_nodes": sorted(tools.graph_nodes),
            "answer": answer,
            "total_tokens": backbone.cum_usage["input"] + backbone.cum_usage["output"],
            "question_text": task["question"],
            "agentx_stages": [{"surface": stages[0]["surface"], "goal": "single-stage (unsplit)"}],
            "agentx_summaries": [],
        }
    summaries = []
    for idx, stage in enumerate(stages, 1):
        stage_answer, trajectory = run_stage(task, backbone, tools, stage)
        summary = summarize_stage(task, backbone, stage, trajectory, stage_answer)
        summaries.append({"idx": idx, "surface": stage["surface"], "summary": summary})

    answer = normalize_answer(synthesize(task, backbone, summaries), task)
    return {
        "chosen_surfaces": sorted(tools.surfaces_used),
        "rag_files": sorted(tools.rag_files),
        "tables": sorted(tools.tables_used),
        "graph_nodes": sorted(tools.graph_nodes),
        "answer": answer,
        "total_tokens": backbone.cum_usage["input"] + backbone.cum_usage["output"],
        "question_text": task["question"],
        "agentx_stages": stages,
        "agentx_summaries": summaries,
    }


# ---------------- 评测 CLI：S4 基线 vs AgentX 分层抽样对跑 ----------------

def stratified_sample(tasks: list[dict], n: int, seed: int = 7) -> list[dict]:
    by_type = defaultdict(list)
    for t in tasks:
        by_type[t["task_type"]].append(t)
    random.seed(seed)
    picked, order = [], ["cross_surface", "table_only", "rag_only", "graph_only"]
    while len(picked) < n:
        for typ in order:
            pool = by_type[typ]
            if pool and len(picked) < n:
                picked.append(pool.pop(random.randrange(len(pool))))
    return picked


import re as _re


def normalize_answer(answer: str, task: dict) -> str:
    """规则化后处理（零成本修格式分）：剥前缀/引号/句号，多段分隔符统一为 '; '。"""
    a = answer.strip()
    a = _re.sub(r'^(the final answer is|the answer is|answer:)\s*', '', a, flags=_re.I)
    a = a.strip('"\''  ).rstrip('.').strip()
    parts = [p.strip() for p in a.split(';')]
    if len(parts) == 1:
        m = _re.match(r'^(.+\.(?:csv|xlsx|docx|pdf|md|txt)),\s*(.+)$', a, flags=_re.I)
        if m:
            parts = [m.group(1), m.group(2)]
    if len(parts) > 1:
        a = '; '.join(parts)
    # 列表型 gold（list）若答案为多个值也用 '; ' 连接（synthesizer 已被约束）
    return a


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tasks", required=True)
    ap.add_argument("--data-root", required=True)
    ap.add_argument("--model", default="glm-4.5-flash")
    ap.add_argument("--sample", type=int, default=20)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--mode", choices=["both", "s4", "s7"], default="both")
    ap.add_argument("--outdir", default="runs")
    args = ap.parse_args()

    import os
    load = __import__("dotenv").load_dotenv
    load()

    tasks = [json.loads(l) for l in open(args.tasks, encoding="utf-8")]
    sample = stratified_sample(tasks, args.sample, args.seed)
    os.makedirs(args.outdir, exist_ok=True)

    def run_setting(setting, runner_fn, path):
        backbone = RetryBackbone(model=args.model)
        n_err = 0
        with open(path, "w", encoding="utf-8") as f:
            for i, task in enumerate(sample, 1):
                backbone.reset()
                slug = persona_slug(task["source"].get("persona", ""))
                try:
                    tools = ProfileTools(args.data_root, slug,
                                         source_task_id=str(task["source"]["task_id"]))
                    try:
                        if setting == "S4":
                            from runner.agents import run_s4_react_all
                            trace = run_s4_react_all(task, backbone, tools)
                            trace["total_tokens"] = (backbone.cum_usage["input"]
                                                     + backbone.cum_usage["output"])
                        else:
                            trace = run_s7_agentx(task, backbone, tools)
                    finally:
                        tools.close()
                except Exception as e:  # noqa: BLE001
                    n_err += 1
                    trace = {"id": task["id"], "setting": setting, "error": repr(e)[:200],
                             "chosen_surfaces": [], "rag_files": [], "tables": [],
                             "graph_nodes": [], "answer": "", "total_tokens": 0,
                             "question_text": task["question"]}
                trace.update({"id": task["id"], "setting": setting, "model": args.model})
                f.write(json.dumps(trace, ensure_ascii=False) + "\n")
                f.flush()
                print(f"  [{setting}] {i}/{len(sample)} {task['id']} tokens={trace['total_tokens']}")
        return path

    from scoring.score_run import score_run
    paths = []
    if args.mode in ("both", "s4"):
        paths.append(("S4", run_setting("S4", None, f"{args.outdir}/s4_{args.model}.jsonl")))
    if args.mode in ("both", "s7"):
        paths.append(("S7-AgentX", run_setting("S7", None, f"{args.outdir}/s7_agentx_{args.model}.jsonl")))

    all_tasks = {t["id"]: t for t in tasks}
    print("\n==== 对比报告 ====")
    for name, path in paths:
        traces = {json.loads(l)["id"]: json.loads(l) for l in open(path, encoding="utf-8")}
        subset = [all_tasks[tid] for tid in traces if tid in all_tasks]
        report = score_run(subset, traces)
        agg = report["overall"]
        print(f"{name}: aggregate={agg.get('aggregate', 'n/a')} "
              f"route={agg.get('route', 'n/a')} evidence={agg.get('evidence', 'n/a')} "
              f"answer={agg.get('answer', 'n/a')} "
              f"avg_tokens={sum(t['total_tokens'] for t in traces.values()) / max(len(traces), 1):.0f}")
        with open(path.replace(".jsonl", ".scored.json"), "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)


if __name__ == "__main__":
    main()
