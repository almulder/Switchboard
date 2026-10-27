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
| `drivers/Switchboard_Parent.groovy` | driver "Switchboard Parent" | the one "Switchboard" device; every other device is its child |
| `drivers/Switchboard_Switch.groovy` | driver "Switchboard Switch" | Stop All and Guest Mode switches |

The drivers start at **0.1.0**. Header, author and licence like Rule Forge (PolyForm Noncommercial 1.0.0).

## 2. Virtual devices (children of one "Switchboard" parent device)

The app's only child is the parent device "Switchboard" (driver "Switchboard Parent", DNI `SB-<appId>-hub`, created
in `initialize()`). Every other Switchboard device is a child of that device, so they sit together under one entry in
the device list (Albert, 0.6.0). The parent driver creates them with
`addChildDevice("almulder", "<driver name>", dni, [name:, label:, isComponent: false])` when the app calls
`sbAddChild(driver, dni, label)`; the app also uses `sbChild(dni)`, `sbChildren()`, `sbDeleteChild(dni)`,
`sbRelabel(dni, label)` and `sbEvent(dni, name, value)` on it. Children call the parent device with the contract below
and the parent passes each call on to the app unchanged. No upgrade path from 0.5.x (app-child devices): their
DNIs clash, so 0.5 devices are deleted by hand before the 0.6 code goes on (only Albert's test hub had 0.5).
DNIs: `SB-<appId>-spk-<id>`, `SB-<appId>-ntf-<id>`, `SB-<appId>-snd-<id>` (siren). Ids are short (`p1`, `n1`, `r1`).
Labels: "Announce – <profile name>", "Notify – <name>", "Siren – <name>"; renaming in the app relabels the device.
Deleting an item in the app deletes its device (with a confirm link). `uninstalled()` deletes the parent's children, then the parent.

**Missing devices (0.7.0, Albert):** a device deleted outside Switchboard (Devices page) is never re-created on its own
(not on Done, not after a reboot). Devices are created only when a new item is saved, or by **Recreate**.
- Missing = a saved profile / notifier / siren with no device; a Guest Mode (source "create") whose switch was made
  once (`state.guests[gid].made`) and is gone; the Stop All switch when the option is on and it was made once
  (`state.stopMade`).
- Shown: a warning on the main page listing them, each linking to its page (Stop All → Settings), plus "Recreate all"
  when there are several; a red "device missing" pill on list rows; on the item's page a warning with **Recreate
  device / switch** (Delete is at the bottom as usual); the app's name in the Apps list gets
  `<span class='sb-missing' style='color:#d92d20'>(N devices missing)</span>` (`app.updateLabel`, refreshed on
  initialize, main page render, Recreate, and ~3 s after a child is deleted).
- Recreate makes a NEW device (new id): rules must be pointed at it again and it must be ticked in webCoRE.
- Every child driver's `uninstalled()` calls `parent.sbChildRemoved(dni)`; the parent passes it to the app.

**Leftover settings:** `initialize()` removes `<kind>_<id>_*` settings whose id isn't in that kind's state map
(drafts included), e.g. a deleted Guest Mode's name re-saved by its open page right after Delete.

**Stop All switch:** when `stopSwitchOn` is on (default), Switchboard also creates a normal (non-component) child
switch (driver "Switchboard Switch", DNI `SB-<appId>-stopall`, label "Switchboard – Stop All") for
dashboards, buttons and voice. Turning it on runs Stop all (§4) and Switchboard turns it back off after ~2 s.
Turning the setting off deletes it (if the hub refuses, the error is shown and the switch is kept). Guest Mode
switches use their own DNIs (`SB-<appId>-gm-<id>`).

**Switchboard Switch** (Stop All, created Guest Mode switches): `on()` / `off()` set `switch` and call
`parent.sbSwitch(dni, "on"|"off")`. The app handles it ~50 ms later (`runInMillis`, `overwrite: false`), like an event
subscription, so the app's own `on()` / `off()` never re-enter it. Switches picked from elsewhere ("existing" Guest
Mode source) are still subscribed.

### Contract: driver → app (the only calls drivers make)

