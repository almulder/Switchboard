# Switchboard — design (v0.1)

Switchboard is a Hubitat app that delivers announcements, notifications and siren alerts on behalf of any other app.
Automations (webCoRE pistons, Rule Machine rules, HSM, etc.) send to **Switchboard virtual devices**. Switchboard
decides how, when and whether the real speakers, phones and sirens are used.

Goals: simple to set up, no changes to how people write rules, nothing silently lost, and safe testing.

## 1. Files

| File | Type | Purpose |
|---|---|---|
| `apps/Switchboard.groovy` | app (`namespace "almulder"`, `singleInstance: true`, `installOnOpen: true`, `menu: "Automations"`) | all settings, the speech queue, routing, modes, log |
| `drivers/Switchboard_Speaker.groovy` | driver "Switchboard Speaker" | virtual speaker, one per Announcement Profile |
| `drivers/Switchboard_Notifier.groovy` | driver "Switchboard Notifier" | virtual notification device |
| `drivers/Switchboard_Siren.groovy` | driver "Switchboard Siren" | virtual siren |

All four start at **0.1.0**. Header, author and licence like Rule Forge (PolyForm Noncommercial 1.0.0).

## 2. Virtual devices (children of the app)

Created with `addChildDevice("almulder", "<driver name>", dni, [name:, label:, isComponent: false])`.
DNIs: `SB-<appId>-spk-<id>`, `SB-<appId>-ntf-<id>`, `SB-<appId>-snd-<id>` (siren). Ids are short (`p1`, `n1`, `r1`).
Labels: "Announce – <profile name>", "Notify – <name>", "Siren – <name>"; renaming in the app relabels the device.
Deleting an item in the app deletes its device (with a confirm link). `uninstalled()` deletes all children.

### Contract: driver → app (the only calls drivers make)

```
parent.sbSpeak(String dni, Map req)    // req: [cmd: <command name>, text: String|null, uri: String|null,
                                       //       volume: Integer|null, sound: String|null (sound name)]
parent.sbStop(String dni)
parent.sbVolume(String dni, Integer volume)   // setVolume / volumeUp / volumeDown / mute result
parent.sbNotify(String dni, String text)
parent.sbSiren(String dni, String mode)       // "siren" | "strobe" | "both" | "off"
```

Drivers keep no logic beyond parsing arguments, calling the parent, and showing attributes. The app updates driver
attributes with `child.sendEvent(name:, value:)` on the child device (ChildDeviceWrapper supports `sendEvent`).

### Speaker driver
Capabilities: `Actuator`, `SpeechSynthesis`, `AudioNotification`, `MusicPlayer`, `AudioVolume`, `Refresh`.
Accept every signature other apps use, all forwarded with `cmd` = the command name:
`speak(text)`, `speak(text, volume)`, `speak(text, volume, voice)`, `playText(text[, volume])`,
`playTextAndRestore(text[, volume])`, `playTextAndResume(text[, volume])`, `playTrack(uri[, volume])`,
`playTrackAndRestore(uri[, volume])`, `playTrackAndResume(uri[, volume])`, `playAnnouncement(text[, volume[, …]])`
(Echo Speaks style, extra args ignored), custom command `playSound(String name)` (a sound from the app's library by
name), `setVolume(n)`, `volumeUp()`, `volumeDown()`, `mute()`, `unmute()`, `stop()`. MusicPlayer commands that make
no sense (`play`, `pause`, `nextTrack`, `previousTrack`, `setLevel`, `setTrack`, `restoreTrack`, `resumeTrack`)
exist so pickers and rules don't error; they log "not used" (setLevel = setVolume).
Attributes: `status` (idle / queued / playing), `volume` (the last volume a rule asked for), `mute`, `lastMessage`,
`lastSource`, `queueLength`, `trackDescription` (= lastMessage, for dashboards).

### Notifier driver
Capabilities: `Actuator`, `Notification`. `deviceNotification(text)` → `sbNotify`. Attributes `lastMessage`,
`lastSource`.

### Siren driver
Capabilities: `Actuator`, `Alarm`, `Switch`. `siren()`, `strobe()`, `both()`, `off()`; `on()` = both. Attributes
`alarm`, `switch` reflect what Switchboard did (in Test Mode: the requested state, so rules that check it still work;
`lastMessage` says "Test Mode — sirens not sounded").

## 3. App pages

Main page (status first): Test Mode / Guest Mode pills with on/off buttons, queue status, last 10 log lines, then
links: **Announcement Profiles**, **Notifications**, **Sirens**, **Sound files**, **Speakers & queue**,
**Test Mode**, **Guest Mode**, **Message log**, **Settings**. Reuse Rule Forge's UI kit (`uiCss`, `uiHeader`, ⓘ tips
via fixed-position CSS tooltip, pills). Plain short wording, detail in tips.

