r"""
验收 8：README 有架构图、演示视频位置、测试结果、"为什么用眼镜而不是手机"。

    python tools\accept_8_readme.py

PASS: README.md has all four, the architecture section contains a diagram (a code block
or mermaid block naming both the glasses and the phone), and the test-results section
covers all eight acceptance criteria.
"""
import os
import re

from acceptlib import save, verdict
from adbutil import ROOT


def section(text, *titles):
    """Body of the first '## ' section whose heading contains one of titles."""
    for m in re.finditer(r"^##\s+(.+)$", text, re.M):
        if any(t.lower() in m.group(1).lower() for t in titles):
            nxt = re.search(r"^##\s+", text[m.end():], re.M)
            return text[m.end(): m.end() + nxt.start()] if nxt else text[m.end():]
    return None


def main():
    path = os.path.join(ROOT, "README.md")
    text = open(path, encoding="utf-8").read() if os.path.exists(path) else ""
    arch = section(text, "架构", "Architecture") or ""
    demo = section(text, "演示视频", "Demo") or ""
    tests = section(text, "测试结果", "Test results") or ""
    why = section(text, "为什么用眼镜", "Why glasses") or ""
    checks = {
        "architecture diagram": "```" in arch and ("眼镜" in arch or "Glasses" in arch) and ("手机" in arch or "Phone" in arch),
        "demo video location": bool(re.search(r"(docs/|https?://|\.mp4|\.mov)", demo)),
        "test results for all 8 criteria": all(re.search(r"\|\s*%d\s*\|" % i, tests) for i in range(1, 9)),
        "why glasses instead of phone": len(why.strip()) > 200,
    }
    ok = all(checks.values())
    save("8_readme", {"pass": ok, "checks": checks})
    verdict(ok, "README 内容齐全", ["%-34s %s" % (k, "yes" if v else "MISSING") for k, v in checks.items()])
    return ok


if __name__ == "__main__":
    main()
