r"""
Check the rules data against the official pages it cites.

    python tools\verify_rules.py            # quotes checked against cached page text (tools\out\sources)
    python tools\verify_rules.py --live     # re-download every source first (detects pages that changed)

For every city file in rules\ (all *.json except items.json):
  * every catalog item has a rule; "unknown" is allowed but needs a note
  * bin is one of the city's bins; reason is non-empty and at most 15 words
  * the source exists, its URL is on one of the city's official domains
  * the quote is at least 20 characters and appears in that source's text
  * if the source declares "sections" (list-style guides), the quote sits under the
    heading for the rule's bin, not merely somewhere on the page; "only_bins" limits a
    single-topic page (e.g. a hazardous-waste page) to the bins it can speak for
  * if the city declares "page_disposal" (one page per item), the page's own disposal
    heading matches the rule's bin, unless the rule carries a written "exception"

Exit code 0 only if everything passes. The "quote appears on the page" check is what keeps
the table from being written from common sense: every bin must point at an official sentence.
"""
import glob
import json
import os
import re
import sys
from urllib.parse import urlparse

from sources import ROOT, contains, norm, page_text

RULES = os.path.join(ROOT, "rules")
MAX_REASON_WORDS = 15
MIN_QUOTE_CHARS = 20


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


ARCHIVE = re.compile(r"^https?://web\.archive\.org/web/\d+(?:id_)?/(https?://.+)$")


def official_url(url):
    """The page a web.archive.org snapshot is a copy of; other URLs unchanged.

    Some city sites (cupertino.gov, sanjoseca.gov) refuse scripted downloads, so their
    pages are cited through archive.org snapshots, which serve the original bytes."""
    m = ARCHIVE.match(url)
    return m.group(1) if m else url


def _as_list(v):
    return v if isinstance(v, list) else [v]


def _find_line(lines, spec):
    """Index of a line exactly equal to the heading. "Heading#2" = its 2nd occurrence
    (a site menu can repeat a heading before the real one)."""
    name, _, nth = spec.partition("#")
    hits = [i for i, line in enumerate(lines) if line == name]
    n = int(nth) if nth else 1
    return hits[n - 1] if len(hits) >= n else None


def section_regions(text, src, bin_):
    """Text under each of the bin's headings, up to the next declared heading or boundary.
    Returns (regions, missing_heading)."""
    lines = [l.strip() for l in text.split("\n")]
    sections = src["sections"]
    stops_specs = [s for v in sections.values() for s in _as_list(v)] + src.get("boundaries", [])
    stops = sorted(i for i in (_find_line(lines, s) for s in stops_specs) if i is not None)
    regions = []
    for spec in _as_list(sections[bin_]):
        start = _find_line(lines, spec)
        if start is None:
            return None, spec
        end = next((i for i in stops if i > start), len(lines))
        regions.append("\n".join(lines[start:end]))
    return regions, None


def page_bins(text, cfg):
    """Bins named by the disposal headings at the top of a one-item page."""
    starts = list(re.finditer(cfg["content_after"], text))
    body = text[starts[-1].end():] if starts else text
    top = [l.strip() for l in body.split("\n") if l.strip()][:cfg["lines"]]
    return {b for h, b in cfg["headings"].items() if any(h in l for l in top)}


