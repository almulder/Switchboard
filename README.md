# Switchboard

> ⚠️ **Work in progress — not ready for use.**
> There is no installable code here yet. Please don't install anything from this repository until a release is announced.

Switchboard is a planned companion app for **Hubitat Elevation**. It sits between your automations and your speakers, phones and sirens, and handles how and when announcements and alerts are delivered.

It works alongside the rules you already have. It does not replace them. Anything that can send a command to a device should be able to use it: webCoRE, Rule Machine, Basic Rules, Hubitat Safety Monitor, the Notifications app, Maker API and dashboards.

## The problem it solves

- Announcements from different rules talk over each other or get cut off, so you end up adding guessed pauses.
- Urgent alerts (smoke, CO, water, intrusion) wait behind everyday chatter.
- The same announcement plays twice within seconds.
- One rule's `stop()` or `setVolume()` interferes with another rule's announcement or with your music.
- Every alert rule has to check Guest Mode, test switches and quiet hours on its own.

## How it will work (planned)

Switchboard creates virtual devices. Your rules send to these instead of the real speakers and phones:

- **Virtual speakers** (one per announcement profile, e.g. "Announce – Everyday", "Announce – Urgent"). Every message goes into one shared queue that knows how long each message takes. Urgent messages go to the front, and volume is set and restored per profile. Sound files (MP3) and chimes are supported.
- **Virtual notifiers** (e.g. "Notify – Everyone", "Notify – Text when away"). They route pushes, texts and email to the real devices, skip duplicates, and can send only when nobody is home.
- **Virtual sirens.** These sound the real sirens, except during testing.

Global modes then apply to every rule in one place:

- **Test Mode:** nothing speaks or sounds; every alert goes to the testers' phones instead.
- **Guest Mode:** choose per virtual device what stays quiet. Safety alerts always play.
- **Quiet hours:** day and night volume per profile.

A message log shows what was said or sent, when, and which rule sent it.

## Status

Design stage. Planned features may change before the first release.

## Feedback

Ideas and questions are welcome in [Issues](../../issues).

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md): free for personal and other noncommercial use. Commercial use of any part of this code is not permitted.
