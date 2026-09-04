"""配置：从环境变量读取（见 .env），GLM 走 OpenAI 兼容端点。"""
import os

GLM_API_KEY = os.environ.get("GLM_API_KEY", "")
GLM_MODEL = os.environ.get("GLM_MODEL", "glm-4.5-flash")  # 充值后改 glm-5.3-flash 即可
GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"

SECKILL_BASE = os.environ.get("SECKILL_BASE", "http://localhost:8080")
