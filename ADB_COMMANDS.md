# FocusLock from a computer

Everything here is for your own laptop: provisioning a phone, adjusting a setup
without squinting at a small screen, and the recovery path if a session goes
wrong. All of it needs USB debugging on the phone, so if you have used the
**Turn off ADB debugging** button in the You tab, none of it will work until the
phone is factory reset or a session that hands the phone back ends.

---

## 1. Provisioning as Device Owner

Device Owner is the one thing FocusLock cannot grant itself, and it is what
makes kiosk mode real. Without it the launcher stays reachable, Safe Mode boots
the phone with FocusLock inert, and apps cannot be suspended or hidden.

**Android refuses to set a Device Owner while any account exists on the device.**
That is the step people miss.

```bash
# 1. On the phone: remove every Google (and other) account, and any work profile.
#    Settings > Passwords & accounts > remove each one.

# 2. On the phone: Settings > About phone > tap Build number seven times,
#    then Developer options > USB debugging = on.

# 3. Plug in and accept the debugging prompt on the phone, then:
adb devices
# The phone must be listed as "device", not "unauthorized" or "offline".

# 4. Install the APK if it is not already there.
#
#    Use the **full** build. The demo flavour removes AdminReceiver from the
#    manifest outright, so `dpm set-device-owner` has no component to point at
#    and step 5 will fail - that is the demo's whole safety guarantee, not a bug.
#
#    The APK is not at apk/debug/app-debug.apk: the project has a "tier" flavour
#    dimension, so every output lives under its flavour directory.
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk

# 5. Make FocusLock the device owner:
adb shell dpm set-device-owner com.focuslock.mdm/.AdminReceiver
```

Success looks like:

```
Success: Device owner set to package com.focuslock.mdm
Active admin set to component {com.focuslock.mdm/com.focuslock.mdm.AdminReceiver}
```

Common failures:

| Message | Cause |
|---|---|
| `Not allowed to set the device owner because there are already several users` | Another user or work profile exists. Remove it. |
| `Not allowed to set the device owner because there are already some accounts` | An account is still signed in. Remove all of them. |
| `Device owner cannot be set after device setup is complete` | Some OEM builds only allow this before first-boot setup finishes. Factory reset, skip account sign-in, then run the command. |

Verify from the phone: **You → Device Owner setup** should read *Active*.

### The QR route

A factory-reset phone shows a QR scanner if you tap the welcome screen six
times. QR provisioning sets Device Owner without a computer, but it needs the
APK hosted at a URL along with its signature checksum, and it wipes the phone as
part of setup. For a phone already in use, the ADB route above is the practical
one.

### Removing Device Owner

```bash
adb shell dpm remove-active-admin com.focuslock.mdm/.AdminReceiver
```

This only works while `DISALLOW_*` restrictions are not blocking it, which in
practice means outside an active session. The in-app path is to start a session
with **Hand the phone back at the end** switched on, and let it finish.

---

## 2. Remote commands

The receiver is guarded by `android.permission.DUMP`, which only ADB and system
apps hold, so no ordinary app can send these. Always target the component
explicitly:

```bash
adb shell am broadcast -n com.focuslock.mdm/.AdbCommandReceiver \
  -a com.focuslock.mdm.ADB_COMMAND --es cmd status
```

Read the results with `adb logcat -s FocusLockAdb`.

Every command that writes something logs either what it did or **why it
refused**. A refusal during a running session is expected, not a fault: the rule
freeze lets a change that blocks *more* through and holds back anything that
blocks *less*, so `allow_app` mid-session is declined on purpose and the log says
so. End the session first (see Recovery) if that is what you meant.

### App rules

```bash
# Set what happens when an app is opened
--es cmd allow_app  --es value com.example.app    # opens freely
--es cmd pause_app  --es value com.example.app    # breathing pause first
--es cmd block_app  --es value com.example.app    # does not open in a session
--es cmd clear_app  --es value com.example.app    # back to following its category

# Protect an app from every rule, session and schedule
--es cmd always_allow --es value com.android.phone
```

### Sites and words

```bash
--es cmd add_website    --es value https://example.com
--es cmd remove_website --es value https://example.com
--es cmd add_keyword    --es value "for you"
```

### Inspection and maintenance

```bash
--es cmd status         # dumps session state and every capability switch
--es cmd sync_policy    # re-applies Device Owner policy immediately
--es cmd start_service  # restarts the enforcement service
```

### Recovery

Ending a session from a computer needs an explicit confirmation flag, so it can
never happen by a mistyped command:

```bash
adb shell am broadcast -n com.focuslock.mdm/.AdbCommandReceiver \
  -a com.focuslock.mdm.ADB_COMMAND --es cmd stop_session --ez confirm true
```

This ends the session, un-suspends and un-hides every managed app, and clears
lock-task policy. It is logged.

Deliberately **not** available over ADB: turning a capability *on*. Provisioning
a phone should not be able to enable a guard the person never chose.

---

## 3. What no software tier can close

Stated plainly, because the alternative is a false sense of security:

- **Factory reset from recovery always works.** FocusLock never sets
  `DISALLOW_FACTORY_RESET`, and the You tab keeps a reset button behind a
  countdown. This is the intended ultimate exit, and it erases the phone.
- **ADB is only closed if you close it.** Provisioning does not disable it and
  neither does starting a session. Only the button in the You tab does.
- **There is no server.** No account, no remote admin, nobody else who can lock
  or unlock this phone. If that is what you needed, this is the wrong tool.
