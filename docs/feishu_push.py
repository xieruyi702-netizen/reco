# -*- coding: utf-8 -*-
"""把 markdown 子集灌入飞书 docx：支持 #/##/### 标题、- bullet、普通段落"""
import json, subprocess, time, sys

DOC = "FWifdaXU5oLy03xrn9Nc9gKDnvV"
S = "/Users/a1021501009/.cursor/skills/xieruyi_feishu/scripts/feishu_docs.py"

sys.path.insert(0, "/Users/a1021501009/.cursor/skills/xieruyi_feishu/scripts")
import feishu_docs as fd

def token():
    return fd.get_tenant_token(fd.load_credentials())

def api(method, path, body=None):
    if method == "DELETE":
        tok = token()
        r = subprocess.run(["curl", "-s", "-X", "DELETE",
            f"https://open.feishu.cn/open-apis{path}",
            "-H", f"Authorization: Bearer {tok}",
            "-H", "Content-Type: application/json; charset=utf-8",
            "-d", json.dumps(body or {}, ensure_ascii=False)], capture_output=True, text=True)
        try:
            return json.loads(r.stdout)
        except Exception:
            print("DEL ERR:", r.stdout[:300]); sys.exit(1)
    sub = "raw-post" if method == "POST" else ("raw-patch" if method == "PATCH" else "raw-get")
    cmd = ["python3", S, sub, "--path", path]
    if body is not None:
        cmd += ["--json", json.dumps(body, ensure_ascii=False)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    out = r.stdout.strip()
    try:
        return json.loads(out)
    except Exception:
        print("API ERR:", out[:500], r.stderr[:300]); sys.exit(1)

def block(btype, key, text):
    return {"block_type": btype, key: {"elements": [{"text_run": {"content": text}}]}}

def md_to_blocks(md):
    blocks = []
    for line in md.split("\n"):
        line = line.rstrip()
        if not line.strip():
            continue
        if line.startswith("### "):
            blocks.append(block(5, "heading3", line[4:]))
        elif line.startswith("## "):
            blocks.append(block(4, "heading2", line[3:]))
        elif line == "---":
            blocks.append({"block_type": 22, "divider": {}})
        elif line.startswith("# "):
            blocks.append(block(3, "heading1", line[2:]))
        elif line.startswith("- "):
            blocks.append(block(12, "bullet", line[2:]))
        elif line.startswith("  - "):
            blocks.append(block(12, "bullet", line[4:]))
        else:
            blocks.append(block(2, "text", line))
    return blocks

# 清空旧内容（batch_delete 全部 children）
r = api("GET", f"/docx/v1/documents/{DOC}/blocks/{DOC}/children?page_size=500")
items = r.get("data", {}).get("items") or []
if items:
    for i in range(0, len(items), 400):
        chunk = items[i:i+400]
        r2 = api("DELETE", f"/docx/v1/documents/{DOC}/blocks/{DOC}/children/batch_delete?document_revision_id=-1",
                 {"start_index": i, "end_index": i + len(chunk)})
        if r2.get("code") not in (0, None):
            print("DEL ERR:", json.dumps(r2, ensure_ascii=False)[:300]); sys.exit(1)
        time.sleep(0.4)
    print("cleared", len(items))

md = open(sys.argv[1], encoding="utf-8").read()
blocks = md_to_blocks(md)
print("total blocks:", len(blocks))

# 分批 POST（每批 25 块，限频 3/s）
PATH = f"/docx/v1/documents/{DOC}/blocks/{DOC}/children?document_revision_id=-1"
for i in range(0, len(blocks), 25):
    batch = blocks[i:i+25]
    r = api("POST", PATH, {"children": batch})
    if r.get("code") not in (0, None):
        print("POST ERR:", json.dumps(r, ensure_ascii=False)[:500]); sys.exit(1)
    print(f"posted {min(i+25,len(blocks))}/{len(blocks)}")
    time.sleep(0.4)
print("DONE")
