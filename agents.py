"""Multi-agent 编排：LangGraph supervisor 模式。

参考：LangGraph 官方 supervisor（条件边分发 + 结果回环）、OpenHands 事件留痕思想。
- supervisor：LLM 结构化路由，决定分派给哪个专项 agent 或收工
- query_agent：只读查询（余量/详情）
- ops_agent：写操作（补货，工具内有额度护栏）
- patrol_agent：系统巡检（订单分布/Outbox/死信）
每个专项完成后回边 supervisor，由其决定继续分派或汇总收工。
"""
import json
from typing import Annotated, Literal, TypedDict

from langchain_openai import ChatOpenAI
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.prebuilt import create_react_agent

from config import GLM_API_KEY, GLM_BASE_URL, GLM_MODEL
from tools import patrol_stats, query_detail, query_stock, restock

llm = ChatOpenAI(model=GLM_MODEL, api_key=GLM_API_KEY, base_url=GLM_BASE_URL, temperature=0)

# ---- 专项 agent（ReAct：LLM 自主决定调哪个工具、调几次）----
query_agent = create_react_agent(
    llm, [query_stock, query_detail],
    prompt="你是券信息查询专员。只做查询，回答要简洁带数字。完成后给出一句话结论。",
)
ops_agent = create_react_agent(
    llm, [restock, query_stock],
    prompt="你是补货运营专员。执行补货前后各查一次余量以核对生效，补货量必须尊重指令要求。",
)
patrol_agent = create_react_agent(
    llm, [patrol_stats],
    prompt="你是系统巡检专员。调用巡检工具获取运行状态，指出异常（如死信堆积>0、Outbox积压>0），正常则汇报健康。",
)

SPECIALISTS = {"query_agent": "查询专员", "ops_agent": "补货专员", "patrol_agent": "巡检专员"}


class State(TypedDict):
    messages: Annotated[list, add_messages]  # supervisor 视角的任务分派与结论
    route: str                               # 当前路由决策
    steps: int                               # 防环护栏：最多 8 跳


def supervisor(state: State):
    """总调度：根据用户任务与已收到的结论，结构化决定下一步。"""
    msgs = [
        {"role": "system", "content":
            "你是秒杀系统运维总调度。把用户任务拆解并分派给专项专员："
            "query_agent=查余量/详情，ops_agent=补货，patrol_agent=巡检系统状态。"
            "规则：1.每条[专员结论]代表该子任务已完成，绝不要重复分派已完成的子任务；"
            "2.当结论足以覆盖用户任务的全部要求时，立即路由 FINISH 并亲自写出面向用户的中文总结报告（含数字）；"
            "3.只输出 JSON: {\"next\": \"query_agent|ops_agent|patrol_agent|FINISH\", \"instruction\": \"给该专员的一句话任务\"}"},
    ] + state["messages"]
    raw = llm.invoke(msgs).content
    try:
        decision = json.loads(raw[raw.index("{"): raw.rindex("}") + 1])
    except Exception:
        decision = {"next": "FINISH", "instruction": "汇总已有信息"}
    nxt = decision.get("next", "FINISH")
    if nxt not in SPECIALISTS and nxt != "FINISH":
        nxt = "FINISH"
    instruction = decision.get("instruction", "")
    msgs = state["messages"] + ([{"role": "user", "content": f"[调度指令→{SPECIALISTS[nxt]}] {instruction}"}] if nxt in SPECIALISTS else [])
    return {"route": nxt, "messages": msgs}


def run_specialist(state: State):
    name = state["route"]
    agent = {"query_agent": query_agent, "ops_agent": ops_agent, "patrol_agent": patrol_agent}[name]
    task = state["messages"][-1].content
    result = agent.invoke({"messages": [{"role": "user", "content": task}]})
    answer = result["messages"][-1].content
    return {"messages": state["messages"] + [{"role": "assistant", "content": f"[{SPECIALISTS[name]}结论] {answer}"}],
            "steps": state.get("steps", 0) + 1}


def route(state: State) -> Literal["query_agent", "ops_agent", "patrol_agent", "report"]:
    if state.get("steps", 0) >= 8:  # 防环护栏
        return "report"
    return state["route"] if state["route"] in SPECIALISTS else "report"


def report(state: State):
    """收工：总调度综合全部专员结论，写出最终报告。"""
    raw = llm.invoke([
        {"role": "system", "content": "你是秒杀系统运维总调度。综合下面的专员结论，写一份简洁的中文总结报告（分点、含数字、直接回答用户任务）。"},
    ] + [m for m in state["messages"] if m.content.startswith("[")]).content
    return {"messages": state["messages"] + [{"role": "assistant", "content": raw}]}


graph = StateGraph(State)
graph.add_node("supervisor", supervisor)
graph.add_node("specialist", run_specialist)
graph.add_node("report", report)
graph.add_edge(START, "supervisor")
graph.add_conditional_edges("supervisor", route, {"query_agent": "specialist",
                                                  "ops_agent": "specialist",
                                                  "patrol_agent": "specialist",
                                                  "report": "report"})
graph.add_edge("specialist", "supervisor")  # 结果回环，supervisor 决定继续或收工
graph.add_edge("report", END)
app = graph.compile()


def run(task: str) -> str:
    """执行一次多 agent 协作，返回最终报告与执行轨迹。"""
    state = app.invoke({"messages": [{"role": "user", "content": task}], "route": "", "steps": 0})
    steps = [m.content for m in state["messages"] if m.content.startswith("[")]
    return state["messages"][-1].content, steps
