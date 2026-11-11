# Pinehold: the plan from here to revenue

Written 2026-11-11 for whichever model picks this up next. It is self-contained: read it top to
bottom once, then work the phases in order. Every task has a check that must pass before it counts.

Owner: Youssef (solo developer, student, Egypt, Africa/Cairo time). He reads short, plain replies.

---

## Progress log (newest first)

**2026-11-11, pass 1b**
- Sealed removal guard **verified on the emulator** with a minted Pro code: Pinehold's app info,
  the uninstall dialog and Device admin settings all bounce back to Pinehold; Wi-Fi settings stay open.
- The full admin's request page listed "erase the phone" and screen-lock powers, so Sealed now asks
  for `SealedAdminReceiver`, which declares no policies. With it active, even `adb pm uninstall`
  fails (DELETE_FAILED_DEVICE_POLICY_MANAGER).
- Settings window after the admin request shortened to 45 s (in the repo; not yet in a build).
- Emulator note: it now has Pro and the Sealed admin active, so wipe it before the next fresh-install test.

**2026-11-11, pass 1 (Opus 5.5)**
- Done: code maps for all three repos. A1 removal test: during a Firm promise, Settings > Apps >
  Pinehold > Uninstall worked in three taps. A2: a Sealed promise now holds Pinehold's app info,
  device-admin, special-access pages and the uninstall dialog, and asks once for device admin
  (`SystemSurfaces.isRemovalScreen`, `FocusRules`, `askForDeviceAdmin`). Unit-tested; **not
  device-tested, because Sealed needs a Pro code on the emulator.** Firm stays endable by design.
- Done: A4 running promise on the slab; gate verified with words and a picture.
- Done: B1 Blossom layouts for Rules (rule sets as cards) and Insights (garden week, numbers behind
  one tap). You keeps the normal layout in Blossom.
- Done: B4 PC Pro: `PcLicence` (offline FLP codes, compatible with the server's licence.js,
  4 tests), the service refuses Sealed without Pro, You > Pinehold Pro to enter a code.
  Only Sealed is gated so far. Runs against the new service only (his laptop still runs dist6).
- Done: D3 plans: Windows Pro $14.99 (PC code), Everywhere $29.99 (phone + PC + 6 Keeper months,
  env `BUNDLE_KEEPER_MONTHS`). Server live. **Keeper months are recorded but cannot be claimed yet**
  (needs the account link in Phase C); do not sell Everywhere until then.
- Done: D6 30-day any-reason refund on every page. D2 new landing page (slab look, real captures,
  honest Cold Turkey table). **Site pushed, not deployed:** the live APK is still the old 2.0, so
  deploy the site only together with A8.
- Next: test Sealed on the emulator with a Pro code, A3 video on the gate, A5 trim, A6 battery,
  A7 regression, then A8 publish + site deploy. Then B2, B5 installer, C sync.

## 0. Rules that override everything below

**How he wants the work done**
- Do the work yourself. No subagents, ever (he is short on usage).
- Keep going without stopping to ask; he is often away. Stop only for the items in section 8.
- Replies: short, plain, accurate. Lead with the result. Say what is untested.
- No music anywhere, in video or app (he considers it haram). Non-musical sound only.
- Fewer options, less text. He called text-heavy screens "a documentary". One idea per screen.
- He has given blanket permission to commit, pull and push all three repos.

**Hard limits (do not cross even if asked casually)**
- Never print, echo or commit secrets or keys. Never commit the licence private key.
- Never type a password, never paste a secret into a website. He does those himself.
- Never create accounts, complete purchases or move money. Real-purchase tests are his.
- Never touch his real phone (serial `a666cc29`) for test automation. Use `-s emulator-5554`.
  Install on his phone only when he says it is plugged in and asks.
- Never grant security permissions on his phone for him. Never set the Pinehold password; he or
  his family sets it.
- Posting to social media needs his explicit "yes" per post. No automated engagement on
  strangers' posts.
- Destructive commands (deleting data, force push, wiping anything but the test emulator) need
  his explicit confirmation.
- Do not ship the Windows service to customers until he has run that exact build on his own laptop.