```
parent.sbSpeak(String dni, Map req)    // req: [cmd: <command name>, text: String|null, uri: String|null,
                                       //       volume: Integer|null, sound: String|null (sound name)]
parent.sbStop(String dni)
parent.sbVolume(String dni, Integer volume)   // setVolume / volumeUp / volumeDown / mute result
parent.sbNotify(String dni, String text)
parent.sbSiren(String dni, String mode)       // "siren" | "strobe" | "both" | "off"
parent.sbSwitch(String dni, String value)     // Switchboard Switch: "on" | "off"
```

Drivers keep no logic beyond parsing arguments, calling the parent, and showing attributes. The app updates driver
attributes through the parent device (`sbEvent`, which calls `sendEvent` on its child).

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

### Setup lists and edit pages (Announcement Profiles, Notifications, Sirens, Sound files, Guest Modes)
Each kind has a **list page** (`profilesPage`, `notifyPage`, `sirensPage`, `soundsPage`, `guestPage`): header and
hint, then one `href` row per item (title = name plus status pills such as "no speakers", "URGENT", "SOUNDING",
"no URL", "ON"/"off"; description = a short summary, e.g. "1 speaker · fixed volume 20 · normal", "2 devices ·
always", "1 siren · stop after 5 min", "chime.mp3 · 5 s", "ON until … · own switch"), then an **Add new …** href.
No hideable sections and no name field on the list page.
Each item opens its own **edit page** (`profileEditPage`, `notifyEditPage`, `sirenEditPage`, `soundEditPage`,
`guestEditPage`) via `href … params: [id: <id>]`; Add opens it with `params: [id: "new", n: <nonce>]`. Hubitat drops
params on submitOnChange redraws, so the id is kept in `state.editId_<kind>` (the nonce stops a redraw that resends
"new" from making a second draft). The edit page shows all of the item's inputs (Name first) in normal sections,
test buttons and "Used by", then **Save** and a two-step **Delete** ("Yes, delete" / "Cancel", `state.confirmDel`).
**New items are drafts:** "new" allocates the next real id and stores it with `draft: true`. Drafts have no device
or switch and are left out of every runtime path (`ids()`, `soundIds()`, `guestIds()`, routing, Guest Mode checks,
counts, "Used by"). Save on a draft needs a name (sounds also a URL), clears `draft`, and creates the device / switch.
Every page except that kind's edit page (and `initialize()`) calls `cleanDrafts()`, which deletes unsaved drafts and
removes all their `<kind>_<id>_` settings. Delete on a draft just discards it.
**Existing items:** inputs are stored as they change; Save relabels the device ("Changes are saved as you make
them."). Buttons can't navigate, so after Save the page shows "✓ Saved" and a "Back to <list>" link. Delete works as
before (device first; the item stays if the hub refuses, with the error shown).

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

**Make sure sirens turn off** section (on the Sirens list page): `sirenVerify` bool (default on) "Check that sirens
really turn off and retry" (tip: checks 2 s after the off, every 2 s for 10 s, then every 15 s up to 10 min; it can
only see what the siren reports); `sirenAlertDevs` (`capability.notification`, multiple) "Tell me if a siren won't
turn off" — empty = the Test Mode testers (`tmDevices`); both empty = log + `log.warn` only.

### Sound files
As FEATURE_SPEC_v2 §1.1: `state.sounds` id → [name, url, secs]; inputs `snd_<id>_file/_url/_name/_secs` on the
sound's edit page (a new sound's are the draft's own settings), delete, test (`sndTestSpk` picks the test speaker).
Tip: Hubitat File Manager files are `http://<hub-ip>/local/<file>.mp3`.

**One "Sound" menu (0.5.0):** `snd_<id>_file` (enum) lists the hub's File Manager audio files
(`hubAudioFiles()`: `GET http://127.0.0.1:8080/hub/fileManager/json`, .mp3/.wav/.m4a/.aac/.ogg/.flac, label
"name  (N KB)") followed by a last option `__url` "🌐 Use a web address (URL)…".
- Picking a hub file fills `snd_<id>_url` with `hubFileUrl(file)` (`http://<hub-ip>/local/<encoded name>`), sets
  `snd_<id>_secs` to the estimate from the file size (128 kbit/s) and fills the name if it is empty.
- Picking `__url` shows the `snd_<id>_url` text input and, directly under it, the how-to (direct audio link, http
  vs https, examples) and the URL checks (must start with http(s)://, should end in an audio extension).
- Default: an existing sound whose URL is not a hub `/local/` URL → `__url`; no audio files on the hub → `__url`;
  a URL pointing to `/local/<file>` of a listed file → that file; otherwise no default (the user picks). A `/local/`
  URL of a file that isn't listed shows "Plays: <url>".
- `state.sndPick` (sound id → the menu value the page last saw) tells a new pick from a redraw: only a changed value
  (or a file picked while the URL is empty) rewrites the URL / length / name. The first time a sound is shown, a
  leftover menu value that disagrees with its URL is replaced by what the URL says, so opening a page never changes
  a sound. Deleting a sound or discarding a draft forgets its entry.
- Empty list → warning "No sound files found on the hub — upload an .mp3 in Settings → File Manager, or use a web
  address". (The old `snd_<id>_src` "Where is the sound?" input is gone; no back-compat.)
- `snd_<id>_secs` "Length (seconds)": used until the speaker reports the real length (§4, track length).

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
`g2`…; stored in `state.guests` id → [name]. Added with "Add new Guest Mode" like the other lists (the first one's
name is pre-filled **"Guest Mode -SB"**); its switch is created on Save. Delete on its edit page (confirm).
Per Guest Mode `<id>`:
- `gm_<id>_name` (renaming relabels its switch);
- `gm_<id>_source` create (default: Switchboard creates a Virtual Switch labelled with the name; a normal,
  non-component switch that webCoRE, Rule Machine and dashboards can use and turn on/off freely) / existing switch
  (`gm_<id>_switch`) / hub modes (`gm_<id>_modes`);
- `gm_<id>_endDate` + `gm_<id>_endTime` and an "On until…" button; `gm_<id>_defaultHours` "If turned on another
  way (dashboard, rule), turn off after (hours)", default 24, 0 = stay on; `gm_<id>_remindTime` + reminder
  notifiers `gm_<id>_remindDevs`.
Guest Mode pills on the main page show each one that is on. On/off buttons per mode on the main page and on each
Guest Mode's edit page.

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
`nightStart`, `nightEnd`; `homeSensors` (presence, for "only when nobody is home"); `defaultTrackSecs` "If a
speaker doesn't report a track's length, assume (seconds)" (default 10; tip: only used for tracks Switchboard doesn't
know the length of, and only when the speaker doesn't report it (Sonos does); it decides how long the next
announcement waits and when the volume goes back; it never cuts a track short); `srcLookup` bool "Find which rule
sent each message" (default on); logging (debug auto-off 30 min, description).
There is **no hub login** (0.5.0 removed `hubUser` / `hubPass`): the hub lets its own apps read
`127.0.0.1:8080` pages without logging in, also with Hub Login Security on. If such a read is refused anyway
(`state.hubReadBlocked`, see Source lookup), the Settings page and the Message log page show: "The hub didn't let
Switchboard read its own pages, so 'From' shows unknown and the hub sound list is empty. Everything else works."

**Stopping alarms** section: `stopOnDisarm` bool (default on) "When Safety Monitor is disarmed or cancels its alert,
stop all sirens and sounds"; `stopSwitchOn` bool (default on) "Create a 'Switchboard – Stop All' switch for
dashboards, buttons and voice" (created / deleted on Done). The main page's "Right now" section always shows a
**Stop all now** button.

**Draft / delete cleanup:** removing an item's settings removes every `<kind>_<id>_` key in `settings` *and* every
known input name of that kind (an empty device picker is not in `settings.keySet()`): prof name, priority, spk,
volMode, dayVol, nightVol, fixedVol, chime, dedupeSec, staleSec; ntf name, devs, when, dedupeSec, prefix, pushover;
sir name, devs, maxMin; (all three) guestPlay, guestQuiet, guestOther; snd name, file, url, secs; gm name, source, switch,
modes, endDate, endTime, defaultHours, remindTime, remindDevs.

## 4. Behaviour

**Speech queue:** reuse Rule Forge's engine (ConcurrentLinkedQueue per app, semaphore, measured TTS duration, urgent
queue interrupts normal but never another urgent, stale drop, per-speaker methods, volume restore, Sonos
`playHighPriorityTTS` for urgent). Each item: [dni, profile, text|uri|sound, kind, callerVolume, queuedAt, source].
Order per profile item: chime → sound/track → text; busy time = sound secs or measured text length + lead + gap.
Track playback: `playTrackAndRestore(url, vol)` if supported, else set volume + `playTrack(url)` and restore after
`secs`. A `playTrack(uri)` from a rule whose uri matches a library sound uses that sound's length; otherwise
`defaultTrackSecs` (Settings, default 10).
**Play sounds without restore (0.7.3, per speaker, `spkPlain_<id>`):** skips `playTrackAndRestore` and uses the
setVolume + `playTrack` path. Found on a Sonos S1 Connect:Amp (Hubitat's Sonos Player 2 driver): after
`playTrackAndRestore`, the next `speak` came out 1–2 minutes late; after plain `playTrack` it came out on time.
S2 speakers were fine either way. Cost: music playing on that speaker stops instead of resuming.

**Real track length (0.5.0):** every track / sound part that starts on real speakers (`playTrackOn`) schedules
`trackLengthCheck` 2 s later (`runInMillis`, `overwrite: false`, data: speaker ids, sent / start time, assumed secs,
the busy end it returned, lead + gap in ms, dni, url, name). It reads each speaker's `trackDuration` (Sonos: "0:04:13"
or "4:13"; also plain seconds; "unknown" / blank / 0 ignored). A value is only trusted if its event is newer than
the moment the track was sent (otherwise it is an earlier track's), and — when the driver reports `trackUri` /
`currentTrackUri` — only if that names our file (not music resumed after a short clip). The longest reported length
wins; if it differs from the assumed one, every `BUSY` entry that still carries that part's original end and dni
(its speakers, and "ALL" in global mode) gets `until = start + reported + lead + gap`, then `processQueue()` runs and
reschedules its wake-up. Volume restore follows automatically (it waits until the speaker is no longer busy). Entries
already replaced (next part, next item) or removed (stop / Stop all) are never touched. Library sounds use their
`secs` until the speaker reports a length (reported wins); a speaker that reports nothing keeps the assumed length.

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
(no login: the hub lets its own apps read these pages), find the newest `type == "command"` row for that command within ±5 s (and matching
text where present), strip HTML from `producedBy` → source name (e.g. "Goodnight 🌙"). Update the log entry and the
device's `lastSource`. The `producedBy` link's app id is then looked up once (`/installedapp/statusJson/<appId>` → `installedApp.name`,
e.g. "webCoRE Piston", "Rule-5.1") and cached, so the source reads "WC – Goodnight" (WC webCoRE, RM Rule Machine,
BR Basic Rules, HSM, RL Room Lighting, BC Button Controllers, SAR Simple Automation Rules, NT Notifications,
API Maker API, DB dashboards; any other app shows its own type). A legend sits above the log.
Real speakers are only ever driven from Switchboard's own scheduled run (never inside the rule's call), so a real
speaker's Events page always credits "Switchboard". Cost ≈ 0.3 s.
Text prefix `[Name] ` at the start of a message overrides the lookup and is removed before
speaking/pushing. Failures → "unknown"; never delays delivery. Undocumented endpoint: wrap all parsing in try/catch.
**Refused reads:** if `eventsJson`, `statusJson` or the File Manager list answers 401 / 403 or with something that
isn't JSON (HTML, e.g. a login page), `state.hubReadBlocked = true` (one `log.warn`); the next successful read clears
it. While set, Settings and Message log show the short note (see Settings). Timeouts and other errors don't set it.
`initialize()` removes the old `state.hubCookie` (and `hubCookieAt`).

