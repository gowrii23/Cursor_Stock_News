# BSE Blueprint Screener

Personal Android APK: EOD news-driven overreaction screener for Nifty 50 + Next 50.

**Not an auto-trade signal.** Ranked, tagged dashboard only. Personal use.

## What it does

Flags stocks where:

1. Price fell idiosyncratically vs beta-implied index move (`z < -1.5` by default)
2. Matching news exists (Pulse / NSE / demo fallback)
3. Severity keyword filter tags `CANDIDATE` / `EXCLUDE` / `UNKNOWN`
4. Optional BSE Blueprint theme tags apply
5. Composite conviction score ranks the watchlist

## Stack

- Kotlin UI (Dashboard, Stock Detail + MPAndroidChart, News Feed, Settings)
- Python logic via [Chaquopy](https://chaquo.com/chaquopy/) (`pandas`, `numpy`, Yahoo chart API via `requests`, `feedparser`)
- WorkManager daily job (Wi‑Fi / charging constraints configurable)
- On-device SQLite history

## Build (debug APK)

```bash
export ANDROID_HOME=$HOME/android-sdk   # or your SDK path
./gradlew :app:assembleDebug
```

APK output:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First launch

- Seeds a **demo** watchlist offline so UI works immediately
- Pull-to-refresh / menu **Run screen** tries live `yfinance` + Pulse; falls back to demo if network/data fails
- Tune keywords, z-threshold, and Blueprint JSON in **Settings**

## Project layout

```
app/src/main/java/.../ui/      # Activities
app/src/main/java/.../work/    # WorkManager
app/src/main/python/           # Screener pipeline
app/src/main/assets/           # Universe + Blueprint tags + defaults
```

## Notes

- Universe list is static JSON (refresh quarterly)
- Severity keywords are a living config — expect iteration
- Chaquopy free tier is intended for open-source / evaluation; review [Chaquopy licensing](https://chaquo.com/chaquopy/) before redistribution
APK download helper