**Owner-only rules (personal build, never in the store build)**
- AI chat blocked on phone always; on laptop allowed 08:00-16:00 Sat-Wed, free Thu and Fri.
  NotebookLM and OpenRouter are never blocked.
- Prayer lock: 5 minutes before Adhan to 35 after (Cairo, Egyptian General Authority). Calls and
  Quran stay open.
- Videos: first 14 days curriculum only (Physics, Chemistry, Biology, Geology, English, Arabic;
  he is science track, no Math). After that, one non-curriculum video per day.

---

## 1. Where everything is

| Thing | Location | Branch | Notes |
|---|---|---|---|
| Android app (new design) | cloud `/home/claude/focuslock_v2`, laptop `D:\Android_Apps\FocusLock\Pinehold_Next` | `pinehold-next` (pushed) | Package `com.focuslock.mdm`, flavors `store` and `personal` |
| Windows app | laptop `D:\FocusLock PC` | `pinehold-internet` | **No git remote.** Commits are local only. .NET 9 WPF + SYSTEM service + WFP |
| Server | laptop `D:\FocusLock_Fulfillment` | `Hello` | Render auto-deploys on push. https://focuslock-payment.onrender.com |
| Website | laptop `D:\FocusLockWeb` | `Hello` | Render static site, **manual deploy** (service `srv-da8s63942hec73du9h90`). https://focus-lock-c0xg.onrender.com |
| QA scripts | laptop `D:\Android_Apps\FocusLock\qa` | - | See section 9 |

**Code maps (read these instead of re-scanning the code; regenerate after big changes)**
- Android: `docs/CODEMAP_ANDROID.md` in this repo.
- Windows: `D:\FocusLock PC\CODEMAP.md`. Server: `D:\FocusLock_Fulfillment\CODEMAP.md`.
- Regenerate Windows and server with `node D:\Android_Apps\FocusLock\qa\codemap.js`.

**Build and test commands**
- Android: `D:\Android_Apps\FocusLock\qa\buildall.cmd` (tests + store + personal release, log in
  `Pinehold_Next\buildall.log`, last line `EXIT 0`). Needs `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`
  and `-Pkotlin.compiler.execution.strategy=in-process` (both are in the script). 5-9 minutes.
- Windows: `& 'C:\Program Files\dotnet\dotnet.exe' build -c Debug` in `src\FocusLock.App`;
  `dotnet test` in `D:\FocusLock PC` (470 tests). Release packaging: `build.ps1 -OutputDirectory distN`.
- Server: `node --test test/` in `D:\FocusLock_Fulfillment` (39 tests). `npm` needs `cmd /c`.
- Emulator: AVD `Medium_Phone_API_36.0`, `ANDROID_SDK_ROOT=D:\Android_Studio`,
  adb at `D:\Android_Studio\platform-tools\adb.exe`.

**What is verified today**
- Phone: fresh-install path on a wiped emulator (3 steps, 2 permissions, session starts, YouTube
  blocked after 42 s in background). Promise card on the pine slab. GIF animates on the Soft gate.
  Sealed confirmation text is correct. 179 unit tests pass.
- Laptop: new look on all four pages, Blossom theme with its own Focus layout and dock, 470 tests pass.
- Server: family pack (amount picks the plan, 3 codes for $19.99), `/api/checkout-config` live.
- Site: family-pack button, auto-delivery page, `/get.html` are **pushed but not deployed**.

**What is not verified**
- Any real-money purchase. Video (not GIF) on the gate. Battery on a real phone. Prayer lock live.
- How easily Pinehold can be removed on a phone without device owner.
- The Windows release build running as the installed service (the service on his laptop is still
  the old `dist6`; `dist7` is built but not installed; the new look has only run as a debug window).

---

## 2. What "done" means

1. A stranger who taps an ad at 2 a.m. can install on Android, make a promise and be blocked,
   with no computer and no confusion, in under three minutes.
2. A stranger can buy Phone Pro, the Family pack or the Everywhere bundle, and receive what they
   paid for on the next screen with nothing to paste.