### Announcement Profiles (each = one virtual speaker)
`prof_<id>_name`, `prof_<id>_spk` (real speakers, `capability.speechSynthesis`, multiple),
`prof_<id>_priority` normal / urgent, volume: `prof_<id>_volMode` daynight / fixed / caller ("Use the volume the rule
sends"), `prof_<id>_dayVol` (default 70), `prof_<id>_nightVol` (40), night window `nightStart`/`nightEnd` are global
(Settings, default 21:00–08:00), `prof_<id>_fixedVol`; `prof_<id>_chime` (sound, optional, played before each text);
`prof_<id>_dedupeSec` "Skip the same message if it was played in the last (seconds)" default 15; `prof_<id>_staleSec`
(normal only, default 30); `prof_<id>_guest` normal / quiet ("Stay silent during Guest Mode") / only; urgent profiles
default normal and show "Safety alerts should keep playing during Guest Mode". Test button. "Used by" shows the
Switchboard device name (other apps can't be queried).

### Notifications (each = one virtual notifier)
`ntf_<id>_name`, `ntf_<id>_devs` (`capability.notification`, multiple), `ntf_<id>_when` always / away ("Only when
nobody is home", uses `homeSensors` from Settings), `ntf_<id>_dedupeSec` (default 15), `ntf_<id>_guest`
normal / quiet / only, `ntf_<id>_prefix` (optional text added before each message), `ntf_<id>_pushover` bool
"Mark as high priority (Pushover [H])". Test button.

### Sirens (each = one virtual siren)
`sir_<id>_name`, `sir_<id>_devs` (`capability.alarm`, multiple, tip: some devices claim Alarm but aren't sirens, e.g.
power monitors), `sir_<id>_maxMin` "Stop after (minutes)" default 5, `sir_<id>_guest` normal / quiet.

### Sound files
As FEATURE_SPEC_v2 §1.1: `state.sounds` id → [name, url, secs]; inputs `snd_<id>_name/_url/_secs`, add/delete/test.
Tip: Hubitat File Manager files are `http://<hub-ip>/local/<file>.mp3`.

### Speakers & queue
Per real speaker: method (auto / echo / setThenSpeak / speakVol / playTextRestore / speakOnly), extra wait, delay
between setVolume and speak, restore volume. Queue mode global / overlap, duration measure / estimate, gap, max queue.
(Copy from Rule Forge.)

### Test Mode (replaces "Monthly Test")
**Only the app can turn Test Mode on or off. There is no Test Mode switch device** (no `tmSource`, `tmLabel`,
`tmOutside`; nothing outside the app can start it). State lives in `state.tmOn` / `state.tmOffAt`.
Testers `tmDevices` (`capability.notification`, multiple); `tmAutoOff` "Turn off after" — enum 15 / 30 / 60 / 120 /
180 / 240 minutes, default 60, **never more than 4 hours** (clamp any value to 240); `tmRemind` minutes.
**A hub reboot turns Test Mode off** (`systemStart` → off, push "Test Mode turned off: hub restarted"). After it
ends (timer, reboot or button), it can only be turned on again in the app, with the acknowledgement ticked again.
**While on:** nothing is spoken, no sounds or chimes, no sirens. Every announcement, notification and siren request
is pushed to the testers instead, as `TEST – <source>: <text>` (`🔊 <sound>` for sounds, `🚨 Sirens would sound
(<name>)` for sirens). Normal notifiers still deliver nothing else. Same-text dedupe 2 s (speech + push of one alert
= one tester message).
**Acknowledgement every time:** the Test Mode page shows a warning box and a checkbox
`tmAck` "I understand: while Test Mode is on, alarms, sirens and announcements in the home are silenced — a real
emergency only reaches the testers' phones. Smoke alarms with their own sounders still sound." The **Turn on Test
Mode** button only works when it is ticked; `tmAck` is cleared every time Test Mode turns off.
Turning on always pushes the warning text and the auto-off time to the testers; reminders repeat it.

### Guest Modes (as many as you like)
A list of named Guest Modes, e.g. "Guest Mode -SB", "Guest Mode Paul", "Guest Mode Lily", "Housekeeper". Ids `g1`,
`g2`…; stored in `state.guests` id → [name]. Add with `newGuestName` (pre-filled **"Guest Mode -SB"** for the first
one, blank afterwards) + `btnAddGuest`; delete link per mode (confirm).
Per Guest Mode `<id>`:
- `gm_<id>_name` (renaming relabels its switch);
- `gm_<id>_source` create (default: Switchboard creates a Virtual Switch labelled with the name; a normal,
  non-component switch that webCoRE, Rule Machine and dashboards can use and turn on/off freely) / existing switch
  (`gm_<id>_switch`) / hub modes (`gm_<id>_modes`);
- `gm_<id>_endDate` + `gm_<id>_endTime` and an "On until…" button; `gm_<id>_defaultHours` "If turned on another
  way (dashboard, rule), turn off after (hours)", default 24, 0 = stay on; `gm_<id>_remindTime` + reminder
  notifiers `gm_<id>_remindDevs`.
Guest Mode pills on the main page show each one that is on. On/off buttons per mode on the Guest Modes page.

**Assigning them:** every profile, notifier and siren (prefix = `prof_<id>`, `ntf_<id>`, `sir_<id>`) has:
- `<prefix>_guestPlay` "Play while any of these Guest Modes are on" (multi-select);
- `<prefix>_guestQuiet` "Stay silent while any of these are on" (multi-select; its options leave out the modes picked
  in `_guestPlay`, so a mode can never be in both lists — if one somehow is, treat it as Play);
- `<prefix>_guestOther` "The rest of the time": play (default) / silent ("only plays while a Guest Mode above says
  Play" — e.g. a "Housekeeper is here" notifier).
Rule, checked for each message: (1) any `_guestPlay` mode on → deliver (**Play always wins**); (2) else any
`_guestQuiet` mode on → skip; (3) else follow `_guestOther`. Several Guest Modes can be on at once and share devices.
Urgent profiles show the hint "Safety alerts should keep playing during Guest Mode". (Replaces the single `_guest`
setting and `_guestOnly`.)

### Message log
Last 100 entries in `state.log` (time, device, kind speak/sound/push/siren, text, outcome: played / queued / skipped
duplicate / skipped stale / skipped guest / test → testers / failed, source). Page shows newest first with filters
by device and outcome; "Clear log" button.

### Settings
`nightStart`, `nightEnd`; `homeSensors` (presence, for "only when nobody is home"); `srcLookup` bool "Find which
rule sent each message" (default on); optional `hubUser`/`hubPass` for Hub Security (tip: only needed if Hub Login
Security is on); logging (debug auto-off 30 min, description).

## 4. Behaviour

**Speech queue:** reuse Rule Forge's engine (ConcurrentLinkedQueue per app, semaphore, measured TTS duration, urgent
queue interrupts normal but never another urgent, stale drop, per-speaker methods, volume restore, Sonos
`playHighPriorityTTS` for urgent). Each item: [dni, profile, text|uri|sound, kind, callerVolume, queuedAt, source].
Order per profile item: chime → sound/track → text; busy time = sound secs or measured text length + lead + gap.
Track playback: `playTrackAndRestore(url, vol)` if supported, else set volume + `playTrack(url)` and restore after
`secs`. A `playTrack(uri)` from a rule whose uri matches a library sound uses that sound's length; otherwise
`defaultTrackSecs` (Settings, default 10).

**stop():** removes the *waiting* items of that virtual speaker. If the item currently playing came from that same
virtual speaker, also sends `stop()` to its real speakers and frees the queue. Never touches other devices' items.

**setVolume on a virtual speaker:** stored as the device's `volume` attribute; used only when the profile's
volMode is `caller`. Never sent to real speakers outside an announcement (fixes rules that change music volume).

**Duplicate filter:** same text on the same virtual device within `dedupeSec` → skipped, logged.

**Guest Modes:** apply the `_guestPlay` / `_guestQuiet` / `_guestOther` rule above (Play wins); log "skipped – Guest Mode Paul" (the mode's
name).

**Test Mode:** overrides everything above (always delivers to testers, even if the device is Guest-quiet or the
message is a duplicate within dedupe — testers see every event; tester dedupe 2 s only).

**Source lookup:** ~1500 ms after a command arrives, `asynchttpGet` `http://127.0.0.1:8080/device/eventsJson/<deviceId>`
(cookie from optional login), find the newest `type == "command"` row for that command within ±5 s (and matching
text where present), strip HTML from `producedBy` → source name (e.g. "Goodnight 🌙"). Update the log entry and the
device's `lastSource`. The `producedBy` link's app id is then looked up once (`/installedapp/statusJson/<appId>` → `installedApp.name`,
e.g. "webCoRE Piston", "Rule-5.1") and cached, so the source reads "WC – Goodnight" (WC webCoRE, RM Rule Machine,
BR Basic Rules, HSM, RL Room Lighting, BC Button Controllers, SAR Simple Automation Rules, NT Notifications,
API Maker API, DB dashboards; any other app shows its own type). A legend sits above the log.
Real speakers are only ever driven from Switchboard's own scheduled run (never inside the rule's call), so a real
speaker's Events page always credits "Switchboard". Cost ≈ 0.3 s.
Text prefix `[Name] ` at the start of a message overrides the lookup and is removed before
speaking/pushing. Failures → "unknown"; never delays delivery. Undocumented endpoint: wrap all parsing in try/catch.

**Hub restart:** `systemStart` → Test Mode off (push to testers), re-initialize, clear stale busy state. The queue
lives in memory, so messages still waiting at a reboot or code save are lost; their log entries say
"failed – hub restarted". (Replaying minutes-old alerts after a reboot would be worse.)

**Turning Test Mode on:** messages still waiting in the queue are pushed to the testers, never dropped. Sirens that
are already sounding keep sounding until their "Stop after" time (a possibly real alarm is not silenced).

## 5. Not in v0.1
Device Groups, Shared Values, rule types, templates (all from Rule Forge) — dropped. Per-person routing beyond
separate profiles/notifiers. HPM manifest (before public release).
