r"""Quick recognition probe: can Qwen pick the right catalog item for a photo of trash?

    python tools\eval_probe.py [--set eval\realworld] [--model qwen3-vl-flash] [--limit N] [--jobs 3] [--degrade]

Test photos: eval\commons_probe\ (one openly licensed Wikimedia Commons photo per item;
source page, author and license for each are in manifest.json). These are clean stock-style
photos, so results are optimistic; the acceptance test uses photos taken with the glasses.

Uses the same catalog (rules/items.json) and rules the app will use. Images are resized
to <=1024px / JPEG q80 like the glasses send. Prints item accuracy, the bin each city
would show (right or wrong), latency, and every miss.
"""
import base64
import io
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request

from PIL import Image

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IMGS = os.path.join(PROJECT, "eval", "commons_probe")
OUT = os.path.join(PROJECT, "tools", "out")


def props():
    out = {}
    for line in open(os.path.join(PROJECT, "local.properties"), encoding="utf-8"):
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def catalog_prompt(items):
    """Same text the phone app sends: rules/prompt.txt with the catalog filled in."""
    lines = ["- %s: %s (%s)" % (it["id"], it["name"], it["hint"]) for it in items]
    with open(os.path.join(PROJECT, "rules", "prompt.txt"), encoding="utf-8") as f:
        return f.read().strip().replace("{catalog}", "\n".join(lines))


# The phone scales every photo down to this long side before calling the model (Classifier.MODEL_SIDE):
# ~400 fewer image tokens than 1024px, measurably faster, same answers on the probe set.
MODEL_SIDE = int(os.environ.get("BAYBIN_MODEL_SIDE", "768"))


