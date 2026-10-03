r"""
拍验收用的测试集：用眼镜对着真实垃圾拍照，每张记下它是什么。

    python tools\collect_testset.py

眼镜用 USB 连电脑（或无线 adb）。每一轮：
  1. 输入物品编号或 id（直接回车 = 和上一张同一种物品，q = 退出）
  2. 戴上/举起眼镜对准这个物品，回车拍照
照片存到 eval\glasses_set\<id>__<n>.jpg，标签写进 eval\glasses_set\manifest.json。
眼镜上只有一张临时照片（app 自己的目录，每次覆盖），手机上不存任何东西。

至少 50 张；最好 30 种以上不同物品、换几个角度和光线。拍完运行：
    python tools\accept_2_accuracy.py
"""
import json
import os
import sys
import time

from adbutil import GLASS_PKG, ROOT, glasses, wake_glasses

SET = os.path.join(ROOT, "eval", "glasses_set")
SCAN_RX = r"BB_SCAN id=(\d+) outcome=(\S+) open=(-?\d+) shot=(-?\d+) send=(-?\d+) bytes=(-?\d+) total=(\d+)"


def load_manifest():
    path = os.path.join(SET, "manifest.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return {"note": "Photos taken with the Rokid glasses camera (tools/collect_testset.py); item = catalog id.",
            "photos": []}


def save_manifest(man):
    with open(os.path.join(SET, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(man, f, ensure_ascii=False, indent=1)


def main():
    with open(os.path.join(ROOT, "rules", "items.json"), encoding="utf-8") as f:
        items = json.load(f)["items"]
    ids = [it["id"] for it in items]
    os.makedirs(SET, exist_ok=True)
    man = load_manifest()
    g = glasses()
    wake_glasses(g)

    for i, it in enumerate(items, 1):
        print("%3d %-22s %s" % (i, it["id"], it["name"]))
    last = None
    while True:
        counts = {}
        for ph in man["photos"]:
            counts[ph["item"]] = counts.get(ph["item"], 0) + 1
        print("\n已有 %d 张，%d 种物品。" % (len(man["photos"]), len(counts)))
        ans = input("物品编号或 id（回车=%s，q=退出）: " % (last or "-")).strip()
        if ans.lower() == "q":
            break
        if ans == "" and last:
            item = last
        elif ans.isdigit() and 1 <= int(ans) <= len(ids):
            item = ids[int(ans) - 1]
        elif ans in ids:
            item = ans
        else:
            print("不认识：%s" % ans)
            continue
        input("对准「%s」，回车拍照…" % item)
        wake_glasses(g)
        g.logcat_clear()
        g.shell("am broadcast -a com.baybin.glass.SCAN -p %s --ez captureOnly true --ez save true" % GLASS_PKG)
        m = g.wait_log(SCAN_RX, timeout=15)
        if not m or m.group(2) != "capture_only":
            print("拍照失败，再试一次。")
            continue
        n = counts.get(item, 0) + 1
        name = "%s__%d.jpg" % (item, n)
        g.run("pull", "/sdcard/Android/data/%s/files/last.jpg" % GLASS_PKG, os.path.join(SET, name), check=True)
        man["photos"].append({"file": name, "item": item, "taken": time.strftime("%Y-%m-%d %H:%M:%S")})
        save_manifest(man)
        print("存好了：eval\\glasses_set\\%s（%s 字节）" % (name, m.group(6)))
        last = item


if __name__ == "__main__":
    try:
        main()
    except (KeyboardInterrupt, EOFError):
        print()
        sys.exit(0)
