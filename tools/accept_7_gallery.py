r"""
验收 7：手机相册测试模式（不用眼镜）。

    python tools\accept_7_gallery.py

With the glasses link switched off, photos go through the phone app's gallery mode: the
same code path as the 「相册测试」 button after a photo is picked (scaled to what the
glasses would send, then the normal pipeline). The test photos are copied into the app's
private folder (run-as, nothing in shared storage) and removed afterwards.

Also checks the button itself: it is on the main screen and opens the system photo
picker (closed again with Back; no screenshot is taken, the picker shows personal photos).

PASS: every photo answered with the line 1 the official rules give for it, the answer
appears on the phone screen, and the button opens the picker.
"""
import json
import os
import re
import time

from acceptlib import (both, clear_private, classify_private, expected_line1, load_rules, push_private, save, set_city,
                       verdict)
from adbutil import PHONE_PKG, ROOT

SAMPLE = ["plastic_bottle", "battery", "pizza_box_greasy", "food_scraps", "carton", "snack_wrapper"]


def main():
    g, p = both()
    p.shell("input keyevent KEYCODE_WAKEUP")
    p.shell("am start -n %s/.MainActivity" % PHONE_PKG)
    p.shell("am broadcast -a com.baybin.phone.DISCONNECT -p %s --ez stay true" % PHONE_PKG)
    time.sleep(2)
    rules = load_rules()
    with open(os.path.join(ROOT, "eval", "commons_probe", "manifest.json"), encoding="utf-8") as f:
        man = json.load(f)
    rows = []
    clear_private(p)
    try:
        set_city(p, "cupertino")
        for item in SAMPLE:
            name = "g_%s.jpg" % item
            push_private(p, os.path.join(ROOT, "eval", "commons_probe", man[item]["file"]), name)
            r = classify_private(p, name) or {"line1": "(no answer)", "item": "-", "model": "-1", "total": "-1"}
            want = expected_line1(rules, "cupertino", item)
            rows.append({"item": item, "got_item": r["item"], "want": want, "got": r["line1"], "line2": r.get("line2"),
                         "ok": r["line1"] == want, "total_ms": int(r["total"])})
            print("  %s %-18s -> %-18s %-28s %s ms" % ("OK" if rows[-1]["ok"] else "--", item, r["item"], r["line1"],
                                                     r["total"]))
        locked = "isKeyguardShowing=true" in p.shell("dumpsys window | grep isKeyguardShowing")
        ui_shows = picker = None
        if not locked:
            p.shell("am start -n %s/.MainActivity" % PHONE_PKG)
            time.sleep(1.5)
            xml = p.ui_xml()
            ui_shows = rows[-1]["got"] in xml.replace("&#10;", "\n")
            m = re.search(r'text="相册测试"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                p.shell("input tap %d %d" % ((x1 + x2) // 2, (y1 + y2) // 2))
                time.sleep(2.5)
                top = p.shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -1")
                picker = PHONE_PKG not in top and ("media" in top.lower() or "photo" in top.lower() or "gallery" in top.lower())
                p.shell("input keyevent KEYCODE_BACK")
                time.sleep(1)
            else:
                picker = False
    finally:
        clear_private(p)
        p.shell("am broadcast -a com.baybin.phone.RECONNECT -p %s" % PHONE_PKG)

    good = sum(r["ok"] for r in rows)
    ok = good == len(rows) and ui_shows is not False and picker is not False
    save("7_gallery", {"pass": ok, "rows": rows, "phone_locked": locked, "ui_shows_answer": ui_shows,
                       "button_opens_picker": picker})
    verdict(ok, "手机相册测试模式（不用眼镜）", [
        "%d/%d gallery photos answered with the right bin (glasses link off)" % (good, len(rows)),
        "answer on the phone screen: %s; 「相册测试」 opens the photo picker: %s" % (
            "skipped (phone locked)" if locked else ui_shows, "skipped (phone locked)" if locked else picker),
    ])
    return ok


if __name__ == "__main__":
    main()
