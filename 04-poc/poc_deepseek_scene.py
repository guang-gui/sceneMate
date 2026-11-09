#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
SceneMate x DeepSeek 视觉可行性验证（POC）

用法:
    set DEEPSEEK_API_KEY=sk-xxx
    python poc_deepseek_scene.py <图片路径> [--repeat 3] [--detail low] [--json-out result.json]

设计约束（与架构文档 ADR-001 / ADR-013 对齐）:
  - 模拟 App 上传: 长边 720px, JPEG q=70
  - 必须关闭思考模式, 否则延迟不可控
  - 强制 JSON 输出 + 本地 schema 校验（LLM 输出不可信）
"""

import argparse
import base64
import io
import json
import os
import re
import sys
import time
from datetime import datetime, timedelta, timezone

import requests
from PIL import Image

API_URL = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-flash"

SYSTEM_PROMPT = """你是 SceneMate 取景助手的场景分析模块。你只输出 JSON，不输出任何解释性文字。

任务：根据一张手机取景画面（可能有人物）和端侧上下文，判断场景、光线、主体，并给出最多 1 条可执行指令。

硬规则：
1. instruction.text 只能包含「方向 + 距离/量词」，禁止出现形容词或模糊词：
   构图、光线、氛围、感觉、注意、尝试、适当、一些、更好、美。
2. actor 只能是 "subject"（被摄者，即画面里的人）或 "photographer"（拿手机的人）。
   默认 subject。文案示例："让她后退半步" / "让她向左 0.6m" / "你蹲低 20cm"。
3. confidence 为 0-1 的实数。tier 由置信度决定：>=0.85 strong，0.70-0.85 weak，<0.70 hint。
4. tier 为 hint 时，params 全部为 null，且不得给出参数建议。
5. 逆光/暗光（画面主体欠曝或 lux 很低）时，risk.low_light 置 true，
   advice 必须诚实提示「建议改用原生相机夜景」，不得承诺画质。
6. 参数只能是建议值：iso 100-6400，shutter 为 "1/xxx" 字符串，ev -2.0~+2.0，wb_k 2500-8000。
7. 无法判断时，宁可给 hint 和 null，也不要编造。

