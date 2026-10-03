# 交接说明：湾区乐奇垃圾桶识别器（BayBin）

写于 2026-10-02。接手的人先读完这个文件，再读 `README.md`。用户说中文，回复用中文。

## 1. 项目是什么

戴 Rokid 眼镜（RG_glasses，Android 12，2 GB 内存）看着垃圾按一下键，镜片显示两行字：
- 第一行：哪个桶。
- 第二行：不超过 15 个英文词的理由。
- 拿不准时显示 `Not sure — check city guide`。

现在支持 Cupertino 和 San José，在手机 App 里选城市。

流程：
1. 眼镜拍照：1024×768，JPEG q80，用完即丢。
2. 通过蓝牙 RFCOMM 发给手机。
3. 手机调千问 `qwen3-vl-flash`（DashScope 的 OpenAI 兼容接口）。模型只从 `rules/items.json` 的 78 种里挑一个 id。
4. 手机按 `rules/<city>.json` 查出桶和理由，发回眼镜显示。

用户原始需求里的硬性约束（不能破坏）：
- 眼镜端只拍照、发送、显示，不放模型，不放大库，峰值内存 ≤150 MB。
- 规则只能来自市政府或垃圾公司的官方页面，每条带出处。官方没说清的标 `unknown`（显示 Not sure），不准猜。
- API key 不进代码、不进 Git、不进安装包。手机上自己填，Android Keystore 加密后只放应用私有目录。电脑测试脚本才读 `local.properties`（gitignore）。
- 8 条验收标准，每条一个脚本：`tools/accept_1…8_*.py`，汇总脚本是 `tools/accept_all.py`。

## 2. 代码在哪

| 路径 | 内容 |
|---|---|
| `protocol/…/Proto.kt` | 两端共用的帧格式（`"BB"` + 类型 + 长度 + 内容），类型：HELLO / SCAN / RESULT / PROBE / PROBE_ACK / PREPARE |
| `glasses-app/` v0.2.0 | `MainActivity`（按键→拍照→发送→显示，8 s 超时）、`OneShotCamera`（只能用 CameraX，手写 Camera2 会让这台的相机 HAL 卡死）、`PhoneLink`（insecure RFCOMM 服务端） |
| `phone-app/` v0.3.0 | `App.kt`（总装配，debug 广播钩子）、`GlassesLink`（RFCOMM 客户端，自动重连）、`LinkService`（前台服务）、`Pipeline`（识别和判定，见第 4 节）、`QwenClient`、`Rules`（读 assets）、`Gallery`（相册照片用 ImageDecoder 解码）、`Upright` |
| `rules/` | `items.json`（78 种）、`cupertino.json`、`san_jose.json`、`prompt.txt`。编译时拷进手机 assets。`REVIEW.md` 是生成的，不要手改 |
| `tools/` | 见第 5 节 |
| `eval/commons_probe/` | 55 张商品照，每种一张 |
| `eval/realworld/` | 51 张网上真实照片，人工标注，其中 22 张是"盒子里还有食物" |
| `docs/acceptance.md` | 验收结果表，由 `accept_all.py` 生成 |
| `docs/demo-lens.mp4` | 镜片录屏 |

## 3. 环境

- **编译：** Gradle 跑在 WSL 里，用 `python tools\deploy.py build | glasses | phone | all`，详见 `构建.sh`。
- **adb：** 用环境变量 `BAYBIN_ADB` 指到本机的 `adb.exe`（不在 PATH 时）。眼镜和手机用 `adb devices` 区分，不要把序列号写进仓库。
- **Python：** 3.x。先设 `PYTHONIOENCODING=utf-8`。
- **用 Python heredoc 改文件：** 多次把 `\r`、`\n`、反斜杠弄坏。小改动请直接编辑文件，改完用 `python -c "import ast;…"` 或重新编译检查。

## 4. 已经做完的（重要的设计决定）

**连接：两个 App 走自己的 RFCOMM 通道。**
- 原因：Rokid CXR-M SDK 的 `connectBluetooth` 需要 Rokid 开发者平台按眼镜序列号签发的授权（`snEncryptContent` + `clientSecret`），传 null 时它直接返回、什么也不做。
- 不要伪造授权，也不要在用户电脑上找凭证。
- CXR-M 只在第一次配对时用来读眼镜的蓝牙地址，这一步不需要授权。

**识别：**
- DashScope 只返回第一个输出 token 的 logprobs，所以要求模型只回答裸 id。
- 判定规则（`Pipeline.decide`，`tools/eval_probe.py` 的 `shown()` 是同一套逻辑的镜像）：
  - 首 token 概率 < 0.5 → Not sure。
  - 排第二的候选概率 ≥ 0.25，而且按前缀会落到别的桶 → Not sure。
- 时间控制：
  - 2 s 和 4.3 s 时各补发一份请求，先到先用。
  - 7 s 还没有回答就显示 No connection。
  - 用 HTTP/1.1：用 HTTP/2 时，一条连接卡住会连补发的请求一起卡住。
  - 眼镜按键时先发 PREPARE，手机趁拍照的时间预热两条 HTTPS 连接。
  - 给模型的图缩到 768 px。

