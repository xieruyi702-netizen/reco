"""记忆存储层（Redis 8 为事实/消息/摘要的真相源，MySQL 只做审计）。

Key 约定（conv = conversation id）：
  conv:{cid}:fact:{fid}     JSON：事实文本+向量+来源(session/dia_id)+实体+时间
  conv:{cid}:msg:{dia_id}   JSON：原始对话轮次+向量（history_scan 数据源）
  conv:{cid}:summary:s{i}   会话摘要（情景记忆）
  conv:{cid}:index          Set：事实 fid 索引

向量检索规模（单对话 ≤2k 向量）采用 Python 侧余弦暴力扫描——万级以内
无需 RediSearch 模块依赖，确定且足够快（<5ms）。
"""
from __future__ import annotations

import json
import math

import redis

REDIS_HOST, REDIS_PORT = "127.0.0.1", 6380


def r() -> redis.Redis:
    return redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)


def _cos(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a)) or 1.0
    nb = math.sqrt(sum(x * x for x in b)) or 1.0
    return dot / (na * nb)


class MemoryStore:
    def __init__(self, conv_id: str):
        self.cid = conv_id
        self.r = r()
        self.pfx = f"conv:{conv_id}"

    # ---- 写 ----
    def put_fact(self, fid: str, fact: dict, emb: list[float]) -> None:
        fact = dict(fact, emb=emb)
        self.r.set(f"{self.pfx}:fact:{fid}", json.dumps(fact, ensure_ascii=False))
        self.r.sadd(f"{self.pfx}:index", fid)

    def put_message(self, dia_id: str, msg: dict, emb: list[float]) -> None:
        self.r.set(f"{self.pfx}:msg:{dia_id}", json.dumps(dict(msg, emb=emb), ensure_ascii=False))

    def put_session_summary(self, session_idx: int, summary: str) -> None:
        self.r.set(f"{self.pfx}:summary:s{session_idx}", summary)

    # ---- 读（检索在 Python 侧余弦扫描）----
    def _all(self, kind: str) -> list[dict]:
        out = []
        for key in self.r.scan_iter(f"{self.pfx}:{kind}:*"):
            raw = self.r.get(key)
            if raw:
                d = json.loads(raw)
                d["fact_id"] = key.split(f":{kind}:")[-1]  # key 携带 id，注入字典
                out.append(d)
        return out

    def knn_facts(self, query_emb: list[float], k: int = 5,
                  exclude_superseded: bool = True) -> list[dict]:
        items = [f for f in self._all("fact") if not (exclude_superseded and f.get("superseded"))]
        for f in items:
            f["score"] = _cos(query_emb, f["emb"])
        items.sort(key=lambda x: -x["score"])
        return items[:k]

    def knn_messages(self, query_emb: list[float], k: int = 5) -> list[dict]:
        items = self._all("msg")
        for m in items:
            m["score"] = _cos(query_emb, m["emb"])
        items.sort(key=lambda x: -x["score"])
        return items[:k]

    def all_facts(self) -> list[dict]:
        return self._all("fact")

    def session_summary(self, session_idx: int) -> str | None:
        return self.r.get(f"{self.pfx}:summary:s{session_idx}")

    def count(self, kind: str) -> int:
        return sum(1 for _ in self.r.scan_iter(f"{self.pfx}:{kind}:*"))
