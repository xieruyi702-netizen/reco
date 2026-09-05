"""LoCoMo 两相位评测：相位1 盲摄取（禁见 QA）→ 相位2 轻量 ReAct 答题 + token-F1。

消融配置：
  layered  全量分层记忆（语义事实 + 情景摘要 + 原文窗口）
  rag_only 纯 RAG 对照（仅原文窗口向量检索，无事实抽取、无摘要）

用法：
  python memory/eval_locomo.py --conv 0 --qa-limit 60 --config both --sample-seed 7
"""
from __future__ import annotations

import argparse
import collections
import json
import random
import re
import time

from memory.embedder import embed_one
from memory.store import MemoryStore
from memory.writer import LLM, ingest_session
from memory.react_reader import answer_question

ADVERSARIAL = "Not mentioned in the conversation"


def norm(s: str) -> str:
    s = str(s).lower().strip().rstrip(".").replace('"', "")
    s = re.sub(r"[,;!?]", " ", s)
    return " ".join(w for w in s.split() if w not in {"a", "an", "the", "is", "was", "are", "were"})


def token_f1(pred: str, gold: str) -> float:
    p, g = norm(pred).split(), norm(gold).split()
    if not p or not g:
        return 1.0 if not g and not p else 0.0
    common = collections.Counter(p) & collections.Counter(g)
    ov = sum(common.values())
    if ov == 0:
        return 0.0
    prec, rec = ov / len(p), ov / len(g)
    return 2 * prec * rec / (prec + rec)


def load_conversations(path: str) -> list[dict]:
    return json.load(open(path, encoding="utf-8"))


def ingest_conversation(llm: LLM, store: MemoryStore, conv: dict, config: str) -> dict:
    """相位1：按时间顺序盲摄取（只看会话原文，禁见 QA）。"""
    conv_id = conv["sample_id"]
    conv_part = conv["conversation"]
    session_keys = sorted(
        [k for k in conv_part if k.startswith("session_") and not k.endswith("date_time")],
        key=lambda k: int(k.split("_")[1]))
    stats = {"facts_extracted": 0, "duplicates": 0, "superseded": 0, "messages": 0, "sessions": 0}
    for key in session_keys:
        idx = int(key.split("_")[1])
        date_time = conv_part.get(f"session_{idx}_date_time", "")
        turns = conv_part[key]
        if config == "layered":
            s = ingest_session(llm, store, conv_id, idx, date_time, turns)
            for k in stats:
                stats[k] += s.get(k, 0)
        else:  # rag_only：仅原文向量入库（无抽取、无摘要）
            for t in turns:
                store.put_message(t["dia_id"], {
                    "speaker": t["speaker"], "text": t["text"],
                    "session_idx": idx, "date_time": date_time,
                }, embed_one(t["text"]))
            stats["messages"] += len(turns)
        stats["sessions"] += 1
    return stats


def stratified_qa(qa: list[dict], limit: int, seed: int) -> list[dict]:
    by_cat = collections.defaultdict(list)
    for q in qa:
        by_cat[q["category"]].append(q)
    random.seed(seed)
    picked = []
    order = [4, 1, 2, 5, 3]  # multi-hop 优先（最难点）
    target = min(limit, len(qa))
    while len(picked) < target:
        progressed = False
        for c in order:
            pool = by_cat[c]
            if pool and len(picked) < target:
                picked.append(pool.pop(random.randrange(len(pool))))
                progressed = True
        if not progressed:
            break
    return picked


CAT_NAMES = {1: "single_hop", 2: "temporal", 3: "open_domain", 4: "multi_hop", 5: "adversarial"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data_locomo/locomo10.json")
    ap.add_argument("--conv", type=int, default=0)
    ap.add_argument("--qa-limit", type=int, default=60)
    ap.add_argument("--config", choices=["layered", "rag_only", "both"], default="both")
    ap.add_argument("--model", default="deepseek-v4-flash")
    ap.add_argument("--sample-seed", type=int, default=7)
    ap.add_argument("--outdir", default="runs_mem")
    args = ap.parse_args()

    import os
    from dotenv import load_dotenv
    load_dotenv()
    llm = LLM(args.model, os.environ["WSB_API_BASE"], os.environ["WSB_API_KEY"])

    convs = load_conversations(args.data)
    conv = convs[args.conv]
    conv_id = conv["sample_id"]
    qa_sample = stratified_qa(conv["qa"], args.qa_limit, args.sample_seed)
    print(f"对话 {conv_id}: {len(conv['qa'])} QA，抽样 {len(qa_sample)}；"
          f"会话 {len(conv['conversation'])//2} 个")

    os.makedirs(args.outdir, exist_ok=True)
    configs = ["layered", "rag_only"] if args.config == "both" else [args.config]
    results = {}
    for config in configs:
        import time as _t
        t0 = _t.time()
        store = MemoryStore(conv_id)
        # 记忆库隔离：重建该对话命名空间
        for key in list(store.r.scan_iter(f"conv:{conv_id}:*")):
            store.r.delete(key)

        st = ingest_conversation(llm, store, conv, config)
        ingest_s = _t.time() - t0
        print(f"[{config}] 摄取完成: {st} 耗时 {ingest_s:.0f}s | "
              f"facts={store.count('fact')} messages={store.count('msg')}")

        rows = []
        t1 = _t.time()
        for i, q in enumerate(qa_sample, 1):
            gold = str(q.get("answer") or (q.get("adversarial_answer") if q["category"] == 5 else "") or q.get("gold_answer", ""))
            r = answer_question({"question": q["question"]}, llm, store, config)
            f1 = token_f1(r["answer"], gold)
            rows.append({"id": q["question"][:40], "cat": q["category"],
                         "f1": f1, "answer": r["answer"][:120],
                         "gold": gold[:60], "steps": len(r["steps"])})
            print(f"  [{config}] {i}/{len(qa_sample)} cat{q['category']} F1={f1:.2f}")
        qa_s = _t.time() - t1
        by_cat = collections.defaultdict(list)
        for r in rows:
            by_cat[r["cat"]].append(r["f1"])
        cat_str = " ".join(f"{CAT_NAMES[c]}={sum(v)/len(v):.2f}" for c, v in sorted(by_cat.items()))
        overall = sum(r["f1"] for r in rows) / len(rows)
        results[config] = {"overall": overall, "by_cat": {k: sum(v)/len(v) for k, v in by_cat.items()},
                           "tokens": llm.calls, "ingest_s": ingest_s, "qa_s": qa_s}
        print(f"RESULT [{config}] overall F1={overall:.3f} | {cat_str} | "
              f"LLM调用={llm.calls} 摄取={ingest_s:.0f}s QA={qa_s:.0f}s")
        with open(f"{args.outdir}/{config}_{args.model}_conv{args.conv}.json", "w",
                  encoding="utf-8") as f:
            json.dump({"config": config, "overall": overall, "by_cat": results[config]["by_cat"],
                       "rows": rows}, f, ensure_ascii=False, indent=1)

    if len(results) == 2:
        print("\n==== 消融对比 ====")
        for cfg, r in results.items():
            print(f"{cfg:9s} overall={r['overall']:.3f} by_cat={r['by_cat']} "
                  f"LLM调用={r['tokens']} 摄取={r['ingest_s']:.0f}s")


if __name__ == "__main__":
    main()
