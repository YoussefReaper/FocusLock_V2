# FocusLock architecture

A short map of the codebase, and the reasoning behind the two decisions that
shape everything else: **the user owns the data**, and **no screen names a
colour**.

---

## 1. The Capability Registry

Every behaviour FocusLock has is one boolean in one place.

```
Capabilities.kt        the catalogue: id, label, plain-English blurb, group,
                       default, the permission it needs, and the one honest
                       line to show if it is switched off
CapabilityRegistry     reads and writes the user's choices, plus per-capability
                       parameters
```

Nothing in the app is allowed to flip a switch on its own. The only writer other
than the user is onboarding, and only on an explicit "use this setup" tap. If a
capability is on but Android has not granted the permission it needs, the Rules
screen says so in place rather than silently doing nothing.

`Screens` in the same file maps a capability to its detail editor as a plain
string, so the registry never has to know about Activity classes.

## 2. User-owned data

The old build kept the allow list, the kill list and the WhatsApp phrases in
`Constants` and read them directly from the enforcement loop, which meant the
app's behaviour was a constant the user could not touch. Those three lists are
now migration seeds only.

| Store | Owns |
|---|---|
| `AppRules` | per-app policy, per-category policy, always-allowed, kiosk allowlist mode |
| `KeywordRules` | every watched phrase, including the built-in WhatsApp / Shorts / Reels / adult guards as ordinary editable rows |
| `RuleStore` | custom target / condition / action rules, first match wins |
| `ScheduleManager` | quiet windows |
| `PlaceRules` | places and Wi-Fi conditions |
| `AppLimits` | daily minute and open budgets |
| `Bedtime` | the night window |
| `AllowlistStore` | the site list |
| `TakeABreak` | timed exceptions and the daily allowance |
| `FocusTaskStore` | tasks, notes, subtasks, attachments |
| `EarnPool` | the earned minutes the Budgeted apps draw on |
| `TelegramGuard` | the per-chat Telegram allowlist |

All of it sits on `FocusStore`, which is SharedPreferences plus JSON. Room was
considered and rejected: the whole config is a few kilobytes, it has to be
readable synchronously from an AccessibilityService and a Service on their own
tick loops, and a JSON blob is trivially exportable as a profile file. `ProfileIo`
turns the lot into one document.

`Migration.run` copies the legacy constants in once. After that they are never
consulted again.

## 3. One decision point

```
RuleEngine.decide(context, package) -> GuardDecision(ALLOW | PAUSE | BLOCK, headline, detail)
```

Everything that used to be scattered through `AppBlockerService.isAllowed` lives
here, reads from the stores above, and returns the decision *with the words the
block screen will show*. Enforcement never writes its own copy of a rule or a
message.

Order of precedence, highest first: own package, critical system surfaces, the
USB settings page, a settings page the user opened from inside FocusLock, the
overlay-permission guard, always-allowed, an **overlay** schedule window
(absolute - see below, and [ScheduleWindow.overlay]), an active break pass, an
Earn task's narrowing, an ordinary schedule window, bedtime, place rules, daily
limits, custom rules, then per-app policy. Kiosk inverts the last step into an
allowlist.

The first six rows are the ones that are allowed or blocked *before* the
session gate, and two of them are worth knowing about: the settings and USB rows
exist so a long lock cannot brick a phone, and the overlay-permission guard
fires with no session running at all, because taking away the ability to draw
the block screen is not a settings disagreement.

An overlay window sits above the break pass on purpose: it is checked, and
can return BLOCK, before `TakeABreak.hasActivePass` ever gets a look - a pass
granted before the window started must not survive into it. Everything below
an overlay window's own BLOCK is consequently unreachable for anything outside
always-allowed and that window's own `allowedApps`: no break, no Earn spend, no
custom rule can widen it. The matching real-world half of "absolute" is
`KioskPolicy.buildLockTaskPackages` pinning the phone to that exact same set at
the OS level (see the loophole rule in section 6) - the accessibility check alone is
always a polite request, reachable in the gap between two polls; the
Device-Owner lock task is what makes it actually unbypassable, including the
home screen and every other FocusLock screen. An overlay window also freezes
every rule store while it runs (`CapabilityRegistry.isFrozen`), the same
mechanism a locked session uses, so it cannot be edited away from inside
FocusLock either - the one place that stays reachable inside every overlay
window by design.

`PolicyCache` memoises the hot reads against a `PolicySync` revision counter, so
the loop is not re-parsing JSON four times a second, and a flipped switch still
takes effect on the very next tick.

## 4. Enforcement

