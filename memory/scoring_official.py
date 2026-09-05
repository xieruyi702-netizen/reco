"""官方对齐评分器：逐条复刻 LoCoMo task_eval/evaluation.py 的确定性口径。

规则（与官方逐一对应）：
  normalize：lower → 去标点 → 去冠词(a/an/the/and) → 空白归一
  cat 2/3/4：token F1(normalize(output), normalize(gold))
  cat 1    ：逗号拆子答案，逐 gold 子答案取 max F1 后求均值
  cat 5    ：输出（原始小写）含 'no information available' 或 'not mentioned' → 1，否则 0
"""
from __future__ import annotations

import re
import string
from collections import Counter


def normalize_answer(s: str) -> str:
    def remove_articles(text):
        return re.sub(r"\b(a|an|the|and)\b", " ", text)

    def white_space_fix(text):
        return " ".join(text.split())

    def remove_punc(text):
        exclude = set(string.punctuation)
        return "".join(ch for ch in text if ch not in exclude)

    return white_space_fix(remove_articles(remove_punc(s.lower())))


def f1_score(prediction: str, ground_truth: str) -> float:
    pred_t = normalize_answer(prediction).split()
    gold_t = normalize_answer(ground_truth).split()
    if not pred_t or not gold_t:
        return float(pred_t == gold_t)
    common = Counter(pred_t) & Counter(gold_t)
    ov = sum(common.values())
    if ov == 0:
        return 0.0
    prec, rec = ov / len(pred_t), ov / len(gold_t)
    return 2 * prec * rec / (prec + rec)


def official_f1(prediction: str, ground_truth: str) -> float:
    """cat 1 的多子答案口径：逗号拆分，逐 gold 取 max 后求均值。"""
    predictions = [p.strip() for p in prediction.split(",")]
    ground_truths = [g.strip() for g in ground_truth.split(",")]
    return sum(max(f1_score(p, gt) for p in predictions) for gt in ground_truths) / len(ground_truths)


def score_locomo(category: int, output: str, gold: str) -> float:
    if category in (2, 3, 4):
        return f1_score(output, gold)
    if category == 1:
        return official_f1(output, gold)
    if category == 5:
        low = output.lower()
        return 1.0 if ("no information available" in low or "not mentioned" in low) else 0.0
    raise ValueError(f"unknown category {category}")
