"""工具层：@tool 封装对 Java 秒杀系统 REST 接口的调用（类型注解即 schema，docstring 即工具描述）。"""
import requests
from langchain_core.tools import tool

from config import SECKILL_BASE

# 本机服务直连，绕过系统代理（http_proxy 等会劫持 localhost 请求导致超时）
NO_PROXY = {"http": None, "https": None}


@tool
def query_stock(voucher_id: int) -> str:
    """查询指定券的实时余量（高频变化数据，直读 Redis 主库）。"""
    r = requests.get(f"{SECKILL_BASE}/voucher/{voucher_id}/stock", timeout=10, proxies=NO_PROXY)
    d = r.json()
    return f"券{voucher_id}余量: {d['data']}" if d["code"] == 0 else f"券{voucher_id}不存在"


@tool
def query_detail(voucher_id: int) -> str:
    """查询券详情（名称与描述，多级缓存数据）。"""
    r = requests.get(f"{SECKILL_BASE}/voucher/{voucher_id}/detail", timeout=10, proxies=NO_PROXY)
    d = r.json()
    return f"券{voucher_id}详情: {d['data']}" if d["code"] == 0 else f"券{voucher_id}不存在"


@tool
def restock(voucher_id: int, amount: int) -> str:
    """给指定券增加库存（写操作）。amount 为增加数量，须为正整数且不超过 10000。"""
    if not (0 < amount <= 10000):
        return "amount 非法：须为 1~10000"
    r = requests.post(f"{SECKILL_BASE}/voucher/{voucher_id}/addStock",
                      params={"amount": amount}, timeout=10, proxies=NO_PROXY)
    d = r.json()
    return d["msg"] if d["code"] == 0 else "券不存在"


@tool
def patrol_stats() -> str:
    """巡检系统运行状态：订单分布（待支付/已支付/已取消）、Outbox 待投递消息数、死信队列(DLT)堆积量。"""
    r = requests.get(f"{SECKILL_BASE}/admin/stats", timeout=15, proxies=NO_PROXY)
    d = r.json()
    o, p = d["orders"], d["outbox_pending"]
    return (f"订单: 待支付={o['unpaid']} 已支付={o['paid']} 已取消={o['canceled']} | "
            f"Outbox待投递={p} | 死信堆积={d['dlt_total']}")