```
AppBlockerService     the loop: what is in front, ask the engine, act
ContentGuardService   accessibility; reads on-screen text, matches KeywordRules
GuardState            what the guard saw a moment ago, with a 1.5s expiry
KioskPolicy           every Device-Owner call, each gated on a capability
PolicySync            "the user changed something" -> re-apply, debounced
FocusNotificationService  dismisses alerts from blocked apps
```

The loop runs at 4Hz. It does **not** stay alive just because something is
configured: schedules, bedtime, place rules and daily limits are all no-ops
outside a session (`SessionManager.isEnforcing`), so a watcher sitting up all
night to reach an 8pm window it would only turn away from is battery spent on
nothing. The service starts when a session, a standalone task or a test starts,
and stops when none of them is running - which also means there is no slow idle
rate, because every tick of a running loop is a tick where something could be
in front of the person.

`KioskPolicy.buildLockTaskPackages` is deliberately pure so the most consequential
rule in the app is unit-testable without a device.

## 5. The design system

```
UiPrefs.Tokens   the resolved token set: colours, typeface, radius, density,
                 text scale, reduced motion, the bedtime override
FocusUi          every component, all bound to Tokens
FocusDialog      themed dialogs, because AlertDialog.Builder ignores all of this
Motion           short, eased, and silent when reduced motion is on
Copy             every word the user reads at a hard moment
```

**A screen never names a colour.** It asks `FocusUi` for a component, passes the
tokens, and gets back something already wearing the user's theme. That is why
changing the accent reaches the block screen, the app picker and the browser
rather than just the dashboard.

Screens are built in code rather than XML for the same reason: an XML attribute
is a hardcoded value waiting to drift out of sync. The five layouts that survive
(`activity_webview`, `activity_text_search`, `activity_video_library`,
`activity_anime_folder`, `activity_daily_player`) exist only because they host a
`WebView`, a `RecyclerView` or a `PlayerView`, and they declare structure and
nothing else - as do the three `item_*.xml` row layouts the list adapters
inflate. The single deliberate exception is the video player, which is black.

`FocusScreenActivity` is the base every settings screen sits on: it owns token
resolution, the system bars, the scrolling column, the page header and the
re-render on resume. Subclasses only describe content.

## 6. Earn Mode

Finish real work, unlock the rest of the phone. Off by default, and never
proposed unless the person asks for it by name in onboarding.

```
FocusTasks.kt   FocusTask + FocusTaskStore: the full task panel schema
EarnMode.kt     EarnMode (the deal), EarnSession (one task, one clock),
                EarnPool (the earned minutes)
PhotoProof.kt   on-device verification, no network, no model required
TasksTab        the list, the pool, the deal
TaskEditorActivity   the panel
EarnSessionActivity  the active screen
```

### The loophole rule

The whole feature hangs on one line in `KioskPolicy.buildLockTaskPackages`: when
an Earn task is active it **replaces** the standing allowlist rather than adding
to it, and `EarnSession.allowedPackages` has already intersected the task's apps
with the user's standing allowlist before that point. A task can therefore only
ever subtract.

The one case that is not a plain replace: an active overlay schedule window
(§4) is a ceiling nothing gets to widen, an Earn task included, so there the
task's set is **intersected** with the window's own `allowedApps` rather than
substituted for it - a task cannot open a door the window shut, same rule,
window as the new ceiling instead of the standing allowlist.

Worked example. Sanctuary allows `[Browser, Notes, Calculator]`; the task "Write
essay" asks for `[Docs, Browser]`. `Docs` is not on the standing allowlist, so
the live set passed to `setLockTaskPackages` is `[FocusLock, Browser]` plus
always-allowed and system surfaces. `Docs` cannot launch, and neither can Notes
or Calculator — the task narrowed the mode. The rejected app is named on screen
rather than silently dropped.

Two deliberate exceptions, both stated in the UI:

- **Always-allowed apps are unioned back in.** A task must never be able to lock
  someone out of their own phone calls.
- **The camera is added** for a photo-proof task, because a proof you cannot
  photograph is a task you cannot finish.

A standalone task with no apps named locks to FocusLock alone. A *merged* task
with no apps named changes nothing, which is what leaving the field empty means.

### Why the reward is shaped the way it is

Deci, Koestner and Ryan (1999) found tangible rewards undermine intrinsic
motivation at d = -0.40 when paid for merely engaging, -0.36 for completing, and
-0.28 for performance — worst of all on work the person already enjoys. So the
default rate is performance-contingent (minutes scale with verified focus time,
not with ticking a box), and any task can be marked *enjoyable*, after which it
pays nothing on purpose and says why. Every number belongs to the user.

