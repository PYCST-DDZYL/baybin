r"""
Official-source text: download a page (HTML or PDF), extract its text, cache it.

    python tools\sources.py fetch <url> [<url> ...]   # 下载并缓存，打印缓存文件路径
    python tools\sources.py grep <url> <word> [...]    # 在某个来源里找关键词（看上下文）

verify_rules.py uses the same functions, so a quote that was found while researching is
checked against exactly the same extracted text. Cache: tools\out\sources\.
"""
import hashlib
import io
import os
import re
import sys
import time
import unicodedata
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", "out", "sources")
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/140.0 Safari/537.36")

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def _cache_path(url):
    return os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest()[:16] + ".txt")


def _html_text(raw):
    from lxml import html as lh
    doc = lh.fromstring(raw)
    for bad in doc.xpath("//script|//style|//noscript|//svg"):
        bad.drop_tree()
    # Block-level breaks so list items don't run together.
    for el in doc.iter():
        if el.tag in ("br", "p", "li", "div", "tr", "h1", "h2", "h3", "h4", "h5", "td", "th", "section"):
            el.tail = "\n" + (el.tail or "")
    return doc.text_content()


def _pdf_text(raw):
    import pymupdf
    with pymupdf.open(stream=raw, filetype="pdf") as pdf:
        return "\n".join(page.get_text() for page in pdf)


HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,application/pdf,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Upgrade-Insecure-Requests": "1",
}


def download(url):
    """Some city sites (cupertino.gov, sanjoseca.gov) refuse any non-browser client; for
    those the text is captured in Chrome and saved with save_snapshot() instead."""
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=40) as r:
        raw = r.read()
        ctype = r.headers.get("Content-Type", "")
        encoding = (r.headers.get("Content-Encoding") or "").lower()
    # urllib doesn't decode compressed bodies. web.archive.org "id_" snapshots replay the
    # original response bytes, which are often gzip- or brotli-compressed.
    if encoding in ("gzip", "x-gzip") or raw[:2] == b"\x1f\x8b":
        import gzip
        raw = gzip.decompress(raw)
    elif encoding == "deflate":
        import zlib
        raw = zlib.decompress(raw)
    elif encoding == "br":
        try:
            import brotli
        except ImportError:
            raise RuntimeError("%s is brotli-compressed; pip install brotli" % url)
        raw = brotli.decompress(raw)
    if raw[:4] == b"%PDF" or "pdf" in ctype:
        return _pdf_text(raw)
    return _html_text(raw)


def page_text(url, refresh=False):
    """Extracted text of url, from cache unless refresh."""
    path = _cache_path(url)
    if not refresh and os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return f.read().split("\n", 2)[2]
    text = download(url)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r"\n\s*\n+", "\n", text).strip()
    os.makedirs(CACHE, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("URL: %s\nFETCHED: %s\n%s" % (url, time.strftime("%Y-%m-%d"), text))
    return text


def norm(s):
    """Loose form for quote matching: case, quotes, dashes, whitespace, soft hyphens."""
    s = unicodedata.normalize("NFKC", s).lower()
    s = s.replace("­", "")
    s = re.sub(r"[‘’ʼ`]", "'", s)
    s = re.sub(r"[“”]", '"', s)
    s = re.sub(r"[‐-―−]", "-", s)
    return re.sub(r"\s+", " ", s).strip()


def contains(url, quote):
    return norm(quote) in norm(page_text(url))


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return
    if argv[0] == "fetch":
        for url in argv[1:]:
            try:
                t = page_text(url, refresh=True)
                print("%6d chars  %s\n        -> %s" % (len(t), url, _cache_path(url)))
            except Exception as e:
                print("FAILED %s: %s" % (url, e))
    elif argv[0] == "grep":
        text = page_text(argv[1])
        for word in argv[2:]:
            for m in re.finditer(re.escape(word), text, re.I):
                a, b = max(0, m.start() - 150), min(len(text), m.end() + 150)
                print("[%s] …%s…\n" % (word, text[a:b].replace("\n", " | ")))


if __name__ == "__main__":
    main(sys.argv[1:])
