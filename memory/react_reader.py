"""读路径：轻量 ReAct 答题 agent（文本协议，≤4 步，三工具）。

工具集按消融配置注入：
  layered：memory_search（语义事实向量检索）/ timeline（时序排序）/ history_scan（原文窗口）
  rag_only：仅 history_scan（纯 RAG 对照：无事实抽取、无摘要）
"""
from __future__ import annotations

import json

from memory.embedder import embed_one
from memory.store import MemoryStore


class MemoryTools:
    def __init__(self, store: MemoryStore, allowed: list[str]):
        self.store = store
        self.allowed = allowed

    def _search_facts(self, query: str, k: int = 5) -> str:
        hits = self.store.knn_facts(embed_one(query), k=k)
        if not hits:
            return "[]"
        return json.dumps([
            {"fact": h["text"], "date": h.get("date_time"),
             "session": h.get("session_idx"), "score": round(h["score"], 3)}
            for h in hits], ensure_ascii=False)

    def _timeline(self, query: str, k: int = 8) -> str:
        """时序通道：命中事实按会话顺序排列（temporal 题专用）。"""
        facts = self.store.knn_facts(embed_one(query), k=k)
        items = sorted(facts, key=lambda h: (h.get("session_idx", 0), h.get("date_time", "")))
        return json.dumps([
            {"order": i + 1, "date": h.get("date_time"), "session": h.get("session_idx"),
             "fact": h["text"]} for i, h in enumerate(items)], ensure_ascii=False)

    def _history_scan(self, query: str, k: int = 5) -> str:
        hits = self.store.knn_messages(embed_one(query), k=k)
        hits.sort(key=lambda h: (h.get("session_idx", 0), h.get("dia_id", "")))
        return json.dumps([
            {"dia_id": h.get("dia_id") or "", "session": h.get("session_idx"),
             "date": h.get("date_time"), "speaker": h.get("speaker"), "text": h["text"]}
            for h in hits], ensure_ascii=False)

    def dispatch(self, name: str, args: dict) -> str:
        if name == "memory_search" and "memory_search" in self.allowed:
            return self._search_facts(args.get("query", ""), int(args.get("k", 5)))
        if name == "timeline" and "timeline" in self.allowed:
            return self._timeline(args.get("query", ""))
        if name == "history_scan" and "history_scan" in self.allowed:
            return self._history_scan(args.get("query", ""), int(args.get("k", 5)))
        return f"unknown/disallowed tool: {name}"


TOOL_SPECS = {
    "memory_search": '{"tool":"memory_search","args":{"query":"<text>","k":5}} '
                     "search long-term memory facts (best for factual recall)",
    "timeline": '{"tool":"timeline","args":{"query":"<text>"}} '
                "retrieve memory facts sorted chronologically (best for temporal/order questions)",
    "history_scan": '{"tool":"history_scan","args":{"query":"<text>","k":5}} '
                    "scan original conversation utterances (best for verbatim details)",
}


def answer_question(task: dict, llm, store: MemoryStore,
                    config: str = "layered", max_steps: int = 4) -> dict:
    """对一个问题执行轻量 ReAct。config: layered | rag_only"""
    allowed = ["history_scan"] if config == "rag_only" else \
        ["memory_search", "timeline", "history_scan"]
    mt = MemoryTools(store, allowed)
    menu = "\n".join(TOOL_SPECS[t] for t in allowed)

    system = ("You are a memory agent answering questions about long past "
              "conversations. Each turn output ONE JSON object, either a tool "
              "call or the final answer:\n" + menu +
              '\n  {"final_answer": "<answer>"}\n'
              "Use tools to recall, then answer with at most 15 words — cite "
              "the key entity/value only, no full sentences. If the conversation "
              "never mentions it, output exactly: Not mentioned in the conversation.")
    messages = [{"role": "system", "content": system},
                {"role": "user", "content": task["question"]}]
    steps = []
    for _ in range(max_steps):
        raw = llm.chat_messages_json(messages)
        try:
            obj = json.loads(raw[raw.index("{"): raw.rindex("}") + 1])
        except Exception:
            break
        if "final_answer" in obj:
            return {"answer": str(obj["final_answer"]), "steps": steps}
        tool, args = obj.get("tool"), obj.get("args") or {}
        result = mt.dispatch(tool, args)
        steps.append({"tool": tool, "args": args})
        messages.append({"role": "assistant", "content": raw})
        messages.append({"role": "user", "content": f"[observation]\n{result[:3000]}"})
    messages.append({"role": "user",
                     "content": "[system] Step budget exhausted. Give your best final answer now."})
    raw = llm.chat_messages_json(messages)
    try:
        obj = json.loads(raw[raw.index("{"): raw.rindex("}") + 1])
        answer = obj.get("final_answer", raw[:200])
    except Exception:
        answer = raw[:200]
    return {"answer": answer, "steps": steps}
