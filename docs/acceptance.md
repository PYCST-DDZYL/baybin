# 验收结果

由 `python tools\accept_all.py` 生成；每一项的原始数据在 `tools\out\accept\`。

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
