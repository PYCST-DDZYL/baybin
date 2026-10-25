# Handoff: BayBin

Written 2026-10-02; implementation update 2026-10-11. Read this, then `README.md`. The owner writes in Chinese. Reply to the owner in Chinese. This file is in English because the public repository is in English.

## October 25 maintenance review

The local source remains phone v0.4.1 / glasses v0.3.0. Bluetooth sends and queued reader callbacks now belong to their original connection; an old send failure cannot close the replacement socket, and stale glasses photos cannot migrate to a replacement phone. Failed outgoing dials close their sockets. Automatic reconnect pauses during discovery and resumes when an unconnected search ends. Debug broadcast registration uses the flags overload on both apps, resolving the glasses Lint failure while preserving older Android support.

Key entry rejects embedded whitespace, control characters and non-ASCII text before encryption. Encryption exceptions and failed preference commits return an error; both entry screens retain the input and only report success or close after storage confirms it. Reading an older, invalid saved key treats it as missing instead of building an invalid Authorization header. Tests cover input validation, encrypted-value handoff, encryption/storage failures and both error-message languages; they do not exercise a device Keystore.

Changing the key or model invalidates running and queued recognition jobs, clears the busy indicator, and publishes a new Pipeline. Each photo keeps the Pipeline captured when submitted. Qwen cancellation is checked before image serialization, before launching the initial request, and after waiting for responses. Initial request launch is inside the cancellation cleanup scope. A JVM regression test uses deliberately invalid connection settings to prove an already-superseded photo stops before request construction or network access.

Local validation passed: 26 phone JVM tests (19 recognition, 6 key-storage policy, 1 early cancellation), 5 Python tool tests, cached quotation checks for all five cities and 78 items, both apps' Debug Lint (0 errors; 31 phone / 10 glasses warnings), and both Debug and Release builds. Application/rule source hash: `bd586f75e271e1ab9f4f89a8ca30e97fb8df741fb1b26d5dbe7ddaae7d203a89`. No new device installation, Bluetooth hardware regression, live cloud accuracy evaluation or live quotation fetch was performed. The older device/accuracy reports below remain historical; this maintenance does not establish the 90% recognition target.

## October 11 update — read before the historical notes

Latest follow-up source/build is phone v0.4.1 (versionCode 5); glasses remain v0.3.0. A sandwich complaint exposed the catalog's narrow uncontained-food hint (peels/cores/eggshells/moldy scraps). A public whole-sandwich photo with the old structured prompt returned `unknown` plus `food=present`; the same photo with the corrected prompt returned `food_scraps`, food present, one target, first-token p >0.999, 3,225 ms. A plated sandwich remained `food_in_container` (6,011 ms); closed pizza still has unknown interior and asks for retake. Whole/fresh/partly eaten foods are now explicitly covered in both prompts/catalog; ingredients and halves are one food target. `food_scraps` requires food present, and the Chinese short name is 食物／厨余. Existing confidence/material/container guards are retained. README links the public samples and licenses; all local artifacts are grouped under ignored `tools/out/sandwich_audit/`, with no phone writes.

The v0.4.1 debug/release build, 19 recognition tests, cached five-city quotation checks and both APK key-exclusion/asset checks passed. Source hash is `f6dd733c3a035af8e70b704de083728bbeadb3364fbbec41cbb378e316c8c78c`. The phone was unavailable over adb for this follow-up: no v0.4.1 installation, device accuracy rerun, or access to the user's exact failed photo/log. The glasses remained attached; a read-only UI/log check showed the prior dark-scene test result (same request 145615 as `current_glasses.json`), which is not evidence about the user's sandwich attempt. The targeted cloud checks are PC diagnostics, not full phone-pipeline acceptance. All device reports and restored-device-state notes below refer to v0.4.0 and are STALE for the new source. The 90% acceptance bar remains unmet; do not promote the two-photo diagnosis to an overall accuracy claim.

