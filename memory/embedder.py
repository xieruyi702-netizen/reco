"""嵌入层：fastembed 本地 ONNX 模型（BAAI/bge-small-en-v1.5，384 维）。
本地推理零 API 成本、结果确定（同文本同向量），符合评测协议的确定性要求。"""

from functools import lru_cache

MODEL = "BAAI/bge-small-en-v1.5"


@lru_cache(maxsize=1)
def _model():
    from fastembed import TextEmbedding
    return TextEmbedding(model_name=MODEL)


def embed(texts: list[str]) -> list[list[float]]:
    return [v.tolist() for v in _model().embed(texts)]


def embed_one(text: str) -> list[float]:
    return embed([text])[0]
