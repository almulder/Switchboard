# Switchboard

> 🧪 **Beta — testers wanted.**
> Switchboard works and runs every day on my own hub, but nobody else has tried it yet. It is **not in HPM** until a few people have tested it, so for now it installs by hand (steps below). Feedback, bugs and ideas are very welcome in [Issues](../../issues).

Switchboard is a companion app for **Hubitat Elevation**. It sits between your automations and your speakers, phones and sirens, and handles **how and when** announcements and alerts are delivered.

It works alongside the rules you already have. It does not replace them. Anything that can send a command to a device can use it: webCoRE, Rule Machine, Basic Rules, Hubitat Safety Monitor, Room Lighting, Button Controllers, the Notifications app, Maker API and dashboards.

## Why it exists

I kept running into the same problems:

- Announcements from different rules talked over each other or got cut off, so I was adding guessed pauses everywhere.
- Urgent alerts (smoke, CO, water, intrusion) waited behind everyday chatter.
- The same announcement played twice within seconds.
- One rule's `stop()` or `setVolume()` broke another rule's announcement, or the music that was playing.
- Every alert rule had to check Guest Mode, test switches and quiet hours on its own.

Switchboard fixes those in one place, so every rule gets the fix without being rewritten.

## How it works

Switchboard creates virtual devices, all under one **Switchboard** parent device. Your rules send to these instead of the real speakers and phones:

- **Announce – … speakers** (one per Announcement Profile, e.g. "Announce – Doors", "Announce – Urgent"). Every message goes into one shared queue that knows how long each message takes, so nothing overlaps or gets cut off. Urgent profiles jump to the front. Volume is set and restored per profile, with separate day and night levels. Chimes and MP3 sound files are supported, including a chime that runs straight into the words.
- **Notify – … devices** (e.g. "Notify – Everyone", "Notify – Text when away"). They forward pushes, texts and email to the real devices you pick, skip duplicates, and can send only when nobody (or a chosen person) is away. Email keywords (`Subject:`, `File:`, `To:`, `From:`) pass through to email devices and are stripped for phones.
- **Siren – … devices.** These sound the real sirens, check that they really turned off afterwards, and retry if not.

Then a few settings apply to every rule at once:

- **Test Mode:** nothing in the house speaks or sounds; every alert goes to the testers' phones instead. Turns itself off after a time you choose. Pistons can read it from the parent device's `testMode` attribute.
- **Modes:** for example "Guest Mode, Lily" or "Yard Work". Each mode has a switch, and each profile decides what it does while that mode is on. Safety alerts always play.
- **Stop all:** disarming HSM, the Stop All switch, or a button stops everything queued or playing. Messages sent after the stop (like your piston's "disarmed") still play.
- **People:** one combined presence device per person ("Presence – Name - SB") that merges their phone app, OwnTracks and home Wi-Fi, ignores stale or stuck sources, and offers an optional "Presence – Anyone Home - SB". Use it in pistons, HSM and the notification "When" rules.
- **Message log:** the last 100 messages, showing what was played, skipped or sent, when, and which rule sent it (WC, RM, BR, HSM…), with filters.

## Requirements

- Hubitat Elevation, platform **2.5.0** or newer
- The Switchboard app and all six Switchboard drivers (below)

## Install (manual, until it's in HPM)

**1. Drivers.** In Hubitat, go to **Drivers code → New driver → Import**, paste each URL below, click **Import**, then **Save**. Repeat for all six:

```
https://raw.githubusercontent.com/almulder/Switchboard/main/drivers/Switchboard_Parent.groovy
https://raw.githubusercontent.com/almulder/Switchboard/main/drivers/Switchboard_Speaker.groovy
https://raw.githubusercontent.com/almulder/Switchboard/main/drivers/Switchboard_Notifier.groovy
https://raw.githubusercontent.com/almulder/Switchboard/main/drivers/Switchboard_Siren.groovy
https://raw.githubusercontent.com/almulder/Switchboard/main/drivers/Switchboard_Switch.groovy
https://raw.githubusercontent.com/almulder/Switchboard/main/drivers/Switchboard_Presence.groovy
```

**2. App.** Go to **Apps code → New app → Import**, paste this URL, click **Import**, then **Save**:

```
https://raw.githubusercontent.com/almulder/Switchboard/main/apps/Switchboard.groovy
```

**3. Add it.** Go to **Apps → Add user app → Switchboard**. On newer firmware it's listed under **Automations** in the sidebar once installed. It creates the Switchboard parent device on first open.

**Updating:** re-import the app and any changed drivers the same way (open the code, **Import**, **Save**), then open the app and click **Done**. The version is shown in each file's header.

## Quick start

1. **Speakers & queue:** check how each real speaker should speak. Auto-detect works for most; Sonos and Echo Speaks have their own options.
2. **Announcement Profiles:** add one (e.g. "Doors"), pick the real speakers and the day/night volume. Save. You now have an "Announce – Doors" device.
3. In your piston or rule, send the speak command to **Announce – Doors** instead of the real speaker.
4. Add **Notifications** and **Sirens** the same way when you're ready.
5. Try **Test Mode** before you trust it with real alerts.

> ⚠️ While Test Mode is on, alarms, sirens and announcements in the home are silenced, and a real emergency only reaches the testers' phones. Smoke alarms with their own sounders still sound.

## What I'd like from testers

- Which speakers and notification devices you use (Sonos, Echo Speaks, Google, phones, email…) and whether they behave.
- Anything confusing in setup.
- Anything in the message log that doesn't match what you heard.

Please open an [Issue](../../issues) or message me on the Hubitat Community forum.

## Status

Beta. Settings and device names may still change before the first HPM release. Design notes are in [docs/DESIGN.md](docs/DESIGN.md).

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md): free for personal and other noncommercial use. Commercial use of any part of this code is not permitted.