The v0.4.0 baseline (glasses v0.3.0), followed by the v0.4.1 food correction above, uses ONE structured response: bare id first (preserving its token probability), then six visible-evidence fields, within the original 7 s cloud deadline. `inspection.txt` is the app prompt; `prompt.txt` remains the PC id-only diagnostic. `Recognition.kt` strictly parses all fields, refuses missing/invalid confidence and punctuation tokens, canonicalizes visible food in containers, and enforces per-city evidence checks. `PhotoQuality.kt` stops unusable exposure without a cloud call. Rules may have `checks` and separately cited `by_material` variants; San José #6 cups/packaging have explicit garbage variants. `verify_rules.py` checks those quotations too. The earlier two-call experiment repeatedly ran out of time and is no longer the implementation.

The old configured Flash model returned 404 model_not_found for the current Beijing account; Plus was listed and successfully tested. Default/PC local model is now qwen3-vl-plus. Settings has a collapsed model override so future availability changes do not require a rebuild. Keys remain Keystore encrypted and never compiled into either APK. Slow cloud responses are still a practical limit.

Glasses show an aiming frame, retain preparation/retake instructions rather than overwriting them with an item name, and right-key retake uncertain/error results. Single-photo picker no longer passes an invalid maximum of 1. Superseded jobs cancel cloud calls and cannot overwrite the newest photo. A Pipeline caches only parsed evidence from KIND_OK results for identical upright JPEGs (SHA-256), 16 entries / three minutes in memory; city rules are reapplied, uncertain/error answers are never cached, and a key/model change replaces the Pipeline. No photos or cache files are persisted.

Accuracy tests classify each photo once and reapply its evidence in all five cities through debug REVIEW. They report coverage, definite-bin precision, wrong bins and timeouts, and require zero wrong definite bins. Gallery UI checks compare the displayed result and require the picker to open; locked-phone skips fail. Scripts verify app-private `files/eval` cleanup and restore city/language/reconnect preference. SIGINT from the host can forcibly end the process before finally executes: explicitly clean and restore saved state after an interrupted run. Current raw results include a source hash; older reports are STALE, not current passes. The PC eval probe is now explicitly first-pass-only, not a mirror of the complete pipeline.

The historical acceptance tables below and in README are preserved as historical results. Do not present them as validation of the new build. Unit tests cover strict replies, conflict/food handling, city material conditions, missing evidence, preparation display and exposure. See current raw results for device validation.

Device testing exposed food on ceramic dishes and closed pizza boxes incorrectly treated as empty material. The final container set includes ceramic/glass/metal vessels and cardboard/paperboard boxes. The seventh-line schema remains the same size, but `paper` replaces `lining`: clean/soiled for cardboard, uncoated/coated for other paper containers, or unknown. Paper boxes need visible cleanliness; greasy pizza boxes need visible soiling. A milk/juice label cannot validate `paperboard_box`. The app prompt omits duplicate display names and keeps id + full hints. Each city has a checked `guide_url`; an uncertain result without an item citation now offers that guide on the phone. The completed pre-paper-check accuracy run is retained as `tools/out/accept/pre_paper_safety_accuracy.json`; it failed and must not be presented as final validation.

A subsequent run exposed a mixed carton collection being treated as fresh-milk cartons in Palo Alto, where aseptic cartons have a different bin. The final `paper` enum also carries `gable` / `aseptic` only with visible construction or readable packaging evidence. Palo Alto requires the corresponding evidence for each carton rule; mixed carton types require one-item retakes. Carton shape, a pour spout, the word milk or the Tetra Pak company name alone are insufficient. The earlier run is retained as `pre_carton_safety_accuracy.json` and also failed.

