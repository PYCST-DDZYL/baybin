# BayBin

Detailed technical documentation and historical test results. For a short Chinese introduction, see the [project homepage](../README.md).

## Current behavior — phone v0.6.0

- The default city for a new installation is San José. Confirm housing and city-managed residential collection in Settings before receiving definite guidance. Current tables cover single-family homes; apartment, business and other service profiles link the official guide instead. The authority, table-update date and recorded per-source check date appear with applicable results. Missing check dates are displayed as unrecorded.
- Free offline text search accepts Chinese/English names and aliases and asks the user to select the exact catalog item. It shares the city's decision path with photos but has no model confidence and makes zero cloud requests. Missing evidence is requested one field at a time. User-supplied evidence is labelled separately and is never counted as a successful first-pass photo recognition.
- Lookup words may appear in any order and use punctuation separators; exact identifiers and names appear before partial matches. All query words must match the same item, and material alternatives remain separate choices. Saving a service profile, key or model keeps Settings open so other draft changes remain available. Housing/service spinners have stable view IDs for Android's rotation-state restoration; key input remains excluded from saved state.
- Food contents, readable resin numbers, explicit absence of recycling markings, paper cleanliness and empty-state confirmation can be supplied on the phone. Weak photo confidence, unrelated targets, conflicting evidence and unsupported city rules remain uncertain. Follow-up results are shown on the phone; they are not a new glasses scan or a live replacement of a finished lens result.
- Missing drink-bottle/jug and fresh-carton labels can also be supplied explicitly. When the city's rules require carton construction evidence, a separate question distinguishes a labelled fresh/refrigerated gable-top carton from an aseptic/UHT/shelf-stable carton or visible foil layers. Shape, a spout or the Tetra Pak name alone remains insufficient. Known cleaner/oil labels block both fresh and aseptic carton results, including after a construction-based category change. Unknown answers are not repeatedly requested.
- Phone capture delegates to the installed camera with a full-size private-cache FileProvider URI. URI grants are revoked on return; the private capture file is deleted after decoding or cancellation. Stale files left by process death are removed on a subsequent launch/capture after 24 hours. The latest failed phone JPEG can be retried from bounded process memory, retaining its camera or gallery source. No camera photo is saved to shared storage by BayBin.
- San José has cited conditions for #6 and unmarked rigid takeaway packaging, stained cardboard and clean/soiled frozen-food boxes. Visible food takes priority over packaging material. Readable compostable markings choose the compostable-plastic category only after contents are established; the selected city still decides the bin.
- San José batteries, fluorescent bulbs and small electronics expose the County's [official booking entry](https://hhw.santaclaracounty.gov/make-hhw-appointment), checked 2026-10-25. The [County conditions](https://hhw.santaclaracounty.gov/drop-household-waste) require booking and provide the address afterward. This is a verified booking route, not a nearest-location claim or a business-waste service.

`python tools/focus_report.py tools/out/accept/2_accuracy.json --output tools/out/focus.json` summarizes the 12-item San José focus scope. It separates wrong definite bins, retakes, cloud timeouts, connection/service errors and setup/scope blocks; old hashes stay STALE. The October 11 historical input yielded 43 focused photos: 22 correct definite bins, 16 retakes, 5 timeouts and zero wrong definite bins. These are old measurements, not a v0.6.0 benchmark. New evaluation must use annotated photos, preserve original labels and record user-assisted outcomes separately. The photo-acceptance runner now checks the first city's configured residential scope before uploading photos.

See [no-key trial considerations](TRIAL_SERVICE.md). Offline lookup is available now; a hosted free vision trial has not been provisioned. All build/test commands in this document run from the repository root. The sections below retain earlier implementation and device-test history and do not establish current photo accuracy.

For the optional 21-case local device check, build the disposable package with `bash 构建.sh --init-script tools/review.init.gradle :phone-app:assembleDebug`, then run `python tools/check_local_flows.py --apk tools/out/review-build/outputs/apk/debug/phone-app-debug.apk` from Windows with the phone connected to ADB. The harness uninstalls its fresh review package afterward and refuses to replace existing installations. The final v0.6.0 device run remains unverified because the phone disconnected; this test does not measure photo accuracy or camera UI acceptance.