**今天修的问题：**
- 用户反馈：烧鹅濑粉装在塑料盒里，被判成了 Recycling。原因是目录里没有"容器里还有食物"这种情况。
- 改动：
  - 新增 item `food_in_container`。
  - `prompt.txt` 加一条：容器里有食物就答 `food_in_container`，不管盒子是什么材料。
  - 几种容器的 hint 写明 EMPTY。
  - 两个城市的规则，都带官方原文：
    - Cupertino：Compost，"Scrape the food into the compost cart first, then scan the empty container."
    - San José：Garbage，理由同样提示先把食物倒掉。
  - `verify_rules.py` 支持 `also` 字段：理由里的操作说明也要有官方原句，同样逐字核对。
  - Cupertino 的 `plastic_cup` 理由改成"先倒空"。
  - 相册模式改用 ImageDecoder，支持 HEIC、PNG、WebP。点「相册测试」时就预热连接。
  - 新工具 `tools/collect_web_testset.py`（从 Commons 和 Openverse 找允许转载的图片），以及测试集 `eval/realworld`。
  - `eval/commons_probe/manifest.json` 里那张"装满树莓的塑果盒"加了 `expect: food_in_container`。

**今天的结果（手机上真实流程，v0.3.0）：**

| 测试 | Cupertino | San José | 说明 |
|---|---|---|---|
| 真实照片 51 张，修之前 | 71% | 82% | PC 上测，第一行对的比例 |
| 真实照片 51 张，修之后 | 49/51 = 96%，0 张错桶 | 49/51 = 96%，1 张错桶 | 手机上测 |
| 商品照 55 张 | 55/55 | 55/55 | |
| 相册模式（第 7 项） | 6/6 | | |

**其他几项（第 1、3、4、5、6 项）：** 当天早上已经在真机上 PASS，眼镜端之后没有改过。
- 按键到显示：中位约 3.6 s，最长 4.98 s。
- 眼镜峰值内存 45.5 MB，100 次扫描无泄漏、无崩溃。
- 断网显示 No connection。

## 5. 常用命令

```
python tools\verify_rules.py            # 规则逐字对官方原文，必须保持 OK
python tools\rules_table.py             # 重新生成 rules\REVIEW.md
python tools\eval_probe.py --set eval\realworld     # 只在 PC 上测识别（快，和手机逻辑一致）
python tools\accept_2_accuracy.py       # 手机上测准确率（只需要手机连着 adb）
python tools\accept_7_gallery.py        # 相册模式
python tools\accept_all.py --report     # 用已保存的结果重写 docs\acceptance.md 和 README 里的结果表
python tools\deploy.py build && python tools\deploy.py phone
```

第 1、3、4、5、6 项需要眼镜连着手机，而且手机要开着蓝牙。

## 6. 还没做的（按顺序）

1. **更新 `README.md`：**
   - 「识别和"不确定"」一节加上 `food_in_container` 的说明。
   - 「规则从哪来」一节提一下 `also`。
   - 「测试结果」下面的说明段落现在只讲商品照和"6 次云端超时"，已经过时。改成讲 `eval/realworld`（51 张）和修复前后的对比。
   - 工具表加上 `collect_web_testset.py` 和 `eval_probe.py --set`。
   - 「已知限制」更新成第 7 条里的两个问题。
   - 然后运行 `python tools\accept_all.py --report`，再运行 `python tools\accept_8_readme.py`，确认 PASS。
2. **（可选）减少一种误判为 Not sure 的情况：** 首 token 是 `food` 时，它也是 `food_can` 的前缀，而 `food_can` 是 Recycling，所以 `pizza_box_greasy` 和 `food_in_container` 有时会被判成 Not sure（只是不确定，不会给错桶）。
   - 可能的改法：判断"第二候选会不会换桶"时，跳过选中答案自己的那个前缀组。
   - 要求：`Pipeline.kt` 和 `eval_probe.shown()` 必须一起改；改完两个测试集都重跑，确认不变差。
3. **不要提交 Git**，除非用户要求。
4. **改规则时：**
   - 每条 rule 的 `quote` 必须是官方页面上的原句，`verify_rules.py` 会查。
   - `unknown` 必须写 note。
   - `reason` 不超过 15 个词。

## 7. 已知的剩余问题

- `eval/realworld/plastic_tub__1.jpg`：透明盖子和蓝色盒子摆在一起，模型答成了 `plastic_cap`，San José 显示 Garbage（错）。照片本身就有两样东西，比较模糊。
- 盒子里还有披萨的照片：显示 Not sure（第 6 节第 2 条）。
- 装满饮料的杯子（包括珍珠奶茶）答 `plastic_cup` → Recycling，第二行写的是"先倒空"。没有归成 `food_in_container`，因为 Recology 的指南说堆肥桶不收液体。
- 云端延迟随北京时段变化：北京晚高峰最慢。用美国（弗吉尼亚）区的 key 会更快，需要用户自己去开。

## 8. 等用户自己做的

- 用眼镜拍 ≥50 张真实垃圾：`python tools\collect_testset.py`，存到 `eval\glasses_set\`。拍完 `accept_2` 会自动把这一组也算进去。
- 录完整演示视频：`docs/demo.mp4`。
- 手机蓝牙现在是关着的（当初就是关的，测试完已经恢复）。用眼镜之前要打开，App 会自己连上。
- 规矩：
  - 手机上不要留文件。必须放的话只能放 `内部存储/Download/BayBin`。测试照片用 `run-as` 放进 App 私有目录，用完删掉。
  - 不要杀 `com.lenstrans.glass`。
  - 改手机设置前先问用户。