def as_glasses_jpeg(path, degrade=False):
    im = Image.open(path).convert("RGB")
    if degrade:
        # Crude stand-in for a glasses snapshot: item ~45% of a 1024x768 frame on a plain
        # background, a bit dim and soft (fixed-focus camera, indoor light).
        from PIL import ImageEnhance, ImageFilter
        frame = Image.new("RGB", (1024, 768), (118, 112, 104))
        im.thumbnail((int(1024 * 0.45), int(768 * 0.55)))
        frame.paste(im, ((1024 - im.width) // 2, (768 - im.height) // 2))
        im = ImageEnhance.Brightness(frame.filter(ImageFilter.GaussianBlur(1.3))).enhance(0.7)
    # Gallery accept path, which is what the phone actually sends: scale to 1024 and
    # write JPEG q80, then scale to MODEL_SIDE and write q80 again. One in-memory
    # resize hides that second encode; a folded milk carton flipped only after it.
    im.thumbnail((1024, 1024))
    mid = io.BytesIO()
    im.save(mid, "JPEG", quality=80)
    im = Image.open(io.BytesIO(mid.getvalue())).convert("RGB")
    im.thumbnail((MODEL_SIDE, MODEL_SIDE))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=80)
    return buf.getvalue()


def ask(base, key, model, jpeg, prompt):
    # The endpoint returns logprobs for the first output token only, so the reply is the bare id.
    body = {"model": model, "max_tokens": 16, "temperature": 0, "logprobs": True, "top_logprobs": 5,
            "messages": [{"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}},
                {"type": "text", "text": prompt}]}]}
    if model.startswith("qwen3.") or model.startswith("qwen3-max"):
        body["enable_thinking"] = False
    req = urllib.request.Request(base.rstrip("/") + "/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    t = time.perf_counter()
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.load(r)
    choice = data["choices"][0]
    first = ((choice.get("logprobs") or {}).get("content") or [None])[0]
    return (time.perf_counter() - t) * 1000, choice["message"]["content"], data.get("usage", {}), first


def parse(text, ids):
    """Bare id expected; tolerate quotes, backticks, a trailing period or an 'id:' prefix."""
    for word in re.findall(r"[a-z][a-z0-9_]*", text.lower()):
        if word in ids:
            return word
    return "unknown"


def first_token(first):
    """(token, p, [(alt_token, p), ...]) from the first-token logprobs, or (None, None, [])."""
    import math
    if not first:
        return None, None, []
    alts = [(a["token"], math.exp(a["logprob"])) for a in first.get("top_logprobs", []) if a["token"] != first["token"]]
    return first["token"], math.exp(first["logprob"]), alts


# Same thresholds as the phone app (Pipeline.P_MIN / P_ALT).
P_MIN, P_ALT = 0.5, 0.25


def load_photos(path):
    """[(file path, expected item)] from either manifest layout: {item: {"file":..}} or {"photos": [{"file","item"}]}."""
    with open(os.path.join(path, "manifest.json"), encoding="utf-8") as f:
        man = json.load(f)
    if "photos" in man:
        return [(os.path.join(path, m["file"]), m["item"]) for m in man["photos"]]
    # "expect" overrides the key when the photo shows something else under today's catalog
    return [(os.path.join(path, m["file"]), m.get("expect", item)) for item, m in sorted(man.items())]


def shown(city, item, p_first=None, alts=()):
    """Line 1 the phone would show — mirrors Pipeline.decide() in the phone app."""
    rules, bins = city["rules"], city["bins"]
    r = rules.get(item) if item != "unknown" else None
    if r is None or r["bin"] == "unknown":
        return "Not sure"
    if p_first is not None and p_first < P_MIN:
        return "Not sure"
    for tok, p_alt in alts:
        prefix = "".join(ch for ch in tok.lower() if ch.isalnum() or ch == "_")
        if not prefix or p_alt < P_ALT or item.startswith(prefix):
            continue  # punctuation, or the chosen id's own prefix ("food" / food_in_container)
        rival = {"unknown"} if "unknown".startswith(prefix) else set()
        rival |= {(rules.get(i) or {}).get("bin", "unknown") for i in rules if i.startswith(prefix)}
        # Mixed prefix is not a bin change: "food" starts food_can (recycling) and also
        # food_in_container (same bin as a greasy pizza box).
        if rival and all(b != r["bin"] for b in rival):
            return "Not sure"
    return bins[r["bin"]]["line1"]


def main(argv):
    from concurrent.futures import ThreadPoolExecutor
    model = argv[argv.index("--model") + 1] if "--model" in argv else None
    limit = int(argv[argv.index("--limit") + 1]) if "--limit" in argv else None
    jobs = int(argv[argv.index("--jobs") + 1]) if "--jobs" in argv else 3
    imgs = os.path.abspath(argv[argv.index("--set") + 1]) if "--set" in argv else IMGS
    p = props()
    model = model or p.get("qwen.model", "qwen3-vl-flash")
    items = json.load(open(os.path.join(PROJECT, "rules", "items.json"), encoding="utf-8"))["items"]
    ids = {it["id"] for it in items}
    cities = {c: json.load(open(os.path.join(PROJECT, "rules", c + ".json"), encoding="utf-8")) for c in ("cupertino", "san_jose")}
    photos = load_photos(imgs)[:limit] if limit else load_photos(imgs)
    prompt = catalog_prompt(items)

    def one(job):
        path, expected = job
        jpeg = as_glasses_jpeg(path, degrade="--degrade" in argv)
        try:
            ms, text, usage, first = ask(p["qwen.baseUrl"], p["qwen.apiKey"], model, jpeg, prompt)
        except urllib.error.HTTPError as e:
            ms, text, usage, first = 0.0, "HTTP %d %s" % (e.code, e.read().decode(errors="replace")[:200]), {}, None
        except (TimeoutError, urllib.error.URLError) as e:  # a slow call counts as a failure, not a crash
            ms, text, usage, first = 30000.0, "TIMEOUT %s" % e, {}, None
        got = parse(text, ids)
        tok, conf, alts = first_token(first)
        row = {"file": os.path.basename(path), "expected": expected, "got": got, "p_first": conf, "first": tok,
               "alts": alts[:3], "ms": round(ms), "raw": text.strip()[:120], "tokens_in": usage.get("prompt_tokens")}
        for c, city in cities.items():
            row[c] = (shown(city, expected), shown(city, got, conf, alts))
        return row

    with ThreadPoolExecutor(max_workers=jobs) as pool:
        rows = list(pool.map(one, photos))
    for row in rows:
        mark = "OK " if row["got"] == row["expected"] else "-- "
        print("%s%-26s %-20s -> %-20s p1=%-5s %5d ms  CUP %s/%s  SJ %s/%s" % (
            mark, row["file"][:26], row["expected"], row["got"], "%.2f" % row["p_first"] if row["p_first"] is not None else "-",
            row["ms"], row["cupertino"][0], row["cupertino"][1], row["san_jose"][0], row["san_jose"][1]))

    n = len(rows)
    times = [r["ms"] for r in rows]
    item_ok = sum(r["got"] == r["expected"] for r in rows)
    print("\nset %s, model %s, %d photos, prompt tokens ~%s" % (os.path.basename(imgs), model, n, rows[0]["tokens_in"] if rows else "?"))
    print("item exactly right      : %d/%d = %.0f%%" % (item_ok, n, 100.0 * item_ok / n))
    for c in cities:
        ok = sum(r[c][0] == r[c][1] for r in rows)
        wrong_bin = sum(r[c][0] != r[c][1] and r[c][1] != "Not sure" for r in rows)
        print("%-10s right line 1   : %d/%d = %.0f%%   (confidently wrong bin: %d)" % (c, ok, n, 100.0 * ok / n, wrong_bin))
    print("latency ms: median %.0f, p90 %.0f, max %.0f" % (
        statistics.median(times), sorted(times)[int(0.9 * (len(times) - 1))], max(times)))
    tag = "%s_%s_%d" % (os.path.basename(imgs), model, MODEL_SIDE) + ("_degraded" if "--degrade" in argv else "")
    os.makedirs(OUT, exist_ok=True)
    json.dump(rows, open(os.path.join(OUT, "eval_probe_%s.json" % tag), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    main(sys.argv[1:])