Look at a piece of trash through Rokid glasses and press the ring's right button. The lens shows a framing guide, then the bin and any preparation or retake instruction.

```
绿桶
先倒出食物，空盒再拍
```

When the answer is not reliable, the first line is 「不确定」 and the next line explains what to photograph. The city is chosen on the phone: Cupertino, San José, Palo Alto, Los Altos, or Berkeley, with the household service scopes described below. The glasses do not store the city. Model recognition cannot guarantee every answer is correct.

Rules come only from city and hauler pages. Each rule cites its source, and a script checks the quoted sentence against that page (see [Where the rules come from](#where-the-rules-come-from)).

## How to use it

1. Build and install: `python tools\deploy.py all` (Gradle runs in WSL; see `构建.sh`). Open BayBin on the phone and type your own Qwen key. After it is saved, that box hides. Settings, at the top right, is where you replace it. The key stays on that phone, encrypted with the Android Keystore. It is not compiled into the APK and it is not in this repository. PC test scripts read `local.properties`: copy `local.properties.example`, fill in your own key, and do not commit that file.
2. Open **BayBin** on the glasses and **湾区垃圾分类** on the phone. Tap the big button to connect. Bluetooth and location permissions are requested when you use the glasses, rather than at launch; gallery-only use needs neither. After permission is granted, or you accept the system's request to turn Bluetooth on, the connection/search continues automatically. Denying optional notification permission does not block it. If only one pair is found, it connects by itself. Later, tap 「连接眼镜」, or the phone reconnects on its own (a foreground service; the screen can be locked). 「换一副」 searches again.
3. On the phone, pick California, then the city under it.
4. Put one item in the framing guide and press the ring's right button (a temple click also takes the photo). The left button leaves the app. Read preparation instructions before disposal. An uncertain result stays for six seconds; adjust as instructed and press right once to retake. Other results return to aiming on the first press.
5. With no glasses: tap 「相册」 and pick a photo. The phone scrolls to the result card and shows a progress indicator. 「取消本次识别」 stops a gallery job; you can also choose a different photo. A connection or service failure offers 「重新识别这张照片」 without reopening the picker. Only the latest failed gallery JPEG is retained in memory for this button, and starting another photo or cancelling clears it; it is never written to a file. Retake guidance asks for a new photo instead of repeating an unchanged image. The answer stays on the phone, with translated item names in Chinese mode and a link to the official ruling.

## Architecture

```
Glasses (Rokid RG, Android 12, 2 GB RAM)
  button ─┬─ PREPARE ─────────────────────► Phone: open two HTTPS connections to Qwen
           └─ CameraX: one JPEG, 1024×768, q80, about 40–65 KB
                │
                │  SCAN (Bluetooth RFCOMM, about 200 KB/s)
                ▼
Phone (Android, foreground service keeps the link up)
  Upright: rotate from EXIF; check clearly unusable exposure
  Qwen qwen3-vl-plus (Model Studio, OpenAI-compatible API)
    · one 768 px call: catalog id first, then six visible contents/material/label fields
    · first-token probability belongs to the item id; incomplete evidence → Not sure
    · 7 s deadline with hedged retries; superseded calls are cancelled
    · missing confidence, inconsistent fields, multiple targets → retake guidance
    · identical confirmed photos: reuse evidence from a bounded memory cache
  rules/<city>.json: verified id/material → bin + preparation + official source
                │
                │  RESULT (two lines)
                ▼
Glasses: show the two lines. No network, no model, no history.
```

- **Glasses** (`glasses-app`) do three things: take the photo, send it, show the result. No network permission, no model, no large library (CameraX is the only dependency, because this headset's camera HAL only returns a JPEG for that pairing). The photo is at most 1024 px, JPEG q80, and is not kept.
- **Phone** (`phone-app`): `GlassesLink` owns the Bluetooth connection, `LinkService` is the foreground service, `Pipeline` is the recognition path (glasses and gallery share it), `QwenClient` calls Qwen, `Rules` reads the rule tables.
- **Protocol** (`protocol`): one frame format, `"BB"` + type + length + body.
- **Rules** (`rules/`): `items.json` is the catalog. The five city JSON files contain the bin, reason, source, quotation, evidence `checks`, and optional `by_material` rulings. `inspection.txt` is the structured app prompt; `prompt.txt` is retained for the PC id-only diagnostic. The build copies them into the phone app's assets. The app and the checker read the same files, including quotations for conditional rulings.

### Connection: why the apps use their own Bluetooth socket

Rokid's CXR-M SDK has a message channel, but `connectBluetooth` requires a per-headset license from Rokid's developer site (`snEncryptContent` + `clientSecret`). Without those values the call returns and does nothing. The two apps use their own **RFCOMM** socket: the glasses listen on an insecure RFCOMM service (no pairing dialog), and the phone connects. CXR-M is used only once, to read the glasses' Bluetooth address at first pairing, and that call does not need the license. If a license shows up later, the CXR channel can replace this socket. The `Proto` messages stay the same.

Measured (phone S26 Ultra ↔ RG glasses): 16 KB through 512 KB all fit in one transfer, about 200 KB/s sustained. A 40–65 KB photo is about 0.3–0.5 s round trip.

## Recognition and "not sure"

- The model does one job: pick an id from the catalog of 78, or answer `unknown`. **The rule table picks the bin, not the model.**
- Food still in a container is `food_in_container`, not the container's material. A meal, noodles, soup, rice, cake, or scattered leftovers (more than a smear of grease) count. The prompt says to use this id whether the container is foam, plastic, paper, or foil. An empty container uses its own id. Cupertino: scrape the food into the green compost cart first. San José: scrape it into the garbage cart first (single-family homes have no food-scraps cart), then look at the empty container. A cup still full of a drink, including bubble tea, is not this id. Recology says the compost cart does not take liquids, so the id stays `plastic_cup` (recycling) and the second line says to empty it first.
- Clearly dark, overexposed, or blank photos stop on the phone before a cloud request. Structured inspection checks for multiple unrelated targets. A matching lid beside its base and a group of the same waste kind count as one target. The previous color-blob `TwoThings` check is no longer in the live pipeline.
- Inspection returns the item id on its first line, then food contents, plastic marking, paper condition, container emptiness, label type and target count. The `paper` field checks clean/soiled cardboard, inner coatings on paper containers, and evidenced gable/aseptic carton construction. Closed boxes cannot establish clean interiors. Visible food in ceramic, glass, metal or disposable containers maps to `food_in_container` even when the reply names the container. Hidden contents stay unknown. City conditions are enforced before a definite bin; for example San José plastic cups require a readable accepted number, while visibly marked #6 cups use that city's separately cited garbage ruling. Palo Alto distinguishes fresh cartons (compost) from aseptic cartons (garbage), so visible packaging evidence is required; mixed carton types require a one-item photo. Uncertain results without an item-specific citation link the selected city's official guide.
- The lens shows `Not sure — check city guide` when:
  1. the model says `unknown`, or something that is not in the catalog;
  2. the city's official pages do not say, or they contradict each other (the rule is `unknown`);
  3. first-token probability is missing, invalid, below 0.5, or belongs to punctuation rather than the selected id; or a runner-up at 0.25 or more would change the bin. The runner-up is a prefix and the chosen id's own prefix is skipped;
  4. evidence is absent, conflicting, incomplete, or fails a city's material/content condition. Guidance asks for the opening, printed label, recycling number, lining, better light, or a single target.
- Token probabilities and model observations do not guarantee correctness. The app never uses a self-reported confidence score. Rules choose the bin. The earlier two-call implementation repeatedly ran out of time; the current structured response removes that second wait while retaining required evidence checks.
- Cloud responses can exceed the deadline. If the phone has no answer at 2 seconds it sends the request again. At 4.3 seconds it opens a new connection and sends a third copy. The first reply wins. At 7 seconds the lens shows `No connection` (the glasses time out at 8 seconds, which leaves time to send the result back). These are copies of the same inspection request; there is no second verification stage. Current measured timeouts are reported below.
- When the glasses button is pressed, the glasses send `PREPARE` first. During the ~1.3 s exposure the phone opens two HTTPS connections (one for the first call, one for a retry) so the transpacific TLS handshake is not on the critical path. HTTP/1.1 is required: on one HTTP/2 connection a stalled request stalls the retry too.
- The model image is 768 px. A small unreadable marking requires a closer photo. Superseded photos cancel requests. Confirmed identical JPEGs reuse only the parsed result in memory for at most three minutes (16 entries), with the newly selected city's rules reapplied; retake/failed results are never cached and no images/files enter this cache. Changing the key or model clears it. The default is now `qwen3-vl-plus`: the configured `qwen3-vl-flash` returned HTTP 404 during the October 11 account check. The model can be changed in phone Settings → Advanced without rebuilding; it must support vision and token probabilities in the key's region. The original October 2 Flash benchmarks below do not describe this version.

## Where the rules come from

- **Cupertino**: the Recology South Bay 2025-26 sorting guide (PDF), Recology's sorting guide, cart pages, hazardous-waste and e-waste pages, and cupertino.gov recycling, compost, and HHW pages. The city site blocks scripts, so those pages are checked against web.archive.org snapshots.
- **San José**: the per-item pages on SanJoseRecycles.org ("Where does it go?").
- **Palo Alto** (single-family): the city What Goes Where toolkit, the single-family curbside page, and GreenWaste of Palo Alto's 2023 detailed material guide. Carts are blue recycling, green compost, and black garbage. Food goes in the green cart.
- **Los Altos** (single-family): Mission Trail's residential service guide and its household hazardous waste page. Carts are blue recycling, green organics, and gray garbage. The gray cart's first line is "Landfill", so the lens says gray and not black.
- **Berkeley** (household, and 1–9 unit buildings for recycling): the city waste sorting guide. Carts are blue recycling, green compost, and grey trash. The grey cart's first line is "Landfill". Recycling is split: paper on one side, bottles and cans on the other. The city's dark-blue recycling cart is for buildings of 10 or more units and is not used here.
- `python tools\verify_rules.py --live` fetches the official pages again and checks each rule: the quotation is on the page, under the right section, the bin matches that section, and the link is on an official domain. `rules/REVIEW.md` is the side-by-side table, with sources and notes.
- If the reason also tells the person to do something first (scrape food out of a container, for example), the rule carries an `also` field with a second official sentence. `verify_rules.py` checks `also` the same way. That sentence documents the step. It does not pick the bin.
- Items the official pages do not cover, or where the pages disagree, are `unknown`, with the reason in `note`. Cupertino milk cartons are one case: the city page says compost, the Recology 2025 guide says recycling, so the lens shows Not sure. Recology's number is 408-725-4020, or email environmental@cupertino.gov.

**The first line uses each city's own bin name.** Cupertino: Recycling / Compost / Landfill / Special handling. San José: Recycling / Garbage / Yard trimmings / Special handling. Palo Alto: Recycling / Compost / Garbage / Special handling. Los Altos: Recycling / Compost / Landfill / Special handling. Berkeley: Recycling / Compost / Landfill / Special handling. Single-family San José has no compost cart. The green cart is "Yard trimmings" and the trash cart is "Garbage". Palo Alto's black cart is "Garbage", and food goes in the green cart instead. Los Altos calls the gray cart garbage; its first line stays "Landfill" so the glasses say gray, not black. The official cart color decides the word. That is intentional. To collapse San José onto four shared names, change `line1` under `bins` in `rules/san_jose.json`.

## Test results

**October 25 phone v0.5.0 usability update.** Local validation passed 35 phone JVM tests, 5 Python tool tests, cached quotation checks for all five cities, Debug Lint with zero errors on both apps, and both Debug/Release builds. New tests cover required versus optional connection permissions and retry-photo identity/lifecycle. The connected phone was locked, so the new on-screen permission, progress, cancel and retry flows have not been verified on hardware. No new live-cloud accuracy run was performed; these usability changes do not establish the 90% recognition target. Older device and accuracy reports below remain historical.

Full table: [acceptance.md](acceptance.md). Raw logs are in `tools\out\accept\`. To run again: `python tools\accept_all.py`.

**Food follow-up, phone v0.4.1.** A reported sandwich failure exposed a catalog gap: uncontained food was described only as peels, cores, eggshells and moldy scraps. With the v0.4.0 prompt, a clear whole sandwich returned `unknown` even though the same response said `food=present`. The catalog and both prompts now explicitly cover whole/fresh/partly eaten solid foods, including sandwiches, burgers, bread and pizza. A hand-held food uses `food_scraps`; food on/in a plate or packaging uses `food_in_container`. Ingredients and two sandwich halves count as one food target. Food-category replies must still establish visible food, and material/closed-container checks remain.

The same two public photos were tested with the structured prompt on a PC:

| Photo | Before | After | After response time |
|---|---|---|---|
| [Whole uncontained sandwich](https://commons.wikimedia.org/wiki/File:Sandwich_(1).jpg), Renee Comet / NCI, public domain | `unknown`, despite `food=present` | `food_scraps`, food present, one target | 3,225 ms |
| [Sandwich on a plate](https://commons.wikimedia.org/wiki/File:Bread_sandwich.jpg), Nandhiniraji, CC BY-SA 4.0 | `food_in_container` | `food_in_container`, food present, one target | 6,011 ms |

Both corrected responses had valid first-token probabilities and all seven fields; the uncontained sandwich's first-token probability was greater than 0.999. A closed-pizza-box control still reported unknown interior/paper condition and requires a retake. A plastic-cup control remained a cup, rather than food, and reported multiple targets. These are PC response diagnostics, with no phone shutter, Bluetooth, local exposure gate or app deadline/hedges; they are not a new phone accuracy measurement. Local photos/attribution/raw replies are grouped in ignored `tools/out/sandwich_audit/`, with no files written to the phone. Source hash: `f6dd733c3a035af8e70b704de083728bbeadb3364fbbec41cbb378e316c8c78c`.

Phone v0.4.1 debug/release builds and 19 recognition unit tests passed; both APKs contain the updated prompts/catalog and exclude the configured key. The five-city cached quotation checks passed. The phone was disconnected from adb during this follow-up, so v0.4.1 has not been installed or tested on that phone, and the user's exact failed photo/log was unavailable. The v0.4.0 device results below are a baseline, not validation of v0.4.1. The overall 90% acceptance target remains unmet.

**October 11 baseline validation, phone v0.4.0 / glasses v0.3.0.** Source hash: `dfc8d1ae888ea905ec8f56775611e46dffefb42004d6c7958c59b47efc9bb660`. The real phone pipeline inspected 106 photos once each, then reapplied their evidence under all five city rules. "Correct" compares the first line with the existing item labels and includes correct unknown-city answers; "definite" counts answers actually naming a bin. Retakes and timeouts remain misses. These sets do not annotate visible contents or material and are not a real glasses-photo acceptance set.

| City | Real web photos: correct / 51 | Definite / 51 | Stock photos: correct / 55 | Definite / 55 | Wrong definite bins |
|---|---|---|---|---|---|
| Cupertino | 26 (51.0%) | 26 | 42 (76.4%) | 33 | 0 |
| San José | 25 (49.0%) | 25 | 41 (74.5%) | 41 | 0 |
| Palo Alto | 27 (52.9%) | 22 | 43 (78.2%) | 34 | 0 |
| Los Altos | 27 (52.9%) | 25 | 41 (74.5%) | 28 | 0 |
| Berkeley | 30 (58.8%) | 26 | 42 (76.4%) | 20 | 0 |

**Accuracy acceptance FAIL: the 90% requirement is unmet.** No definite answer had a wrong bin or mismatched item label in this run; that is a result on these photos, not a guarantee. There were 9 cloud timeouts among 51 web photos and 3 among 55 stock photos, plus one service error. Missing interior/label/marking evidence and multiple targets caused retakes. A closed pizza-box pile and mixed carton collection now abstain instead of selecting a potentially wrong bin.

Phone gallery processing, including decode/preparation/cloud, had median 4,940 ms, p90 7,075 ms and maximum 7,130 ms. Fresh web-photo median was 5,376 ms and stock-photo median 4,553 ms. These timings exclude the glasses shutter and Bluetooth. A separate identical-photo check took 2,809 ms initially and 90 ms from the in-memory evidence cache, with zero new cloud calls. That cache timing does not describe a newly photographed object.

Debug/release builds succeeded; 18 recognition unit tests and 5 test-tool safety checks passed. The official quotation checker passed all 5 × 78 rules and the two San José material variants against cached source text; changed material/carton conditions were separately checked on current official pages, and city guide links use official domains. All four APKs excluded the configured API key; both phone APKs contained the current rule assets. Device checks passed for dark/bright/blank photos without cloud calls, corrupt-photo handling, canceled old jobs not replacing newer results, and verified private-file cleanup. A two-scan glasses check exercised a single right-key retake on a dark scene (2,846 / 1,185 ms), with no crashes or process restarts and sampled glasses PSS 110.5 MiB. It does not validate recognition of real trash or the 100-scan memory criteria.

The six-photo phone-only gallery check returned 4/6 expected first lines; the bottle and closed pizza box requested retakes. **Gallery acceptance FAIL:** the phone was locked, so displayed-answer and picker-button checks were not verified. Phone state was restored to San José / English / automatic reconnect, with the glasses connected. `files/eval` was confirmed absent and no shared-storage test files were created. Raw reports: `2_accuracy.json`, `7_gallery.json`, `current_validation.json`, `current_glasses.json` in the ignored local output directory.

The following table is the October 2 historical snapshot, not validation of phone v0.4.0 / glasses v0.3.0. Fresh acceptance results carry a source hash; the report marks other versions STALE. Accuracy now reports all five cities, definite-bin precision, coverage, wrong bins, and cloud timeouts. Missing material annotations in these old photo sets count abstentions as misses. Zero wrong definite bins is additionally required to pass. Locked-phone UI checks cannot pass by being skipped.

<!-- RESULTS -->
| # | Criterion | Result | Data | When |
|---|---|---|---|---|
| 1 | Button to lens text ≤5 s | PASS | 20/20 ≤5 s; median 3611.5 ms, p90 4532, max 4978 (camera ~1364.0, cloud ~1812.0) | 2026-10-02 05:28:48 |
| 2 | ≥50-item set, accuracy ≥90% | PASS | realworld (real-world web photos, hand-labelled, 51): cupertino 49/51, san_jose 49/51; commons_probe (stock photos (Wikimedia Commons), one per item, 55): cupertino 55/55, san_jose 55/55 | 2026-10-02 11:55:24 |
| 3 | Glasses peak PSS ≤150 MB | PASS | peak 45.5 MB (100 samples) | 2026-10-02 05:45:52 |
| 4 | No leak across 100 scans | PASS | growth -0.9 MB, trend -5.2 KB/scan | 2026-10-02 05:45:52 |
| 5 | Zero crashes across 100 scans | PASS | crash lines 0, restarts 0, unfinished 0, camera errors 0 | 2026-10-02 05:45:52 |
| 6 | Offline shows No connection, no hang | PASS | offline → result_2 1572ms; recovered → result_1 2356ms; BT dropped → no_link 1ms; relinked → result_1 2323ms | 2026-10-02 05:46:20 |
| 7 | Phone gallery test (no glasses) | PASS | 6/6 right with glasses link off; screen shows answer: True; button opens picker: True | 2026-10-02 11:55:45 |
| 8 | README has the required sections | PASS | architecture diagram, demo video location, test results for all 8 criteria, why glasses instead of phone | 2026-10-02 16:58:18 |
<!-- /RESULTS -->

**Criterion 2.** The numbers above are the real pipeline on the phone. `eval/realworld` has 51 photos from the web, labelled by hand, 22 of them food still in a container (sources and licenses are in `eval/realworld/manifest.json`). Before the `food_in_container` fix, the same decision on a PC got the first line right on 36/51 Cupertino photos (71%, 13 in the wrong bin) and 42/51 San José photos (82%, 8 in the wrong bin). After the fix, the row above: Cupertino 49/51 (96%, 0 wrong bins), San José 49/51 (96%, 1 wrong bin). Stock photos in `eval/commons_probe` (Wikimedia Commons, one per item) are 55/55 in both cities. Stock photos are cleaner than the web photos. The two misses are under [Known limits](#known-limits).

An earlier round used only stock photos. 6 of 110 calls got no cloud answer within 7 seconds and were counted wrong. All 104 answers that did come back had the right bin. The acceptance bar is still trash photographed with the glasses: `python tools\collect_testset.py` (at least 50 photos, stored in `eval\glasses_set\`), then `python tools\accept_2_accuracy.py` includes that set on its own.

**Criteria 1 and 3–5.** Those runs were at night with the lights off, so the camera saw nearly black frames and all 100 answers were "Not sure", which is the right answer. The timing, memory, and stability numbers still count. In daylight a real object is a larger JPEG (about 60 KB) and Bluetooth adds about 0.1 s.

**Criterion 7.** In an earlier full run, 1 of 6 gallery photos hit the cloud timeout (only one retry then). The 4.3 s third request was added after that. The row above is from after the change.

## Demo

- Full demo (glasses, real trash): `docs/demo.mp4`. **Not recorded yet.** One way to record it: film the trash in front of the glasses with a phone held sideways, and record the lens with `scrcpy -s <glasses serial>`, then put the two videos side by side.
- Lens recording (button → `Looking…` → `Thinking…` → result): `docs/demo-lens.mp4`.

## Why glasses instead of a phone

Both hands are usually busy: one holds the thing being thrown out, the other holds a lid or a bag. Taking out a phone means unlocking it, opening an app, aiming, and taking a photo. That needs a free hand, and the trash usually has to be put down first. People skip the lookup and toss it. One item in the wrong cart can get a whole load rejected as contaminated and sent to landfill.

The object is already in view. One press on the temple sends the photo, and the answer appears in the line of sight. Nothing has to be put down, and there is no screen to look down at. The current cloud timing is reported above; difficult photos can require a retake.

The glasses are limited: little battery, little memory, little compute (this headset has 2 GB). They only see and display. Recognition and rules run on the phone and in the cloud. Peak memory on the glasses stays under 150 MB. Without glasses, the phone app's gallery test uses the same rules and the same pipeline.

## Development

| Command | What it does |
|---|---|
| `python tools\deploy.py all` | Build (WSL) and install on the glasses and the phone |
| `python tools\probe.py pair / transfer / scan N / photo / camera N / show` | Hardware measurements: link, transfer size and speed, end-to-end timing, capture, lens layout |
| `python tools\verify_rules.py [--live]` | Check each rule against the official text |
| `python tools\rules_table.py` | Regenerate `rules/REVIEW.md` |
| `python tools\eval_probe.py [--set eval\realworld]` | First-pass item diagnostic on a PC, before structured evidence checks; use `accept_2_accuracy.py` for real app accuracy |
| `python tools\collect_testset.py` | Photograph a test set with the glasses |
| `python tools\collect_web_testset.py` | Find redistributable photos on Wikimedia Commons and Openverse, then label them into `eval\realworld` |
| `python tools\accept_1_latency.py` … `accept_8_readme.py` | One script per acceptance criterion |
| `python tools\accept_all.py` | Run them and write `docs/acceptance.md` |

The apps do not write shared storage. Install with `adb install` (streamed; no APK left on the phone). Test photos go only into app-private `files/eval`, are deleted after each photo, and cleanup is checked. Tests restore the prior city, language, and reconnect preference. A forcibly interrupted process needs explicit cleanup with `clear_private` and state restoration before another run. If something must be retained on the phone, it goes only in `内部存储/Download/BayBin` (`python tools\deploy.py phone-files` / `phone-clean`).

## Known limits

- Criterion 2 still needs a set photographed with the glasses (see above).
- A matching lid beside its base counts as one container. That identity alone does not establish contents or plastic markings: show the open interior and any required printed number. A loose cap with no base is still `plastic_cap`.
- Pizza still in the box is `food_in_container`: empty the food into the cart that takes it, then look at the empty box. An empty greasy pizza box is `pizza_box_greasy`. Both ids are Compost in Cupertino and Garbage in San José.
- Cloud latency follows Model Studio load. Beijing evening (Bay Area morning) is the slow part. A key issued in the Beijing region is what this project was timed with. A key in the US (Virginia) region, with `qwen.baseUrl` set to `https://dashscope-us.aliyuncs.com/compatible-mode/v1`, saves a transpacific round trip. Open that key yourself.
- The shutter is about 1.35 s, of which about 0.85 s is the camera HAL capturing a still. That part is not optimized.
- Official pages leave 17 Cupertino items, 1 San José item, 12 Palo Alto items, 19 Los Altos items, and 42 Berkeley items unclear. Those show Not sure.
- Mountain View and East Palo Alto can be added later. The rule format is the same: one `rules/<city>.json`.
- Voice trigger is not built. Input is the buttons.
