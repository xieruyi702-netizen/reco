"""S8 Plan-and-Execute：与 ReAct（边想边做）相对的"先规划、后确定性执行"架构。

与 S4/S7 的本质区别（LLM 触达次数）：
  S4 ReAct        每次工具调用前后都有 LLM 决策（N 次 LLM 调用，上下文交错增长）
  S7 阶段式       规划 1 次 + 每阶段迷你 ReAct + 摘要/汇总（介于两者之间）
  S8 Plan-Execute 开头 Planner 一次性产出完整工具调用计划 → 执行器【无 LLM】
                  按序执行全部调用 → 结尾 Synthesizer 一次汇总（全程仅 2 次 LLM 调用）

适用前提：任务时程短（工具调用 ≤6 步即可解），恰好匹配 WorkSurface-Bench 的原子任务形态。
风险：计划是"盲执行"，某步查空/报错不会自适应重规划 —— 由一次 repair 调用兜底（可关）。
"""
from __future__ import annotations

import json
import re as _re

from runner.tools import ProfileTools

PLAN_SYSTEM = (
    "You are an enterprise data agent. Produce a COMPLETE tool-call plan to "
    "answer the question — the plan will be executed verbatim WITHOUT you in "
    "the loop, so every call must be concrete and self-contained. Available "
    "tools:\n"
    '  {"tool":"kb_search","args":{"query":"<text>","k":3}}            search documents\n'
    '  {"tool":"table_list","args":{}}                                  list tables\n'
    '  {"tool":"table_describe","args":{"view":"<name>"}}               table columns\n'
    '  {"tool":"table_query","args":{"sql":"SELECT ..."}}               read-only DuckDB SQL\n'
    '  {"tool":"graph_search_entities","args":{"query":"<text>"}}       find graph nodes\n'
    '  {"tool":"graph_neighbors","args":{"node":"<id>"}}                node neighbors\n'
    '  {"tool":"graph_traverse","args":{"node":"<id>","rel":"<opt>"}}   traverse by relation\n'
    "Rules: at most 6 steps; order by data dependency (find ids/names before "
    "querying them); write FULL literal arguments (no placeholders); prefer "
    "SQL aggregation (COUNT/COUNT DISTINCT) for counting questions. "
    'Output strict JSON: {"steps":[{...},{...}]}'
)

REPAIR_SYSTEM = (
    "The executed plan did not yield enough information. Produce a MINIMAL "
    "follow-up plan (1-2 tool calls) targeting the missing evidence. Same "
    'JSON format: {"steps":[...]}'
)


def env_snapshot(task: dict, tools: ProfileTools) -> str:
    """确定性环境快照：真实表清单 + 图谱任务节点约定（规划器不再盲猜参数）。"""
    try:
        tables = tools.table_list()
    except Exception:
        tables = []
    task_node = f"task_{task['source']['task_id']}"
    return (f"Workspace tables (real names, use verbatim in SQL):\n"
            f"{json.dumps(tables, ensure_ascii=False)[:1500]}\n"
            f"Graph: the task's node is \"{task_node}\"; file nodes look like "
            f"\"t<id>::<filename>\"; answer file questions with the bare filename.")


def plan(task: dict, backbone, failed_summary: str | None = None, env: str = "") -> list[dict]:
    system = PLAN_SYSTEM if not failed_summary else REPAIR_SYSTEM
    user = task["question"]
    if env:
        user += "\n\n" + env
    if failed_summary:
        user += "\n\nExecuted results (insufficient):\n" + failed_summary
    raw = backbone.chat(system, user, max_tokens=600)
    try:
        steps = json.loads(raw[raw.index("{"): raw.rindex("}") + 1])["steps"]
        return [s for s in steps if isinstance(s, dict) and "tool" in s][:6]
    except Exception:
        return []


