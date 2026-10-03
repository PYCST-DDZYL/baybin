r"""
Build and install.

    python tools\deploy.py build            # 两个 app 都编 debug
    python tools\deploy.py build release    # 两个都编 release
    python tools\deploy.py glasses [release]  # 装到眼镜（不重新编译）
    python tools\deploy.py phone   [release]  # 装到手机（不重新编译）
    python tools\deploy.py all     [release]  # 编译 + 两边都装
    python tools\deploy.py phone-files        # 看手机上 Download/BayBin 里有什么
    python tools\deploy.py phone-clean        # 删掉手机上的 Download/BayBin（只删这一个文件夹）

手机上的文件只会出现在 内部存储/Download/BayBin 这一个文件夹里（见 adbutil.PHONE_DIR）；
装 App 用 adb install，不会在手机存储里留安装包。

Gradle 跑在 WSL 里（和 题库扫描 / 镜译 同一套工具链），见 构建.sh。
"""
import os
import subprocess
import sys

from adbutil import ROOT, GLASS_PKG, PHONE_DIR, PHONE_PKG, glasses, phone, wake_glasses


def win_to_wsl(p):
    """D:\\a\\b  ->  /mnt/d/a/b"""
    p = os.path.abspath(p)
    drive, rest = os.path.splitdrive(p)
    return "/mnt/" + drive[0].lower() + rest.replace("\\", "/")


def apk(module, variant):
    return os.path.join(ROOT, module, "build", "outputs", "apk", variant,
                        "%s-%s.apk" % (module, variant))


def build(variant):
    task = "assembleRelease" if variant == "release" else "assembleDebug"
    print("[build] %s（WSL 里的 Gradle，第一次会慢一点）" % task)
    sh = win_to_wsl(os.path.join(ROOT, "构建.sh"))
    rc = subprocess.call(["wsl.exe", "-e", "bash", "-c", 'bash "%s" %s' % (sh, task)])
    if rc != 0:
        sys.exit("编译失败，看上面的信息。")


def install_glasses(variant):
    g = glasses()
    path = apk("glasses-app", variant)
    print("[glasses] install %s" % os.path.basename(path))
    print(g.run("install", "-r", "-t", path, check=True).strip())
    for perm in ("CAMERA", "BLUETOOTH_CONNECT"):
        g.shell("pm grant %s android.permission.%s" % (GLASS_PKG, perm))
    wake_glasses(g)


def install_phone(variant):
    p = phone(required=False)
    path = apk("phone-app", variant)
    if p is None:
        print("[phone] 手机没连 adb，跳过。APK 在：\n    %s" % path)
        return
    print("[phone] install %s" % os.path.basename(path))
    print(p.run("install", "-r", "-t", path, check=True).strip())
    for perm in ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
                 "POST_NOTIFICATIONS"):
        p.shell("pm grant %s android.permission.%s" % (PHONE_PKG, perm))
    p.shell("am start -n %s/.MainActivity" % PHONE_PKG)


def main(argv):
    if not argv:
        print(__doc__)
        return
    cmd = argv[0]
    variant = "release" if "release" in argv[1:] else "debug"
    if cmd == "build":
        build(variant)
    elif cmd == "glasses":
        install_glasses(variant)
    elif cmd == "phone":
        install_phone(variant)
    elif cmd == "phone-files":
        p = phone()
        print(p.shell("ls -la %s 2>/dev/null || echo '%s does not exist (nothing of ours on the phone)'" % (PHONE_DIR, PHONE_DIR)))
    elif cmd == "phone-clean":
        p = phone()
        p.shell("rm -rf %s" % PHONE_DIR)
        print("removed %s" % PHONE_DIR)
    elif cmd == "all":
        build(variant)
        install_glasses(variant)
        install_phone(variant)
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
