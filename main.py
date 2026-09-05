"""入口：加载 .env，交互式 CLI（或 --task 单次执行）。"""
import sys

from dotenv import load_dotenv

load_dotenv()  # 读取 agent/.env

import agents  # noqa: E402  需在 load_dotenv 之后导入


def main():
    if len(sys.argv) > 2 and sys.argv[1] == "--task":
        task = sys.argv[2]
    elif len(sys.argv) > 1:
        task = sys.argv[1]
    else:
        print('用法: python main.py "任务描述"  例: python main.py "看下券7卖得怎么样，余量低于900就补500，顺便巡检系统"')
        return
    report, steps = agents.run(task)
    print("\n=== 执行轨迹 ===")
    for s in steps:
        print("·", s)
    print("\n=== 最终报告 ===")
    print(report)


if __name__ == "__main__":
    main()