3. A stranger can install Pinehold for Windows from the site, and a PC Pro code unlocks something real.
4. A promise made on one device holds on the other (sync), at least phone to laptop.
5. The site shows the product as it is now, in the new look.
6. A launch video exists and the first two weeks of posts are drafted and approved.
7. Youssef has run both final builds on his own phone and laptop for at least a day.

---

## 3. Phase A: make the phone sellable (first, about 2 days)

A1. **Removal test without device owner.** On the fresh emulator, with a Firm then a Sealed session
running, try: uninstall from launcher, uninstall from Settings, force stop, revoke usage access,
revoke overlay, clear data, safe mode. Write down what works.
*Check:* a table of each attempt and its result in `qa\removal.md`.

A2. **Harden what Android allows.** Likely moves: request device admin during the first Sealed
promise (blocks plain uninstall); `PermissionGuard` already watches revocations, so make a revoked
permission during a session open the recovery screen and count as a broken promise; detect
Settings' app-info page for Pinehold via usage stats and bring the gate forward. Do not claim
"cannot be removed" anywhere unless A1 proves it; say "hard to remove" and offer the wizard for
"cannot".
*Check:* repeat A1; update site and in-app wording to match the truth.

A3. **Finish the gate.** Test a short MP4 on the gate (silent, looping). Put the Firm gate on the
slab too: today Firm just bounces the user to Pinehold's home with no words. Show the promise
words and picture for a few seconds when a blocked app is bounced.
*Check:* screenshots of Soft, Firm and Sealed gates with words and a video.

A4. **Running-session screen on the slab,** so idle and running match the laptop. Use the same
`pt` token trick as `buildStarter` in `FocusDashboardTab.kt`.

A5. **Trim the rest of the phone to the new voice.** Walk Rules and You; cut paragraphs over about
75 characters unless they are warnings or privacy statements; rename any leftover Gentle, Block,
Strict, Sanctuary to Soft, Firm, Sealed. Keep Arabic in step (both `values` and `values-ar`).

A6. **Battery and lag.** `ContentGuardService` already drops non-browser events when idle. Measure
with `adb shell dumpsys batterystats --reset`, 30 minutes of normal use, then
`dumpsys batterystats com.focuslock.mdm`. Check the `AppBlockerService` poll interval; back off
when the screen is off.
*Check:* numbers before and after in `qa\battery.md`.

A7. **Full regression on a wiped emulator:** onboarding, each strength to its end, early exit with
and without password, schedule window, bedtime, Keeper check, Pro code redeem, Arabic layout.
*Check:* all pass; fix what does not.

A8. **Publish.** Copy the store APK to the site as a new versioned file, update `version.json`,
`SHA256SUMS.txt` (rewrite line-wise, never string-replace), and every download link. Push the
server so it serves the same file to buyers.
*Gate:* only after Youssef has used the personal build on his phone for a day (section 8).

---

## 4. Phase B: the Windows product (about 4-6 days)

B1. **Blossom on every page.** Rules, Insights and You get their own layouts in Blossom, as Focus
did (`Views\TodayCuteView.xaml` is the pattern: same view model, different conversation). Keep
them to one question or one number per card; the sprout speaks the page's one line.

B2. **Second look pass on what has not had one:** `SessionView` (running session and the long
commitment), `TasksView`, password dialogs, tray menu, the block page shown in the browser, the
Safe Browser window. Same rules: slab, large type, outlined fields, no paragraphs.

