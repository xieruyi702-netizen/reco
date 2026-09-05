"""写入流水线（摄取相位，无 ReAct）：会话 → 原子事实抽取 → 去重 → 冲突标记 → 入库。

流程（每个会话）：
  1. 轨迹文本化（带 dia_id 与会话时间戳）
  2. LLM 原子事实抽取：3-8 条，独立成立、含实体与支持轮次 dia_id
  3. 去重：新事实 embedding 与既有事实余弦 ≥0.93 → 视为重复，跳过入库
  4. 冲突标记：0.88≤余弦<0.93 且主体实体相同 → 新事实入库、旧事实标 superseded
     （时序冲突裁决：以会话顺序为准，新会话的事实覆盖旧会话的同主体表述）
  5. 语义入库 + 原始轮次带向量入库（history_scan 数据源）+ 会话摘要（情景记忆）
"""
from __future__ import annotations

import json
import time
import urllib.request

from memory.embedder import embed, embed_one
from memory.store import MemoryStore

EXTRACT_SYSTEM = (
    "Extract 3-8 ATOMIC facts from this conversation segment. Rules: each fact "
    "is independently understandable, contains concrete entities (names, places, "
    "dates, numbers), and cites the supporting turn id(s) (e.g. D1:5). Do not "
    "merge unrelated facts. Output strict JSON: "
    '{"facts":[{"text":"...","entities":["..."],"dia_ids":["D1:5"]}]}')


class LLM:
    """DeepSeek/GLM 兼容客户端：思考关闭 + 429 退避 + reasoning 兜底。"""

    def __init__(self, model: str, api_base: str, api_key: str):
        self.model, self.base, self.key = model, api_base.rstrip("/"), api_key
        self.calls = 0

    def chat_messages_json(self, messages: list[dict], max_tokens: int = 700) -> str:
        body = json.dumps({"model": self.model, "messages": messages,
            "max_tokens": max_tokens, "temperature": 0,
            "thinking": {"type": "disabled"}}).encode()
        req = urllib.request.Request(self.base + "/chat/completions", data=body,
            headers={"Authorization": f"Bearer {self.key}", "Content-Type": "application/json"})
        delay = 5
        for attempt in range(7):
            try:
                with urllib.request.urlopen(req, timeout=180) as resp:
                    data = json.load(resp)
                self.calls += 1
                msg = data["choices"][0]["message"]
                return msg.get("content") or msg.get("reasoning_content") or ""
            except Exception as e:  # noqa: BLE001
                if attempt == 6:
                    raise
                wait = min(60, delay * (2 ** attempt)) if "429" in repr(e) else delay
                time.sleep(wait)

    def chat(self, system: str, user: str, max_tokens: int = 800) -> str:
        body = json.dumps({"model": self.model, "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user}],
            "max_tokens": max_tokens, "temperature": 0,
            "thinking": {"type": "disabled"}}).encode()
        req = urllib.request.Request(self.base + "/chat/completions", data=body,
            headers={"Authorization": f"Bearer {self.key}", "Content-Type": "application/json"})
        delay = 5
        for attempt in range(7):
            try:
                with urllib.request.urlopen(req, timeout=180) as resp:
                    data = json.load(resp)
                self.calls += 1
                msg = data["choices"][0]["message"]
                return msg.get("content") or msg.get("reasoning_content") or ""
            except Exception as e:  # noqa: BLE001
                if attempt == 6:
                    raise
                wait = min(60, delay * (2 ** attempt)) if "429" in repr(e) else delay
                time.sleep(wait)


def extract_facts(llm: LLM, transcript: str) -> list[dict]:
    raw = llm.chat(EXTRACT_SYSTEM, transcript, max_tokens=900)
    try:
        return json.loads(raw[raw.index("{"): raw.rindex("}") + 1])["facts"]
    except Exception:
        return []


def ingest_session(llm: LLM, store: MemoryStore, conv_id: str,
                   session_idx: int, date_time: str, turns: list[dict]) -> dict:
    """摄取一个会话。返回统计。"""
    transcript = "\n".join(
        f"[{t['dia_id']}] {t['speaker']}: {t['text']}" for t in turns)
    stats = {"facts_extracted": 0, "duplicates": 0, "superseded": 0, "messages": len(turns)}

    facts = extract_facts(llm, f"Session date: {date_time}\n\n{transcript}") \
        if __import__("os").environ.get("MEMORY_MODE", "layered") == "layered" else []
    stats["facts_extracted"] = len(facts)

    for i, f in enumerate(facts):
        text = f.get("text", "").strip()
        if not text:
            continue
        fid = f"{conv_id}:s{session_idx}:f{i}"
        emb = embed_one(text)
        near = store.knn_facts(emb, k=1)
        if near and near[0]["score"] >= 0.93:
            stats["duplicates"] += 1  # 重复：跳过
            continue
        if near and near[0]["score"] >= 0.88:
            near[0]["superseded"] = True  # 时序冲突：新表述覆盖旧表述
            store.put_fact(near[0]["fact_id"], near[0], near[0]["emb"])
            stats["superseded"] += 1
        store.put_fact(fid, {
            "text": text, "entities": f.get("entities", []),
            "dia_ids": f.get("dia_ids", []), "session_idx": session_idx,
            "date_time": date_time, "conv": conv_id,
        }, emb)

    # 原始轮次入库（history_scan 数据源）+ 会话摘要（情景记忆）
    for t in turns:
        store.put_message(t["dia_id"], {
            "speaker": t["speaker"], "text": t["text"],
            "session_idx": session_idx, "date_time": date_time,
        }, embed_one(t["text"]))
    summary = llm.chat(
        "Summarize this conversation segment in 2 sentences, keeping names, "
        "dates and key events.", transcript, max_tokens=300)
    store.put_session_summary(session_idx, summary)
    return stats