def check_city(path, items, live):
    city = load(path)
    name = city.get("name", os.path.basename(path))
    bins = set(city["bins"])
    domains = city["official_domains"]
    sources = city["sources"]
    rules = city["rules"]
    errors = []

    for sid, src in sources.items():
        for key in ("url", "live_url"):
            if key not in src:
                continue
            host = urlparse(official_url(src[key])).hostname or ""
            if not any(host == d or host.endswith("." + d) for d in domains):
                errors.append("source %s: %s %s is not on %s" % (sid, key, host, domains))
        if live:
            try:
                page_text(src["url"], refresh=True)
            except Exception as e:
                errors.append("source %s: download failed: %s" % (sid, e))

    for item in items:
        if item not in rules:
            errors.append("%s: no rule (use bin 'unknown' with a note if there is no official answer)" % item)
    for item in rules:
        if item not in items:
            errors.append("%s: rule for an item that is not in items.json" % item)

    counts = {}
    for item, rule in rules.items():
        b = rule.get("bin")
        counts[b] = counts.get(b, 0) + 1
        if b == "unknown":
            if not rule.get("note"):
                errors.append("%s: unknown without a note saying what was checked" % item)
            continue
        if b not in bins:
            errors.append("%s: bin '%s' not in %s" % (item, b, sorted(bins)))
        reason = rule.get("reason", "").strip()
        words = len(reason.split())
        if not reason or words > MAX_REASON_WORDS:
            errors.append("%s: reason has %d words (max %d)" % (item, words, MAX_REASON_WORDS))
        sid = rule.get("source")
        if sid not in sources:
            errors.append("%s: unknown source '%s'" % (item, sid))
            continue
        quote = rule.get("quote", "")
        if len(quote) < MIN_QUOTE_CHARS:
            errors.append("%s: quote too short to be evidence: %r" % (item, quote))
            continue
        src = sources[sid]
        try:
            text = page_text(src["url"])
        except Exception as e:
            errors.append("%s: could not read %s: %s" % (item, src["url"], e))
            continue
        if not contains(src["url"], quote):
            errors.append("%s: quote not found on %s: %r" % (item, src["url"], quote))
            continue

        if "only_bins" in src and b not in src["only_bins"]:
            errors.append("%s: source %s can only support %s, rule says %s" % (item, sid, src["only_bins"], b))

        if b in src.get("sections", {}) and not rule.get("exception"):
            regions, missing = section_regions(text, src, b)
            if regions is None:
                errors.append("%s: heading %r not found on %s (page changed?)" % (item, missing, src["url"]))
            elif not any(norm(quote) in norm(r) for r in regions):
                errors.append("%s: quote is on the page but not under %s (add an 'exception' if deliberate)"
                              % (item, _as_list(src["sections"][b])))

        cfg = city.get("page_disposal")
        if cfg:
            found = page_bins(text, cfg)
            if b not in found and not rule.get("exception"):
                errors.append("%s: page heading says %s, rule says %s (add an 'exception' if deliberate)"
                              % (item, sorted(found) or "nothing", b))

        # Extra evidence for what the reason tells people to do (e.g. "scrape the food out first"):
        # each must be an official sentence too, but it doesn't decide the bin.
        for extra in rule.get("also", []):
            esid, equote = extra.get("source"), extra.get("quote", "")
            if esid not in sources:
                errors.append("%s: 'also' cites unknown source '%s'" % (item, esid))
            elif len(equote) < MIN_QUOTE_CHARS:
                errors.append("%s: 'also' quote too short to be evidence: %r" % (item, equote))
            elif not contains(sources[esid]["url"], equote):
                errors.append("%s: 'also' quote not found on %s: %r" % (item, sources[esid]["url"], equote))

    summary = ", ".join("%s %d" % (b, n) for b, n in sorted(counts.items()))
    return name, len(rules), summary, errors


def main(argv):
    live = "--live" in argv
    catalog = load(os.path.join(RULES, "items.json"))
    items = [i["id"] for i in catalog["items"]]
    dupes = {i for i in items if items.count(i) > 1}
    failed = False
    if dupes:
        print("items.json: duplicate ids %s" % sorted(dupes))
        failed = True
    print("catalog: %d items" % len(items))

    for path in sorted(glob.glob(os.path.join(RULES, "*.json"))):
        if os.path.basename(path) == "items.json":
            continue
        name, n, summary, errors = check_city(path, items, live)
        status = "OK" if not errors else "%d problem(s)" % len(errors)
        print("%-10s %3d rules  [%s]  %s" % (name, n, summary, status))
        for e in errors:
            print("    - " + e)
        failed = failed or bool(errors)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    main(sys.argv[1:])