### The pool

Finishing a task credits minutes to one balance, `EarnPool`, held in
milliseconds. There is nothing to "start": the pool is spent by the apps the
user labelled **Budgeted** (`AppPolicy.LIMIT`), which is what that label now
means.

For a Budgeted app, `RuleEngine.decide` asks two questions in order:

1. Does it still have free daily minutes of its own (`AppLimits`)? Use them.
2. Otherwise, is there anything in the pool at all? If yes, open it — and
   `AppBlockerService` charges the pool for the wall-clock time between ticks
   for as long as that app is the one in front. If no, block it and say so.

`EarnPool.isOnPoolTime` is the single predicate both the engine and the drain
loop call, so "is this app being paid for out of the pool" cannot mean two
different things in two places.

Two consequences worth stating. A Budgeted app with **no** daily limit has no
free allowance at all, so it needs the pool from its first second. And the pool
no longer lifts anything else: it used to open *every* blocked app for the
length of a hand-started window, which is a different feature, and it is gone.

A gap check on the charge is load-bearing. The drain only runs on the path
where a *foreign* app was decided about, so a tick spent inside FocusLock's own
UI - or in the transition grace, or behind a content-guard hit, or with the
process asleep - never reaches it. Without the check the anchor would still
point at the app that was open before, and the pool would be billed for time the
person spent inside FocusLock itself.

Earned minutes lift app rules and session blocks. They deliberately do **not**
lift a schedule, bedtime or a daily budget: those are commitments about *when*,
and the reward was for work. They also cannot be spent inside a kiosk session,
because kiosk's contract is that it runs to the end.

### What photo proof actually checks

A 400-label classifier cannot tell "wrote two pages" from "photographed a
notebook", so `PhotoProof` leads with checks that need no model and catch the
cheats people really use: a perceptual hash refuses a reused photo, a
detail-variance test refuses a wall or a covered lens, and file freshness refuses
anything not taken during the session. The photo is deleted immediately; only a
64-bit fingerprint survives. Content matching is an optional layer —
implement `PhotoProof.ContentMatcher` and uncomment one line in
`build.gradle.kts` for bundled, offline ML Kit labelling. The UI says which of
these is running rather than implying more than it does.

## 7. Navigation

Five tabs, always labelled, always in the same order:

- **Focus** — what is happening now, and the one decision worth making
- **Tasks** — the task list, and the earning layer if it is switched on
- **Library** — the replacement ecosystem: safe browser, text search, video library
- **Rules** — every editor, plus the Capability Registry itself
- **You** — appearance, help, and the way out

`MainActivity` is a shell; tabs are plain view builders (`FocusTab`) rather than
fragments, which keeps state, theming and refresh in one obvious place.

## 8. Tone

`Copy` has two registers. **Kind** is the default: plain, warm, second-person,
and it never implies the person failed. **Plain** is the same information with
the warmth removed, for people who find encouragement patronising. Nothing in
either register scolds, counts failures, or threatens a streak — `Streaks`
pauses on a missed day and keeps the best run, because streak-shattering is the
most common way a habit app turns one bad day into a quit.

## 9. Tiers

Two product flavours, one codebase, and the difference between them is meant to
be legible from inside the app.

| | `full` | `demo` |
|---|---|---|
| `ENFORCEMENT` | true | false |
| `DEMO` | false | true |
| Device Owner | provisionable | `AdminReceiver` removed from the manifest |
| Sessions | unlimited | one, ever |
| Sanctuary, Kiosk | available | shown, locked, and labelled |
| A blocked app | may be forced back to FocusLock | always a screen, never an action |

`ENFORCEMENT` is the OS-level switch: no suspend, no hide, no lock task, no
notification dismissal, no permission emergency. `DEMO` is the product switch,
and it is deliberately separate, because the two questions are not the same one.
The demo has no Device Owner, so every OS-level act was already inert for it —
what `DEMO` decides is the two limits the person actually meets, plus whether
the loop may put the block screen on top of another app at all.

`DemoMode` owns both limits, and enforces them in `SessionManager.start` rather
than only in the UI. A greyed-out tile is a hint; the store is the rule, and it
holds even if a future screen forgets to ask. Its one session is recorded as the
session *begins*, so a second start cannot slip in while the first is running.

Everything downstream of the decision is shared. The demo is not a second,
simpler copy of the engine — it is the same `RuleEngine.decide`, showing the same
words, and the tier only decides what may then be *done* about them.
