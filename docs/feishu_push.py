# -*- coding: utf-8 -*-
"""把 markdown 子集灌入飞书 docx：支持 #/##/### 标题、- bullet、普通段落"""
import json, subprocess, time, sys

DOC = "FWifdaXU5oLy03xrn9Nc9gKDnvV"
S = "/Users/a1021501009/.cursor/skills/xieruyi_feishu/scripts/feishu_docs.py"

def api(method, path, body=None):
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
        elif line.startswith("# "):
            blocks.append(block(3, "heading1", line[2:]))
        elif line.startswith("- "):
            blocks.append(block(12, "bullet", line[2:]))
        elif line.startswith("  - "):
            blocks.append(block(12, "bullet", line[4:]))
        else:
            blocks.append(block(2, "text", line))
    return blocks

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
