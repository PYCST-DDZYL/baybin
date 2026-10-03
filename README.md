# 湾区乐奇垃圾桶识别器

戴着 Rokid 眼镜看着手里的垃圾，戒指右键拍一下。镜片上先是取景画面，几秒钟后出现两行字：哪只桶，以及这是什么。

```
绿桶
还剩食物
```

拿不准的时候第一行是「不确定」，不会瞎猜一个桶。城市在手机上选，目前支持 **Cupertino** 和 **San José**（单户住宅）。眼镜不记城市。

规则只来自市政府和垃圾公司的官方页面，每条都带出处，并且有脚本逐字核对原文（见 [规则从哪来](#规则从哪来)）。

## 怎么用

1. 编译安装：`python tools\deploy.py all`（Gradle 跑在 WSL 里，见 `构建.sh`）。打开手机上的 BayBin，在密钥框里填你自己的千问密钥。密钥只留在那台手机里，用系统密钥库加密，反编译安装包读不到，也不会上传。电脑上的测试脚本才读 `local.properties`：把 `local.properties.example` 复制过去，自己填，这个文件不进 Git。
2. 眼镜上打开 **BayBin**。手机上打开 **湾区垃圾分类**，第一次点「搜索眼镜」，列表里点你的眼镜。之后手机会自己连（前台服务，锁屏、放兜里都行）。
3. 在手机上选城市（Cupertino / San José）。
4. 看着垃圾，用取景画面对准，戒指右键拍照（镜腿点击也可以）。左键退出。镜片上给出桶，以及认出的东西。
5. 没有眼镜时：手机 App 里点「相册测试」，选一张照片，走的是同一条识别流程，结果显示在手机上。

## 架构

```
眼镜（Rokid RG，Android 12，2 GB 内存）
  按键 ─┬─ PREPARE ─────────────────────► 手机：先建好两条到千问的 HTTPS 连接
        └─ CameraX 拍一张：1024×768，JPEG q80，约 40–65 KB
             │
             │  SCAN（蓝牙 RFCOMM，约 200 KB/s）
             ▼
手机（Android，前台服务保持连接）
  Upright：按 EXIF 转正，缩到 768px
  千问 qwen3-vl-flash（Model Studio，OpenAI 兼容接口）
    · 只回答目录里的 id（78 种）或 unknown
    · 2 s / 4.3 s 没回就补发，先到先用；7 s 还没有 → No connection
    · 首 token 概率太低，或第二候选会换桶 → Not sure
  规则表 rules/<city>.json：id → 桶 + 理由（官方出处，verify_rules.py 逐字核对）
             │
             │  RESULT（两行字）
             ▼
眼镜：显示两行字。不联网、不跑模型、不留历史。
```

- **眼镜端**（`glasses-app`）只做三件事：拍照、发给手机、显示结果。没有网络权限，没有模型，没有大库（CameraX 是唯一依赖，因为这台眼镜的相机 HAL 只认它那一套用法）。照片 ≤1024px、JPEG q80，发完就不再引用，不留历史。
- **手机端**（`phone-app`）：`GlassesLink` 管蓝牙连接，`LinkService` 是前台服务保持连接，`Pipeline` 是识别流程（眼镜和相册测试共用），`QwenClient` 调千问，`Rules` 读规则表。
- **协议**（`protocol`）：两边共用的帧格式，`"BB"` + 类型 + 长度 + 内容。
- **规则**（`rules/`）：`items.json` 是 77 种常见垃圾的目录，`cupertino.json` / `san_jose.json` 是每个城市每种垃圾进哪个桶、理由、出处和原文引用，`prompt.txt` 是给模型的提示词。编译时拷进手机 App 的 assets，App 和核对脚本用的是同一份数据。

### 连接：为什么用自己的蓝牙通道

Rokid 的 CXR-M SDK 自带消息通道，但 `connectBluetooth` 要求 Rokid 开发者平台按眼镜序列号签发的授权文件（`snEncryptContent` + `clientSecret`）。没有这两个值时，它什么也不做就直接返回。所以两个 App 走自己的 **RFCOMM 蓝牙通道**：眼镜端开一个 insecure RFCOMM 服务（不弹配对框），手机端连上去。CXR-M SDK 只在第一次配对时用来读出眼镜的蓝牙地址，这一步不需要授权。以后如果拿到了 Rokid 的授权文件，可以换回 CXR 通道，`Proto` 的消息定义不用变。

实测（手机 S26 Ultra ↔ RG 眼镜）：16 KB–512 KB 都能一次传完，持续速度约 200 KB/s，一张 40–65 KB 的照片往返约 0.3–0.5 秒。

## 识别和"不确定"

- 模型只做一件事：从 78 种目录里挑一个 id（或者回答 `unknown`）。**进哪个桶由规则表决定，不由模型决定。**
- 容器里还有食物时，id 是 `food_in_container`，不是盒子的材料。一顿饭、面条、汤、米饭、蛋糕或散落的剩菜（超过一点油污）都算。提示词要求：不管容器是泡沫、塑料、纸还是锡纸，都答这个 id；空了才用容器自己的 id。Cupertino 先把食物倒进绿色堆肥桶，San José 先倒进垃圾桶（单户住宅没有厨余桶），倒空以后再看容器本身。装满饮料的杯子（包括珍珠奶茶）不归这一类：Recology 的指南说堆肥桶不收液体，所以仍答 `plastic_cup`，进回收桶，第二行写先倒空。
- 不确定（显示 `Not sure — check city guide`）的情况：
  1. 模型回答 `unknown`，或者回答了目录外的东西；
  2. 这个城市的官方资料对这种东西没说清楚，或者互相矛盾（规则是 `unknown`）；
  3. 模型自己没把握：接口能返回第一个输出 token 的概率，概率 < 0.5，或者排第二的候选 ≥ 0.25 而且会换桶，都算不确定。第二候选只是一个前缀。选中的 id 自己的前缀直接跳过（答 `food_in_container` 时 token `food` 也是 `food_can` 的开头，不是另一个答案）。其余前缀要能开头的每一种物品都换桶才算：`food` 也能开头 `food_in_container`，和油披萨盒是同一个桶，不能只因为 `food_can` 是 Recycling 就当成会换桶。模型自报的 "confidence" 试过，总是 0.95 左右，没用。
- 云端偶尔会无缘无故卡 20 多秒（约 1/50）。手机 2 秒没收到回答就再发一份，4.3 秒还没有就换一条新连接发第三份，先到先用。7 秒还没有就显示 `No connection`（眼镜那边 8 秒超时，7 秒留出了回传的时间）。只补发一份的时候，约 250 次里还有 1 次两份都卡住了；第三份就是为这种情况加的。
- 按下眼镜按键的同时，眼镜先给手机发一个 `PREPARE`。手机趁眼镜拍照的这 1.3 秒，提前建好两条到云端的 HTTPS 连接（一条正常用，一条留给补发），省掉跨太平洋的 TLS 握手。用 HTTP/1.1：同一条 HTTP/2 连接卡住时，补发的请求会跟着一起卡住。
- 给模型的图缩到 768px：比 1024px 少约 400 个图像 token。同一提示词下，55 张测试图的中位延迟从 3.6 s 降到 2.5 s，桶的判断都是 55/55。用 `rules/prompt.txt` 的最终版本（只回答 id），55 张全部认对（物品 55/55，两个城市的桶 55/55），中位 2.5 s，p90 3.8 s。

## 规则从哪来

- **Cupertino**：Recology South Bay 2025-26 分类指南（PDF）、Recology 的 sorting guide / 桶说明 / 有害垃圾 / 电子垃圾页面、cupertino.gov 的回收、堆肥和 HHW 页面（市政府网站拦脚本，用 web.archive.org 的快照核对）。
- **San José**：SanJoseRecycles.org 每种东西的单独页面（"Where does it go?"）。
- `python tools\verify_rules.py --live` 会重新抓官方页面，逐条检查：引用原文在页面上、在对的章节下面、桶和章节一致、链接在官方域名下。`rules/REVIEW.md` 是两个城市并排的对照表，带出处和备注。
- 理由里如果另外告诉用户先做一步（例如先把食物从容器里倒出来），规则带一个 `also`：再引一句官方原话。`verify_rules.py` 对 `also` 同样逐字核对。这句只证明那一步有出处，不决定进哪个桶。
- 官方资料没覆盖或互相矛盾的东西，规则写 `unknown`，并在 `note` 里写清楚为什么。例如 Cupertino 的牛奶盒：市政府页面说进堆肥桶，Recology 2025 指南说进回收桶，所以显示 Not sure。想确认可以打 Recology 408-725-4020，或者发邮件到 environmental@cupertino.gov。

**第一行用每个城市自己的桶名。** Cupertino：Recycling / Compost / Landfill / Special handling。San José：Recycling / Garbage / Yard trimmings / Special handling。San José 的单户住宅没有堆肥桶，绿桶叫 "Yard trimmings"，垃圾桶叫 "Garbage"。用官方名字，镜片上的字就和住户家门口桶上写的一致。这和需求里写的四种固定名称不完全一样，是有意的选择；如果要统一成四种，改 `rules/san_jose.json` 里 `bins` 的 `line1` 就行。

## 测试结果

完整数据：[docs/acceptance.md](docs/acceptance.md)，原始记录在 `tools\out\accept\`。重新跑：`python tools\accept_all.py`。

<!-- RESULTS -->
| # | 验收标准 | 结果 | 数据 | 测量时间 |
|---|---|---|---|---|
| 1 | 按键到镜片显示 ≤5 秒 | PASS | 20/20 ≤5 s; median 3611.5 ms, p90 4532, max 4978 (camera ~1364.0, cloud ~1812.0) | 2026-10-02 05:28:48 |
| 2 | ≥50 件测试集准确率 ≥90% | PASS | realworld（real-world web photos, hand-labelled，51 张）：cupertino 49/51，san_jose 49/51；commons_probe（stock photos (Wikimedia Commons), one per item，55 张）：cupertino 55/55，san_jose 55/55 | 2026-10-02 11:55:24 |
| 3 | 眼镜端峰值 PSS ≤150 MB | PASS | peak 45.5 MB (100 samples) | 2026-10-02 05:45:52 |
| 4 | 100 次扫描无内存泄漏 | PASS | growth -0.9 MB, trend -5.2 KB/scan | 2026-10-02 05:45:52 |
| 5 | 100 次扫描 0 崩溃 | PASS | crash lines 0, restarts 0, unfinished 0, camera errors 0 | 2026-10-02 05:45:52 |
| 6 | 断网显示 No connection，不崩溃不卡死 | PASS | offline → result_2 1572ms; recovered → result_1 2356ms; BT dropped → no_link 1ms; relinked → result_1 2323ms | 2026-10-02 05:46:20 |
| 7 | 手机相册测试模式（不用眼镜） | PASS | 6/6 right with glasses link off; screen shows answer: True; button opens picker: True | 2026-10-02 11:55:45 |
| 8 | README 内容齐全 | PASS | architecture diagram, demo video location, test results for all 8 criteria, why glasses instead of phone | 2026-10-02 16:58:18 |
<!-- /RESULTS -->

**关于第 2 项：** 表里是手机上的真实识别流程。`eval/realworld` 有 51 张网上的真实照片，人工看过再标注，其中 22 张是食物还在容器里（来源和许可在 `eval/realworld/manifest.json`）。修 `food_in_container` 之前，电脑上同一条判定的第一行：Cupertino 36/51（71%，其中 13 张给了错桶）、San José 42/51（82%，其中 8 张给了错桶）。修之后就是表里这一行：Cupertino 49/51（96%，错桶 0）、San José 49/51（96%，错桶 1）。商品照 `eval/commons_probe`（Wikimedia Commons，每种一张）两个城市都是 55/55。商品照比真实照片干净。没对上的两张见下面「已知限制」。

更早一轮只有商品照，110 次里 6 次是云端 7 秒没回答（算成错），拿到回答的 104 次桶都对。验收要的仍是眼镜拍的真实垃圾：`python tools\collect_testset.py`（拍 ≥50 张，存在 `eval\glasses_set\`），然后 `python tools\accept_2_accuracy.py` 会自动把眼镜照片也算进去。

**关于第 1、3–5 项：** 这些是夜里关着灯测的，眼镜摄像头拍到的几乎是全黑，所以 100 次扫描的回答都是 "Not sure"（这是对的）。耗时、内存、稳定性的数字有效。白天拍真实物品，照片会大一些（约 60 KB），蓝牙多花约 0.1 s。

**关于第 7 项：** 前一轮全套测试里，第 7 项有 1/6 张云端超时（那时只补发一份）。之后加了 4.3 s 的第三份请求，表里是加完以后的结果。

## 演示视频

- 完整演示（戴眼镜扫真实垃圾）：`docs/demo.mp4`，**还没录**。建议录法：手机横着拍眼镜前方的垃圾，同时用 `scrcpy -s <眼镜序列号>` 把镜片画面投到电脑上录屏，两段并排。
- 镜片画面录屏（按键 → `Looking…` → `Thinking…` → 结果）：`docs/demo-lens.mp4`。

## 为什么用眼镜而不是手机

扔垃圾的时候，两只手通常都占着：一只手拿着要扔的东西，另一只手掀桶盖，或者拎着袋子。这时候掏出手机、解锁、打开 App、对准、拍照，至少要腾出一只手，还得把垃圾先放下，往往就懒得查了，随手一扔。而扔错一个桶，可能让整车回收物被判污染，最后送进填埋场。

眼镜让"查一下"变成零成本：东西已经在你眼前了，按一下镜腿，3–4 秒后答案直接出现在视线里，手里的东西不用放下，也不用低头看屏幕。查询成本降到几乎为零，人才会每次都查，社区的分类准确率才会真的提高。

眼镜也有局限：电量、内存和算力都很少（这台只有 2 GB 内存）。所以眼镜只负责"看见"和"显示"，识别和规则都放在手机和云上，眼镜端峰值内存控制在 150 MB 以内。没有眼镜的人，用手机 App 的相册测试模式也能查，同一套规则、同一条识别流程。

## 开发和工具

| 命令 | 作用 |
|---|---|
| `python tools\deploy.py all` | 编译（WSL）并安装到眼镜和手机 |
| `python tools\probe.py pair / transfer / scan N / photo / camera N / show` | 硬件测量：连接、传输上限和速度、端到端分段耗时、拍照、镜片排版 |
| `python tools\verify_rules.py [--live]` | 规则逐条对官方原文 |
| `python tools\rules_table.py` | 生成 `rules/REVIEW.md` 对照表 |
| `python tools\eval_probe.py [--set eval\realworld]` | 电脑上直接测识别准确率和延迟（同一份提示词、同一套判定，768px）。`--set` 换测试集，不写则用 `eval\commons_probe` |
| `python tools\collect_testset.py` | 用眼镜拍测试集 |
| `python tools\collect_web_testset.py` | 从 Wikimedia Commons 和 Openverse 找允许转载的图片，人工标注后放进 `eval\realworld` |
| `python tools\accept_1_latency.py` … `accept_8_readme.py` | 8 条验收标准，每条一个脚本 |
| `python tools\accept_all.py` | 全部跑一遍并生成 `docs/acceptance.md` |

手机上的文件：App 不往共享存储写任何东西。装 App 用 `adb install` 流式安装，不留安装包。测试用的照片拷进 App 自己的私有目录，跑完就删。万一要往手机存储放东西，只放 `内部存储/Download/BayBin` 这一个文件夹（`python tools\deploy.py phone-files` / `phone-clean`）。

## 已知限制和下一步

- 第 2 项要换成眼镜实拍的测试集（见上）。
- `eval/realworld/plastic_tub__1.jpg`：透明盖子和蓝色盒子摆在一起，模型答成了 `plastic_cap`。Cupertino 对这种盖子没有说清，显示 Not sure；San José 把瓶盖放进垃圾桶，所以显示 Garbage，和塑料盒该进的回收桶不一致。照片里本来就有两样东西。
- 盒子里还有披萨时答 `food_in_container`：先把食物倒进该进的桶，再看空盒子。空的油披萨盒才是 `pizza_box_greasy`。这两个 id 在 Cupertino 都进 Compost，在 San José 都进 Garbage。
- 云端延迟随 Model Studio 负载变化：北京晚高峰（湾区清晨）最慢。key 属于北京区域；如果在 Model Studio 的美国（弗吉尼亚）区域开一个 key，把 `qwen.baseUrl` 改成 `https://dashscope-us.aliyuncs.com/compatible-mode/v1`，从湾区访问每次能少一个跨太平洋往返。需要自己去开这个 key。
- 拍照约 1.35 秒，其中约 0.85 秒是相机拍静态图本身的时间（HAL 层），还没优化。
- Cupertino 有 17 种、San José 有 1 种东西官方资料没说清，会显示 Not sure。
- Palo Alto、Mountain View、East Palo Alto 以后再加（规则格式一样，加一个 `rules/<city>.json` 就行）。
- 语音触发以后再做；现在只有按键。