B3. **Add the promise to the laptop gate.** The words exist (`ComposerIntent`); add the picture
(reuse the phone's rule: image, GIF or silent clip under 40 MB) and show both on the block page.

B4. **Pro gating.** The server already issues and verifies `FLP` codes (`issuePcLicence`,
`verifyPcLicence` in `licence.js`). Add offline verification in the app with the public key, a
"You > Pro" entry to paste a code, and gate: Sealed (DeepFreeze), schedules beyond one, bedtime,
Blossom and accents, Keeper. Free keeps Firm sessions and one rule set.
*Check:* unit tests for valid, tampered and wrong-product codes; a fresh profile shows the gates.

B5. **Installer a customer can run.** One signed-if-possible `Pinehold-Windows-Setup.exe` that
installs the service and app, asks for admin once, and can uninstall cleanly when no session is
running. Unsigned builds trigger SmartScreen: say so on the download page with a screenshot of
"More info > Run anyway", and tell Youssef what a code-signing certificate costs (section 8).
*Check:* install, run a session, reboot, uninstall, all on a clean Windows VM or his laptop.

B6. **Run the release build as the real service on his laptop.** He runs the admin commands. Then
`personal.on` marker for his own rules (`C:\ProgramData\FocusLock\personal.on`).

B7. **Owner extras** (personal only): the video rule and Keeper-checked downloads on the PC.

B8. **Update channel.** A `version.json` for Windows and an in-app "update available" line.

B9. **Give the repo a remote.** It has none; a dead disk loses the whole Windows app. Ask him for a
private GitHub repo (section 8), then push.

---

## 5. Phase C: one promise on both devices (about 3-5 days)

Keep it small. This is the feature Cold Turkey cannot copy quickly, so it must be reliable, not rich.

C1. **Model.** One "promise" record per account: id, words, strength, start, end, protects, origin
device. Server stores it (Mongo, `accounts.js` already has accounts, OAuth and entitlements).
Endpoints: `POST /v1/promise` (start), `GET /v1/promise` (current), `POST /v1/promise/end`
(honoured only where the strength allows). A promise never loosens from a remote call: a device
may only extend or tighten.

C2. **Phone.** On start, post it. On resume and every few minutes while signed in, pull; if a
promise is live elsewhere, start the matching session locally. Signed-out users lose nothing.

C3. **Laptop.** Same pull loop in the service. Map Soft to Firm on the laptop (the laptop blocks
at the network, it cannot pause and ask) and say so in one line.

C4. **Pairing.** Sign in with Google or GitHub on both (already built on the phone). Restore the
hidden devices page on the laptop only when this works.

C5. **Tests.** Server unit tests for "never loosens"; an end-to-end run with emulator and laptop.
*Check:* start Sealed on the phone, the laptop is held within two minutes; end time matches.

---

## 6. Phase D: site, payments, bundle (about 2-3 days, overlaps B and C)

D1. **Deploy what is already pushed** (needs his Render sign-in, section 8).

D2. **Restyle the site** to the slab look: hero is the promise card itself, one line, one button.
Replace all screenshots with real captures of the new builds (phone and laptop). Cut copy hard.
Add a short "Pinehold or Cold Turkey?" section that is fair: they have Mac and years of trust;
Pinehold has the phone, the promise, the Keeper, and one promise across both.

D3. **Plans in `plans.js`** (amount picks the plan; add tests for each):

| Plan | Price | Delivers |
|---|---|---|
| Free | $0 | Phone and laptop, Soft and Firm |
| Phone Pro | $9.99 once | 1 phone code |
| Family | $19.99 once | 3 phone codes |
| Windows Pro | $14.99 once | 1 PC code |
| Everywhere | $29.99 once | 1 phone code + 1 PC code + Keeper Plus for N months |
| Keeper Plus | $4 / month | Subscription (exists) |

For Everywhere, grant `keeper_plus` as a dated entitlement on the buyer's account at redeem time
(they sign in to claim it), not a PayPal subscription. Set N from the cost data in D4.

D4. **Check Keeper margin before promising 6 months.** The admin dashboard (`/admin`) estimates
token cost per day. Compute cost per active Keeper user per month. If it is under about $0.70,
six months is fine inside $29.99; otherwise offer three.

D5. **All paid plans use the self-delivering button** (the PayPal Buttons flow already written for
Family in `index.html`), then land on `/access.html?paid=...`. Keep the hosted button as fallback.

D6. **Refund policy: 30 days, any reason,** stated next to every price. Cold Turkey offers it;
matching it removes the biggest reason to hesitate over a new product. Update `terms.html`.

D7. **Regional price.** Most early buyers will be Egyptian students who cannot pay $30 or use
PayPal. Add a "student in Egypt?" path: a discount code flow by email now; later Paymob or
Fawry/InstaPay if he opens a merchant account (section 8).

D8. **Legal and trust pages** match the truth: privacy (what the Keeper sends, to whom), terms,
permissions page with the two first-night permissions explained, an honest limits page.

---

## 7. Phase E: launch and sell (starts when A is done; do not wait for C)

E1. **Launch video v3.** Captures exist in the session scratchpad (`reel/v3cap`); recapture on the
new look. 30-40 seconds, no music, real screens: the 2 a.m. scroll, the promise in his own words,
the gate showing those words, the timer ending. Vertical 9:16 first, then 1:1 and 16:9.

E2. **Positioning (one sentence everywhere):** "Make a promise to yourself. Pinehold keeps it."
Not "blocker". Blockers are a commodity; a kept promise is a feeling.

E3. **The wedge.** Do not launch to "everyone who is distracted". Launch to the people he is:
Arabic-speaking students before exams, and Muslims who want their phone to respect prayer and
their eyes. Concretely:
- Ship the **prayer lock and "protect my eyes"** as real store features (opt-in, city picker),
  not owner-only. No large competitor will build this. It is the reason to choose Pinehold.
- Arabic site and Arabic video cut. The app is already translated.
- Timing: Egyptian midterms and finals, and Ramadan (people actively want this then).

E4. **Channels, in order of expected return for zero budget**
1. Short vertical videos (TikTok, Reels, Shorts): his own story as a student building his own
   lock. One a day for 14 days. Draft all 14 scripts for his approval.
2. Reddit: r/nosurf, r/digitalminimalism, r/productivity, r/androidapps, r/GetStudying,
   r/islam-adjacent study communities. One honest "I built this because" post each, spaced out,
   following each subreddit's self-promotion rules.
3. Student groups he is already in (Telegram, WhatsApp, Facebook groups for STEM schools):
   free Pro codes for the first 50 in exchange for honest feedback.
4. Product Hunt and Hacker News "Show HN" once the Windows app and sync exist.
5. Small Muslim-productivity and study YouTubers: offer free codes, no payment.
All posting goes through him or through Artemis (`D:\Phone Agent\tools\artemis`,
`phone.cmd "<task>" --device <serial>`) only after his yes on each post.

E5. **Google Play.** Sideloading is the single biggest drop in the funnel. Read the current Play
policy on accessibility, usage access and device admin, write the permission declaration, and
submit a Play build (the store flavor without the parts Play forbids). Budget for rejection and
appeal. Keep the site APK as the full-strength version.

E6. **Measure.** `stats.js` already counts redeem attempts, orders and Keeper checks. Add
download count and first-session-started (an anonymous ping, opt-in, disclosed). Weekly: visits,
downloads, first sessions, purchases. The number that matters early is download to first
session; if it is under half, fix onboarding before buying any traffic.

E7. **Support.** A short FAQ from the first twenty questions; a reply template for refunds; the
"lost my code" flow already works (redeem the same PayPal ID again).

---

## 8. Things only Youssef can do (ask once, in one message, as early as possible)

1. Sign in to Render in the built-in browser, or press Manual Deploy himself.
2. One real $9.99 and one real $19.99 purchase (or a friend's card), then refund himself.
3. Plug in his phone and say "install"; use the personal build for a day.
4. Run the admin commands to install the Windows release build as the service.
5. A family member sets the Pinehold password; then he starts the 90-day session.
6. Create a private GitHub repo for the Windows app.
7. Decide: code-signing certificate for Windows (removes the SmartScreen warning; costs money
   yearly; check current prices) or ship unsigned with instructions.
8. Decide: Google Play developer account (one-time fee) for E5.
9. Decide: a local payment route for Egypt (D7).
10. Approve each post and the video before anything goes out.

---

## 9. Tools, and traps already hit (read before touching anything)

**QA scripts in `D:\Android_Apps\FocusLock\qa`** (run with `powershell -ExecutionPolicy Bypass -File`)
- `emu.ps1 -do "tap:<text>;swipe:up;wait:2;list;shot:name;key:4"` drives the emulator only.
- `pccap.ps1 -pages "Focus,Rules" -tag x [-scroll 60] [-build]` rebuilds, restarts and captures
  the Windows app. Page names are clicked by UI Automation, so `"You,Blossom,Focus"` also works
  to switch theme.
- `tile.ps1 -files "a.png,b.png" -out t.png -scale 0.6 [-cols 1]` joins captures into one image.
  Always tile before looking; single full-size captures are expensive.
- `buildnext.cmd`, `buildall.cmd` for Android.

**Traps**
- The desktop tool returns early on quiet commands. For long builds, start them detached
  (`Start-Process cmd ... -WindowStyle Hidden`) and poll the log for `EXIT`.
- Never send a file to the laptop in the same step that creates it; the copy races and ships the
  old file. Write, then copy, then build, as three separate steps.
- PowerShell 5 reads scripts as ANSI: a `·` or `—` typed in a `.ps1` becomes garbage. Use
  `&#183;` in XAML or do text edits with Node.
- JavaScript `String.replace` treats `$'` and `$1` in the replacement as patterns. Use a function
  replacer or `split().join()`.
- `-File script.ps1 -pages a,b` passes one string, not an array; split inside the script.
- The emulator becomes device owner after the wizard and can then only be reset with
  `emulator -wipe-data`. A fresh-install test needs that wipe.
- WPF: an `OpacityMask` does not clip overflowing children; add `ClipToBounds="True"`.
  `DynamicResource` brushes resolve to the nearest ancestor, which is how the slab recolours its
  children (same idea as `pt = tokens.copy(...)` on Android).
- If the laptop disconnects, say so and stop; do not loop.
- The stop hook insists every commit is pushed; push the Android repo after each commit.

---

## 10. Recommendations, after thinking about how this wins

**1. Sell the promise, not the block.** Cold Turkey, Freedom and Opal sell control. Nobody sells
"your own words, shown back to you at the moment you break". That screen is the product. Every
ad, screenshot and store image should be that gate.

**2. Own a niche the big ones will not enter.** Prayer-aware locking, lowering the gaze
("protect my eyes"), Arabic first, exam-season focus for students. It is a small market for a
venture-backed company and a perfectly good one for a solo developer. Being the obvious choice
for one group beats being the fifth choice for everyone.

**3. Be the cross-device one.** Cold Turkey has no phone. Opal has no real Windows lock. "One
promise holds your phone and your laptop" is true for almost no one else, and it is the only
reason a laptop user would pick a new product over Cold Turkey. Build sync before any other
laptop feature.

**4. Do not fight on strictness claims you cannot prove.** Without device owner, an Android app
can be removed by someone determined. Say exactly what each level does. Honesty is rare in this
category and it is already in the product's voice.

**5. Remove the two biggest reasons not to buy:** a 30-day any-reason refund, and Google Play.

**6. Price for where the buyers are.** One-time prices are right (people who fight compulsion
distrust subscriptions). Keep $9.99 as the entry. Make Everywhere the obvious choice by placing
it between Phone Pro and the sum of the parts. Offer a student price for Egypt instead of losing
those buyers entirely.

**7. Let the free tier do the marketing.** Free Soft and Firm with no account is generous and
should stay. The upgrade moment is natural: the first time someone ends a Firm promise early and
regrets it, offer Sealed.

**8. Keep Blossom, and say why.** A lock app is usually grim. A gentle look is a real reason for
some people, especially younger users, to stay. But it is a look, not the brand: pine is the brand.

**9. Sequence for cash, not completeness.** Phone Pro can earn in days. Launch it to the wedge
as soon as Phase A passes, with the laptop marked "coming, free for early buyers". Early buyers
fund and test the rest. Waiting for sync before the first sale is how solo products die unsold.

**10. One honest risk.** Everything here depends on a single person and on PayPal reaching his
buyers. If payments from Egypt and the region are the bottleneck, D7 moves to the front.

---

## 11. Order of work, in one list

1. Send Youssef section 8 as one message.
2. Phase A (A1 to A7), then wait for his day on the personal build, then A8.
3. In parallel while waiting: D1, D2, D6, D8, E1, E3 store features, E4 scripts.
4. Launch Phone Pro to the wedge (E4 items 1 to 3).
5. Phase B, then his install, then the Windows download goes live with Windows Pro.
6. Phase C, then Everywhere goes on sale and E4 item 4 happens.
7. E5 (Play) runs alongside from step 3 onward; it is slow and outside our control.
8. Every week: E6 numbers, fix the worst drop, repeat.
