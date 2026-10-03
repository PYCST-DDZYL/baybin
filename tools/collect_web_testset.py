r"""
Real-world test photos from openly licensed images on the web (Wikimedia Commons + Openverse).

    python tools\collect_web_testset.py search     # candidates for every query below -> tools\out\web_candidates\
    python tools\collect_web_testset.py pick c12=food_in_container c40=plastic_takeout ...
                                                   # download picks (≤1024px) into eval\realworld\ with label + attribution
    python tools\collect_web_testset.py list       # what eval\realworld\manifest.json holds

Only licenses that allow redistribution are used (CC0, public domain, CC BY, CC BY-SA; no NC/ND).
Every photo is looked at and labelled by a person (candidate contact sheets); search results
are only suggestions. The manifest keeps source page, author and license for each file.
"""
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "out", "web_candidates")
SET = os.path.join(ROOT, "eval", "realworld")
UA = "BayBinTestSet/0.2 (personal recycling-app test set; python-urllib)"
PER_SOURCE = 6
TILE = 150

# (group, query). Groups only decide which contact sheet a candidate lands on.
QUERIES = [
    ("food_in_box", "chinese takeout food container"),
    ("food_in_box", "takeaway food container"),
    ("food_in_box", "take out food box"),
    ("food_in_box", "styrofoam food container"),
    ("food_in_box", "noodle soup takeaway"),
    ("food_in_box", "cup noodles"),
    ("food_in_box", "leftovers container"),
    ("food_in_box", "bento box takeout"),
    ("food_in_box2", "lunch box rice plastic container"),
    ("food_in_box2", "salad plastic container"),
    ("food_in_box2", "roast goose rice"),
    ("food_in_box2", "rice noodle soup"),
    ("empty", "empty takeout container"),
    ("empty", "empty plastic container"),
    ("empty", "empty pizza box"),
    ("empty", "empty yogurt cup"),
    ("scraps", "food waste"),
    ("scraps", "banana peel"),
    ("scraps", "apple core"),
    ("scraps", "egg shells"),
    ("trash", "crushed can"),
    ("trash", "crumpled paper"),
    ("trash", "disposable coffee cup"),
    ("trash", "plastic bag litter"),
    ("trash2", "chips bag"),
    ("trash2", "plastic bottle trash"),
    ("trash2", "bubble tea cup"),
    ("trash2", "used napkin"),
]


def get(url, tries=5, timeout=60):
    """GET with backoff for HTTP 429 (both APIs rate-limit anonymous clients)."""
    for attempt in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=timeout).read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == tries - 1:
                raise
            wait = int(e.headers.get("Retry-After") or 0) or 15 * (attempt + 1)
            print("    429, waiting %ds" % wait)
            time.sleep(wait)


def commons(query, n):
    q = urllib.parse.urlencode({
        "action": "query", "format": "json", "generator": "search", "gsrsearch": query + " filetype:bitmap",
        "gsrnamespace": 6, "gsrlimit": 15, "prop": "imageinfo", "iiprop": "url|mime|extmetadata", "iiurlwidth": TILE * 2})
    data = json.loads(get("https://commons.wikimedia.org/w/api.php?" + q))
    out = []
    for p in sorted(data.get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 0)):
        ii = (p.get("imageinfo") or [{}])[0]
        meta = ii.get("extmetadata", {})
        lic = meta.get("LicenseShortName", {}).get("value", "")
        low = lic.lower()
        if ii.get("mime") not in ("image/jpeg", "image/png") or "nc" in low.split("-") or "-nd" in low:
            continue
        if not any(f in low for f in ("cc0", "public domain", "cc by", "cc-by", "pd")):
            continue
        out.append({"src": "commons", "title": p["title"], "page": ii.get("descriptionurl"), "small": ii.get("thumburl"),
                    "license": lic, "creator": re.sub(r"<[^>]+>", "", meta.get("Artist", {}).get("value", ""))[:120]})
        if len(out) >= n:
            break
    return out


def flickr_size(url, suffix):
    """live.staticflickr.com/…/id_secret[_x].jpg -> same photo at another size (n=320px, b=1024px)."""
    return re.sub(r"(_[a-z])?\.jpg$", "_%s.jpg" % suffix, url)


def openverse(query, n):
    q = urllib.parse.urlencode({"q": query, "license": "by,by-sa,cc0,pdm", "page_size": 20})
    data = json.loads(get("https://api.openverse.org/v1/images/?" + q))
    out = []
    for x in data.get("results", []):
        url = x.get("url") or ""
        if x.get("source") == "wikimedia":
            continue  # Commons results come from the Commons search
        small = flickr_size(url, "n") if "staticflickr.com" in url else url
        lic = x.get("license") or ""
        lic = lic.upper() if lic in ("cc0", "pdm") else ("CC %s %s" % (lic.upper(), x.get("license_version") or "")).strip()
        out.append({"src": "openverse/" + (x.get("source") or "?"), "title": x.get("title") or "", "page": x.get("foreign_landing_url"),
                    "small": small, "full": flickr_size(url, "b") if "staticflickr.com" in url else url,
                    "license": lic, "creator": (x.get("creator") or "")[:120]})
        if len(out) >= n:
            break
    return out