**Hub restart:** `systemStart` → Test Mode off (push to testers), re-initialize, clear stale busy state. The queue
lives in memory, so messages still waiting at a reboot or code save are lost; their log entries say
"failed – hub restarted". (Replaying minutes-old alerts after a reboot would be worse.)

**Stopping alarms (`stopAll(why)`):** run by the **Stop all now** button ("Stop all button"), the Stop All switch
("Stop All switch"), and — only while `stopOnDisarm` is on — `location hsmStatus` "disarmed"/"allDisarmed"
("Safety Monitor disarmed") and `location hsmAlert` "cancel" ("Safety Monitor alert cancelled"). It:
- turns off every Switchboard siren that is sounding (in `sirenOffAt`, or its virtual siren's alarm ≠ off / switch on):
  `off()` to its real sirens, clears its stop timer, sets the virtual siren's alarm / switch to off;
- frees every speaker whose current Switchboard item is urgent or a sound / track item (any of its parts; `BUSY`
  entries carry `alarm`) and sends `stop()` to those real ones (from Switchboard's own run), then runs the queue,
  which restores volumes as usual; a speaker playing a normal text announcement (even its chime) is left alone;
- drops every waiting urgent item and every waiting sound / track item (`playSound`, `playTrack`; a profile chime
  before text doesn't count), log outcome "stopped – <why>"; waiting normal text announcements are kept (so
  "System disarmed" is still spoken);
- logs one entry (kind "stop", device "Switchboard", text = why, outcome "stopped") and an info line — only when
  something was actually stopped (a siren, a speaker or a dropped waiting item); otherwise debug log only (so a
  cancel followed by a disarm makes one entry, and an everyday disarm makes none).

**Siren off verification (0.4.0, only while `sirenVerify` is on):** every `off()` Switchboard sends to real sirens
goes through `sirenCommand`, so every path is covered (rule `off`, "Stop after" timer, `stopAll`, the edit page's
off button, deleting a siren). Each real device sent off goes into `atomicState.sirenVerify` (device id → since,
tries, alerted, name, sbName, sbDev, rid, next, rf); a device already in the map keeps its clock and tries. One
handler, `sirenVerifyCheck` (`runInMillis`, overwritten each time, scheduled for the earliest `next`), serves all.
- A check: if the device has `refresh`, call it and read 1 s later (so reads still land 2 s after each off).
  "Off" = `alarm` is "off" (if it reports alarm) and `switch` is "off" (if it reports switch); a missing value
  counts as off (nothing to judge by). Values are read with `currentValue(attr, true)`.
- Off → removed; if a warning was pushed, push "✓ <name> is off now (after N tries)"; log "off confirmed" when it
  took more than one try (a first-try success is silent).
- Still on → `off()` again (Alarm and Switch share the one `off()` command), tries + 1, log "<name> still on — off
  sent again (try N)" (outcome "retry"); next look 2 s later.
- After 10 s still on → push once "⚠️ <name> didn't turn off after <n> tries. Still trying." (log outcome "alert"),
  then look every 15 s (one log entry per try); at 10 min after the first off → push "❌ Gave up on <name> after
  10 minutes — please check it", log "gave up", drop it.
- Log entries: kind "siren", device = the Switchboard siren's device label (else "Switchboard"), source Switchboard.
- Pushes go straight to `sirenAlertDevs` / testers via `pushTo` (Pushover [H]) and bypass Test Mode and Guest Mode
  routing; each is also a `log.warn`.
- A siren / strobe / both request that reaches real sirens removes those devices from the map first (a deliberate
  re-trigger is not fought). The check applies its decisions under `withStateLock` only to entries that are still
  there with the same `since`; device commands, pushes and log writes run outside the lock.
- Guest Mode skips and Test Mode never sound real sirens, so they start nothing to verify (an off in Test Mode is still
  sent and checked — harmless).
- Hub restart / Done: `initialize()` re-arms the check from the saved map (turning the setting off clears it).

**Turning Test Mode on:** messages still waiting in the queue are pushed to the testers, never dropped. Sirens that
are already sounding keep sounding until their "Stop after" time (a possibly real alarm is not silenced).

## 5. Not in v0.1
Device Groups, Shared Values, rule types, templates (all from Rule Forge) — dropped. Per-person routing beyond
separate profiles/notifiers. HPM manifest (before public release).
