# FocusLock v2.0

A calm, offline-first Android focus and digital wellbeing blocker with optional **Device Owner** enforcement (Sanctuary & Kiosk modes that cannot be force-stopped or uninstalled mid-session), Earn Mode, and full English & Arabic (`العربية`) localization.

---

## Why FocusLock

- **2-minute first run:** Pick which apps pull you in (or tap a one-tap preset: **Study**, **Sleep**, or **Social detox**), choose how firm (**Gentle**, **Firm**, or **Unbreakable**), and start blocking immediately with just Usage Access.
- **Unbreakable when you want it:** Standard blockers can be force-stopped or uninstalled the moment an urge hits. A 5-minute optional setup at a PC grants Android **Device Owner** so Sanctuary and Kiosk sessions hold until their timer ends.
- **100% offline & private:** No accounts, no cloud telemetry, no analytics SDKs. Every rule, schedule, daily budget, and usage chart stays on your phone.
- **Arabic-first & English-first:** Every screen, block message, and rule editor is written in warm, blame-free English and Arabic.

---

## Free vs. Pro ($9.99 one-time)

| Included in Free (forever) | Included in FocusLock Pro ($9.99 one-time) |
|---|---|
| Gentle & Block sessions | **Sanctuary & Kiosk** (unbreakable Device Owner modes) |
| App blocking & Website allowlist | **Earn Mode** (complete real tasks to bank leisure minutes) |
| Weekly schedules & Daily limits (minutes & opens) | **Bedtime mode** (automatic night screen dimming & category lock) |
| Keyword guard, Safe browser, Text search, Approved YouTube channels | **Place & Wi-Fi rules** and **Custom if-this-then-that rules** |

- **One-time purchase ($9.99):** Free updates forever. No subscriptions.
- **Offline licence verification:** After checkout at [`https://focus-lock-c0xg.onrender.com/#buy`](https://focus-lock-c0xg.onrender.com/#buy), you receive a signed `FL2-XXXX-XXXX-XXXX-SSSS` licence code verified offline on your device via HMAC-SHA256.
- **14-day refund promise:** If FocusLock is not the right fit for your phone, reply to your receipt within 14 days for a full refund.

---

## Building & Verifying

### Build Debug APK & Run Unit Tests
```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

### Verify APK Checksum (SHA-256)
Before sideloading a release APK, you can verify its integrity:
```bash
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

### Optional 5-Minute PC Setup (Device Owner)
To enable **Sanctuary** and **Kiosk** modes:
1. Enable **Developer options** and **USB debugging** on your Android phone.
2. Connect your phone to a computer and run:
   ```bash
   adb shell dpm set-device-owner com.focuslock.mdm/.AdminReceiver
   ```
   *(Or use the guided Windows setup wizard included in `tools/wizard`.)*