Final source hash: `dfc8d1ae888ea905ec8f56775611e46dffefb42004d6c7958c59b47efc9bb660`. The completed 106-photo `2_accuracy.json` FAILS 90% acceptance: realworld correct line 1 is 26/51 Cupertino, 25 San José, 27 Palo Alto, 27 Los Altos, 30 Berkeley; commons_probe is 42/55, 41, 43, 41, 42 respectively. Zero wrong definite bins and zero confirmed item-label mismatches in every city; do not call that 100% recognition. Nine realworld and three stock-photo cloud timeouts plus one service error remain. Phone-only median is 4,940 ms, p90 7,075 ms, maximum 7,130 ms, excluding shutter/Bluetooth. Older sets lack evidence annotations; do not relabel them just to make abstentions pass. README has the full current coverage table before the preserved historical table.

Debug/release build and 18 unit tests passed; 5 Python safety tests passed. `accept_all` retains fresh measured FAIL reports despite their nonzero exit code, while stale/missing output or a failed process claiming PASS is rejected. Cached official quotations passed all five cities and the two material variants. Four APKs exclude the configured key. `current_validation.json` passed six checks: confirmed identical-photo cache (2,809 ms first / 90 ms repeat), dark/bright/blank rejection without cloud calls, corrupt-photo handling, and stale cloud-job cancellation. UI corruption checks were not verified because the phone was locked. `current_glasses.json` passed two dark-scene scans and one-key uncertain retake, no crashes/restarts, sampled glasses PSS 110.5 MiB; this is a functional check, not a real-item accuracy or 100-scan memory run. `7_gallery.json` FAILS: 4/6 expected first lines, locked-phone screen/picker checks skipped. Do not report those UI checks as passed.

Current device state was verified: San José, English, automatic reconnect true, glasses CONNECTED; phone v0.4.0 / glasses v0.3.0. App-private `files/eval` is absent; `/sdcard/Download/BayBin` is absent, and no shared-storage test files were created. Do not change phone system settings to make tests pass. The owner explicitly requested uploading this completed work to the existing GitHub BayBin project in this session.

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
| `phone-app/` v0.3.0 | `App.kt` (wiring, debug broadcasts), `GlassesLink` (RFCOMM client, reconnects), `LinkService` (foreground service), `Pipeline` (recognition and the bin decision; section 4), `TwoThings` (local two-object stop, before the model), `QwenClient`, `Rules` (reads assets), `Gallery` (ImageDecoder), `Upright` |
| `rules/` | `items.json` (78 ids), `cupertino.json`, `san_jose.json`, `palo_alto.json`, `los_altos.json`, `berkeley.json`, `prompt.txt`. Copied into the phone assets at build time. `REVIEW.md` is generated. Do not edit it by hand |
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

- `eval/realworld/plastic_tub__1.jpg`: a clear lid beside the blue base. The model used to say `plastic_cap` and now says `plastic_tub`. `TwoThings` still stops two blobs that differ in shape. These two are both about as wide as they are tall, so the photo goes to the model. A loose cap with no base is unchanged. The check does not call a detector.
- Pizza still in the box can show Not sure. That is the prefix case in section 4, and it does not assign the wrong bin.
- A cup full of a drink, including bubble tea, is `plastic_cup` → Recycling, and the second line says to empty it first. It is not `food_in_container`. Recology says the compost cart does not take liquids.
- Cloud latency follows the Beijing time of day. Beijing evening is the slow part. A US (Virginia) key is faster. The owner has to open that key.

## 8. For the owner to do

- Photograph at least 50 real items with the glasses: `python tools\collect_testset.py`, stored in `eval\glasses_set\`. `accept_2` includes that set once it exists.
- Record the full demo: `docs/demo.mp4`.
- Bluetooth was left off after the historical October 2 test. The October 11 state is connected with automatic reconnect; preserve that state and do not toggle system settings without the owner's instruction.
- House rules:
  - Do not leave files on the phone. If something must stay, only `内部存储/Download/BayBin`. Test photos go in with `run-as`, into the app's private directory, and are deleted afterward.
  - Do not kill `com.lenstrans.glass`.
  - Ask before changing a phone setting.
