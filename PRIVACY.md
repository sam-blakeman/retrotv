# Privacy Policy — Retro TV

**Last updated:** November 2026

Retro TV is a free, open-source video and IPTV player built with privacy in mind.

## Data We Collect

**None.** Retro TV has:
- No analytics or telemetry
- No advertising or tracking SDKs
- No developer-operated servers or accounts
- No crash reporting sent to third parties

## Data Stored on Your Device

All settings and credentials stay **exclusively on your device** in Android's private app storage (with cloud backup disabled via `android:allowBackup="false"`):
- **IPTV credentials (optional):** If you choose to connect your own Xtream Codes server, your server address, username, and password are stored locally on your device and sent **only** to the server you configured.
- **Preferences & watch progress:** Your favourite channels, bezel preference, Teletext weather town, added local video folders, and local resume timestamps are stored only on your device.
- **Cached catalogues:** Channel lists and VOD catalogues downloaded from your IPTV server are cached locally on your device to speed up loading.

## Local Video Access

If you grant video media permission (`READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE`) or choose folders using Android's folder picker, Retro TV reads video filenames and plays videos **locally on your device only**. Files and filenames are never uploaded anywhere.

## Third-Party Services Used by Optional Features

- **RETROTEXT Teletext (when opened):**
  - Fetches public news headlines from **BBC News RSS feeds** (`feeds.bbci.co.uk`).
  - Fetches weather forecasts from **Open-Meteo** (`api.open-meteo.com` and `geocoding-api.open-meteo.com`), sending only the town name you entered in Settings (or London by default). No GPS or device location is accessed.
- **Your IPTV Provider (if configured):** Stream requests, EPG lookups, and poster images are requested directly from the server you entered.

## Contact

If you have questions about this privacy policy, please open an issue on the [GitHub repository](https://github.com/sam-blakeman/retrotv).
