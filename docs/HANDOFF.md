# Handoff: BayBin

Written 2026-10-02. Read this, then `README.md`. The owner writes in Chinese. Reply to the owner in Chinese. This file is in English because the public repository is in English.

## 1. What this is

Rokid glasses (RG_glasses, Android 12, 2 GB RAM). Look at a piece of trash, press a button, and the lens shows two lines:
- Line 1: which bin.
- Line 2: a reason of at most 15 English words.
- If the answer is not reliable: `Not sure — check city guide`.

Cupertino and San José, chosen in the phone app.

Flow:
1. The glasses take one photo: 1024×768, JPEG q80, then drop it.
2. Bluetooth RFCOMM sends it to the phone.
3. The phone calls Qwen `qwen3-vl-flash` (DashScope's OpenAI-compatible API). The model may only return an id from the 78 in `rules/items.json`.
4. The phone looks up `rules/<city>.json` and sends the bin and the reason back to the glasses.

Constraints from the original request. Do not break these:
- The glasses only capture, send, and display. No model, no large database, peak memory ≤150 MB.
- Rules come only from a city or hauler page, each with a citation. If the page is unclear, the rule is `unknown` (the lens shows Not sure). Do not guess.
- The API key is not in source, not in Git, and not in the APK. The owner types it on the phone. Android Keystore encrypts it in app-private storage. PC test scripts read `local.properties` (gitignored).
- Eight acceptance checks, one script each: `tools/accept_1…8_*.py`. `tools/accept_all.py` runs them.

## 2. Where the code is

| Path | What |
|---|---|
| `protocol/…/Proto.kt` | Shared frames (`"BB"` + type + length + body). Types: HELLO / SCAN / RESULT / PROBE / PROBE_ACK / PREPARE |
| `glasses-app/` v0.2.0 | `MainActivity` (button → photo → send → display, 8 s timeout), `OneShotCamera` (CameraX only; a hand-written Camera2 capture never returns a JPEG on this HAL), `PhoneLink` (insecure RFCOMM server) |
| `phone-app/` v0.3.0 | `App.kt` (wiring, debug broadcasts), `GlassesLink` (RFCOMM client, reconnects), `LinkService` (foreground service), `Pipeline` (recognition and the bin decision; section 4), `QwenClient`, `Rules` (reads assets), `Gallery` (ImageDecoder), `Upright` |
| `rules/` | `items.json` (78 ids), `cupertino.json`, `san_jose.json`, `prompt.txt`. Copied into the phone assets at build time. `REVIEW.md` is generated. Do not edit it by hand |
| `tools/` | Section 5 |
| `eval/commons_probe/` | 55 stock photos, one per item |
| `eval/realworld/` | 51 real web photos, labelled by hand, 22 of them food still in a container |
| `docs/acceptance.md` | Acceptance table, written by `accept_all.py` |
| `docs/demo-lens.mp4` | Lens recording |

## 3. Environment

- **Build:** Gradle runs in WSL. `python tools\deploy.py build | glasses | phone | all`. See `构建.sh`.
- **adb:** `BAYBIN_ADB` points at this machine's `adb.exe` when it is not on `PATH`. Tell the glasses and the phone apart with `adb devices`. Do not commit serial numbers.
- **Python:** 3.x. Set `PYTHONIOENCODING=utf-8` first.
- **Do not edit files with a Python heredoc.** That has corrupted `\r`, `\n`, and backslashes. Edit the file, then check with `python -c "import ast;…"` or a rebuild.

## 4. Decisions already made

**The two apps use their own RFCOMM socket.**
- Rokid CXR-M `connectBluetooth` needs a per-headset license (`snEncryptContent` + `clientSecret`). Null does nothing and returns.
- Do not invent a license, and do not go looking for one on the owner's computer.
- CXR-M is used only to read the Bluetooth address at first pairing. That call does not need the license.

**Recognition:**
- DashScope returns logprobs for the first output token only, so the model must answer with the bare id.
- Decision (`Pipeline.decide`; `shown()` in `tools/eval_probe.py` is the same rules):
  - First-token probability < 0.5 → Not sure.
  - Runner-up ≥ 0.25, and that prefix would be a different bin → Not sure.
- Timing:
  - Extra requests at 2 s and 4.3 s. The first reply wins.
  - No reply at 7 s → No connection.
  - HTTP/1.1. On HTTP/2 a stalled connection stalls the retry too.
  - The glasses send PREPARE on the button press. The phone opens two HTTPS connections while the camera exposes.
  - The model image is scaled to 768 px.

**Food left in a container:**
- A plastic box of roast-goose rice noodles was labelled Recycling. The catalog had no "food still in the container" id.
- What changed:
  - New item `food_in_container`.
  - `prompt.txt`: food in a container is `food_in_container`, whatever the container is made of.
  - Hints on the empty-container ids say EMPTY.
  - Both cities cite an official sentence:
    - Cupertino: Compost. "Scrape the food into the compost cart first, then scan the empty container."
    - San José: Garbage, and the reason says to empty the food first.
  - `verify_rules.py` checks an `also` field the same way it checks `quote`.
  - Cupertino `plastic_cup` says to empty it first.
  - Gallery mode uses ImageDecoder (HEIC, PNG, WebP). Tapping 「相册测试」 warms the connections.
  - `tools/collect_web_testset.py` finds redistributable Commons and Openverse photos. The set is `eval/realworld`.
  - The raspberry clamshell in `eval/commons_probe/manifest.json` has `expect: food_in_container`.

**Results on the phone, real pipeline, v0.3.0:**

| Set | Cupertino | San José | Note |
|---|---|---|---|
| 51 real photos, before the fix | 71% | 82% | PC, first line correct |
| 51 real photos, after the fix | 49/51 = 96%, 0 wrong bins | 49/51 = 96%, 1 wrong bin | On the phone |
| 55 stock photos | 55/55 | 55/55 | |
| Gallery (criterion 7) | 6/6 | | |

**Criteria 1, 3, 4, 5, and 6** passed on the devices that morning. The glasses app was not changed after that.
- Button to display: median about 3.6 s, max 4.98 s.
- Glasses peak 45.5 MB. 100 scans, no leak, no crash.
- Offline shows No connection.

The runner-up check already skips the chosen id's own prefix, in both `Pipeline.kt` and `eval_probe.shown()`. Do not add that a second time.

## 5. Commands

```
python tools\verify_rules.py            # quotations must stay OK
python tools\rules_table.py             # regenerate rules\REVIEW.md
python tools\eval_probe.py --set eval\realworld     # PC only; same decision as the phone
python tools\accept_2_accuracy.py       # accuracy on the phone (phone adb is enough)
python tools\accept_7_gallery.py        # gallery mode
python tools\accept_all.py --report     # rewrite docs\acceptance.md and the README table from saved results
python tools\deploy.py build && python tools\deploy.py phone
```

Criteria 1, 3, 4, 5, and 6 need the glasses linked to the phone, and Bluetooth on.

## 6. Still open, in order

1. **README** already describes `food_in_container`, `also`, `eval/realworld`, the tool table, and the known misses. Do not run `accept_all.py --report` unless the owner asks. It rewrites `docs/acceptance.md` and the README results table.
2. **Do not commit** unless the owner asks.
3. **When changing a rule:**
   - `quote` must be a sentence from the official page. `verify_rules.py` checks it.
   - `unknown` needs a note.
   - `reason` is at most 15 words.

## 7. Known misses

- `eval/realworld/plastic_tub__1.jpg`: a clear lid and a blue box together. The model said `plastic_cap`. San José shows Garbage, which is wrong for the box. The photo really does contain two things.
- Pizza still in the box can show Not sure. That is the prefix case in section 4, and it does not assign the wrong bin.
- A cup full of a drink, including bubble tea, is `plastic_cup` → Recycling, and the second line says to empty it first. It is not `food_in_container`. Recology says the compost cart does not take liquids.
- Cloud latency follows the Beijing time of day. Beijing evening is the slow part. A US (Virginia) key is faster. The owner has to open that key.

## 8. For the owner to do

- Photograph at least 50 real items with the glasses: `python tools\collect_testset.py`, stored in `eval\glasses_set\`. `accept_2` includes that set once it exists.
- Record the full demo: `docs/demo.mp4`.
- Phone Bluetooth was off, on purpose, and was left off after the tests. Turn it on before using the glasses. The app reconnects.
- House rules:
  - Do not leave files on the phone. If something must stay, only `内部存储/Download/BayBin`. Test photos go in with `run-as`, into the app's private directory, and are deleted afterward.
  - Do not kill `com.lenstrans.glass`.
  - Ask before changing a phone setting.