def execute(task: dict, tools: ProfileTools, steps: list[dict]) -> list[dict]:
    """无 LLM 确定性执行：按计划逐条调用，单步失败不影响后续步骤。"""
    results = []
    for i, step in enumerate(steps, 1):
        tool, args = step.get("tool"), step.get("args") or {}
        try:
            fn = getattr(tools, tool, None)
            out = fn(**args) if fn else "unknown tool"
        except Exception as e:  # noqa: BLE001
            out = f"ERROR: {e}"
        if not isinstance(out, str):
            out = json.dumps(out, ensure_ascii=False, default=str)
        results.append({"step": i, "tool": tool, "args": args, "result": out[:2000]})
    return results


def _numeric_conflict(answer: str, results: list[dict]) -> int:
    """自洽校验（零 gold 依赖）：合成答案为纯数字、且轨迹里恰有一个 COUNT 聚合
    结果数字与之不符 → 判定模型眼数出错，返回权威数字；否则返回 None。"""
    a = answer.strip()
    if not a.replace('.','').replace('-','').isdigit():
        return None
    candidates = set()
    for r in results:
        if r["tool"] == "table_query":
            sql = (r["args"] or {}).get("sql", "").upper()
            if "COUNT" in sql:
                for tok in _re.findall(r"\d+", r["result"]):
                    candidates.add(int(tok))
    try:
        ans_n = int(float(a))
    except ValueError:
        return None
    if len(candidates) == 1 and ans_n not in candidates:
        return candidates.pop()
    return None


def _low_confidence(answer: str, results: list[dict]) -> bool:
    if "insufficient" in answer.lower() or "not enough" in answer.lower():
        return True
    if any((r["result"] or "").startswith("ERROR") or (r["result"] or "") == "[]" for r in results):
        return True
    return False


def run_s8_plan_execute(task: dict, backbone, tools: ProfileTools, repair: bool = True,
                        escalate=None) -> dict:
    """escalate: 置信不足时的升级回调（全工具 ReAct）；None 表示不升级。"""
    escalated = False
    env = env_snapshot(task, tools)
    steps = plan(task, backbone, env=env)
    results = execute(task, tools, steps)

    body = json.dumps(results, ensure_ascii=False)[:8000]
    answer = backbone.chat(
        "You are an enterprise data agent. Answer strictly from the executed "
        "tool results. Output the final answer value only — no preamble.",
        f"Question: {task['question']}\n\nExecuted plan results:\n{body}",
        max_tokens=400)

    # 一次 repair 兜底：结果查空/明显失败时重规划再执行一轮（可关闭）
    if repair and (not results or all("[]" in r["result"] or "ERROR" in r["result"] for r in results)):
        repair_steps = plan(task, backbone, failed_summary=body[:3000], env=env)
        results += execute(task, tools, repair_steps)
        body = json.dumps(results, ensure_ascii=False)[:8000]
        answer = backbone.chat(
            "Answer strictly from the executed tool results. Output the final "
            "answer value only.",
            f"Question: {task['question']}\n\nExecuted plan results:\n{body}",
            max_tokens=400)

    # ---- Verify/升级段（零 gold 依赖，只用自身工具证据）----
    fixed = _numeric_conflict(answer, results)
    if fixed is not None:
        answer = str(fixed)  # 眼数被工具 COUNT 证伪，校正为聚合结果
    if escalate is not None and (
            fixed is not None or _low_confidence(answer, results)):
        escalated = True
        tools2_marker = len(tools.trace)
        answer = escalate(task, backbone, tools)  # 全工具 ReAct，8 步
        _ = tools2_marker
    return {
        "chosen_surfaces": sorted(tools.surfaces_used),
        "rag_files": sorted(tools.rag_files),
        "tables": sorted(tools.tables_used),
        "graph_nodes": sorted(tools.graph_nodes),
        "answer": answer,
        "total_tokens": backbone.cum_usage["input"] + backbone.cum_usage["output"],
        "question_text": task["question"],
        "s8_plan": steps,
        "s8_results": results,
        "escalated": escalated,
        "numeric_fixed": fixed is not None,
    }