def search():
    os.makedirs(OUT, exist_ok=True)
    cands, seen = [], set()
    for group, query in QUERIES:
        found = []
        for fn in (commons, openverse):
            try:
                found += fn(query, PER_SOURCE)
            except Exception as e:
                print("  %s %r failed: %s" % (fn.__name__, query, e))
            time.sleep(3.5)  # Openverse: 20 requests/minute anonymous
        for c in found:
            if c["page"] in seen:
                continue
            seen.add(c["page"])
            c.update({"id": "c%d" % (len(cands) + 1), "group": group, "query": query})
            cands.append(c)
        print("%-34s %d candidates" % (query, len(found)))
    with open(os.path.join(OUT, "candidates.json"), "w", encoding="utf-8") as f:
        json.dump(cands, f, ensure_ascii=False, indent=1)
    sheets(cands)


def sheets(cands):
    cols = 8
    groups = []
    for c in cands:
        if c["group"] not in groups:
            groups.append(c["group"])
    for g in groups:
        items = [c for c in cands if c["group"] == g]
        rows = (len(items) + cols - 1) // cols
        sheet = Image.new("RGB", (cols * TILE, rows * (TILE + 14)), "white")
        draw = ImageDraw.Draw(sheet)
        for i, c in enumerate(items):
            x, y = (i % cols) * TILE, (i // cols) * (TILE + 14)
            try:
                im = Image.open(io.BytesIO(get(c["small"], timeout=40))).convert("RGB")
                im.thumbnail((TILE - 4, TILE - 4))
                sheet.paste(im, (x + 2, y + 2))
            except Exception as e:
                draw.text((x + 4, y + 40), "fetch failed", fill="red")
            draw.text((x + 3, y + TILE), c["id"], fill="black")
            time.sleep(0.3)
        path = os.path.join(OUT, "sheet_%s.png" % g)
        sheet.save(path)
        print("-> %s (%d)" % (path, len(items)))


def pick(args):
    with open(os.path.join(OUT, "candidates.json"), encoding="utf-8") as f:
        cands = {c["id"]: c for c in json.load(f)}
    os.makedirs(SET, exist_ok=True)
    man_path = os.path.join(SET, "manifest.json")
    man = json.load(open(man_path, encoding="utf-8")) if os.path.exists(man_path) else {
        "note": "Openly licensed real-world photos (Wikimedia Commons, Openverse/Flickr), labelled by hand. "
                "item = catalog id the app should answer.", "photos": []}
    have = {p["page"] for p in man["photos"]}
    for a in args:
        cid, item = a.split("=")
        c = cands[cid]
        if c["page"] in have:
            print("%s already in the set" % cid)
            continue
        if c["src"] == "commons":
            q = urllib.parse.urlencode({"action": "query", "format": "json", "titles": c["title"], "prop": "imageinfo",
                                        "iiprop": "url", "iiurlwidth": 1024})
            info = json.loads(get("https://commons.wikimedia.org/w/api.php?" + q))
            url = next(iter(info["query"]["pages"].values()))["imageinfo"][0]["thumburl"]
        else:
            url = c["full"]
        im = Image.open(io.BytesIO(get(url))).convert("RGB")
        im.thumbnail((1024, 1024))
        n = sum(p["item"] == item for p in man["photos"]) + 1
        name = "%s__%d.jpg" % (item, n)
        im.save(os.path.join(SET, name), "JPEG", quality=88)
        man["photos"].append({"file": name, "item": item, "title": c["title"], "page": c["page"], "license": c["license"],
                              "creator": c["creator"], "source": c["src"], "query": c["query"]})
        have.add(c["page"])
        with open(man_path, "w", encoding="utf-8") as f:
            json.dump(man, f, ensure_ascii=False, indent=1)
        print("%-5s -> %-28s %s (%s)" % (cid, name, c["title"][:50], c["license"]))
        time.sleep(1.5)


def show_list():
    man = json.load(open(os.path.join(SET, "manifest.json"), encoding="utf-8"))
    counts = {}
    for p in man["photos"]:
        counts[p["item"]] = counts.get(p["item"], 0) + 1
    print("%d photos, %d items" % (len(man["photos"]), len(counts)))
    for k, v in sorted(counts.items()):
        print("  %-22s %d" % (k, v))


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "search":
        search()
    elif cmd == "pick":
        pick(sys.argv[2:])
    elif cmd == "list":
        show_list()
    else:
        print(__doc__)
