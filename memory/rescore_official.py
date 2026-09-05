"""用官方对齐评分器重打分已保存的评测运行（零 LLM 成本）。

用法：python memory/rescore_official.py runs_mem_v1/layered_deepseek-v4-flash_conv0.json [更多文件...]
"""
from __future__ import annotations

import collections
import json
import sys

from memory.scoring_official import score_locomo

CAT_NAMES = {1: "single_hop", 2: "temporal", 3: "open_domain", 4: "multi_hop", 5: "adversarial"}


def rescore(path: str) -> dict:
    d = json.load(open(path, encoding="utf-8"))
    rows = d["rows"]
    new = []
    for r in rows:
        s = score_locomo(r["cat"], r["answer"], r["gold"])
        new.append(dict(r, aligned_f1=s))
    overall = sum(r["aligned_f1"] for r in new) / len(new)
    by_cat = collections.defaultdict(list)
    for r in new:
        by_cat[r["cat"]].append(r["aligned_f1"])
    by_cat = {CAT_NAMES[c]: sum(v) / len(v) for c, v in sorted(by_cat.items())}
    out = {"config": d["config"], "aligned_overall": round(overall, 4), "aligned_by_cat":
           {k: round(v, 3) for k, v in by_cat.items()}, "rows": new}
    with open(path.replace(".json", ".aligned.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    return out


if __name__ == "__main__":
    for path in sys.argv[1:]:
        r = rescore(path)
        print(f"{r['config']:9s} aligned_overall={r['aligned_overall']:.3f} by_cat={r['aligned_by_cat']}")
