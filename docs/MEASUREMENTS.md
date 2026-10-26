# 2026-10-26 照片诊断

手机 v0.6.1，应用/规则源码哈希 `e4faf219c6899b7d7116a00f31b2c4c779ea7a34dc0e988601ee3a0aa877a971`。

使用原 `eval/realworld/manifest.json` 中的 51 张公开授权照片和原标签，在独立 Android 包中走手机实际照片流水线。测试范围显式设为 San José 独立住宅居民清运规则；这不代表用户本人的住房或服务资格。没有补充人工答案，没有调整置信度门槛。

| 结果 | 数量 |
| --- | ---: |
| 正确的确定投放结果 | 26 |
| 错误的确定投放结果 | 0 |
| 需补充信息或重拍 | 7 |
| 收到明确云端超时结果 | 10 |
| 20 秒内未收到测试结果 | 8 |
| 合计 | 51 |

按全部请求计算成功率为 **26/51 = 51.0%**，未达到 90% 目标。确定结果中未发现错桶，不等于识别准确率为 100%。

设备在测试期间锁屏，最后 8 次回调缺失的原因尚未确认。这是锁屏设备诊断，不能直接代表前台交互体验，也不能证明较旧版本有所提升。Android 对缓存应用动态广播的延迟行为是排查方向之一，[官方说明](https://developer.android.com/about/versions/14/behavior-changes-all#context-registered-broadcasts-queued)提供了背景；目前没有足够证据将它认定为本次全部回调缺失的原因。

每张照片上传前均校验主机与手机私有测试文件的 SHA-256；未替换照片或改标签。清单 SHA-256：`91ea8f8e92334793a061e1a5533fe5659dc97d0a957d215b0e95a6107c3bf542`。原始结果保留于本地忽略目录 `tools/out/photo_accuracy_v061.json`；公开摘要见 [测量数据](measurements-v061.json)。

复测命令：先用 `tools/review.init.gradle` 构建独立测试包，再运行：

```text
python tools/check_photo_accuracy.py --apk tools/out/review-build/outputs/apk/debug/phone-app-debug.apk --output tools/out/photo_accuracy_next.json
```

使用 `local.properties` 中已有的测试密钥，会将所选公开测试照片发送给千问。工具拒绝覆盖正式应用，测试结束卸载独立包。脚本完成采集与达到准确率门槛是不同状态，必须查看报告中的 `completed`、`pass` 和各类计数。后续诊断子集不得拼入这批 51 张结果来提高成功率。