只输出如下 JSON 结构，不要 markdown 代码块：
{
  "scene": {"label": "英文短标签", "confidence": 0.0},
  "lighting": {"label": "backlit|frontlit|flat|low_light|mixed", "confidence": 0.0, "lux_estimate": 0},
  "subject": {"label": "person|pet|none|group", "count": 0, "confidence": 0.0},
  "tier": "strong|weak|hint",
  "instruction": {"actor": "subject|photographer", "direction": "back|forward|left|right|lower|higher|none", "magnitude": "half_step|one_step|0.3m|0.6m|1.0m|20cm|40cm|none", "text": "中文指令"},
  "params": {"iso": null, "shutter": null, "ev": null, "wb_k": null, "reason": ""},
  "explain": {"before": "", "after": "", "mechanism": "", "benefit": ""},
  "risk": {"low_light": false, "advice": ""}
}"""

FORBIDDEN = ["构图", "光线", "氛围", "感觉", "注意", "尝试", "适当", "一些", "更好", "美"]
REQUIRED = ["scene", "lighting", "subject", "tier", "instruction", "params", "explain", "risk"]


def now_beijing():
    return datetime.now(timezone(timedelta(hours=8)))


def is_peak(dt):
    """高峰时段: 周一至周五 9:00-12:00, 14:00-18:00 (北京时间)"""
    if dt.weekday() >= 5:
        return False
    h = dt.hour
    return (9 <= h < 12) or (14 <= h < 18)


def prepare_image(path, long_edge=720, quality=70):
    img = Image.open(path).convert("RGB")
    w, h = img.size
    scale = long_edge / max(w, h)
    if scale < 1:
        img = img.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality, optimize=True)
    raw = buf.getvalue()
    return base64.b64encode(raw).decode("ascii"), len(raw), img.size


def call_api(b64, size, context, detail):
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": context},
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": "data:image/jpeg;base64," + b64,
                            "detail": detail,
                        },
                    },
                ],
            },
        ],
        "response_format": {"type": "json_object"},
        "max_tokens": 800,
        "temperature": 0.0,
        "thinking": {"type": "disabled"},
    }
    t0 = time.perf_counter()
    resp = requests.post(
        API_URL,
        headers={
            "Authorization": "Bearer " + os.environ["DEEPSEEK_API_KEY"],
            "Content-Type": "application/json",
        },
        json=payload,
        timeout=60,
    )
    latency_ms = int((time.perf_counter() - t0) * 1000)
    if resp.status_code != 200:
        raise RuntimeError("HTTP %s: %s" % (resp.status_code, resp.text[:400]))
    return resp.json(), latency_ms


def strip_fence(text):
    text = text.strip()
    m = re.match(r"^```(?:json)?\s*(.*?)\s*```$", text, re.S)
    return m.group(1) if m else text


def validate(obj, context):
    issues = []
    for k in REQUIRED:
        if k not in obj:
            issues.append("missing_key:%s" % k)

    inst = obj.get("instruction") or {}
    text = inst.get("text", "")
    for w in FORBIDDEN:
        if w in text:
            issues.append("forbidden_word:%s" % w)
    if inst.get("actor") not in ("subject", "photographer"):
        issues.append("bad_actor:%s" % inst.get("actor"))

    tier = obj.get("tier")
    if tier not in ("strong", "weak", "hint"):
        issues.append("bad_tier:%s" % tier)
    p = obj.get("params") or {}
    if not isinstance(p, dict):
        issues.append("bad_params_type")
        p = {}
    if tier == "hint" and any(p.get(k) is not None for k in ("iso", "shutter", "ev", "wb_k")):
        issues.append("hint_but_params_present")

    scene = obj.get("scene") or {}
    c = scene.get("confidence")
    if not isinstance(c, (int, float)) or not (0 <= c <= 1):
        issues.append("bad_confidence:%s" % c)

    if p.get("iso") is not None and not (100 <= p["iso"] <= 6400):
        issues.append("iso_out_of_range:%s" % p["iso"])
    if p.get("ev") is not None and not (-2.0 <= p["ev"] <= 2.0):
        issues.append("ev_out_of_range:%s" % p["ev"])
    if p.get("wb_k") is not None and not (2500 <= p["wb_k"] <= 8000):
        issues.append("wb_out_of_range:%s" % p["wb_k"])
    return issues


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--detail", default="low", choices=["low", "high", "original", "auto"])
    ap.add_argument("--json-out", default=None)
    args = ap.parse_args()

    if not os.environ.get("DEEPSEEK_API_KEY"):
        sys.exit("请先设置 DEEPSEEK_API_KEY 环境变量")

    b64, nbytes, size = prepare_image(args.image)
    print("图片: %s  缩放后: %sx%s  JPEG: %.1f KB" % (args.image, size[0], size[1], nbytes / 1024))

    context = json.dumps({
        "task": "分析这张取景画面，输出 JSON",
        "device_context": {
            "platform": "ios",
            "iso": 800,
            "shutter": "1/60",
            "face_count": 1,
            "frame_height_ratio": 0.41,
            "ambient_lux": 320,
            "distance_sigma_m": 0.31,
            "height_calibrated": False,
        },
        "note": "height_calibrated=false，不要输出绝对米数，只给相对位移或量词",
    }, ensure_ascii=False)

    peak = is_peak(now_beijing())
    print("时段: %s (%s价)" % ("高峰" if peak else "空闲", "高峰" if peak else "空闲"))

    results = []
    for i in range(args.repeat):
        try:
            data, latency = call_api(b64, size, context, args.detail)
        except Exception as e:
            print("调用失败: %s" % e)
            sys.exit(1)

        usage = data.get("usage", {})
        content = strip_fence(data["choices"][0]["message"]["content"])
        try:
            obj = json.loads(content)
        except Exception:
            print("JSON 解析失败，原始输出:\n%s" % content[:800])
            sys.exit(1)

        issues = validate(obj, context)
        in_tok = usage.get("prompt_tokens", 0)
        out_tok = usage.get("completion_tokens", 0)
        price_in = 2.0 if peak else 1.0
        price_out = 8.0 if peak else 4.0
        cost = in_tok / 1e6 * price_in + out_tok / 1e6 * price_out

        print("\n--- 第 %d 次 ---" % (i + 1))
        print("延迟: %d ms   输入 %d tok / 输出 %d tok   成本 ¥%.4f"
              % (latency, in_tok, out_tok, cost))
        print("校验: %s" % ("通过" if not issues else "问题 " + ", ".join(issues)))
        print(json.dumps(obj, ensure_ascii=False, indent=2))

        results.append({"latency_ms": latency, "input_tokens": in_tok,
                        "output_tokens": out_tok, "cost_cny": round(cost, 5),
                        "issues": issues, "output": obj})

    if args.repeat > 1:
        lat = sorted(r["latency_ms"] for r in results)
        p50 = lat[len(lat) // 2]
        p90 = lat[min(len(lat) - 1, int(len(lat) * 0.9))]
        print("\n=== 汇总 (n=%d) 延迟 P50 %d ms / P90 %d ms ===" % (len(lat), p50, p90))

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        print("已写入 %s" % args.json_out)


if __name__ == "__main__":
    main()
