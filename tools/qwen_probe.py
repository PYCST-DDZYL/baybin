r"""
Time the Qwen vision call from this PC with a real glasses photo.

    python tools\qwen_probe.py [image.jpg] [N=5]

Defaults to tools\out\last.jpg (python tools\probe.py photo makes one).
Reads qwen.apiKey / qwen.baseUrl / qwen.model from local.properties.
Standard library only.
"""
import base64
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROMPT = "Name the main object in this photo in at most five English words. Reply with the name only."

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def local_props():
    props = {}
    path = os.path.join(ROOT, "local.properties")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    props[k.strip()] = v.strip()
    return props


def ask(base, key, model, jpeg):
    body = {
        "model": model,
        "messages": [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}},
            {"type": "text", "text": PROMPT},
        ]}],
        "max_tokens": 20,
        "temperature": 0,
    }
    req = urllib.request.Request(base.rstrip("/") + "/chat/completions",
                                 data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    t = time.perf_counter()
    with urllib.request.urlopen(req, timeout=20) as resp:
        data = json.load(resp)
    ms = (time.perf_counter() - t) * 1000
    return ms, data["choices"][0]["message"]["content"].strip(), data.get("usage", {})


def main(argv):
    props = local_props()
    key = props.get("qwen.apiKey", "")
    if not key:
        sys.exit("local.properties 里没有 qwen.apiKey。照 local.properties.example 填好再跑。")
    base = props.get("qwen.baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    model = props.get("qwen.model", "qwen3-vl-flash")
    image = next((a for a in argv if not a.isdigit()), os.path.join(ROOT, "tools", "out", "last.jpg"))
    n = next((int(a) for a in argv if a.isdigit()), 5)
    jpeg = open(image, "rb").read()
    print("%s @ %s, %s (%d KB), %d calls" % (model, base, os.path.basename(image), len(jpeg) // 1024, n))

    times = []
    for i in range(n):
        try:
            ms, text, usage = ask(base, key, model, jpeg)
        except urllib.error.HTTPError as e:
            sys.exit("HTTP %d: %s" % (e.code, e.read().decode(errors="replace")[:400]))
        except urllib.error.URLError as e:
            sys.exit("连不上 %s：%s" % (base, e.reason))
        times.append(ms)
        print("  #%d %5.0f ms  %-30s tokens in/out %s/%s" % (
            i + 1, ms, text, usage.get("prompt_tokens"), usage.get("completion_tokens")))
    print("median %.0f ms, max %.0f ms" % (statistics.median(times), max(times)))


if __name__ == "__main__":
    main(sys.argv[1:])
