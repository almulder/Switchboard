/**
 *  Switchboard
 *
 *  Delivers announcements, push notifications and siren alerts on behalf of any other app.
 *  Automations (webCoRE pistons, Rule Machine rules, HSM…) send to Switchboard virtual devices;
 *  Switchboard decides how, when and whether the real speakers, phones and sirens are used:
 *  one queue for every speaker, volume by day/night, modes (guests, yard work…), Test Mode and a message log.
 *
 *  Requires Hubitat platform 2.5.0 or newer and the five Switchboard drivers
 *  (Switchboard Parent, Switchboard Speaker, Switchboard Notifier, Switchboard Siren, Switchboard Switch).
 *
 *  Author:    Albert Mulder (almulder)
 *  Copyright: © 2026 Albert Mulder
 *
 *  License:   PolyForm Noncommercial License 1.0.0
 *             https://polyformproject.org/licenses/noncommercial/1.0.0
 *             Free for personal / noncommercial use. Commercial use of any
 *             part of this code is not permitted.
 *
 *  Changelog (newest first)
 *  0.8.1 - 2026-10-27 - A mode is a plain mode unless you set it to Guest (was: Guest unless set to Other).
 *  0.8.0 - 2026-10-27 - Guest Modes are now Modes, each a Guest mode or an Other mode. Switches are named for you:
 *                       "Guest Mode, Lily - SB" or "Yard Work - SB" (type only the name).
 *  0.7.5 - 2026-10-27 - A sound's length can be a decimal (e.g. 1.5 s). A speaker's whole-second report no longer overrides
 *                       a length it's within a second of. Profiles can give chimes and sounds their own volume (day / night
 *                       or fixed), separate from the words.
 *  0.7.4 - 2026-10-27 - A chime runs straight into its words: the pause between messages now only follows the last part.
 *  0.7.3 - 2026-10-27 - Per-speaker "Play sounds without restore" (Speakers & queue): for speakers such as Sonos S1 that hold up
 *                       the next message for minutes after playTrackAndRestore. Sounds then use setVolume + playTrack.
 *  0.7.2 - 2026-10-27 - Deleting an item whose device was missing clears "(1 device missing)" right away;
 *                       Save re-checks too.
 *  0.7.1 - 2026-10-26 - Leftover cleanup also removes the empty settings of deleted items (blank times, empty device lists).
 *  0.7.0 - 2026-10-26 - A Switchboard device deleted outside the app is no longer re-created on its own: it shows as missing (main page, its list and page, and "(1 device missing)" after the app's name in the Apps list) with Recreate or Delete. Leftover settings of deleted items are cleaned up.
 *  0.6.0 - 2026-10-26 - Every Switchboard device (Announce, Notify, Siren, Guest Mode and Stop All switches) is now a child of one "Switchboard" parent device. Needs the Switchboard Parent and Switchboard Switch drivers.
 *  0.5.0 - 2026-10-26 - One sound dropdown (hub files + web address); real track length from the speaker; hub login removed (not needed).
 *  0.4.3 - 2026-10-26 - Picking a hub sound file sets its estimated length; deleting a new item shows "Deleted" instead of opening another blank one.
 *  0.4.2 - 2026-10-26 - Sound files: pick one of the hub's File Manager audio files from a list (length estimated), or enter a web address with a short how-to and checks.
 *  0.4.1 - 2026-10-26 - Next on an edit page saves like Save; new items no longer get lost or duplicated when Hubitat redraws pages.
 *  0.4.0 - 2026-10-26 - Sirens: after any off, Switchboard checks they really turned off and retries; pushes a warning if one is still on after 10 s. A retry never follows a re-trigger, and a siren turned on by something else is left alone.
 *  0.3.0 - 2026-10-26 - Stop alarms: disarming Safety Monitor (or the Stop All switch / button) stops sirens and sounds; draft cleanup removes empty device pickers.
 *  0.2.1 - 2026-10-26 - Every page accepts link parameters (Hubitat passed an edit link's [id] to the list page and it crashed).
 *  0.2.0 - 2026-10-26 - Setup lists open each item on its own page with Save / Delete; new items are drafts until saved.
 *  0.1.8 - 2026-10-26 - Message log keeps the last 100 messages (was 200).
 *  0.1.7 - 2026-10-26 - "From" legend only on the Message log page again (removed from the main page).
 *  0.1.6 - 2026-10-26 - Message log filters by From (a rules system or one rule) and by When (quick ranges or between dates).
 *  0.1.5 - 2026-10-26 - The "From" legend also shows above Latest messages on the main page.
 *  0.1.4 - 2026-10-26 - Real speakers are only driven from Switchboard's own run, so their Events page always shows Switchboard.
 *  0.1.3 - 2026-10-26 - Message log shows which rules system sent each message (WC, RM, BR, HSM…), with a legend.
 *  0.1.2 - 2026-10-26 - Switchboard's own devices are never used as real speakers, phones or sirens (would loop).
 *  0.1.1 - 2026-10-26 - Name, URL, tester and end-time fields save as you leave them (buttons couldn't see unsaved text).
 *  0.1.0 - 2026-10-26 - First build from Rule Forge 0.5.0's speech engine.
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore

@Field static final String APP_VERSION = "0.8.1"
@Field static final String STOP_LABEL  = "Switchboard – Stop All"
// rules systems shown in the message log's "From": [app type pattern, code, name]. Other apps show their own type.
@Field static final List<List> RULE_SYSTEMS = [
    ["^webCoRE", "WC", "webCoRE"],
    ["^Rule-|^Rule Machine", "RM", "Rule Machine"],
    ["^Basic Rule", "BR", "Basic Rules"],
    ["Safety Monitor", "HSM", "Safety Monitor"],
    ["^Room Light", "RL", "Room Lighting"],
    ["^Button Controller|^Button Rule", "BC", "Button Controllers"],
    ["^Simple Automation", "SAR", "Simple Automation Rules"],
    ["^Notifier|^Notification", "NT", "Notifications"],
    ["^Maker API", "API", "Maker API"],
    ["Dashboard", "DB", "Dashboards"],
    ["^Switchboard", "SB", "Switchboard"]
]
@Field static final String HUB_LABEL     = "Switchboard"               // the parent device every Switchboard device sits under
@Field static final String PARENT_DRIVER = "Switchboard Parent"
@Field static final String SWITCH_DRIVER = "Switchboard Switch"         // Stop All and Guest Mode switches
@Field static final String NS          = "almulder"
@Field static final String APP_TITLE   = "Switchboard"
@Field static final int    LOG_MAX     = 100

/** the three kinds of virtual device: settings prefix, state key, DNI part, id prefix, driver, label prefix */
@Field static final Map<String, Map> KINDS = [
    prof: [stateKey: "profiles",  dev: "spk", idp: "p", driver: "Switchboard Speaker",  label: "Announce – ", what: "Announcement Profile"],
    ntf : [stateKey: "notifiers", dev: "ntf", idp: "n", driver: "Switchboard Notifier", label: "Notify – ",   what: "notification"],
    sir : [stateKey: "sirens",    dev: "snd", idp: "r", driver: "Switchboard Siren",    label: "Siren – ",    what: "siren"]
]

@Field static final Map<String, String> SPEAK_METHODS = [
    auto           : "Auto-detect",
    speakVol       : "speak(text, volume) — Sonos (built-in & Advanced), most drivers",
    setThenSpeak   : "setVolume, then speak(text) — Google/Chromecast, AirPlay, older drivers",
    playTextRestore: "playTextAndRestore(text, volume)",
    echo           : "setVolumeSpeakAndRestore — Echo Speaks",
    speakOnly      : "speak(text) only — no volume control"
]

@Field static final String TM_WARNING = "While Test Mode is on, alarms, sirens and announcements in the home are silenced — " +
    "a real emergency only reaches the testers' phones. Smoke alarms with their own sounders still sound."
@Field static final Map<String, String> TM_AUTO_OFF = ["15": "15 minutes", "30": "30 minutes", "60": "1 hour",
                                                       "120": "2 hours", "180": "3 hours", "240": "4 hours"]
@Field static final String SND_URL_TIP = "A web address the speaker can play, e.g. an MP3 uploaded to the hub's File Manager: " +
    "http://<hub-ip>/local/<file>.mp3. Speakers that can't play files (no playTrack) skip the sound."
@Field static final String SND_URL_KEY = "__url"          // the Sound menu's last option: "Use a web address (URL)…"
@Field static final String HUB_BLOCKED_MSG = "The hub didn't let Switchboard read its own pages, so 'From' shows unknown and the hub sound list is empty. Everything else works."

// ---- in-memory speech engine state, per app instance (cleared on hub reboot / code save)
@Field static ConcurrentHashMap<Long, ConcurrentLinkedQueue<Map>> Q_NORMAL = new ConcurrentHashMap<>()
@Field static ConcurrentHashMap<Long, ConcurrentLinkedQueue<Map>> Q_URGENT = new ConcurrentHashMap<>()
@Field static ConcurrentHashMap<Long, ConcurrentHashMap<String, Map>> BUSY = new ConcurrentHashMap<>()     // resource -> [until, urgent, dni]
@Field static ConcurrentHashMap<Long, ConcurrentHashMap<String, Map>> RESTORE = new ConcurrentHashMap<>()  // speakerId -> [dev, vol]
@Field static ConcurrentHashMap<Long, Semaphore> LOCKS = new ConcurrentHashMap<>()
@Field static ConcurrentHashMap<Long, Boolean> RERUN = new ConcurrentHashMap<>()
@Field static ConcurrentHashMap<String, Map> LAST_RESTORED = new ConcurrentHashMap<>()   // "appId_spkId" -> [vol, at]
@Field static Set<String> UKEYS = ConcurrentHashMap.newKeySet()                         // urgent messages waiting
@Field static ConcurrentHashMap<String, Long> TM_RECENT = new ConcurrentHashMap<>()       // "tm|…" tester dedupe, "dd|…" device dedupe
@Field static ConcurrentHashMap<Long, Semaphore> STATE_LOCKS = new ConcurrentHashMap<>()  // atomicState read-modify-write (log, siren timers)

definition(
    name: "Switchboard",
    namespace: "almulder",
    author: "Albert Mulder",
    description: "Announcements, notifications and sirens for every app: one speech queue, day/night volume, modes (guests, yard work…), Test Mode and a message log.",
    category: "Convenience",
    menu: "Automations",
    iconUrl: "", iconX2Url: "",
    singleInstance: true,
    installOnOpen: true
)

preferences {
    page(name: "mainPage")
    page(name: "profilesPage")
    page(name: "profileEditPage")
    page(name: "notifyPage")
    page(name: "notifyEditPage")
    page(name: "sirensPage")
    page(name: "sirenEditPage")
    page(name: "soundsPage")
    page(name: "soundEditPage")
    page(name: "speakersPage")
    page(name: "testPage")
    page(name: "guestPage")
    page(name: "guestEditPage")
    page(name: "commitPage")
    page(name: "logPage")
    page(name: "settingsPage")
}

// ===================================================================== HELPERS (UI)

/** CSS for the help icons; sent with every icon so it works on any page (browsers apply it once) */
@Field static final String TIP_CSS = "<style>.rfh{position:relative;display:inline-block;cursor:help;color:#1A77C9;font-weight:bold;outline:none;margin-left:3px}" +
    ".rfh .rft{display:none;position:fixed;z-index:99999;width:280px;max-width:calc(100vw - 20px);background:#333;color:#fff;" +
    "font-weight:normal;font-size:13px;line-height:1.35;padding:7px 9px;border-radius:5px;white-space:normal;text-align:left;box-shadow:0 2px 8px rgba(0,0,0,.3)}" +
    ".rfh:hover .rft,.rfh:focus .rft{display:block}" +
    "@media (max-width:700px){.rfh .rft{left:10px!important;right:10px;top:auto!important;bottom:18px;width:auto;max-width:none;font-size:14px}}</style>"

/**
 * Places the tip next to the icon. The tip is position:fixed so it is never clipped and never
 * changes the page's scroll containers (changing an ancestor's overflow made the page jump to the top).
 * If Hubitat strips the handler, the tip still shows just below the icon.
 */
@Field static final String TIP_JS = "var t=this.querySelector(\".rft\");if(!t||innerWidth<=700)return;var r=this.getBoundingClientRect();" +
    "t.style.left=Math.max(10,Math.min(r.left-6,innerWidth-t.offsetWidth-10))+\"px\";var h=t.offsetHeight;" +
    "t.style.top=(r.bottom+4+h>innerHeight?Math.max(10,r.top-h-4):r.bottom+4)+\"px\""

/** small ⓘ icon; hover (desktop) or tap (phone) shows the help text */
String tip(String text) {
    String t = (text ?: "").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    return TIP_CSS + "<span class='rfh' tabindex='0' onmouseenter='${TIP_JS}' onfocus='${TIP_JS}' " +
           "onclick='event.preventDefault();event.stopPropagation()'>ⓘ<span class='rft'>${t}</span></span>"
}
private String cleanName(String s) { (s ?: "").replaceAll(/['"<>\\]/, "").trim() }
String plain(String s) { (s ?: "").replaceAll(/<[^>]*>/, "") }
private String note(String s) { uiHint(s) }
private String warn(String s) { uiWarn(s) }
private String fmtTime(Long ms) { ms ? new Date(ms).format("EEE MMM d, h:mm a", location.timeZone) : "" }
private String fmtShort(Long ms) { ms ? new Date(ms).format("MMM d h:mm:ss a", location.timeZone) : "" }
private String pageHead(String title, String subtitle) { uiCss() + uiHeader(title, subtitle, modePills()) }
/** TEST MODE / mode pills (empty when all are off) */
private String modePills() {
    List p = []
    if (isTestMode())  p << uiPill("TEST MODE", "warn")
    guestsOn().each { p << uiPill(guestName(it).toUpperCase(), "info") }
    return p.join(" ")
}
/** integer setting with a default (0 is a real value) */
private Integer intSet(String name, Integer dflt) {
    def v = settings[name]
    if (v == null || v.toString() == "") return dflt
    try { return (v as BigDecimal).intValue() } catch (ignored) { return dflt }
}
private Integer toInt(v) {
    if (v == null || v.toString().trim() == "") return null
    try { return (v.toString().trim() as BigDecimal).intValue() } catch (ignored) { return null }
}
private Integer clampVol(Integer v) { v == null ? null : Math.max(0, Math.min(100, v)) }

// ===================================================================== UI KIT

@Field static final String UI_CSS = "<style>" +
    ".rf-card{background:#fff;border:1px solid #e3e8ef;border-radius:12px;padding:14px 16px;margin:4px 0 8px;box-shadow:0 1px 3px rgba(16,24,40,.06)}" +
    ".rf-head{display:flex;flex-wrap:wrap;align-items:center;gap:10px;justify-content:space-between}" +
    ".rf-head h2{margin:0;font-size:1.35em;font-weight:600;color:#101828}" +
    ".rf-sub{color:#667085;font-size:.85em;margin-top:2px}" +
    ".rf-sum{background:linear-gradient(180deg,#f5f9ff,#eef4fd);border:1px solid #d6e4f7;border-left:4px solid #1A77C9;border-radius:10px;padding:10px 14px;line-height:1.55;color:#1d2939}" +
    ".rf-sum b.rf-lbl{display:block;font-size:.78em;letter-spacing:.06em;text-transform:uppercase;color:#1A77C9;margin-bottom:4px}" +
    ".rf-t{display:inline-flex;align-items:center;gap:8px;font-weight:600;color:#101828}" +
    ".rf-num{display:inline-flex;align-items:center;justify-content:center;min-width:22px;height:22px;border-radius:11px;background:#1A77C9;color:#fff;font-size:.8em;font-weight:700}" +
    ".rf-hint{color:#667085;font-size:.88em}" +
    ".rf-warn{color:#b54708;background:#fffaeb;border:1px solid #fedf89;border-radius:8px;padding:6px 10px;display:inline-block}" +
    ".rf-ok{color:#067647;background:#ecfdf3;border:1px solid #abefc6;border-radius:8px;padding:6px 10px;display:inline-block}" +
    ".rf-pill{display:inline-block;padding:2px 10px;border-radius:999px;font-size:.8em;font-weight:600;vertical-align:middle}" +
    ".rf-pill.on{background:#dcfae6;color:#067647}.rf-pill.off{background:#fee4e2;color:#b42318}" +
    ".rf-pill.info{background:#e0eaff;color:#1A77C9}.rf-pill.warn{background:#fef0c7;color:#b54708}" +
    ".sb-log{width:100%;border-collapse:collapse;font-size:.88em}.sb-log td,.sb-log th{padding:4px 6px;border-bottom:1px solid #eef1f5;text-align:left;vertical-align:top}" +
    ".sb-log th{color:#667085;font-weight:600}" +
    "</style>"

String uiCss() { UI_CSS + TIP_CSS }
private String esc(String s) { (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
String uiHeader(String title, String subtitle, String statusHtml = "") {
    "<div class='rf-card rf-head'><div><h2>${esc(title)}</h2><div class='rf-sub'>${esc(subtitle)}</div></div><div>${statusHtml ?: ''}</div></div>"
}
String uiSummary(String html, String label = "What this does") { "<div class='rf-sum'><b class='rf-lbl'>${esc(label)}</b>${html ?: ''}</div>" }
String uiTitle(def number, String text) {
    (number != null && number.toString() != "" ? "<span class='rf-t'><span class='rf-num'>${number}</span>${esc(text)}</span>" : "<span class='rf-t'>${esc(text)}</span>")
}
String uiHint(String t) { "<span class='rf-hint'>${t ?: ''}</span>" }
String uiWarn(String t) { "<span class='rf-warn'>⚠ ${t ?: ''}</span>" }
String uiOk(String t)   { "<span class='rf-ok'>✓ ${t ?: ''}</span>" }
String uiPill(String t, String kind = "info") { "<span class='rf-pill ${kind ?: 'info'}'>${esc(t)}</span>" }

/** two-step delete: "Delete" → "Yes, delete" / "Cancel" (no browser dialog needed) */
private void deleteControls(String kind, String id, String what) {
    String key = "${kind}_${id}".toString()
    if (state.confirmDel == key) {
        if (isDraft(kind, id)) paragraph warn("Delete this new ${what}? It was never saved, so nothing else changes.")
        else paragraph warn("Delete this ${what}? " + (kind == "snd" ? "Profiles using it as a chime stop playing it." :
                       (kind == "gm" ? "Its switch (if Switchboard made it) is deleted too, and profiles, notifications and sirens forget it." :
                       "Its Switchboard device is deleted too; rules using it will need another device.")))
        input "btnDelYes_${key}", "button", title: "Yes, delete", width: 3
        input "btnDelNo", "button", title: "Cancel", width: 3
    } else {
        input "btnDel_${key}", "button", title: "Delete", width: 3
    }
}

// ===================================================================== PAGES

def mainPage(params = null) {
    state.remove("confirmDel")
    syncNames()
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph uiCss() + uiHeader(APP_TITLE, "v${APP_VERSION} · announcements, notifications and sirens", modePills())
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (state.gmErr) { paragraph warn(state.gmErr as String); state.remove("gmErr") }
            if (state.okMsg) { paragraph uiOk(state.okMsg as String); state.remove("okMsg") }
            List<Map> miss = missingDevices()
            refreshMissing(miss)
            if (miss) {
                paragraph warn("${plural(miss.size(), 'Switchboard device')} missing: <b>${esc(miss.collect { it.label }.join(', '))}</b>. " +
                               "Deleted outside Switchboard (or couldn't be created), so rules using ${miss.size() == 1 ? 'it' : 'them'} no longer reach Switchboard. Open each one to recreate or delete it.")
                miss.each { Map m ->
                    if (m.kind == "stop") href name: "hrefMiss_stop", page: "settingsPage", title: "⚠ ${esc(m.label as String)}", description: "Recreate it, or turn the Stop All switch off"
                    else href name: "hrefMiss_${m.kind}_${m.id}", page: EDITS[m.kind as String].edit as String, params: [id: m.id], title: "⚠ ${esc(m.label as String)}", description: "Recreate or delete"
                }
                if (miss.size() > 1) input "btnRecreateAll", "button", title: "Recreate all", width: 4
            }
        }
        section(uiTitle(null, "Right now")) {
            if (isTestMode()) {
                paragraph uiPill("TEST MODE ON", "warn") + " " + note("Nothing sounds at home; everything goes to the testers" +
                          (state.tmOffAt ? " until ${fmtTime(state.tmOffAt as Long)}." : "."))
                input "btnTmOff", "button", title: "Turn Test Mode off", width: 4
            } else {
                paragraph uiPill("Test Mode off", "info")
                href "testPage", title: "Turn on Test Mode…", description: "Confirm the warning on the Test Mode page"
            }
            if (!guestIds()) paragraph note("No modes yet.")
            guestIds().each { String gid ->
                boolean on = isGuestOn(gid)
                Long end = ((state.gmOffAt ?: [:]) as Map)[gid] as Long
                paragraph (on ? uiPill("${guestName(gid)} ON", "info") + (end ? " " + note("until ${fmtTime(end)}") : "")
                              : uiPill("${guestName(gid)} off", "off"))
                if (gmSrc(gid) != "modes") {
                    if (on) input "btnGmOff_${gid}", "button", title: "Turn off", width: 4
                    else input "btnGmOn_${gid}", "button", title: "Turn on", width: 4
                }
            }
            paragraph queueStatus()
            input "btnStopAll", "button", title: "Stop all now", width: 4
            paragraph note("Turns off every Switchboard siren, stops the speakers and drops waiting sounds and urgent messages.")
        }
        section(uiTitle(null, "Latest messages")) {
            List l = logEntries()
            paragraph l ? logTable(l.take(10)) : note("Nothing yet.")
            href "logPage", title: "Message log", description: "All ${l.size()} entries, with filters"
        }
        section(uiTitle(null, "Setup")) {
            href "profilesPage", title: "Announcement Profiles" + tip("Each profile is one Switchboard speaker device for your rules, e.g. 'Announce – Doors'. The profile picks the real speakers, volume, priority and what it does in each mode."),
                 description: "Speakers, volume, priority · ${ids('prof').size()} profile(s)"
            href "notifyPage", title: "Notifications" + tip("Each one is a Switchboard notification device, e.g. 'Notify – Everyone'. Rules send to it; it forwards to the phones you pick."),
                 description: "Phones and push · ${ids('ntf').size()} device(s)"
            href "sirensPage", title: "Sirens", description: "Real sirens behind each Switchboard siren · ${ids('sir').size()} device(s)"
            href "soundsPage", title: "Sound files", description: "Chimes and sounds to play · ${soundIds().size()} sound(s)"
            href "speakersPage", title: "Speakers & queue", description: "How each speaker speaks, queue timing · ${allSpeakers().size()} speaker(s)"
            href "testPage", title: "Test Mode", description: isTestMode() ? "ON" : "Off"
            href "guestPage", title: "Modes", description: guestIds() ? "${plural(guestIds().size(), 'mode')}, ${guestsOn().size()} on" : "None yet"
            href "logPage", title: "Message log", description: "What was played, skipped or sent, and by which rule"
            href "settingsPage", title: "Settings", description: "Night time, who is home, stopping alarms, rule lookup, logging"
        }
    }
}

private String queueStatus() {
    int qn = qNormal().size(), qu = qUrgent().size()
    long t = now()
    List playing = mapFor(BUSY).findAll { k, v -> k != "ALL" && (v.until as Long) > t && v.dni }.collect { k, v -> v.dni as String }.unique()
    List names = playing.collect { sbChild(it)?.displayName ?: it }
    String s = names ? uiPill("Playing: ${names.join(', ')}", "on") + " " : ""
    s += (qn + qu) ? uiPill("Waiting: ${qn} normal, ${qu} urgent", qu ? "warn" : "info") : (names ? "" : uiOk("Queue is empty"))
    return s
}

// ------------------------------------------------------------------ Setup lists: one row per item, each item on its own edit page

/** the five kinds of setup item: state key, id prefix, list page, edit page, words */
@Field static final Map<String, Map> EDITS = [
    prof: [stateKey: "profiles",  idp: "p", list: "profilesPage", edit: "profileEditPage", what: "profile",      listTitle: "Announcement Profiles"],
    ntf : [stateKey: "notifiers", idp: "n", list: "notifyPage",   edit: "notifyEditPage",  what: "notification", listTitle: "Notifications"],
    sir : [stateKey: "sirens",    idp: "r", list: "sirensPage",   edit: "sirenEditPage",   what: "siren",        listTitle: "Sirens"],
    snd : [stateKey: "sounds",    idp: "s", list: "soundsPage",   edit: "soundEditPage",   what: "sound",        listTitle: "Sound files"],
    gm  : [stateKey: "guests",    idp: "g", list: "guestPage",    edit: "guestEditPage",   what: "Mode",         listTitle: "Modes"]
]

/** the kind's state map, drafts included (runtime code uses ids() / soundIds() / guestIds(), which leave drafts out) */
private Map rawMap(String kind) { (state[EDITS[kind].stateKey as String] ?: [:]) as Map }
private boolean isDraft(String kind, String id) {
    def v = id ? rawMap(kind)[id] : null
    return v instanceof Map && v.draft == true
}
/** keys of an item map without drafts */
private List liveKeys(Map m) { m.findAll { k, v -> !(v instanceof Map && v.draft == true) }.keySet() as List }
private String plural(int n, String one, String many = null) { "${n} ${n == 1 ? one : (many ?: one + 's')}".toString() }
/** every input an item's edit page can create, per kind (an empty device picker is not in settings.keySet(), so name them all) */
@Field static final Map<String, List<String>> ITEM_INPUTS = [
    prof: ["name", "priority", "spk", "volMode", "dayVol", "nightVol", "fixedVol", "chime", "sndVolMode", "sndDayVol", "sndNightVol", "sndFixedVol", "dedupeSec", "staleSec", "guestPlay", "guestQuiet", "guestOther"],
    ntf : ["name", "devs", "when", "dedupeSec", "prefix", "pushover", "guestPlay", "guestQuiet", "guestOther"],
    sir : ["name", "devs", "maxMin", "guestPlay", "guestQuiet", "guestOther"],
    snd : ["name", "file", "url", "secs"],
    gm  : ["name", "type", "source", "switch", "modes", "endDate", "endTime", "defaultHours", "remindTime", "remindDevs"]
]
private void removeItemSettings(String kind, String id) {
    String pre = "${kind}_${id}_".toString()
    Set<String> keys = (settings.keySet().findAll { it.startsWith(pre) }.collect { it as String }) as Set
    ((ITEM_INPUTS[kind] ?: []) as List).each { keys << "${pre}${it}".toString() }
    keys.each { String k -> try { app.removeSetting(k) } catch (e) { logDebug "removeSetting ${k}: ${e}" } }
    if (kind == "snd") forgetSndPick(id)
}
private void forgetSndPick(String sid) {
    Map pk = (state.sndPick ?: [:]) as Map
    if (pk.containsKey(sid)) { pk.remove(sid); state.sndPick = pk }
}

/** a new item: next real id, kept as a draft (no device, invisible to the runtime) until Save */
private String newDraft(String kind) {
    Map e = EDITS[kind]
    Map m = rawMap(kind)
    String counter = "next_${kind}".toString()
    Integer next = (state[counter] ?: 1) as Integer
    String id = "${e.idp}${next}".toString()
    while (m.containsKey(id)) { next++; id = "${e.idp}${next}".toString() }
    state[counter] = next + 1
    removeItemSettings(kind, id)
    m[id] = [name: "", draft: true, touched: now()]
    state[e.stateKey as String] = m
    return id
}
private void discardDraft(String kind, String id) {
    Map m = rawMap(kind)
    if (!(m[id] instanceof Map && m[id].draft == true)) return
    m.remove(id)
    state[EDITS[kind].stateKey as String] = m
    removeItemSettings(kind, id)
}
/** deletes every unsaved draft (and its settings) except the one being edited; every page but that edit page calls it */
private void cleanDrafts(String keepKind = null, String keepId = null) {
    EDITS.keySet().each { String kind ->
        Map m = rawMap(kind)
        List gone = m.findAll { k, v -> v instanceof Map && v.draft == true && !(kind == keepKind && k == keepId) }.collect { k, v -> k as String }
        if (!gone) return
        gone.each { String id -> m.remove(id); removeItemSettings(kind, id) }
        state[EDITS[kind].stateKey as String] = m
    }
}

/**
 * The item an edit page shows. Opened by href with params [id: <id>] or [id: "new"]; Hubitat drops params on
 * submitOnChange redraws, so the id is kept in state.editId_<kind>. Returns null when the item is gone.
 */
private String editTarget(String kind, params) {
    String key = "editId_${kind}".toString()
    String pid = params?.id ? params.id.toString() : null
    if (pid == "new") {
        // Hubitat re-renders pages (and rebuilds links) during redraws, so "new" can arrive many times for one Add:
        // show the item just saved (if any, for 20 s), else keep filling the open draft, else start a fresh one.
        Map sv = state["savedNew_${kind}".toString()] as Map
        Map dr = rawMap(kind).find { k, v -> v instanceof Map && v.draft == true }?.with { [id: it.key, v: it.value] }
        String cur
        Long del = state["deleted_${kind}".toString()] as Long
        if (del && now() - del < 20000L) cur = null                                       // just deleted: show "Deleted"
        else if (sv && now() - ((sv.at ?: 0) as Long) < 20000L && rawMap(kind).containsKey(sv.id)) cur = sv.id as String
        else if (dr && now() - (((dr.v as Map).touched ?: 0) as Long) < 120000L) cur = dr.id as String
        else {
            if (dr) discardDraft(kind, dr.id as String)
            cur = newDraft(kind)
            state.remove("confirmDel"); state.remove("editSaved"); state.remove("editErr")
        }
        state[key] = cur
    } else if (pid) {
        if ((state[key] as String) != pid) { state.remove("confirmDel"); state.remove("editSaved"); state.remove("editErr") }
        state[key] = pid
    }
    String id = state[key] as String
    state.editKind = kind
    Map m = rawMap(kind)
    if (id && m[id] instanceof Map && m[id].draft == true) { m[id].touched = now(); state[EDITS[kind].stateKey as String] = m }
    return id && m.containsKey(id) ? id : null
}

private String itemTitle(String kind, String id) {
    KINDS[kind] ? itemName(kind, id) : (kind == "snd" ? soundName(id) : guestName(id))
}
/** top of an edit page: header, errors, "saved" / "gone" notes. Returns false when there is no item to show. */
private boolean editHead(String kind, String id) {
    Map e = EDITS[kind]
    boolean draft = isDraft(kind, id)
    section {
        paragraph pageHead(!id ? (e.listTitle as String) : (draft ? "New ${e.what}" : itemTitle(kind, id)), "${APP_TITLE} · ${e.listTitle}")
        if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
        if (state.gmErr) { paragraph warn(state.gmErr as String); state.remove("gmErr") }
        if (!id) {
            paragraph state.editMsg ? uiOk(state.editMsg as String) : note("This ${e.what} isn't here any more (deleted, or new and never saved).")
            state.remove("editMsg")
            href name: "hrefBackTop_${kind}", page: e.list as String, title: "Back to ${e.listTitle}", description: ""
        } else if (state.editSaved == "${kind}_${id}".toString()) {
            paragraph uiOk("Saved")
            href name: "hrefBackTop_${kind}", page: e.list as String, title: "Back to ${e.listTitle}", description: ""
        } else if (draft) {
            paragraph uiHint("New and not saved yet. Fill it in, then press <b>Save</b> (or <b>Next</b>) at the bottom.")
        }
        if (id && state.okMsg) { paragraph uiOk(state.okMsg as String); state.remove("okMsg") }
        if (id && isMissing(kind, id)) {
            String dev = kind == "gm" ? "switch" : "device"
            paragraph warn("Its ${dev} <b>${esc(kind == 'gm' ? gmSwitchLabel(id) : deviceLabel(kind, id))}</b> is missing (deleted outside Switchboard, or it couldn't be created). " +
                           "Rules that used it no longer reach Switchboard. Recreate it (a new ${dev}: point your rules at it again), or delete this ${e.what} at the bottom.")
            input "btnRecreate_${kind}_${id}", "button", title: "Recreate ${dev}", width: 4
        }
    }
    return id != null
}
/** bottom of an edit page: Save (draft: create it; existing: back to the list) and two-step Delete */
private void editFoot(String kind, String id) {
    Map e = EDITS[kind]
    String key = "${kind}_${id}".toString()
    boolean draft = isDraft(kind, id)
    section {
        if (state.editErr) { paragraph warn(state.editErr as String); state.remove("editErr") }
        if (state.editSaved == key) {
            paragraph uiOk("Saved")
            href name: "hrefBack_${kind}", page: e.list as String, title: "Back to ${e.listTitle}", description: ""
            state.remove("editSaved")
        } else {
            paragraph note(draft ? "Press Save or Next to create it." : "Changes are saved as you make them.")
            input "btnSave_${key}", "button", title: "Save", width: 3
        }
        deleteControls(kind, id, e.what as String)
    }
}
/** list page row: opens the item's edit page */
private void editRow(String kind, String id, String title, String desc) {
    if (isMissing(kind, id)) title += " " + uiPill("device missing", "off")
    href name: "hrefEdit_${kind}_${id}", page: EDITS[kind].edit as String, params: [id: id], title: title, description: desc
}
/** list page "Add new …": opens the edit page on a new draft */
private void addRow(String kind) {
    Map e = EDITS[kind]
    section {
        href name: "hrefAdd_${kind}", page: e.edit as String, params: [id: "new"],
             title: "Add new ${e.what}", description: "Opens a new ${e.what}. It is created when you press Save."
    }
}

/** Save button. Draft: needs a name, then becomes real (device / switch created like before). Existing: relabel, then back to the list. */
private void saveItem(String kind, String id) {
    Map e = EDITS[kind]
    Map m = rawMap(kind)
    if (!m.containsKey(id)) return
    if (isDraft(kind, id)) {
        String n = cleanName(settings["${kind}_${id}_name"] as String)
        if (!n) { state.editErr = "Enter a name first."; return }
        String u = (settings["${kind}_${id}_url"] ?: "").toString().trim()
        if (kind == "snd" && !u) { state.editErr = "Enter the sound's URL first."; return }
        Map v = [name: n]
        if (kind == "snd") v = [name: n, url: u, secs: soundSecs(id)]
        m[id] = v
        state[e.stateKey as String] = m
        if (KINDS[kind]) ensureDevice(kind, id, n)
        else if (kind == "gm") { ensureGuestSwitch(id); initGuests() }      // subscribes hub modes too, no Done needed
        state["savedNew_${kind}".toString()] = [id: id, at: now()]
        logInfo "Added ${e.what} '${n}'"
    } else {
        if (KINDS[kind]) syncNames()
        else if (kind == "snd") syncSounds()
        else { syncGuests(); ensureGuestSwitch(id); initGuests() }
    }
    state.editSaved = "${kind}_${id}".toString()
}

// ------------------------------------------------------------------ Announcement Profiles
def profilesPage(params = null) {
    state.remove("confirmDel")
    syncNames()
    dynamicPage(name: "profilesPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Announcement Profiles", "${APP_TITLE} · Setup")
            paragraph uiHint("Each profile is one speaker device for your rules. Rules speak to <b>Announce – name</b>; " +
                             "Switchboard plays it on the real speakers you pick, one message at a time.")
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (!ids("prof")) paragraph uiHint("No profiles yet.")
            ids("prof").each { String pid ->
                String pre = "prof_${pid}_"
                int n = ((settings[pre + "spk"] ?: []) as List).size()
                boolean urgent = settings[pre + "priority"] == "urgent"
                String vm = settings[pre + "volMode"] ?: "daynight"
                String vol = vm == "fixed" ? "fixed volume ${intSet(pre + 'fixedVol', 60)}" :
                             (vm == "caller" ? "volume from the rule" : "day ${intSet(pre + 'dayVol', 70)} / night ${intSet(pre + 'nightVol', 40)}")
                editRow("prof", pid, esc(itemName("prof", pid)) + (n ? "" : " " + uiPill("no speakers", "warn")) + (urgent ? " " + uiPill("URGENT", "off") : ""),
                        [plural(n, "speaker"), vol, urgent ? "urgent" : "normal"].join(" · "))
            }
        }
        addRow("prof")
    }
}

def profileEditPage(params = null) {
    String pid = editTarget("prof", params)
    dynamicPage(name: "profileEditPage", title: "", install: false, uninstall: false, nextPage: "commitPage") {
        if (!editHead("prof", pid)) return
        boolean draft = isDraft("prof", pid)
        String pre = "prof_${pid}_"
        boolean urgent = settings[pre + "priority"] == "urgent"
        int n = ((settings[pre + "spk"] ?: []) as List).size()
        section(uiTitle(null, "Profile")) {
            input pre + "name", "text", title: "Name", submitOnChange: true, width: 6
            input pre + "priority", "enum", title: "How important?" + tip("Urgent messages jump the queue and interrupt normal ones; use for smoke, CO, water and alarms. Urgent messages never interrupt each other."),
                  options: [normal: "Normal", urgent: "Urgent — interrupts other announcements"], defaultValue: "normal", submitOnChange: true, width: 6
        }
        section(uiTitle(null, "Speakers and volume")) {
            input pre + "spk", "capability.speechSynthesis", title: "Which speakers?", multiple: true, required: false, submitOnChange: true
            if (((settings[pre + "spk"] ?: []) as List).size() != realDevs(settings[pre + "spk"]).size()) paragraph warn("Switchboard's own devices are ignored here. Pick real speakers.")
            if (!n) paragraph warn("Pick at least one speaker, or messages to this profile go nowhere.")
            input pre + "volMode", "enum", title: "How loud?" + tip("Day / night: uses the night window from Settings. Use the volume the rule sends: the volume in speak(text, volume), or the last setVolume a rule sent to this Switchboard device (it never changes the real speakers' volume by itself)."),
                  submitOnChange: true, defaultValue: "daynight", width: 6,
                  options: [daynight: "Day / night volume", fixed: "Fixed volume", caller: "Use the volume the rule sends"]
            String vm = settings[pre + "volMode"] ?: "daynight"
            if (vm == "daynight") {
                input pre + "dayVol", "number", title: "Day volume", range: "0..100", defaultValue: 70, width: 3
                input pre + "nightVol", "number", title: "Night volume", range: "0..100", defaultValue: 40, width: 3
            } else if (vm == "fixed") {
                input pre + "fixedVol", "number", title: "Volume", range: "0..100", defaultValue: 60, width: 3
            }
            Map snds = soundOptions()
            if (snds) {
                input pre + "chime", "enum", title: "Chime before each message (optional)" + tip("A short sound before every spoken message on this profile. Speakers that can't play files just speak."),
                      options: snds, required: false, width: 6
                input pre + "sndVolMode", "enum", title: "Chime and sound volume" + tip("The chime, and sounds rules play on this profile (playSound, playTrack). Same as the words, or a volume of their own. With \"Use the volume the rule sends\", a message whose rule sends no volume plays its chime at the speaker's current volume too."),
                      submitOnChange: true, defaultValue: "same", width: 6,
                      options: [same: "Same as the words", daynight: "Own day / night volume", fixed: "Own fixed volume"]
                String sm = settings[pre + "sndVolMode"] ?: "same"
                if (sm == "daynight") {
                    input pre + "sndDayVol", "number", title: "Sound day volume", range: "0..100", defaultValue: 70, width: 3
                    input pre + "sndNightVol", "number", title: "Sound night volume", range: "0..100", defaultValue: 40, width: 3
                } else if (sm == "fixed") {
                    input pre + "sndFixedVol", "number", title: "Sound volume", range: "0..100", defaultValue: 60, width: 3
                }
            }
        }
        section(uiTitle(null, "Skipping")) {
            input pre + "dedupeSec", "number", title: "Skip the same message if it was played in the last (seconds)" + tip("Stops a rule that fires twice from saying the same thing twice. 0 = never skip."),
                  range: "0..3600", defaultValue: 15, width: 6
            if (!urgent) {
                input pre + "staleSec", "number", title: "Skip if it waited longer than (seconds), 0 = never" +
                      tip("A message can wait in line while others play. Skip it if it waited this long, so you don't hear 'Front door, opened' a minute late."),
                      range: "0..3600", defaultValue: 30, width: 6
            }
        }
        section(uiTitle(null, "Modes")) {
            guestInputs("prof_${pid}", urgent)
        }
        section(uiTitle(null, "Test")) {
            if (draft) {
                paragraph note("Save it first, then test it. Its device <b>Announce – name</b> is created when you press Save.")
            } else {
                input "btnTestProf_${pid}", "button", title: "Test this profile"
                paragraph note("Used by: rules that send to the device <b>${esc(deviceLabel('prof', pid))}</b>.")
            }
        }
        editFoot("prof", pid)
    }
}

// ------------------------------------------------------------------ Notifications
def notifyPage(params = null) {
    state.remove("confirmDel")
    syncNames()
    dynamicPage(name: "notifyPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Notifications", "${APP_TITLE} · Setup")
            paragraph uiHint("Rules send to <b>Notify – name</b>; Switchboard forwards to the phones you pick.")
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (!ids("ntf")) paragraph uiHint("None yet.")
            ids("ntf").each { String nid ->
                String pre = "ntf_${nid}_"
                int n = ((settings[pre + "devs"] ?: []) as List).size()
                editRow("ntf", nid, esc(itemName("ntf", nid)) + (n ? "" : " " + uiPill("no devices", "warn")),
                        [plural(n, "device"), settings[pre + "when"] == "away" ? "only when nobody is home" : "always"].join(" · "))
            }
        }
        addRow("ntf")
    }
}

def notifyEditPage(params = null) {
    String nid = editTarget("ntf", params)
    dynamicPage(name: "notifyEditPage", title: "", install: false, uninstall: false, nextPage: "commitPage") {
        if (!editHead("ntf", nid)) return
        boolean draft = isDraft("ntf", nid)
        String pre = "ntf_${nid}_"
        int n = ((settings[pre + "devs"] ?: []) as List).size()
        section(uiTitle(null, "Notification")) {
            input pre + "name", "text", title: "Name", submitOnChange: true, width: 6
            input pre + "devs", "capability.notification", title: "Send to" + tip("Hubitat app phones, Pushover, email or SMS drivers."),
                  multiple: true, required: false, submitOnChange: true
            if (((settings[pre + "devs"] ?: []) as List).size() != realDevs(settings[pre + "devs"]).size()) paragraph warn("Switchboard's own devices are ignored here. Pick real phones or notification devices.")
            if (!n) paragraph warn("Pick at least one device.")
            input pre + "when", "enum", title: "When", defaultValue: "always", width: 6,
                  options: [always: "Always", away: "Only when nobody is home"]
            if (settings[pre + "when"] == "away" && !homeSensors) paragraph warn("Pick who is home in Settings, or this always sends.")
            input pre + "dedupeSec", "number", title: "Skip the same message if it was sent in the last (seconds)", range: "0..3600", defaultValue: 15, width: 6
            input pre + "prefix", "text", title: "Text added before each message (optional)", required: false, width: 6
            input pre + "pushover", "bool", title: "Mark as high priority (Pushover [H])" + tip("Adds [H] in front of the message on Pushover devices only."), defaultValue: false
        }
        section(uiTitle(null, "Modes")) {
            guestInputs("ntf_${nid}", false)
        }
        section(uiTitle(null, "Test")) {
            if (draft) {
                paragraph note("Save it first, then send a test. Its device <b>Notify – name</b> is created when you press Save.")
            } else {
                input "btnTestNtf_${nid}", "button", title: "Send a test"
                paragraph note("Used by: rules that send to the device <b>${esc(deviceLabel('ntf', nid))}</b>.")
            }
        }
        editFoot("ntf", nid)
    }
}

// ------------------------------------------------------------------ Sirens
def sirensPage(params = null) {
    state.remove("confirmDel")
    syncNames()
    dynamicPage(name: "sirensPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Sirens", "${APP_TITLE} · Setup")
            paragraph uiHint("Rules (and HSM) use <b>Siren – name</b>; Switchboard sounds the real sirens, except in Test Mode.")
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (!ids("sir")) paragraph uiHint("None yet.")
            ids("sir").each { String rid ->
                String pre = "sir_${rid}_"
                int n = ((settings[pre + "devs"] ?: []) as List).size()
                boolean on = (atomicState.sirenOffAt ?: [:]).containsKey(rid)
                Integer mx = intSet(pre + "maxMin", 5)
                editRow("sir", rid, esc(itemName("sir", rid)) + (n ? "" : " " + uiPill("no sirens", "warn")) + (on ? " " + uiPill("SOUNDING", "off") : ""),
                        [plural(n, "siren"), mx > 0 ? "stop after ${mx} min" : "never stops by itself"].join(" · "))
            }
        }
        addRow("sir")
        section(uiTitle(null, "Make sure sirens turn off")) {
            input "sirenVerify", "bool", title: "Check that sirens really turn off and retry" +
                  tip("Whenever Switchboard turns real sirens off, it checks 2 s later and sends off again if one is still on, " +
                      "every 2 s for 10 s, then every 15 s for up to 10 minutes. It can only see what the siren reports " +
                      "(its alarm / switch state; refreshed first when the siren supports it)."),
                  defaultValue: true, submitOnChange: true
            if (sirenVerify != false) {
                input "sirenAlertDevs", "capability.notification", title: "Tell me if a siren won't turn off" +
                      tip("Pushed straight to these, even in Test Mode or any mode. Empty = the Test Mode testers; " +
                          "if those are empty too, it is only written to the log."),
                      multiple: true, required: false
                if (!sirenAlertDevs && !tmDevices) paragraph warn("Nobody picked: a siren that won't turn off is only written to the log.")
            }
        }
    }
}

def sirenEditPage(params = null) {
    String rid = editTarget("sir", params)
    dynamicPage(name: "sirenEditPage", title: "", install: false, uninstall: false, nextPage: "commitPage") {
        if (!editHead("sir", rid)) return
        boolean draft = isDraft("sir", rid)
        String pre = "sir_${rid}_"
        boolean on = !draft && (atomicState.sirenOffAt ?: [:]).containsKey(rid)
        section(uiTitle(null, "Siren")) {
            if (on) paragraph uiPill("SOUNDING", "off")
            input pre + "name", "text", title: "Name", submitOnChange: true, width: 6
            input pre + "devs", "capability.alarm", title: "Real sirens" + tip("Some devices claim to be an alarm but aren't sirens (e.g. some power monitors). Pick only real sirens and strobes."),
                  multiple: true, required: false, submitOnChange: true
            if (((settings[pre + "devs"] ?: []) as List).size() != realDevs(settings[pre + "devs"]).size()) paragraph warn("Switchboard's own devices are ignored here. Pick real sirens.")
            input pre + "maxMin", "number", title: "Stop after (minutes)" + tip("Sirens are turned off after this long even if no rule turns them off. 0 = never."),
                  range: "0..60", defaultValue: 5, width: 6
        }
        section(uiTitle(null, "Modes")) {
            guestInputs("sir_${rid}", false)
        }
        section {
            if (draft) {
                paragraph note("Its device <b>Siren – name</b> is created when you press Save.")
            } else {
                input "btnSirOff_${rid}", "button", title: "Turn these sirens off now"
                paragraph note("Used by: rules that use the device <b>${esc(deviceLabel('sir', rid))}</b>.")
            }
        }
        editFoot("sir", rid)
    }
}

// ------------------------------------------------------------------ Sound files
def soundsPage(params = null) {
    state.remove("confirmDel")
    syncSounds()
    dynamicPage(name: "soundsPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Sound files", "${APP_TITLE} · Setup")
            paragraph uiHint("Named sounds that profiles play as a chime, and rules play with <b>playSound(name)</b>.") + tip(SND_URL_TIP)
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (!soundIds()) paragraph uiHint("No sounds yet.")
            soundIds().each { String sid ->
                String u = soundUrl(sid)
                String file = u ? u.replaceAll(/[?#].*$/, "").tokenize("/").with { it ? it.last() : u } : "no URL yet"
                editRow("snd", sid, esc(soundName(sid)) + (u ? "" : " " + uiPill("no URL", "warn")), "${file} · ${soundSecs(sid).stripTrailingZeros().toPlainString()} s")
            }
        }
        addRow("snd")
    }
}

def soundEditPage(params = null) {
    String sid = editTarget("snd", params)
    dynamicPage(name: "soundEditPage", title: "", install: false, uninstall: false, nextPage: "commitPage") {
        if (!editHead("snd", sid)) return
        boolean draft = isDraft("snd", sid)
        String pre = "snd_${sid}_"
        String url = draft ? (settings[pre + "url"] ?: "").toString().trim() : (soundUrl(sid) ?: "")
        Map files = hubAudioFiles()
        String setUrl = (settings[pre + "url"] ?: "").toString().trim()
        String urlFile = hubFileOf(setUrl, files)                      // the listed hub file the URL points to, if any
        boolean isLocal = setUrl ==~ /(?i)^https?:\/\/[^\/]+\/local\/.+/
        String dflt = urlFile ?: ((setUrl && !isLocal) || !files ? SND_URL_KEY : null)
        // the menu value this page last saw for each sound: a value that differs was just picked
        Map picks = (state.sndPick ?: [:]) as Map
        String cur = settings[pre + "file"] as String
        if (!picks.containsKey(sid)) {
            // first look: a leftover value that doesn't match the sound's URL must not change it, so show what the URL says
            if (setUrl && cur != dflt) {
                if (dflt) app.updateSetting(pre + "file", [type: "enum", value: dflt]) else app.removeSetting(pre + "file")
                cur = dflt
            }
            picks[sid] = cur ?: dflt ?: ""                 // "" = nothing picked (state may drop null values)
            state.sndPick = picks
        }
        String sel = cur ?: dflt
        boolean picked = ((picks[sid] ?: "") as String) != (sel ?: "")
        if (picked) { picks[sid] = sel ?: ""; state.sndPick = picks }
        section(uiTitle(null, "Sound")) {
            Map opts = files.collectEntries { k, v -> [(k): "${k}  (${v.kb} KB)".toString()] }
            opts[SND_URL_KEY] = "🌐 Use a web address (URL)…"
            Map a = [name: pre + "file", type: "enum", title: "Sound" + tip("Files in Hubitat → Settings → File Manager (upload an .mp3 there to see it here), or a web address."),
                     options: opts, required: false, submitOnChange: true, width: 8]
            if (dflt) a.defaultValue = dflt
            input a
            if (!files) paragraph warn("No sound files found on the hub — upload an .mp3 in Settings → File Manager, or use a web address")
            if (sel && sel != SND_URL_KEY && files[sel]) {
                if (picked || !setUrl) {
                    String want = hubFileUrl(sel)
                    if (setUrl != want) app.updateSetting(pre + "url", [type: "text", value: want])
                    app.updateSetting(pre + "secs", [type: "decimal", value: files[sel].secs])      // a new file: estimate its length
                    if (!cleanName(settings[pre + "name"] as String)) app.updateSetting(pre + "name", [type: "text", value: sel.replaceAll(/\.[^.]+$/, "")])
                    url = want
                }
            } else if (sel != SND_URL_KEY && setUrl) {
                paragraph note("Plays: ${esc(setUrl)}")
            }
            if (sel == SND_URL_KEY) {
                input pre + "url", "text", title: "Web address (URL)", submitOnChange: true, width: 12
                paragraph uiHint("Paste a <b>direct link to the audio file</b>. It must end in <b>.mp3</b> (or .wav / .m4a), not a web page.<br>" +
                                 "✓ <code>http://192.168.1.20/sounds/doorbell.mp3</code> — a file on your network<br>" +
                                 "✓ <code>https://example.com/chime.mp3</code> — a file on the internet<br>" +
                                 "✗ YouTube, Spotify or any web page link<br>" +
                                 "<b>http</b> works with every speaker; some speakers can't play <b>https</b> links. Short files (under 30 s) work best.")
                String u = (settings[pre + "url"] ?: "").toString().trim()
                if (u && !(u ==~ /(?i)^https?:\/\/.+/)) paragraph warn("It must start with http:// or https://")
                else if (u && !(u.replaceAll(/[?#].*$/, "") ==~ /(?i).*\.(mp3|wav|m4a|aac|ogg|flac)$/)) paragraph warn("That doesn't look like an audio file link (it should end in .mp3, .wav or .m4a). It may be a web page.")
            }
            input pre + "name", "text", title: "Name", submitOnChange: true, width: 6
            input pre + "secs", "decimal", title: "Length (seconds)" + tip("How long it plays; the next part or message waits this long (1.5 is fine), unless the speaker reports a real length more than a second different (Sonos does). For hub files this is estimated from the file size — adjust if it cuts off or waits too long."),
                  range: "0.5..3600", defaultValue: 5, submitOnChange: true, width: 3
            if (!url) paragraph warn(draft ? "Pick a file or enter a web address." : "Pick a file or enter a web address, or this sound is skipped.")
        }
        section(uiTitle(null, "Test")) {
            if (draft) {
                paragraph note("Save it first, then play it.")
            } else {
                input "sndTestSpk", "enum", title: "Play tests on", options: speakerOptions(), required: false, submitOnChange: true, width: 6
                if (!speakerOptions()) paragraph note("Pick speakers in an Announcement Profile first.")
                if (settings.sndTestSpk) input "btnTestSnd_${sid}", "button", title: "Play it"
                List users = ids("prof").findAll { settings["prof_${it}_chime"] == sid }.collect { itemName("prof", it) }
                paragraph users ? note("Chime for: ${users.join(', ')}") : note("Not used as a chime.")
            }
        }
        editFoot("snd", sid)
    }
}

/** audio files in the hub's File Manager: name → [kb, secs (estimated at 128 kbit/s)]. Empty if the list can't be read. */
private Map hubAudioFiles() {
    Map out = [:]
    try {
        httpGet([uri: "http://127.0.0.1:8080/hub/fileManager/json", timeout: 5]) { resp ->
            def d = resp.data
            if (!(d instanceof Map)) { hubReadResult(false); return }       // a login page or other HTML, not the list
            hubReadResult(true)
            (d.files ?: []).findAll { it instanceof Map && it.type == "file" && (it.name as String) ==~ /(?i).*\.(mp3|wav|m4a|aac|ogg|flac)$/ }
                          .sort { (it.name as String).toLowerCase() }
                          .each { f ->
                long b = 0L
                try { b = ((f.size ?: 0) as String).toLong() } catch (ignored) { }
                out[f.name as String] = [kb: (int) Math.max(1L, (long) (b / 1024)), secs: (int) Math.max(1L, Math.min(3600L, (long) Math.ceil(b / 16000.0d)))]
            }
        }
    } catch (e) {
        if (e.toString() =~ /(?i)\b40[13]\b|unauthori[sz]ed|forbidden/) hubReadResult(false)
        logDebug "File Manager list: ${e}"
    }
    return out
}
private String hubFileName(String name) { URLEncoder.encode(name, 'UTF-8').replace('+', '%20') }
/** URL a speaker can fetch a hub file from */
private String hubFileUrl(String name) {
    String ip = null
    try { ip = location.hub?.localIP } catch (ignored) { }
    if (!ip) try { ip = location.hubs[0]?.getDataValue("localIP") } catch (ignored) { }
    return "http://${ip ?: '127.0.0.1'}/local/${hubFileName(name)}".toString()
}
/** the listed hub file a URL points to (http://<host>/local/<file>), or null */
private String hubFileOf(String url, Map files) {
    String u = (url ?: "").trim().replaceAll(/[?#].*$/, "")
    if (!u || !files || !(u ==~ /(?i)^https?:\/\/[^\/]+\/local\/.+/)) return null
    return files.keySet().find { String k -> u.endsWith("/local/" + hubFileName(k)) || u.endsWith("/local/" + k) } as String
}

/** the hub's own pages (events, app status, File Manager) answered, or refused / sent a login page */
private void hubReadResult(boolean ok) {
    if (ok) { if (state.hubReadBlocked) state.remove("hubReadBlocked") }
    else if (!state.hubReadBlocked) { state.hubReadBlocked = true; log.warn "${APP_TITLE}: ${HUB_BLOCKED_MSG}" }
}

// ------------------------------------------------------------------ Speakers & queue
def speakersPage(params = null) {
    dynamicPage(name: "speakersPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Speakers & queue", "${APP_TITLE} · Setup")
            paragraph uiHint("Every speaker used by an Announcement Profile is listed here.")
            if (!allSpeakers()) paragraph warn("No speakers yet. Pick them in an Announcement Profile.")
        }
        allSpeakers().each { dev ->
            String id = dev.id.toString()
            section("${uiTitle(null, dev.displayName as String)}  ${note(dev.typeName as String)}", hideable: true, hidden: true) {
                input "spkMethod_${id}", "enum", title: "How to speak with volume" + tip("Speaker drivers differ. Auto picks a method from the driver; if volume or speech doesn't work right, try another one and use Test."),
                      options: SPEAK_METHODS, defaultValue: "auto", width: 8
                paragraph note("Auto would use: ${SPEAK_METHODS[autoMethod(dev)]}" +
                               (dev.hasCommand("playHighPriorityTTS") ? " · urgent messages use playHighPriorityTTS" : ""))
                input "spkLead_${id}", "decimal", title: "Extra wait (sec)" + tip("Added to the message length before the next message plays. Raise it if this speaker's messages get cut off."),
                      defaultValue: 0, width: 4
                input "spkSetDelay_${id}", "number", title: "Delay between setVolume and speak (ms)", defaultValue: 700, width: 4
                input "spkRestore_${id}", "bool", title: "Restore previous volume afterwards", defaultValue: true, width: 4
                if (dev.hasCommand("playTrackAndRestore") && dev.hasCommand("playTrack"))
                    input "spkPlain_${id}", "bool", title: "Play sounds without restore" + tip("Turn on if a message after a chime or sound is late by a minute or more (seen on Sonos S1). Sounds then use plain playTrack: music playing on this speaker stops instead of resuming."),
                          defaultValue: false, width: 8
                input "btnTest_${id}", "button", title: "Test this speaker"
            }
        }
        section(uiTitle(null, "How should messages line up?")) {
            input "queueMode", "enum", title: "Queue mode" + tip("One at a time everywhere: every announcement waits for the one before it, even on different speakers. Only when speakers overlap: announcements on different speakers play at the same time; they only wait when they share a speaker."),
                  options: [global: "One at a time everywhere", overlap: "Only wait when speakers overlap"], defaultValue: "global", width: 6
            input "durMode", "enum", title: "Message length" + tip("Measure asks the hub's text-to-speech for the real audio length. Estimate uses the text length (about 12 characters per second)."),
                  options: [measure: "Measure the spoken audio (recommended)", estimate: "Estimate from text length"], defaultValue: "measure", width: 6
            input "gapSec", "decimal", title: "Pause between messages (sec)", defaultValue: 2, width: 4
            input "maxQueue", "number", title: "Max waiting messages", defaultValue: 20, width: 4
            paragraph queueStatus()
            input "btnClearQ", "button", title: "Clear queue"
        }
    }
}

// ------------------------------------------------------------------ Test Mode
def testPage(params = null) {
    dynamicPage(name: "testPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Test Mode", "${APP_TITLE}")
            paragraph uiSummary("While Test Mode is on, nothing is spoken and no sirens sound. Every announcement, notification and " +
                                "siren request goes to the testers' phones instead, starting with TEST.", "What it does")
            if (state.tmErr) { paragraph warn(state.tmErr as String); state.remove("tmErr") }
        }
        section(uiTitle(null, isTestMode() ? "Test Mode is ON" : "Turn it on")) {
            if (isTestMode()) {
                paragraph uiPill("ON", "warn") + (state.tmOffAt ? " " + note("turns off ${fmtTime(state.tmOffAt as Long)}") : "")
                input "btnTmOff", "button", title: "Turn Test Mode off"
            } else {
                paragraph warn("<b>Read this first.</b> " + TM_WARNING)
                input "tmAck", "bool", title: "I understand: while Test Mode is on, alarms, sirens and announcements in the home are silenced — " +
                      "a real emergency only reaches the testers' phones. Smoke alarms with their own sounders still sound.",
                      defaultValue: false, submitOnChange: true
                if (!tmDevices) paragraph warn("Pick at least one tester below first.")
                input "btnTmOn", "button", title: "Turn on Test Mode"
                if (!tmAck) paragraph note("Tick the box to turn it on. You confirm it every time.")
            }
        }
        section(uiTitle(null, "Test Mode settings")) {
            paragraph note("Only this page can turn Test Mode on. There is no switch for it, so dashboards, rules and voice can't start it.") +
                      tip("It turns off by itself after the time below, when you press Turn off, or when the hub restarts. Each time it ends, you have to tick the box again to turn it back on.")
            input "tmDevices", "capability.notification", title: "Testers" + tip("Who gets every alert while Test Mode is on, and the Test Mode reminders."),
                  multiple: true, required: false, submitOnChange: true
            input "tmAutoOff", "enum", title: "Turn off after" + tip("Required for safety: while Test Mode is on, real alarms only reach the testers' phones. Never more than 4 hours."),
                  options: TM_AUTO_OFF, defaultValue: "60", required: true, submitOnChange: true, width: 6
            input "tmRemind", "number", title: "Remind testers every (minutes), 0 = off", range: "0..240", defaultValue: 60, width: 6
        }
    }
}


// ------------------------------------------------------------------ Guest Mode
def guestPage(params = null) {
    state.remove("confirmDel")
    syncGuests()
    dynamicPage(name: "guestPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Modes", "${APP_TITLE}")
            paragraph uiHint("Make a mode for each situation: <b>Guest modes</b> for visits (switch <i>Guest Mode, Paul - SB</i>), " +
                             "<b>other modes</b> for anything else, like yard work (switch <i>Yard Work - SB</i>). " +
                             "Each profile, notification and siren picks which ones make it play, which make it stay silent, and what it does the rest of the time. Play always wins.") +
                      tip("Several modes can be on at once. Each has its own switch that dashboards, webCoRE and Rule Machine can turn on and off.")
            if (state.gmErr) { paragraph warn(state.gmErr as String); state.remove("gmErr") }
            if (!guestIds()) paragraph uiHint("No modes yet.")
            guestIds().each { String gid ->
                boolean on = isGuestOn(gid)
                Long end = ((state.gmOffAt ?: [:]) as Map)[gid] as Long
                String src = gmSrc(gid)
                String how = src == "create" ? "own switch" :
                             (src == "existing" ? "switch: ${settings["gm_${gid}_switch"]?.displayName ?: 'none picked'}" :
                              "hub modes: ${((settings["gm_${gid}_modes"] ?: []) as List).join(', ') ?: 'none picked'}")
                editRow("gm", gid, esc(guestName(gid)) + " " + (on ? uiPill("ON", "info") : uiPill("off", "off")),
                        [on ? "ON" + (end ? " until ${fmtTime(end)}" : "") : "off", how].join(" · "))
            }
        }
        addRow("gm")
    }
}

def guestEditPage(params = null) {
    String gid = editTarget("gm", params)
    syncGuests()
    dynamicPage(name: "guestEditPage", title: "", install: false, uninstall: false, nextPage: "commitPage") {
        if (!editHead("gm", gid)) return
        boolean draft = isDraft("gm", gid)
        String pre = "gm_${gid}_"
        boolean on = isGuestOn(gid)
        Long end = ((state.gmOffAt ?: [:]) as Map)[gid] as Long
        String src = gmSrc(gid)
        section(uiTitle(null, "Mode")) {
            if (!draft) {
                paragraph on ? uiPill("ON", "info") + (end ? " " + note("until ${fmtTime(end)}") : "") : uiPill("off", "off")
                if (src != "modes") {
                    if (on) input "btnGmOff_${gid}", "button", title: "Turn off", width: 3
                    else input "btnGmOn_${gid}", "button", title: "Turn on", width: 3
                }
            }
            input pre + "type", "enum", title: "Kind of mode", submitOnChange: true, defaultValue: "other", width: 6,
                  options: [guest: "Guest mode (a visit)", other: "Other mode (yard work, cleaning…)"]
            input pre + "name", "text", title: "Name" + tip(gmGuest(gid) ? "Just the guest, e.g. Lily or All. The switch is named \"Guest Mode, Lily - SB\"; renaming renames it." :
                                                                   "E.g. Yard Work. The switch is named \"Yard Work - SB\"; renaming renames it."),
                  submitOnChange: true, width: 6
            input pre + "source", "enum", title: "Turned on by", submitOnChange: true, defaultValue: "create", width: 6,
                  options: [create: "Its own switch (recommended)", existing: "A switch I already have", modes: "Hub mode(s)"]
            if (src == "create") {
                paragraph note("Switch: <b>${esc(draft && !cleanName(settings[pre + 'name'] as String) ? '(the name above)' : gmSwitchLabel(gid))}</b>" +(sbChild(gmDni(gid)) ? "" : " (created when you press Save)"))
            } else if (src == "existing") {
                input pre + "switch", "capability.switch", title: "Switch", required: false, width: 6
                if (!settings[pre + "switch"]) paragraph warn("Pick a switch, or this mode can't turn on.")
            } else {
                input pre + "modes", "mode", title: "On while the hub is in", multiple: true, required: false, width: 6
                paragraph note("Your mode rules turn it on and off; Switchboard can't.")
            }
        }
        section(uiTitle(null, "When it ends")) {
            if (src != "modes") {
                input pre + "endDate", "date", title: gmGuest(gid) ? "Guests leave on" : "Ends on", required: false, submitOnChange: true, width: 4
                input pre + "endTime", "time", title: "at", required: false, submitOnChange: true, width: 4
                if (!draft && settings[pre + "endDate"] && settings[pre + "endTime"]) input "btnGmUntil_${gid}", "button", title: "On until then", width: 4
                input pre + "defaultHours", "number", title: "If turned on another way (dashboard, rule), turn off after (hours)" +
                      tip("0 = stay on until something turns it off."), defaultValue: 24, range: "0..720", width: 6
            }
            input pre + "remindTime", "time", title: "Daily reminder at (blank = none)", required: false, width: 4
            input pre + "remindDevs", "capability.notification", title: "Remind", multiple: true, required: false, width: 8
        }
        section {
            if (draft) {
                paragraph note("Once saved, profiles, notifications and sirens can pick it.")
            } else {
                List users = []
                KINDS.keySet().each { String kind -> ids(kind).each { String id ->
                    String p = "${kind}_${id}"
                    if (((settings["${p}_guestQuiet"] ?: []) as List).contains(gid) || ((settings["${p}_guestPlay"] ?: []) as List).contains(gid)) users << deviceLabel(kind, id)
                } }
                paragraph users ? note("Used by: ${users.join(', ')}") : note("Not used by any profile, notification or siren yet.")
            }
        }
        editFoot("gm", gid)
    }
}
/** Next on an edit page = Save: saves (creates a new item), then shows its list. A new item missing its name stays open with the reason. */
def commitPage(params = null) {
    String kind = state.editKind as String
    if (!kind || !EDITS[kind]) return mainPage()
    String id = state["editId_${kind}".toString()] as String
    if (id && rawMap(kind).containsKey(id)) {
        saveItem(kind, id)
        if (isDraft(kind, id)) return this."${EDITS[kind].edit}"()
        state.remove("editSaved")
    }
    return this."${EDITS[kind].list}"()
}

// ------------------------------------------------------------------ Message log
def logPage(params = null) {
    dynamicPage(name: "logPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        List all = logEntries()
        section {
            paragraph pageHead("Message log", "${APP_TITLE} · last ${LOG_MAX} messages")
            if (state.hubReadBlocked) paragraph warn(HUB_BLOCKED_MSG)
        }
        section {
            input "logDev", "enum", title: "Device", options: all.collect { it.dev as String }.findAll { it }.unique().sort(),
                  required: false, submitOnChange: true, width: 4
            input "logOutcome", "enum", title: "Outcome", options: all.collect { it.outcome as String }.findAll { it }.unique().sort(),
                  required: false, submitOnChange: true, width: 4
            input "logFrom", "enum", title: "From" + tip("Pick a whole rules system (e.g. every webCoRE piston) or one rule."),
                  options: fromOptions(all), required: false, submitOnChange: true, width: 4
            input "logWhen", "enum", title: "When", options: LOG_WHEN, defaultValue: "all", required: false, submitOnChange: true, width: 4
            if (logWhen == "custom") {
                input "logFromDate", "date", title: "From date", required: false, submitOnChange: true, width: 2
                input "logFromTime", "time", title: "time (blank = start of day)", required: false, submitOnChange: true, width: 2
                input "logToDate", "date", title: "To date", required: false, submitOnChange: true, width: 2
                input "logToTime", "time", title: "time (blank = end of day)", required: false, submitOnChange: true, width: 2
            }
            List span = logSpan()
            List l = all.findAll { e ->
                (!logDev || e.dev == logDev) && (!logOutcome || e.outcome == logOutcome) && fromMatches(e.source as String) &&
                (span[0] == null || ((e.t ?: 0) as Long) >= (span[0] as Long)) && (span[1] == null || ((e.t ?: 0) as Long) <= (span[1] as Long))
            }
            paragraph fromLegend()
            paragraph note("Showing ${l.size()} of ${all.size()}.")
            paragraph l ? logTable(l) : note("Nothing to show.")
            if (logDev || logOutcome || logFrom || (logWhen && logWhen != "all")) input "btnLogReset", "button", title: "Clear filters", width: 3
            input "btnClearLog", "button", title: "Clear log", width: 3
        }
    }
}

@Field static final Map LOG_WHEN = [all: "Any time", h1: "Last hour", today: "Today", h24: "Last 24 hours", d7: "Last 7 days", custom: "Between dates…"]

/** "From" filter choices: each rules system present ("sys:WC") then each single source */
private Map fromOptions(List all) {
    List srcs = all.collect { it.source as String }.findAll { it }.unique().sort()
    Map o = [:]
    RULE_SYSTEMS.each { r -> if (srcs.any { it.startsWith("${r[1]} – ") }) o["sys:${r[1]}".toString()] = "All ${r[2]} (${r[1]})".toString() }
    srcs.each { o[it] = it }
    return o
}
private boolean fromMatches(String src) {
    if (!logFrom) return true
    String f = logFrom as String
    return f.startsWith("sys:") ? (src ?: "").startsWith(f.substring(4) + " – ") : src == f
}
/** [start, end] in epoch ms for the "When" filter (nulls = open) */
private List logSpan() {
    long t = now()
    switch (logWhen ?: "all") {
        case "h1":  return [t - 3600000L, null]
        case "h24": return [t - 86400000L, null]
        case "d7":  return [t - 7 * 86400000L, null]
        case "today": return [timeToday("00:00", location.timeZone).time, null]
        case "custom": return [logPoint(logFromDate as String, logFromTime as String, false), logPoint(logToDate as String, logToTime as String, true)]
    }
    return [null, null]
}
private Long logPoint(String d, String tm, boolean end) {
    if (!d) return null
    try {
        String hm = tm ? toDateTime(tm).format("HH:mm", location.timeZone) : (end ? "23:59" : "00:00")
        def f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
        f.setTimeZone(location.timeZone)
        long ms = f.parse(d.take(10) + " " + hm).time
        return end ? ms + 59999L : ms
    } catch (e) { return null }
}

/** legend for the codes in the log's "From" column */
private String fromLegend() {
    note("<b>From:</b> " + RULE_SYSTEMS.findAll { it[1] != "SB" }.collect { "${it[1]} = ${it[2]}" }.join(" · ") +
         ". Any other app shows its own type, e.g. <i>Rule Forge – Office Door</i>.")
}

private String logTable(List rows) {
    String h = "<table class='sb-log'><tr><th>Time</th><th>Device</th><th>Message</th><th>Outcome</th><th>From</th></tr>"
    rows.each { Map e ->
        String oc = (e.outcome ?: "") as String
        String kind = oc.startsWith("played") || oc.startsWith("sent") || oc == "off confirmed" ? "on" :
                      (oc.startsWith("failed") || oc in ["alert", "gave up"] ? "off" : (oc.startsWith("test") || oc.startsWith("queued") || oc == "retry" ? "warn" : "info"))
        h += "<tr><td style='white-space:nowrap'>${fmtShort(e.t as Long)}</td><td>${esc(e.dev as String)}</td>" +
             "<td>${esc(e.kind as String)}: ${esc(e.text as String)}</td><td>${uiPill(oc, kind)}</td><td>${esc((e.source ?: '') as String)}</td></tr>"
    }
    return h + "</table>"
}

// ------------------------------------------------------------------ Settings
def settingsPage(params = null) {
    dynamicPage(name: "settingsPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Settings", "${APP_TITLE} · Setup")
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (state.okMsg) { paragraph uiOk(state.okMsg as String); state.remove("okMsg") }
        }
        section(uiTitle(null, "Night time")) {
            input "nightStart", "time", title: "Night starts" + tip("Profiles set to Day / night volume use their night volume between these times. Blank = 9:00 PM."), required: false, width: 4
            input "nightEnd", "time", title: "Night ends" + tip("Blank = 8:00 AM."), required: false, width: 4
            paragraph note("Now: " + (isNight() ? "night" : "day"))
        }
        section(uiTitle(null, "Who is home?")) {
            input "homeSensors", "capability.presenceSensor", title: "Presence sensors" + tip("Used by notifications set to 'Only when nobody is home'. Nobody is home when none of these are present."),
                  multiple: true, required: false
        }
        section(uiTitle(null, "Stopping alarms")) {
            input "stopOnDisarm", "bool", title: "When Safety Monitor is disarmed or cancels its alert, stop all sirens and sounds" +
                  tip("Turns off every Switchboard siren, stops speakers playing an urgent message or a sound, and drops waiting sounds and urgent messages. Normal spoken messages (e.g. 'System disarmed') still play."),
                  defaultValue: true
            if (isMissing("stop", "")) {
                paragraph warn("The <b>${esc(STOP_LABEL)}</b> switch is missing (deleted outside Switchboard, or it couldn't be created). Recreate it, or turn the option below off.")
                input "btnRecreate_stop", "button", title: "Recreate switch", width: 4
            }
            input "stopSwitchOn", "bool", title: "Create a '${STOP_LABEL}' switch for dashboards, buttons and voice" +
                  tip("Turning the switch on stops everything like the 'Stop all now' button; it turns itself back off after 2 seconds."),
                  defaultValue: true
            paragraph note("The switch is created or removed when you press Done on the main page.")
        }
        section(uiTitle(null, "Sounds from rules")) {
            input "defaultTrackSecs", "number", title: "If a speaker doesn't report a track's length, assume (seconds)" +
                  tip("Only used for tracks Switchboard doesn't know the length of, and only when the speaker doesn't report it (Sonos does). It decides how long the next announcement waits and when the volume goes back. It never cuts a track short."),
                  range: "1..3600", defaultValue: 10, width: 6
        }
        section(uiTitle(null, "Which rule sent it?")) {
            input "srcLookup", "bool", title: "Find which rule sent each message" + tip("Shortly after a message arrives, Switchboard reads the device's event list on this hub to find the rule or app that sent it, and shows it in the log. It never delays the message. A message starting with [Name] uses that name instead."),
                  defaultValue: true
            if (state.hubReadBlocked) paragraph warn(HUB_BLOCKED_MSG)
        }
        section(uiTitle(null, "Logging")) {
            input "logEnable", "bool", title: "Debug logging (turns off after 30 minutes)", defaultValue: false
            input "txtEnable", "bool", title: "Description logging", defaultValue: true
        }
    }
}

// ===================================================================== BUTTONS

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnDelNo":    state.remove("confirmDel"); return
        case "btnClearQ":   clearQueue(); return
        case "btnClearLog": withStateLock { atomicState.log = [] }; return
        case "btnLogReset": ["logDev", "logOutcome", "logFrom", "logWhen", "logFromDate", "logFromTime", "logToDate", "logToTime"].each { app.removeSetting(it) }; return
        case "btnTmOn":     turnTestModeOn(); return
        case "btnTmOff":    turnTestModeOff("Test Mode is OFF. Announcements, notifications and sirens are back to normal."); return
        case "btnStopAll":  stopAll("Stop all button"); return
        case "btnRecreateAll": recreateAll(); return
    }
    if (btn.startsWith("btnRecreate_")) {
        List p = (btn - "btnRecreate_").split("_", 2) as List
        recreateDevice(p[0] as String, p.size() > 1 ? p[1] as String : "")
        return
    }
    if (btn.startsWith("btnGmOn_"))    { guestOn(btn - "btnGmOn_", false); return }
    if (btn.startsWith("btnGmUntil_")) { guestOn(btn - "btnGmUntil_", true); return }
    if (btn.startsWith("btnGmOff_"))   { guestOff(btn - "btnGmOff_"); return }
    if (btn.startsWith("btnSave_")) {
        List p = (btn - "btnSave_").split("_", 2) as List
        if (p.size() == 2 && EDITS[p[0]]) { saveItem(p[0] as String, p[1] as String); refreshMissing() }    // its device may not have been created
        return
    }
    if (btn.startsWith("btnDelYes_")) {
        List p = (btn - "btnDelYes_").split("_", 2) as List
        state.remove("confirmDel")
        if (p.size() == 2 && EDITS[p[0]]) {
            String kind = p[0] as String, id = p[1] as String
            if (isDraft(kind, id)) { discardDraft(kind, id); state.editMsg = "Deleted. It was never saved." }
            else {
                if (kind == "snd") deleteSound(id)
                else if (kind == "gm") deleteGuest(id)
                else deleteItem(kind, id)
                if (!rawMap(kind).containsKey(id)) state.editMsg = "Deleted."
            }
            if (!rawMap(kind).containsKey(id)) state["deleted_${kind}".toString()] = now()    // a redraw now shows "Deleted", not a new draft
            refreshMissing()    // it may have been the missing one
        }
        return
    }
    if (btn.startsWith("btnDel_"))      { state.confirmDel = btn - "btnDel_"; return }
    // test buttons: in Test Mode they go to the testers like everything else
    if (isTestMode() && (btn.startsWith("btnTestProf_") || btn.startsWith("btnTest_") || btn.startsWith("btnTestSnd_") || btn.startsWith("btnTestNtf_"))) {
        routeToTesters("Speaker test (${btn})", "Test"); return
    }
    if (btn.startsWith("btnTestProf_")) {
        String pid = btn - "btnTestProf_"
        enqueue([dni: itemDni("prof", pid), profile: pid, text: "Test of the ${itemName('prof', pid)} announcement profile.", source: "Test"], null)
        processQueue(); return
    }
    if (btn.startsWith("btnTest_")) {
        enqueue([speakers: [btn - "btnTest_"], text: "Test announcement from ${APP_TITLE}.", source: "Test"], null)
        processQueue(); return
    }
    if (btn.startsWith("btnTestSnd_")) {
        String sid = btn - "btnTestSnd_"
        if (settings.sndTestSpk) { enqueue([speakers: [settings.sndTestSpk], sound: sid, source: "Test"], null); processQueue() }
        return
    }
    if (btn.startsWith("btnTestNtf_")) {
        String nid = btn - "btnTestNtf_"
        List devs = realDevs(settings["ntf_${nid}_devs"])
        pushTo(devs, "Test from ${APP_TITLE} (${itemName('ntf', nid)}).", false)
        return
    }
    if (btn.startsWith("btnSirOff_")) { sirensOff(btn - "btnSirOff_", "Turned off in the app"); return }
}

// ===================================================================== VIRTUAL DEVICES (profiles, notifiers, sirens)

private Map itemMap(String kind) { (state[KINDS[kind].stateKey as String] ?: [:]) as Map }
/** saved items only: drafts never reach the runtime */
List<String> ids(String kind) {
    Map m = itemMap(kind)
    return liveKeys(m).sort { a, b -> itemName(kind, a as String).toLowerCase() <=> itemName(kind, b as String).toLowerCase() } as List
}
String itemName(String kind, String id) {
    cleanName(settings["${kind}_${id}_name"] as String) ?: (itemMap(kind)[id]?.name ?: id) as String
}
String itemDni(String kind, String id) { "SB-${app.id}-${KINDS[kind].dev}-${id}".toString() }
String deviceLabel(String kind, String id) { "${KINDS[kind].label}${itemName(kind, id)}".toString() }
/** "SB-<appId>-spk-p1" → [kind: "prof", id: "p1"], or null */
private Map parseDni(String dni) {
    List p = (dni ?: "").split("-") as List
    if (p.size() != 4 || p[0] != "SB" || p[1] != app.id.toString()) return null
    String kind = KINDS.find { k, v -> v.dev == p[2] }?.key
    if (!kind || !itemMap(kind).containsKey(p[3]) || isDraft(kind, p[3] as String)) return null
    return [kind: kind, id: p[3]]
}

// ---- every Switchboard device is a child of one "Switchboard" parent device (the app's only child).
// The parent driver creates, deletes, relabels and updates its children; the children call the parent, which calls the app.
private String hubDni() { "SB-${app.id}-hub".toString() }
def hubDev() { getChildDevice(hubDni()) }
/** creates the parent device if missing; returns it */
private def ensureHub() {
    def h = hubDev()
    if (h) return h
    try {
        h = addChildDevice(NS, PARENT_DRIVER, hubDni(), [name: HUB_LABEL, label: HUB_LABEL, isComponent: false])
    } catch (e) {
        log.error "${APP_TITLE}: couldn't create the '${HUB_LABEL}' parent device: ${e}"
        state.pageErr = "Couldn't create the device \"${HUB_LABEL}\". Is the \"${PARENT_DRIVER}\" driver installed in Drivers Code? (${e.message})"
    }
    return h
}
/** one of the parent's children by DNI, or null */
def sbChild(String dni) { hubDev()?.sbChild(dni) }
/** creates a child under the parent device; throws if the hub refuses */
private def sbAddChild(String driver, String dni, String label) {
    def h = ensureHub()
    if (!h) throw new IllegalStateException("the '${HUB_LABEL}' parent device is missing")
    return h.sbAddChild(driver, dni, label)
}
/** deletes a child of the parent device; throws if the hub refuses (still used by a rule) */
private void sbDeleteChild(String dni) { hubDev()?.sbDeleteChild(dni) }
private void sbRelabel(def d, String label) { hubDev()?.sbRelabel(d.deviceNetworkId as String, label) }

/** Switchboard Switch children (Stop All, Guest Modes) report on / off here, through the parent. Handled after the
 *  command returns, like an event subscription would be, so the app's own on() / off() never re-enters the app. */
void sbSwitch(String dni, String value) {
    runInMillis(50, "sbSwitchEvent", [data: [dni: dni, value: value], overwrite: false])
}
void sbSwitchEvent(Map data) {
    String dni = (data?.dni ?: "") as String
    String v = (sbChild(dni)?.currentValue("switch") ?: data?.value ?: "") as String      // now, not when reported: a quick on / off can't land in the wrong order
    if (dni == stopDni()) {
        if (v == "on" && settings.stopSwitchOn != false) stopSwitchHandler(null)
        return
    }
    String gid = guestIds().find { gmSrc(it as String) == "create" && gmDni(it as String) == dni } as String
    if (gid) guestChanged(gid, v == "on")
}

// ---- missing devices: a saved item whose device is gone (deleted outside Switchboard, or couldn't be created).
// Switchboard never re-creates one on its own: its page offers Recreate or Delete, and the app's name in the Apps list
// shows "(1 device missing)" until it's sorted out.
@Field static final String MISSING_TAG = "sb-missing"

/** a child device was deleted (reported by its driver through the parent): update the warning once the hub is done */
void sbChildRemoved(String dni) { runIn(3, "refreshMissing") }

/** [kind, id, label] of every missing device; kind "stop" is the Stop All switch */
List<Map> missingDevices() {
    List<Map> out = []
    Set<String> have = childDnis()
    KINDS.keySet().each { String kind -> ids(kind).each { String id -> if (isMissing(kind, id, have)) out << [kind: kind, id: id, label: deviceLabel(kind, id)] } }
    guestIds().each { String gid -> if (isMissing("gm", gid, have)) out << [kind: "gm", id: gid, label: gmSwitchLabel(gid)] }
    if (isMissing("stop", "", have)) out << [kind: "stop", id: "", label: STOP_LABEL]
    return out
}
/** DNIs of the parent's children, read once (each sbChild is a call into the parent driver) */
private Set<String> childDnis() { (((hubDev()?.sbChildren() ?: []) as List).collect { it.deviceNetworkId as String }) as Set }
boolean isMissing(String kind, String id, Set<String> have = null) {
    if (have == null) have = childDnis()
    if (kind == "stop") return settings.stopSwitchOn != false && state.stopMade == true && !have.contains(stopDni())
    if (kind == "gm") return rawMap("gm").containsKey(id) && !isDraft("gm", id) && gmSrc(id) == "create" && gmMade(id) && !have.contains(gmDni(id))
    return KINDS[kind] != null && rawMap(kind).containsKey(id) && !isDraft(kind, id) && !have.contains(itemDni(kind, id))
}
/** the app's name in the Apps list: "Switchboard (1 device missing)" in red while any are missing */
void refreshMissing(List<Map> miss = null) {
    int n = (miss != null ? miss : missingDevices()).size()
    String base = ((app.label ?: APP_TITLE) as String).replaceAll(/(?s)\s*<span[^>]*sb-missing.*$/, "").trim() ?: APP_TITLE
    String want = n ? "${base} <span class='${MISSING_TAG}' style='color:#d92d20'>(${plural(n, 'device')} missing)</span>".toString() : base
    if (app.label != want) app.updateLabel(want)
}
/** Recreate button: a new device for the item. It gets a new id, so rules that used the old one must be pointed at it again. */
private void recreateDevice(String kind, String id, boolean one = true) {
    def d
    String label
    if (kind == "stop") { d = createStopSwitch(); label = STOP_LABEL }
    else if (kind == "gm") { ensureGuestSwitch(id, true, true); d = sbChild(gmDni(id)); label = gmSwitchLabel(id); if (d) initGuests() }
    else if (KINDS[kind]) { d = ensureDevice(kind, id); label = deviceLabel(kind, id) }
    if (d) {
        if (one) state.okMsg = "Recreated \"${label}\". It's a new device: point the rules that used the old one at it again" +
                               " (and tick it in webCoRE's available devices)."
        logInfo "Recreated the missing device '${label}'"
    }
    if (one) refreshMissing()
}
private void recreateAll() {
    List<Map> miss = missingDevices()
    miss.each { recreateDevice(it.kind as String, it.id as String, false) }
    List<Map> left = missingDevices()
    List done = (miss.collect { it.label } - left.collect { it.label })
    if (done) state.okMsg = "Recreated ${done.join(', ')}. They're new devices: point the rules that used the old ones at them again" +
                            " (and tick them in webCoRE's available devices)."
    refreshMissing(left)
}

/** creates the item's virtual device if missing; returns it */
private def ensureDevice(String kind, String id, String name = null) {
    String dni = itemDni(kind, id)
    def d = sbChild(dni)
    if (d) return d
    String label = "${KINDS[kind].label}${name ?: itemName(kind, id)}".toString()
    try {
        d = sbAddChild(KINDS[kind].driver as String, dni, label)
    } catch (e) {
        log.error "${APP_TITLE}: couldn't create '${label}': ${e}"
        if (hubDev()) state.pageErr = "Couldn't create the device \"${label}\". Is the \"${KINDS[kind].driver}\" driver installed in Drivers Code? (${e.message})"
    }
    return d
}

private void deleteItem(String kind, String id) {
    Map m = itemMap(kind)
    if (!m.containsKey(id)) return
    String dni = itemDni(kind, id)
    if (kind == "prof") sbStop(dni)
    if (kind == "sir") { Map so = atomicState.sirenOffAt ?: [:]; if (so.containsKey(id)) sirensOff(id, "Device deleted") }
    // device first: if the hub refuses (still used by a rule), keep the item so it can be deleted again later
    try { if (sbChild(dni)) sbDeleteChild(dni) } catch (e) {
        log.warn "${APP_TITLE}: couldn't delete device ${dni}: ${e}"
        state.pageErr = "Couldn't delete the device. If a rule still uses it, remove it from the rule first. (${e.message})"
        return
    }
    m.remove(id)
    state[KINDS[kind].stateKey as String] = m
    removeItemSettings(kind, id)
}

/** names from settings → state, relabel devices (missing ones are left for Recreate) */
private void syncNames() {
    KINDS.keySet().each { String kind ->
        Map m = itemMap(kind)
        m.each { id, v ->
            String n = cleanName(settings["${kind}_${id}_name"] as String)
            if (n) v.name = n
        }
        state[KINDS[kind].stateKey as String] = m
        m.keySet().each { String id ->
            def d = sbChild(itemDni(kind, id))
            String want = deviceLabel(kind, id)
            if (d && d.label != want) { try { sbRelabel(d, want) } catch (e) { logDebug "relabel ${want}: ${e}" } }
        }
    }
}

private void childEvent(def child, String name, def value) {
    if (child == null || value == null) return
    try { hubDev()?.sbEvent(child.deviceNetworkId as String, name, value) } catch (e) { logDebug "sendEvent ${name} on ${child}: ${e}" }
}

// ===================================================================== SOUND FILES

List<String> soundIds() {
    Map m = state.sounds ?: [:]
    return liveKeys(m).sort { a, b -> soundName(a as String).toLowerCase() <=> soundName(b as String).toLowerCase() } as List
}
private void deleteSound(String sid) {
    Map m = state.sounds ?: [:]
    m.remove(sid); state.sounds = m
    ["snd_${sid}_name", "snd_${sid}_file", "snd_${sid}_url", "snd_${sid}_secs"].each { app.removeSetting(it as String) }
    forgetSndPick(sid)
    ids("prof").each { pid -> if (settings["prof_${pid}_chime"] == sid) app.removeSetting("prof_${pid}_chime") }
}
private void syncSounds() {
    Map m = state.sounds ?: [:]
    m.each { sid, s ->
        String n = cleanName(settings["snd_${sid}_name"] as String); if (n) s.name = n
        if (settings["snd_${sid}_url"] != null) s.url = settings["snd_${sid}_url"].toString().trim()
        if (settings["snd_${sid}_secs"]) s.secs = soundSecs(sid)
    }
    state.sounds = m
}
String soundUrl(String sid) {
    if (!sid || !state.sounds?.get(sid) || isDraft("snd", sid)) return null
    String u = (settings["snd_${sid}_url"] != null ? settings["snd_${sid}_url"] : state.sounds[sid].url) as String
    return u?.trim() ?: null
}
BigDecimal soundSecs(String sid) {
    def v = settings["snd_${sid}_secs"] ?: state.sounds?.get(sid)?.secs
    return v ? [0.5G, v as BigDecimal].max() : 5G
}
String soundName(String sid) {
    if (!sid || !state.sounds?.get(sid)) return "(deleted sound)"
    return cleanName(settings["snd_${sid}_name"] as String) ?: state.sounds[sid].name as String
}
Map soundOptions() { soundIds().collectEntries { [(it): soundName(it)] } }
private String soundByName(String name) {
    String n = (name ?: "").trim().toLowerCase()
    return n ? soundIds().find { soundName(it).toLowerCase() == n } : null
}
private String soundByUrl(String url) {
    String u = (url ?: "").trim()
    return u ? soundIds().find { soundUrl(it) == u } : null
}

// ===================================================================== DRIVER → APP CONTRACT

/** Speaker: speak / playText* / playTrack* / playAnnouncement / playSound. req: [cmd, text, uri, volume, sound] */
void sbSpeak(String dni, Map req) {
    Map k = parseDni(dni)
    if (!k || k.kind != "prof") { log.warn "${APP_TITLE}: speech from unknown device ${dni}"; return }
    String pid = k.id as String
    def child = sbChild(dni)
    String devName = (child?.displayName ?: deviceLabel("prof", pid)) as String
    String cmd = (req?.cmd ?: "speak") as String
    String raw = req?.text != null ? req.text.toString() : null
    Map pre = splitPrefix(raw)
    String text = pre.text ? (pre.text as String) : null
    String src = pre.source as String
    String uri = req?.uri ? req.uri.toString().trim() : null
    String sndName = req?.sound ? req.sound.toString() : null
    Integer cVol = clampVol(toInt(req?.volume))       // per-message volume; the driver keeps its own volume attribute

    String kind = "speak", sid = null, label
    BigDecimal secs = null
    if (sndName) {
        kind = "sound"; sid = soundByName(sndName)
        label = "🔊 ${sid ? soundName(sid) : sndName}"
    } else if (uri) {
        kind = "sound"; sid = soundByUrl(uri)
        secs = sid ? soundSecs(sid) : (intSet("defaultTrackSecs", 10) as BigDecimal)
        label = "🔊 ${sid ? soundName(sid) : uri}"
    } else if (text) {
        label = text
    } else { logDebug "${devName}: ${cmd} with nothing to say"; return }

    String logId = newLogId()
    Map entry = [id: logId, t: now(), dev: devName, dni: dni, kind: kind, text: label, outcome: "", source: src ?: ""]
    childEvent(child, "lastMessage", label)
    childEvent(child, "trackDescription", label)
    if (src) childEvent(child, "lastSource", src)

    if (sndName && !sid && !isTestMode()) {          // in Test Mode the testers still see the request
        entry.outcome = "failed – no sound named '${sndName}'"
        addLog(entry); scheduleLookup(child, dni, cmd, raw ?: sndName, logId, src); return
    }
    if (isTestMode()) {
        routeToTesters(label, src ?: devName)
        entry.outcome = "test → testers"
        addLog(entry); scheduleLookup(child, dni, cmd, raw, logId, src); return
    }
    String g = guestSkip("prof_${pid}")
    if (g) { entry.outcome = g; addLog(entry); scheduleLookup(child, dni, cmd, raw, logId, src); return }
    if (settings["prof_${pid}_volMode"] == "caller" && callerMuted(child, cVol)) {
        entry.outcome = "skipped – muted"; addLog(entry); scheduleLookup(child, dni, cmd, raw, logId, src); return
    }
    if (isDuplicate(dni, label, intSet("prof_${pid}_dedupeSec", 15))) {
        entry.outcome = "skipped duplicate"; addLog(entry); scheduleLookup(child, dni, cmd, raw, logId, src); return
    }
    entry.outcome = "queued"
    addLog(entry)                                   // log first: a running queue may finish the item before we return
    String res = enqueue([dni: dni, profile: pid, text: text, sound: (uri ? null : sid), uri: uri, secs: secs, kind: kind,
                          callerVolume: cVol, source: src ?: devName, label: label], logId)
    if (res != "queued") logSet(logId, [outcome: res])
    scheduleLookup(child, dni, cmd, raw, logId, src)
    refreshStatus(dni)
    if (res == "queued") kickQueue()
}

/** Speaker stop(): drop this device's waiting items; stop the real speakers if this device's item is playing. */
void sbStop(String dni) {
    int n = 0
    [qUrgent(), qNormal()].each { ConcurrentLinkedQueue<Map> q ->
        new ArrayList<Map>(q).each { Map item ->
            if (item.dni == dni && q.remove(item)) {
                if (item.ukey) UKEYS.remove(item.ukey as String)
                logSet(item.logId as String, [outcome: ((item.step ?: 0) as Integer) > 0 ? "stopped" : "skipped – stopped"])
                n++
            }
        }
    }
    ConcurrentHashMap<String, Map> busy = mapFor(BUSY)
    long t = now()
    List keys = busy.findAll { key, v -> v.dni == dni && (v.until as Long) > t }.collect { key, v -> key as String }
    if (keys) {
        Map byId = allSpeakers().collectEntries { [(it.id.toString()): it] }
        keys.each { String key ->
            busy.remove(key)
        }
        runInMillis(1, "stopSpeakers", [overwrite: false, data: [ids: keys.findAll { byId[it] }]])
    }
    logInfo "Stop from ${sbChild(dni)?.displayName ?: dni}: ${n} waiting removed" + (keys ? ", playing message stopped" : "")
    refreshStatus(dni)
    kickQueue()
}

/**
 * Rules call Switchboard's devices, so commands made inside that call are credited to the rule on the real speaker's
 * Events page. Real speakers are only ever driven from Switchboard's own scheduled run, so they always show Switchboard.
 */
private void kickQueue() { runInMillis(1, "processQueue") }
void stopSpeakers(Map data) {
    Map byId = allSpeakers().collectEntries { [(it.id.toString()): it] }
    ((data?.ids ?: []) as List).each { String id ->
        def dev = byId[id]
        if (dev) { try { if (dev.hasCommand("stop")) dev.stop() } catch (e) { log.warn "${APP_TITLE}: stop failed on ${dev.displayName}: ${e}" } }
    }
    processQueue()
}

/** Speaker setVolume / volumeUp / volumeDown / mute: stored on the virtual device only, never sent to real speakers. */
void sbVolume(String dni, Integer volume) {
    Map k = parseDni(dni)
    if (!k || k.kind != "prof") return
    Integer v = clampVol(volume)
    if (v == null) return
    def child = sbChild(dni)                 // the driver already shows volume / level / mute
    logDebug "${child?.displayName ?: dni}: volume ${v} stored" + (settings["prof_${k.id}_volMode"] == "caller" ? " (used for its announcements)" : " (not used: profile sets its own volume)")
}

/** Notifier deviceNotification(text) */
void sbNotify(String dni, String rawText) {
    Map k = parseDni(dni)
    if (!k || k.kind != "ntf") { log.warn "${APP_TITLE}: notification from unknown device ${dni}"; return }
    String nid = k.id as String
    def child = sbChild(dni)
    String devName = (child?.displayName ?: deviceLabel("ntf", nid)) as String
    Map pre = splitPrefix(rawText)
    String text = pre.text as String
    String src = pre.source as String
    if (!text) return
    String logId = newLogId()
    Map entry = [id: logId, t: now(), dev: devName, dni: dni, kind: "push", text: text, outcome: "", source: src ?: ""]
    childEvent(child, "lastMessage", text)
    if (src) childEvent(child, "lastSource", src)

    if (isTestMode()) {
        routeToTesters(text, src ?: devName); entry.outcome = "test → testers"
    } else if (guestSkip("ntf_${nid}")) {
        entry.outcome = guestSkip("ntf_${nid}")
    } else if (settings["ntf_${nid}_when"] == "away" && someoneHome()) {
        entry.outcome = "skipped – someone home"
    } else if (isDuplicate(dni, text, intSet("ntf_${nid}_dedupeSec", 15))) {
        entry.outcome = "skipped duplicate"
    } else {
        List devs = realDevs(settings["ntf_${nid}_devs"])
        String pfx = (settings["ntf_${nid}_prefix"] ?: "").toString().trim()
        String ptag = (text =~ /^\[[HLNES]\]/).find() ? text.take(3) : ""     // a rule's own Pushover tag stays first
        String msg = pfx ? "${ptag}${pfx} ${text.substring(ptag.length()).trim()}".toString() : text
        int ok = pushTo(devs, msg, settings["ntf_${nid}_pushover"] == true)
        entry.outcome = ok ? "sent" : "failed"
    }
    addLog(entry)
    scheduleLookup(child, dni, "deviceNotification", rawText, logId, src)
}

/** Siren: "siren" | "strobe" | "both" | "off" */
void sbSiren(String dni, String mode) {
    Map k = parseDni(dni)
    if (!k || k.kind != "sir") { log.warn "${APP_TITLE}: siren command from unknown device ${dni}"; return }
    String rid = k.id as String
    String m = (mode ?: "off").toLowerCase()
    if (m == "on") m = "both"
    if (!(m in ["siren", "strobe", "both", "off"])) m = "off"
    def child = sbChild(dni)
    String devName = (child?.displayName ?: deviceLabel("sir", rid)) as String
    String logId = newLogId()
    Map entry = [id: logId, t: now(), dev: devName, dni: dni, kind: "siren", text: m, outcome: "", source: ""]

    if (m == "off") {
        int ok = sirenCommand(rid, "off")
        sirenOffAtSet(rid, null); scheduleSirenCheck()
        childEvent(child, "lastMessage", "Sirens off")
        entry.outcome = isTestMode() ? "off (Test Mode)" : (ok ? "played" : "failed")
    } else if (isTestMode()) {
        routeToTesters("🚨 Sirens would sound (${itemName('sir', rid)})", devName)
        // the driver already shows the requested alarm/switch state, so rules that check it still work
        childEvent(child, "lastMessage", "Test Mode — sirens not sounded")
        entry.outcome = "test → testers"
    } else if (guestSkip("sir_${rid}")) {
        childEvent(child, "alarm", "off"); childEvent(child, "switch", "off")        // reflect what Switchboard did
        childEvent(child, "lastMessage", "Silenced by a mode — sirens not sounded")
        entry.outcome = guestSkip("sir_${rid}")
    } else {
        int ok = sirenCommand(rid, m)
        if (!ok) { childEvent(child, "alarm", "off"); childEvent(child, "switch", "off") }
        childEvent(child, "lastMessage", ok ? "Sounding (${m})" : "No sirens responded")
        entry.outcome = ok ? "played" : "failed"
        Integer mx = intSet("sir_${rid}_maxMin", 5)
        if (ok && mx > 0) {
            sirenOffAtSet(rid, now() + mx * 60000L)
            scheduleSirenCheck()
        }
    }
    addLog(entry)
    scheduleLookup(child, dni, m == "both" ? "both|on" : m, null, logId, null)
}

/** sends the command to the real sirens; returns how many accepted it */
private int sirenCommand(String rid, String m) {
    int ok = 0
    List devs = realDevs(settings["sir_${rid}_devs"])
    // record this command per device BEFORE sending it (and, for a (re)start, stop any off-verification): the
    // verifier re-checks this record under the same lock right before a retry off(), so it can never follow a re-trigger
    Long tok = sirenRecord(devs, m)
    devs.each { dev ->
        try {
            switch (m) {
                case "siren":  dev.siren();  break
                case "strobe": dev.strobe(); break
                case "both":   dev.both();   break
                default:       dev.off()
            }
            ok++
        } catch (e) { log.error "${APP_TITLE}: ${m} failed on ${dev.displayName}: ${e}" }
    }
    logInfo "Sirens ${itemName('sir', rid)}: ${m} (${ok} device(s))"
    // every off to real sirens (rule, stop timer, stopAll, button, delete…) passes here: make sure it took
    if (m == "off") sirenVerifyStart(rid, devs, tok)
    return ok
}

/**
 * atomicState.sirenLast: real device id -> [c: last command Switchboard sent (siren|strobe|both|off), n: token].
 * Tokens strictly increase. A siren/strobe/both also drops the device from the off-verification map.
 */
private Long sirenRecord(List devs, String m) {
    if (!devs) return null
    Long tok = null
    Set<String> dropped = [] as Set
    withStateLock {
        Map last = (atomicState.sirenLast ?: [:]) as Map
        long prev = (last.values().collect { ((it?.n ?: 0L) as Long) }.max() ?: 0L) as Long
        tok = Math.max(now(), prev + 1L)
        devs.each { last[it.id.toString()] = [c: m, n: tok] }
        atomicState.sirenLast = last
        if (m != "off") {
            Map v = (atomicState.sirenVerify ?: [:]) as Map
            devs.each { String k = it.id.toString(); if (v.remove(k) != null) dropped << k }
            if (dropped) atomicState.sirenVerify = v
        }
    }
    if (dropped) { logDebug "Siren off check cancelled (siren sounded again): ${dropped}"; sirenVerifySchedule() }
    return tok
}

// ------------------------------------------------------------------ Siren off verification
// atomicState.sirenVerify: real device id -> [since, tries, alerted, name, sbName, sbDev, rid, next, rf, offT, offAt]
//   offT/offAt = token (see sirenRecord) and time of the latest Switchboard off this entry stands for.
//   since = first off (ms), tries = off() sent so far (the first counts), next = when to look again,
//   rf = refresh() already sent for this look (the read happens 1 s later). One handler, sirenVerifyCheck, serves all.
@Field static final long SV_FAST_MS  = 2000L      // fast phase: look every 2 s …
@Field static final long SV_FAST_FOR = 10000L     // … for 10 s, then warn once
@Field static final long SV_SLOW_MS  = 15000L     // slow phase: every 15 s …
@Field static final long SV_GIVE_UP  = 600000L    // … until 10 min after the first off
@Field static final long SV_RF_WAIT  = 1000L      // refresh() this long before reading

private boolean sirenVerifyOn() { settings.sirenVerify != false }

private void sirenVerifyStart(String rid, List devs, Long tok) {
    if (!sirenVerifyOn() || !devs || tok == null) return
    long t = now()
    String sbName = itemName("sir", rid), sbDev = deviceLabel("sir", rid)
    withStateLock {
        Map v = (atomicState.sirenVerify ?: [:]) as Map
        devs.each { dev ->
            String k = dev.id.toString()
            // offT = token of the off this entry stands for (a retry off() is sent only while it is still the last command)
            if (v[k]) { Map e = [:] + (v[k] as Map); e.offT = tok; e.offAt = t; v[k] = e; return }   // already checked: keep clock and tries
            boolean rf = dev.hasCommand("refresh")
            v[k] = [since: t, tries: 1, alerted: false, name: dev.displayName as String, sbName: sbName, sbDev: sbDev,
                    rid: rid, next: t + SV_FAST_MS - (rf ? SV_RF_WAIT : 0L), rf: false, offT: tok, offAt: t]
        }
        atomicState.sirenVerify = v
    }
    sirenVerifySchedule()
}

/**
 * true when the siren is on because of something other than Switchboard (HSM, a rule driving the real device…):
 * it reported "off" at some point after our off and is on again, or (when its history can't be read) its "on" was
 * reported more than 5 s after our off. Such a siren is a deliberate new alarm and is never sent off by the verifier.
 */
private boolean sirenOnByOther(dev, Map e) {
    long offAt = ((e.offAt ?: e.since ?: 0L) as Long)
    if (!offAt) return false
    Date from = new Date(offAt)
    boolean other = false
    ["alarm", "switch"].each { String a ->
        if (other) return
        def st = null
        try { st = dev.currentState(a) } catch (ignored) { }
        if (st == null || (st.value as String) == "off") return
        Long stT = null
        try { stT = st.date?.time } catch (ignored) { }
        if (stT == null || stT <= offAt) return              // still the on that was there before our off
        try {
            List hist = (dev.statesSince(a, from, [max: 50]) ?: []) as List
            if (hist.any { (it?.value as String) == "off" }) other = true
        } catch (ignored) {
            if (stT > offAt + 5000L) other = true
        }
    }
    return other
}

/** read + (re)schedule under the lock, so a stale read can't unschedule / postpone a check another run just added */
private void sirenVerifySchedule() {
    withStateLock {
        Map v = (atomicState.sirenVerify ?: [:]) as Map
        if (!v) { unschedule("sirenVerifyCheck"); return }
        long next = v.values().collect { (it.next ?: 0L) as Long }.min()
        runInMillis(Math.max(200L, next - now()), "sirenVerifyCheck")
    }
}

/** the real siren devices Switchboard knows, by id */
private Map sirenDevsById() {
    Map m = [:]
    ids("sir").each { String rid -> realDevs(settings["sir_${rid}_devs"]).each { m[it.id.toString()] = it } }
    return m
}

/** off = alarm "off" (if it reports alarm) and switch "off" (if it reports switch); no value = nothing to judge by */
private boolean sirenIsOff(dev) {
    def a = null, s = null
    try { a = dev.currentValue("alarm", true) } catch (ignored) { try { a = dev.currentValue("alarm") } catch (ignored2) { } }
    try { s = dev.currentValue("switch", true) } catch (ignored) { try { s = dev.currentValue("switch") } catch (ignored2) { } }
    return (a == null || a as String == "off") && (s == null || s as String == "off")
}

void sirenVerifyCheck() {
    if (!sirenVerifyOn()) { atomicState.sirenVerify = [:]; unschedule("sirenVerifyCheck"); return }
    Map snap = (atomicState.sirenVerify ?: [:]) as Map
    if (!snap) return
    long t = now()
    Map byId = sirenDevsById()
    // 1) decide, without holding the lock (reading devices)
    Map plan = [:]                                   // id -> [act: refresh|done|retry|giveup|gone, e: new entry or null]
    snap.each { k, e0 ->
        Map e = [:] + (e0 as Map)
        if (((e.next ?: 0L) as Long) > t + 100L) return
        def dev = byId[k as String]
        if (!dev) { plan[k] = [act: "gone"]; return }
        boolean hasRf = dev.hasCommand("refresh")
        if (hasRf && e.rf != true) {
            e.rf = true; e.next = t + SV_RF_WAIT
            plan[k] = [act: "refresh", e: e]; return
        }
        if (sirenIsOff(dev)) { plan[k] = [act: "done", e: e]; return }
        if (sirenOnByOther(dev, e)) { plan[k] = [act: "other", e: e]; return }
        long el = t - ((e.since ?: t) as Long)
        if (el >= SV_GIVE_UP) { plan[k] = [act: "giveup", e: e]; return }
        boolean alertNow = el >= SV_FAST_FOR - 500L && e.alerted != true
        int triesBefore = ((e.tries ?: 1) as Integer)
        e.tries = triesBefore + 1
        if (alertNow) e.alerted = true
        e.rf = false
        e.next = t + (el >= SV_FAST_FOR - 500L ? SV_SLOW_MS : SV_FAST_MS) - (hasRf ? SV_RF_WAIT : 0L)
        plan[k] = [act: "retry", e: e, alert: alertNow, before: triesBefore]
    }
    if (!plan) { sirenVerifySchedule(); return }
    // 2) apply under the lock, only to entries still there and unchanged in origin (a re-trigger removes them).
    //    A retry off() is sent right here, inside the lock, and only while that off is still the device's last
    //    Switchboard command: sirenRecord takes the same lock before any siren/strobe/both is sent, so a retry
    //    can never land after a re-trigger.
    Map go = [:]
    withStateLock {
        Map v = (atomicState.sirenVerify ?: [:]) as Map
        Map last = (atomicState.sirenLast ?: [:]) as Map
        plan.each { k, p ->
            Map cur = v[k] as Map
            if (!cur) return
            if (p.e && ((cur.since ?: 0L) as Long) != ((p.e.since ?: 0L) as Long)) return
            if (p.e) { p.e.offT = cur.offT; p.e.offAt = cur.offAt }      // a newer Switchboard off may have moved them
            if (p.act == "retry") {
                Map lc = last[k] as Map
                if (!lc || lc.c != "off" || cur.offT == null || ((lc.n ?: 0L) as Long) != (cur.offT as Long)) {
                    v.remove(k); go[k] = [act: "superseded", e: p.e]; return
                }
                def dev = byId[k as String]
                try { dev.off() } catch (ex) { log.error "${APP_TITLE}: off failed on ${dev.displayName}: ${ex}" }
                // Alarm and Switch share the one off() command; a switch-only "on" is covered by the same call
            }
            if (p.act in ["refresh", "retry"]) v[k] = p.e else v.remove(k)
            go[k] = p
        }
        atomicState.sirenVerify = v
    }
    // 3) act: device commands, pushes, log
    go.each { k, p ->
        Map e = p.e as Map
        def dev = byId[k as String]
        switch (p.act) {
            case "gone":
                logDebug "Siren off check dropped for device ${k}: no longer one of Switchboard's sirens"
                break
            case "refresh":
                try { dev.refresh() } catch (ex) { log.warn "${APP_TITLE}: refresh failed on ${dev.displayName}: ${ex}" }
                break
            case "done":
                int n = (e.tries ?: 1) as Integer
                if (e.alerted == true) {
                    sirenAlert("✓ ${e.name} is off now (after ${n} tries)")
                    sirenVerifyLog(e, "${e.name} is off now (after ${n} tries)", "off confirmed")
                } else if (n > 1) {
                    sirenVerifyLog(e, "${e.name} is off (after ${n} tries)", "off confirmed")
                }
                break
            case "giveup":
                String gm = "❌ Gave up on ${e.name} after 10 minutes — please check it"
                sirenAlert(gm)
                sirenVerifyLog(e, "${e.name} still on after 10 min and ${e.tries} tries — gave up", "gave up")
                break
            case "superseded":
                logDebug "Siren off check dropped for ${e?.name ?: k}: Switchboard sent it another command"
                break
            case "other":
                String om = "${e.name} was turned on by something else — not retrying"
                if (e.alerted == true) sirenAlert("ℹ️ ${om}") else log.warn "${APP_TITLE}: ${om}"
                sirenVerifyLog(e, om, "not retried")
                break
            case "retry":
                if (p.alert) {
                    sirenAlert("⚠️ ${e.name} didn't turn off after ${p.before} tries. Still trying.")
                    sirenVerifyLog(e, "${e.name} didn't turn off after ${p.before} tries — warning sent, off sent again (try ${e.tries})", "alert")
                } else {
                    sirenVerifyLog(e, "${e.name} still on — off sent again (try ${e.tries})", "retry")
                }
                break
        }
    }
    sirenVerifySchedule()
}

private void sirenVerifyLog(Map e, String text, String outcome) {
    String rid = (e.rid ?: "") as String
    addLog([id: newLogId(), t: now(), dev: (e.sbDev ?: APP_TITLE) as String, dni: rid ? itemDni("sir", rid) : "",
            kind: "siren", text: text, outcome: outcome, source: APP_TITLE])
}

/** safety notice: straight to the chosen phones (or the testers), never through Test Mode / Guest Mode routing */
private void sirenAlert(String msg) {
    List devs = realDevs(sirenAlertDevs)
    if (!devs) devs = realDevs(tmDevices)
    log.warn "${APP_TITLE}: ${msg}"
    if (devs) pushTo(devs, msg, true)
}
private void sirensOff(String rid, String why) {
    sirenCommand(rid, "off")
    sirenOffAtSet(rid, null)
    String dni = itemDni("sir", rid)
    def child = sbChild(dni)
    childEvent(child, "alarm", "off"); childEvent(child, "switch", "off"); childEvent(child, "lastMessage", why)
    addLog([id: newLogId(), t: now(), dev: (child?.displayName ?: deviceLabel("sir", rid)) as String, dni: dni, kind: "siren", text: "off", outcome: why, source: APP_TITLE])
    scheduleSirenCheck()
}
/** siren auto-stop times live in atomicState and change under a lock: sirens are often sounded by several runs at once */
private void sirenOffAtSet(String rid, Long at) {
    withStateLock {
        Map so = (atomicState.sirenOffAt ?: [:]) as Map
        if (at == null) so.remove(rid) else so[rid] = at
        atomicState.sirenOffAt = so
    }
}
private void scheduleSirenCheck() {
    Map so = atomicState.sirenOffAt ?: [:]
    if (!so) { unschedule("sirenTimeoutCheck"); return }
    Long next = so.values().collect { it as Long }.min()
    runIn(Math.max(1L, (long) ((next - now()) / 1000) + 1), "sirenTimeoutCheck")
}
void sirenTimeoutCheck() {
    Map so = atomicState.sirenOffAt ?: [:]
    long t = now()
    new ArrayList(so.keySet()).each { rid ->
        if ((so[rid] as Long) <= t) sirensOff(rid as String, "Stopped after ${intSet("sir_${rid}_maxMin", 5)} min")
    }
    scheduleSirenCheck()
}

// ===================================================================== STOPPING ALARMS

/**
 * Stops everything Switchboard is sounding: sirens off, real speakers stopped (volume restored by the queue),
 * waiting urgent messages and waiting sounds / tracks dropped. Waiting normal text announcements are kept.
 * Callers are all Switchboard's own runs (button, HSM event, Stop All switch event), so the real devices credit Switchboard.
 */
void stopAll(String why) {
    String w = why ?: "Stop"
    // ---- sirens (maxMin 0 sirens have no timer, so the child's own state counts too)
    Map so = (atomicState.sirenOffAt ?: [:]) as Map
    int sirens = 0
    ids("sir").each { String rid ->
        def child = sbChild(itemDni("sir", rid))
        String al = child ? (child.currentValue("alarm") ?: "off") as String : "off"
        boolean sounding = so.containsKey(rid) || al != "off" || child?.currentValue("switch") == "on"
        if (!sounding) return
        sirenCommand(rid, "off")
        sirenOffAtSet(rid, null)
        childEvent(child, "alarm", "off"); childEvent(child, "switch", "off"); childEvent(child, "lastMessage", "Stopped – ${w}".toString())
        sirens++
    }
    scheduleSirenCheck()

    // ---- queue: drop waiting urgent items and waiting sounds / tracks; keep normal text announcements
    int dropped = 0
    Set<String> dnis = [] as Set
    new ArrayList<Map>(qUrgent()).each { Map item ->
        if (qUrgent().remove(item)) { stopDrop(item, w); dropped++; if (item.dni) dnis << (item.dni as String) }
    }
    new ArrayList<Map>(qNormal()).each { Map item ->
        if ((item.sound || item.uri) && qNormal().remove(item)) { stopDrop(item, w); dropped++; if (item.dni) dnis << (item.dni as String) }
    }

    // ---- speakers playing an urgent item or a sound / track item: free them, then stop() the real ones
    //      (a speaker playing a normal text announcement is left alone)
    ConcurrentHashMap<String, Map> busy = mapFor(BUSY)
    long t = now()
    Map byId = allSpeakers().collectEntries { [(it.id.toString()): it] }
    List<String> spk = []
    new ArrayList<String>(busy.keySet()).each { String k ->
        Map b = busy[k]
        if (!b || !b.alarm || (b.until as Long) <= t || !busy.remove(k, b)) return
        if (b.dni) dnis << (b.dni as String)
        if (byId[k]) spk << k
    }

    dnis.each { refreshStatus(it) }
    String summary = "Stop all (${w}): ${sirens} siren(s) off, ${spk.size()} speaker(s) stopped, ${dropped} waiting message(s) removed"
    if (sirens || spk || dropped) {
        addLog([id: newLogId(), t: now(), dev: APP_TITLE, dni: "", kind: "stop", text: w, outcome: "stopped", source: ""])
        logInfo summary
    } else logDebug summary + " – nothing was sounding"
    // a pending queue run must not start a kept announcement before the stop() below reaches the speakers
    unschedule("processQueue")
    stopSpeakers([ids: spk])          // stop() on each, then processQueue (restores volumes, plays kept announcements)
}
private void stopDrop(Map item, String why) {
    if (item.ukey) UKEYS.remove(item.ukey as String)
    logSet(item.logId as String, [outcome: "stopped – ${why}".toString()])
}

void hsmStopStatusHandler(evt) {
    if (settings.stopOnDisarm == false) return
    if ((evt?.value as String) in ["disarmed", "allDisarmed"]) stopAll("Safety Monitor disarmed")
}
void hsmStopAlertHandler(evt) {
    if (settings.stopOnDisarm == false) return
    if ((evt?.value as String) == "cancel") stopAll("Safety Monitor alert cancelled")
}

private String stopDni() { "SB-${app.id}-stopall".toString() }
/** creates or deletes the Stop All switch to match the setting (initialize) */
private void syncStopSwitch() {
    String dni = stopDni()
    def d = sbChild(dni)
    if (settings.stopSwitchOn != false) {
        if (!d && state.stopMade != true) d = createStopSwitch()        // made once; deleted later = missing (Recreate)
        if (d) state.stopMade = true
        if (d && d.currentValue("switch") == "on") runIn(2, "stopSwitchReset")      // its on / off arrive through sbSwitch
    } else if (!d) {
        state.remove("stopMade")
    } else {
        try { sbDeleteChild(dni); state.remove("stopMade") } catch (e) {
            log.warn "${APP_TITLE}: couldn't delete the '${STOP_LABEL}' switch: ${e}"
            state.pageErr = "Couldn't delete the \"${STOP_LABEL}\" switch. If a rule or dashboard uses it, remove it there first. (${e.message})"
        }
    }
}
private def createStopSwitch() {
    def d = null
    try { d = sbAddChild(SWITCH_DRIVER, stopDni(), STOP_LABEL) }
    catch (e) { log.error "${APP_TITLE}: couldn't create the '${STOP_LABEL}' switch: ${e}"; if (hubDev()) state.pageErr = "Couldn't create the switch \"${STOP_LABEL}\". (${e.message})" }
    if (d) state.stopMade = true
    return d
}
void stopSwitchHandler(evt) {
    stopAll("Stop All switch")
    runIn(2, "stopSwitchReset")
}
void stopSwitchReset() {
    def d = sbChild(stopDni())
    if (d && d.currentValue("switch") == "on") { try { d.off() } catch (e) { log.warn "${APP_TITLE}: couldn't turn off '${STOP_LABEL}': ${e}" } }
}

// ===================================================================== DELIVERY HELPERS

/** "[Name] text" → [text: "text", source: "Name"]. Multi-line text works; Pushover tags ([H], [L], [N], [E], [S]) are kept in the text. */
private Map splitPrefix(String raw) {
    if (raw == null) return [text: null, source: null]
    def mt = (raw =~ /(?s)^\s*\[(?![HLNES]\])([^\]]{1,60})\]\s*(.*)$/)
    if (mt.find()) {
        String n = cleanName(mt.group(1) as String)
        return [text: (mt.group(2) as String)?.trim(), source: n ?: null]
    }
    return [text: raw.trim(), source: null]
}

/** caller-volume profiles: a muted Switchboard speaker (mute() → volume 0) stays silent */
private boolean callerMuted(def child, Integer reqVol) {
    if (child?.currentValue("mute") == "muted") return true
    if (reqVol != null) return reqVol == 0
    Integer v = toInt(child?.currentValue("volume"))
    return v != null && v == 0
}

/** same text on the same device within 'secs' → true (the first time is remembered, not refreshed) */
private boolean isDuplicate(String dni, String body, Integer secs) {
    if (!secs || secs <= 0) return false
    long t = now()
    String key = "dd|${app.id}|${dni}|${body}".toString()
    TM_RECENT.keySet().toList().each { String kk -> Long v = TM_RECENT[kk]; if (v != null && v < t - 3600000L) TM_RECENT.remove(kk) }
    Long last = TM_RECENT[key]
    if (last != null && t - last < secs * 1000L) return true
    TM_RECENT[key] = t
    return false
}

private boolean someoneHome() {
    List s = (homeSensors ?: []) as List
    return s.any { it.currentValue("presence") == "present" }
}

/** push to devices; [H] for Pushover devices when high. Returns how many accepted it. */
private int pushTo(List devs, String msg, boolean high) {
    int ok = 0
    (devs ?: []).unique { it.id }.each { d ->
        String m = msg
        if (high && !(m =~ /^\[[HLNES]\]/).find() && (d.typeName ?: "").toLowerCase().contains("pushover")) m = "[H]" + m
        try { d.deviceNotification(m); ok++ } catch (e) { log.error "${APP_TITLE}: notification failed on ${d.displayName}: ${e}" }
    }
    if (ok) logInfo "Push to ${devs*.displayName.join(', ')}: ${msg}"
    return ok
}

// ===================================================================== MESSAGE LOG

private String newLogId() { "${now()}-${(int) (Math.random() * 100000)}".toString() }
List logEntries() { (atomicState.log ?: []) as List }
/** runs c while holding this app's state lock (waits at most 3 s, then runs anyway so nothing is lost) */
private def withStateLock(Closure c) {
    Semaphore s = STATE_LOCKS.get(app.id)
    if (s == null) { STATE_LOCKS.putIfAbsent(app.id, new Semaphore(1)); s = STATE_LOCKS.get(app.id) }
    boolean got = false
    try { got = s.tryAcquire(3000L, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (ignored) { }
    try { return c.call() } finally { if (got) s.release() }
}
private void addLog(Map e) {
    withStateLock {
        List l = (atomicState.log ?: []) as List
        l.add(0, e)
        while (l.size() > LOG_MAX) l.remove(l.size() - 1)
        atomicState.log = l
    }
    logInfo "${e.dev}: ${e.kind} '${e.text}' → ${e.outcome}" + (e.source ? " [${e.source}]" : "")
}
private void logSet(String id, Map ch) {
    if (!id) return
    withStateLock {
        List l = (atomicState.log ?: []) as List
        Map e = l.find { it.id == id } as Map
        if (e) { e.putAll(ch); atomicState.log = l }
    }
}

// ===================================================================== SOURCE LOOKUP (which rule sent it)

private void scheduleLookup(def child, String dni, String cmd, String rawText, String logId, String src) {
    if (src || srcLookup == false || !child) return
    Map data = [dni: dni, devId: child.id.toString(), cmd: cmd ?: "", text: (rawText ?: "").take(40), at: now(), logId: logId]
    runInMillis(1500, "srcLookupRun", [overwrite: false, data: data])
}
/** The hub lets its own apps read 127.0.0.1:8080 without logging in (also with Hub Login Security on). */
void srcLookupRun(Map data) {
    try { srcFetch(data) } catch (e) { logDebug "source lookup: ${e}"; applySource(data, "unknown") }
}
private void srcFetch(Map data) {
    asynchttpGet("srcLookupResp", [uri: "http://127.0.0.1:8080/device/eventsJson/${data.devId}".toString(), timeout: 10], data)
}
/** Undocumented endpoint: every step is guarded and anything unexpected gives "unknown". */
void srcLookupResp(resp, Map data) {
    String src = null
    try {
        int st = resp.status as int
        if (st == 401 || st == 403) hubReadResult(false)
        if (st == 200) {
            def body = null
            try { body = resp.json } catch (ignored) { }
            if (body == null) { try { body = parseJson(resp.data as String) } catch (ignored) { } }
            hubReadResult(body != null)                                     // null: HTML (a login page?), not JSON
            List rows = []
            if (body instanceof List) rows = body as List
            else if (body instanceof Map) rows = (body.events ?: body.data ?: body.rows ?: []) as List
            // rows look like [type: "command", name: "command-speak", descriptionText: "Command called: speak(text, 100)",
            //                 date: "2026-10-26T13:43:38.997-0600", producedBy: "<a href='/installedapp/configure/838' …>Goodnight 🌙</a>"]
            List cmds = ((data.cmd ?: "") as String).tokenize("|").collect { "command-" + it }
            long at = (data.at ?: now()) as Long
            String txt = ((data.text ?: "") as String).trim()
            List cands = rows.findAll { r ->
                r instanceof Map && (r.type as String) == "command" && (!cmds || (r.name as String) in cmds) &&
                (rowTime(r as Map) == null || Math.abs(rowTime(r as Map) - at) <= 5000L)
            }
            List withText = txt ? cands.findAll { r -> r.descriptionText != null && (r.descriptionText as String).contains(txt.take(30)) } : []
            List pick = withText ?: cands
            Map best = pick ? (pick.max { r -> rowTime(r as Map) ?: 0L } as Map) : null
            if (best?.producedBy) {
                src = plain(best.producedBy as String).replace("&amp;", "&").trim()
                String appId = ((best.producedBy as String) =~ /installedapp\/configure\/(\d+)/).with { it.find() ? it.group(1) : null }
                if (src && appId) { withRuleSystem(appId, src, data); return }
            }
        }
    } catch (e) { logDebug "source lookup parse: ${e}" }
    applySource(data, src ?: "unknown")
}

/** Adds which rules system sent it ("WC – Goodnight"): looks up the sending app's type once, then remembers it. */
private void withRuleSystem(String appId, String name, Map data) {
    Map cache = (state.appSys ?: [:]) as Map
    if (cache.containsKey(appId)) { applySource(data, sysLabel(cache[appId] as String, name)); return }
    Map p = [uri: "http://127.0.0.1:8080/installedapp/statusJson/${appId}".toString(), timeout: 10]
    try { asynchttpGet("srcTypeResp", p, data + [appId: appId, srcName: name]) }
    catch (e) { logDebug "rule system lookup: ${e}"; applySource(data, name) }
}
void srcTypeResp(resp, Map data) {
    String type = null
    try {
        int st = resp.status as int
        if (st == 401 || st == 403) hubReadResult(false)
        if (st == 200) {
            def body = null
            try { body = resp.json } catch (ignored) { }
            hubReadResult(body != null)                                     // null: HTML (a login page?), not JSON
            if (body instanceof Map) type = (body?.installedApp?.name ?: body?.installedApp?.appType?.name) as String      // e.g. "webCoRE Piston", "Rule-5.1"
        }
    } catch (e) { logDebug "rule system parse: ${e}" }
    String name = data.srcName as String
    if (type) {
        Map cache = (state.appSys ?: [:]) as Map
        if (cache.size() > 300) cache = [:]
        cache[data.appId as String] = type
        state.appSys = cache
    }
    applySource(data, type ? sysLabel(type, name) : name)
}
/** "WC – Goodnight" for known rules systems, "<app type> – name" for any other app, just the name if they're the same */
private String sysLabel(String type, String name) {
    List hit = RULE_SYSTEMS.find { (type =~ (it[0] as String)).find() }
    if (hit) return "${hit[1]} – ${name}".toString()
    return (type && type != name) ? "${type} – ${name}".toString() : name
}
private Long rowTime(Map r) {
    try {
        def u = r.unixTime ?: r.epoch
        if (u != null && u.toString().isLong()) return u.toString().toLong()
        if (r.date) return toDateTime(r.date.toString()).time
    } catch (ignored) { }
    return null
}
private void applySource(Map data, String src) {
    logSet(data?.logId as String, [source: src])
    def child = data?.dni ? sbChild(data.dni as String) : null
    childEvent(child, "lastSource", src)
}

// ===================================================================== TEST MODE

/** Test Mode has no switch: only the app's button turns it on (state.tmOn, written through atomicState so other runs see it at once) */
Boolean isTestMode() {
    if (atomicState.tmOn != true) return false
    // safety net: past its end time (missed timer) or no testers left → alerts play normally and it is ended
    Long off = state.tmOffAt as Long
    if ((off != null && now() > off + 60000L) || !tmDevices) { runIn(1, "testModeAutoOff"); return false }
    return true
}

/** minutes from the tmAutoOff enum, never more than 4 hours */
private Integer tmAutoOffMins() { Math.max(1, Math.min(240, intSet("tmAutoOff", 60))) }
private Integer tmRemindMins() { intSet("tmRemind", 60) }

private void turnTestModeOn() {
    if (atomicState.tmOn == true) {
        if (isTestMode()) return
        turnTestModeOff("Test Mode turned off automatically. Announcements, notifications and sirens are back to normal.")
        state.tmErr = "Test Mode had already ended. Tick the box again to turn it on."; return
    }
    if (tmAck != true) { state.tmErr = "Tick the box to confirm you read the warning."; return }
    if (!tmDevices) { state.tmErr = "Pick at least one tester first."; return }
    atomicState.tmOn = true
    Integer mins = tmAutoOffMins()
    state.tmOffAt = now() + mins * 60000L
    runIn(mins * 60, "testModeAutoOff")
    Integer r = tmRemindMins()
    if (r > 0) runIn(r * 60, "testModeReminder")
    testNotice("Test Mode is ON until ${fmtTime(state.tmOffAt as Long)}. " + TM_WARNING)
    queueToTesters()          // waiting announcements go to the testers, never dropped
    clearQueue()
    addLog([id: newLogId(), t: now(), dev: "Test Mode", dni: "", kind: "mode", text: "Test Mode on", outcome: "on", source: ""])
}
/** ends Test Mode (button, timer or hub restart); the acknowledgement must be ticked again next time */
private void turnTestModeOff(String why) {
    boolean was = atomicState.tmOn == true
    atomicState.tmOn = false
    unschedule("testModeAutoOff"); unschedule("testModeReminder")
    state.remove("tmOffAt")
    app.updateSetting("tmAck", [type: "bool", value: false])
    if (was) {
        testNotice(why)
        addLog([id: newLogId(), t: now(), dev: "Test Mode", dni: "", kind: "mode", text: "Test Mode off", outcome: "off", source: ""])
    }
}

/** Test Mode routing: the alert is pushed to the testers; the same text within 2 s is sent once. */
private void routeToTesters(String body, String source) {
    long t = now()
    String key = "tm|${app.id}|${body}".toString()
    Long last = TM_RECENT[key]
    if (last != null && t - last < 2000L) { logDebug "Test Mode: '${body}' already sent to testers"; return }
    TM_RECENT[key] = t
    pushTo((tmDevices ?: []) as List, "TEST – " + (source ? "${source}: " : "") + body, false)
}
private void testNotice(String msg) { pushTo((tmDevices ?: []) as List, msg, false) }

/** Test Mode: announcements still waiting in the queue are pushed to the testers instead of being dropped or played */
private void queueToTesters() {
    List items = []
    [qUrgent(), qNormal()].each { ConcurrentLinkedQueue<Map> q -> new ArrayList<Map>(q).each { Map it -> if (q.remove(it)) items << it } }
    items.each { Map it ->
        if (it.ukey) UKEYS.remove(it.ukey as String)
        routeToTesters((it.label ?: it.text ?: "🔊 sound") as String, (it.source ?: "") as String)
        logSet(it.logId as String, [outcome: "test → testers"])
        if (it.dni) refreshStatus(it.dni as String)
    }
}

void testModeAutoOff() {
    if (atomicState.tmOn == true) { logInfo "Test Mode auto-off"; turnTestModeOff("Test Mode turned off automatically. Announcements, notifications and sirens are back to normal.") }
}
void testModeReminder() {
    if (!isTestMode()) return
    testNotice("Reminder: Test Mode is still ON until ${fmtTime(state.tmOffAt as Long)}. " + TM_WARNING)
    Integer r = tmRemindMins()
    if (r > 0) runIn(r * 60, "testModeReminder")
}

// ===================================================================== GUEST MODES (as many as you like)

List<String> guestIds() {
    Map g = (state.guests ?: [:]) as Map
    return liveKeys(g).sort { a, b -> guestName(a as String).toLowerCase() <=> guestName(b as String).toLowerCase() } as List
}
/** Guest mode or other mode (only the name and some wording differ) */
private boolean gmGuest(String gid) { settings["gm_${gid}_type"] == "guest" }
/** the name as typed, without the "Guest Mode," or " - SB" Switchboard adds itself */
private String gmBase(String gid) {
    String raw = cleanName(settings["gm_${gid}_name"] as String) ?: ((state.guests ?: [:])[gid]?.name ?: "") as String
    String n = raw.replaceAll(/(?i)\s*-\s*SB\s*$/, "").trim()
    if (gmGuest(gid)) n = n.replaceAll(/(?i)^guest\s*mode\b\s*[,:\-]?\s*/, "").trim()
    return n ?: raw
}
/** "Guest Mode, Lily" or "Yard Work" */
String guestName(String gid) {
    String b = gmBase(gid)
    return b ? (gmGuest(gid) ? "Guest Mode, ${b}" : b) : "(deleted mode)"
}
/** its own switch: "Guest Mode, Lily - SB" or "Yard Work - SB" */
private String gmSwitchLabel(String gid) { "${guestName(gid)} - SB".toString() }
Map guestOptions() { guestIds().collectEntries { [(it): guestName(it)] } }
private String gmDni(String gid) { "SB-${app.id}-gm-${gid}".toString() }
private String gmSrc(String gid) { (settings["gm_${gid}_source"] ?: "create") as String }

/** the switch behind a Guest Mode (null for hub modes) */
def gmDevice(String gid) {
    String s = gmSrc(gid)
    return s == "create" ? sbChild(gmDni(gid)) : (s == "existing" ? settings["gm_${gid}_switch"] : null)
}
Boolean isGuestOn(String gid) {
    if (!(state.guests ?: [:]).containsKey(gid) || isDraft("gm", gid)) return false
    if (gmSrc(gid) == "modes") {
        List m = (settings["gm_${gid}_modes"] ?: []) as List
        return m ? (location.mode in m) : false
    }
    return gmDevice(gid)?.currentValue("switch") == "on"
}
List<String> guestsOn() { guestIds().findAll { isGuestOn(it) } }
Boolean isGuestMode() { guestsOn().size() > 0 }

/**
 * Guest Mode rule for a profile / notifier / siren (prefix "prof_p1" etc.).
 * Any guestPlay mode on → deliver (Play always wins); else any guestQuiet mode on → skip; else follow guestOther.
 * With no Guest Modes at all it always delivers (a hidden "silent" left over must not mute the device).
 * Returns the log outcome when skipped, or null.
 */
private String guestSkip(String prefix) {
    Map g = ((state.guests ?: [:]) as Map).findAll { k, v -> !(v instanceof Map && v.draft == true) }
    if (!g) return null
    List play  = ((settings["${prefix}_guestPlay"] ?: []) as List).findAll { g.containsKey(it) }
    List quiet = ((settings["${prefix}_guestQuiet"] ?: []) as List).findAll { g.containsKey(it) && !play.contains(it) }
    if (play.any { isGuestOn(it as String) }) return null
    String q = quiet.find { isGuestOn(it as String) } as String
    if (q) return "skipped – ${guestName(q)}"
    return settings["${prefix}_guestOther"] == "silent" ? "skipped – no mode says play" : null
}
/** the Guest Mode pickers for a profile / notifier / siren (Play / Quiet / the rest of the time) */
private void guestInputs(String prefix, boolean urgent) {
    Map opts = guestOptions()
    if (!opts) { paragraph note("Modes: none yet (add them on the Modes page)."); return }
    List play = ((settings["${prefix}_guestPlay"] ?: []) as List)
    input "${prefix}_guestPlay", "enum", title: "Play while any of these modes are on" +
          tip("Play always wins: if one of these is on, it plays even when a 'Stay silent' mode is on too."),
          options: opts, multiple: true, required: false, submitOnChange: true, width: 6
    input "${prefix}_guestQuiet", "enum", title: "Stay silent while any of these are on" +
          tip(urgent ? "Safety alerts should keep playing in every mode." : "For example bedroom doors while guests sleep there."),
          options: opts.findAll { k, v -> !play.contains(k) }, multiple: true, required: false, width: 6
    input "${prefix}_guestOther", "enum", title: "The rest of the time" +
          tip("Silent = only plays while a mode above says Play, e.g. a 'Housekeeper is here' notifier."),
          options: [play: "Play", silent: "Silent (only plays while a mode above says Play)"], defaultValue: "play", required: false, width: 6
    if (urgent && (settings["${prefix}_guestQuiet"] || settings["${prefix}_guestOther"] == "silent")) paragraph warn("Safety alerts should keep playing in every mode.")
}

private void deleteGuest(String gid) {
    Map g = (state.guests ?: [:]) as Map
    if (!g.containsKey(gid)) return
    // switch first: if the hub refuses (still used by a rule), keep the Guest Mode so it can be deleted again later
    if (sbChild(gmDni(gid))) {
        try { sbDeleteChild(gmDni(gid)) } catch (e) { state.gmErr = "Couldn't delete its switch. If a rule uses it, remove it there first. (${e.message})"; return }
    }
    g.remove(gid); state.guests = g
    Map off = (state.gmOffAt ?: [:]) as Map; off.remove(gid); state.gmOffAt = off
    Map was = (state.gmWasOn ?: [:]) as Map; was.remove(gid); state.gmWasOn = was
    Map rn = (state.gmRemindNext ?: [:]) as Map; rn.remove(gid); state.gmRemindNext = rn
    removeItemSettings("gm", gid)
    // take it out of every profile / notifier / siren
    settings.keySet().findAll { it.endsWith("_guestQuiet") || it.endsWith("_guestPlay") }.toList().each { String k ->
        List v = ((settings[k] ?: []) as List).findAll { it != gid }
        if (v) app.updateSetting(k, [type: "enum", value: v]) else app.removeSetting(k)
    }
    scheduleGuestTimers()
}
private void syncGuests() {
    Map g = (state.guests ?: [:]) as Map
    g.each { gid, v -> String n = cleanName(settings["gm_${gid}_name"] as String); if (n) v.name = n }
    state.guests = g
}

/** true once Switchboard has made (or seen) this Guest Mode's switch; a made switch that's gone is "missing" */
private boolean gmMade(String gid) { (((state.guests ?: [:]) as Map)[gid] as Map)?.made == true }
private void setGmMade(String gid, boolean on) {
    Map g = (state.guests ?: [:]) as Map
    if (!(g[gid] instanceof Map) || ((g[gid].made == true) == on)) return
    if (on) g[gid].made = true else g[gid].remove("made")
    state.guests = g
}

/** Switchboard's own mode virtual switches (normal, non-component): "Guest Mode, Lily - SB" / "Yard Work - SB".
 *  Makes the switch only the first time (Save, Done); once made, a deleted switch is "missing" until Recreate (force). */
private void ensureGuestSwitch(String gid, boolean create = true, boolean force = false) {
    def d = sbChild(gmDni(gid))
    String label = gmSwitchLabel(gid)
    if (gmSrc(gid) == "create") {
        if (!d) {
            if (!create || (gmMade(gid) && !force)) return          // missing: Recreate or Delete on its page
            try { d = sbAddChild(SWITCH_DRIVER, gmDni(gid), label) }
            catch (e) { log.error "${APP_TITLE}: couldn't create the '${label}' switch: ${e}"; state.gmErr = "Couldn't create the switch \"${label}\". (${e.message})" }
        } else if (d.label != label) {
            try { sbRelabel(d, label) } catch (e) { logDebug "relabel ${label}: ${e}" }
        }
        if (d) setGmMade(gid, true)
    } else {
        if (d) {
            try { sbDeleteChild(gmDni(gid)); d = null } catch (e) { log.warn "${APP_TITLE}: couldn't delete the unused '${label}' switch: ${e}" }
        }
        if (!d) setGmMade(gid, false)
    }
}
private void ensureGuestSwitches() {
    syncGuests()
    guestIds().each { ensureGuestSwitch(it as String) }     // never made → make it; made and deleted → missing
}

/** subscribe and re-arm every Guest Mode (initialize) */
private void initGuests() {
    boolean hubModes = false
    guestIds().each { String gid -> if (initGuest(gid)) hubModes = true }
    if (hubModes) subscribe(location, "mode", "guestHubModeHandler")
    scheduleGuestTimers()
}
/** returns true when this mode follows hub modes (needs the location subscription) */
private boolean initGuest(String gid) {
    boolean hub = gmSrc(gid) == "modes"
    if (gmSrc(gid) == "existing") { def d = gmDevice(gid); if (d) subscribe(d, "switch", "guestSwitchHandler") }   // created switches report through sbSwitch
    boolean on = isGuestOn(gid)
    Map was = (state.gmWasOn ?: [:]) as Map; was[gid] = on; state.gmWasOn = was
    Map off = (state.gmOffAt ?: [:]) as Map
    if (on && !hub) {
        if (!off[gid]) { Long end = defaultGuestEnd(gid); if (end) off[gid] = end }
    } else off.remove(gid)
    state.gmOffAt = off
    return hub
}
private Long defaultGuestEnd(String gid) {
    Integer h = intSet("gm_${gid}_defaultHours", 24)
    return h > 0 ? now() + h * 3600000L : null
}

void guestSwitchHandler(evt) {
    String devId = evt.device?.id?.toString() ?: evt.deviceId?.toString()
    guestIds().findAll { gmSrc(it) != "modes" && gmDevice(it)?.id?.toString() == devId }.each { guestChanged(it, evt.value == "on") }
}
void guestHubModeHandler(evt) {
    guestIds().findAll { gmSrc(it) == "modes" }.each { guestChanged(it, isGuestOn(it)) }
}
private void guestChanged(String gid, boolean on) {
    Map was = (state.gmWasOn ?: [:]) as Map
    if (was.containsKey(gid) && (was[gid] as Boolean) == on) return
    was[gid] = on; state.gmWasOn = was
    Map off = (state.gmOffAt ?: [:]) as Map
    String n = guestName(gid)
    if (on) {
        if (gmSrc(gid) != "modes" && (!off[gid] || (off[gid] as Long) <= now())) {
            Long end = defaultGuestEnd(gid)
            if (end) off[gid] = end else off.remove(gid)
        }
        state.gmOffAt = off
        guestNotice(gid, "${n} is ON" + (off[gid] ? " until ${fmtTime(off[gid] as Long)}." : "."))
    } else {
        off.remove(gid); state.gmOffAt = off
        guestNotice(gid, "${n} is OFF.")
    }
    addLog([id: newLogId(), t: now(), dev: n, dni: "", kind: "mode", text: "${n} ${on ? 'on' : 'off'}", outcome: on ? "on" : "off", source: ""])
    scheduleGuestTimers()
}

/** "On" button: until the picked end if it's in the future (untilPicked) or for the default hours */
private void guestOn(String gid, boolean untilPicked) {
    def d = gmDevice(gid)
    if (!d) { state.gmErr = "${guestName(gid)} has no switch yet. Press Done on the main page, then try again."; return }
    Long end = null
    if (untilPicked) {
        end = pickedGuestEnd(gid)
        if (!end || end <= now()) { state.gmErr = "Pick an end date and time in the future."; return }
    } else end = defaultGuestEnd(gid)
    Map off = (state.gmOffAt ?: [:]) as Map
    if (end) off[gid] = end else off.remove(gid)
    state.gmOffAt = off
    scheduleGuestTimers()          // arm the auto-off now, even if its switch isn't subscribed yet (picked before Done)
    if (d.currentValue("switch") == "on") {
        guestNotice(gid, "${guestName(gid)} now " + (end ? "ends ${fmtTime(end)}." : "stays on until turned off."))
    } else d.on()
}
private void guestOff(String gid) { gmDevice(gid)?.off() }

private Long pickedGuestEnd(String gid) {
    def dt = settings["gm_${gid}_endDate"], tm = settings["gm_${gid}_endTime"]
    if (!dt || !tm) return null
    try {
        String hm = toDateTime(tm as String).format("HH:mm", location.timeZone)
        def f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
        f.setTimeZone(location.timeZone)
        f.setLenient(false)
        return f.parse((dt as String).take(10) + " " + hm).time
    } catch (e) {
        log.warn "${APP_TITLE}: couldn't read the end date/time of ${guestName(gid)}: ${e}"
        return null
    }
}

/** one timer for every Guest Mode's auto-off and daily reminder (runIn/runOnce replace same-handler runs) */
private void scheduleGuestTimers() {
    unschedule("guestAutoOffCheck"); unschedule("guestReminderCheck")
    Map off = (state.gmOffAt ?: [:]) as Map
    Long nextOff = off.values().collect { it as Long }.min()
    if (nextOff) runOnce(new Date(Math.max(nextOff, now() + 1000L)), "guestAutoOffCheck")
    Map rn = [:]
    guestIds().each { String gid ->
        def rt = settings["gm_${gid}_remindTime"]
        if (rt && settings["gm_${gid}_remindDevs"] && isGuestOn(gid)) {
            try {
                Date t = timeToday(toDateTime(rt as String).format("HH:mm", location.timeZone), location.timeZone)
                long at = t.time
                if (at <= now() + 30000L) at += 86400000L
                rn[gid] = at
            } catch (e) { logDebug "reminder time for ${guestName(gid)}: ${e}" }
        }
    }
    state.gmRemindNext = rn
    Long nextR = rn.values().collect { it as Long }.min()
    if (nextR) runOnce(new Date(nextR), "guestReminderCheck")
}
void guestAutoOffCheck() {
    Map off = (state.gmOffAt ?: [:]) as Map
    long t = now() + 1000L
    off.findAll { k, v -> (v as Long) <= t }.keySet().toList().each { String gid ->
        Map o = (state.gmOffAt ?: [:]) as Map
        if (isGuestOn(gid)) {
            logInfo "${guestName(gid)} auto-off"; guestOff(gid)
            o[gid] = now() + 300000L       // its switch event clears this; if the switch doesn't respond, try again in 5 min (not every second)
        } else o.remove(gid)
        state.gmOffAt = o
    }
    scheduleGuestTimers()
}
void guestReminderCheck() {
    Map rn = (state.gmRemindNext ?: [:]) as Map
    long t = now() + 60000L
    rn.each { gid, at ->
        if ((at as Long) <= t && isGuestOn(gid as String)) {
            Long end = ((state.gmOffAt ?: [:]) as Map)[gid] as Long
            guestNotice(gid as String, "Reminder: ${guestName(gid as String)} is ON" + (end ? " until ${fmtTime(end)}." : "."))
        }
    }
    scheduleGuestTimers()
}
private void guestNotice(String gid, String msg) {
    List devs = (settings["gm_${gid}_remindDevs"] ?: []) as List
    if (devs) pushTo(devs, msg, false)
}

// ===================================================================== SPEECH ENGINE

/** every real speaker used by a profile */
/** real devices only: Switchboard's own virtual devices show up in the pickers, and sending to one would loop */
List realDevs(def list) { ((list ?: []) as List).findAll { d -> d && !(d.deviceNetworkId ?: "").startsWith("SB-${app.id}-") } }
List allSpeakers() {
    List devs = []
    ids("prof").each { pid -> devs += realDevs(settings["prof_${pid}_spk"]) }
    return devs.unique { it.id }
}
Map speakerOptions() { allSpeakers().collectEntries { [(it.id.toString()): it.displayName] }.sort { it.value } }

private ConcurrentLinkedQueue<Map> qFor(ConcurrentHashMap<Long, ConcurrentLinkedQueue<Map>> m) {
    ConcurrentLinkedQueue<Map> q = m.get(app.id)
    if (q == null) { m.putIfAbsent(app.id, new ConcurrentLinkedQueue<Map>()); q = m.get(app.id) }
    return q
}
private ConcurrentLinkedQueue<Map> qNormal() { qFor(Q_NORMAL) }
private ConcurrentLinkedQueue<Map> qUrgent() { qFor(Q_URGENT) }
private ConcurrentHashMap<String, Map> mapFor(ConcurrentHashMap<Long, ConcurrentHashMap<String, Map>> m) {
    ConcurrentHashMap<String, Map> x = m.get(app.id)
    if (x == null) { m.putIfAbsent(app.id, new ConcurrentHashMap<String, Map>()); x = m.get(app.id) }
    return x
}
private Semaphore lockFor() {
    Semaphore s = LOCKS.get(app.id)
    if (s == null) { LOCKS.putIfAbsent(app.id, new Semaphore(1)); s = LOCKS.get(app.id) }
    return s
}

/**
 * Add an item to the queue (does not start it; call processQueue()). Returns the log outcome.
 *   dni, profile, text, sound (sound id), uri + secs (playTrack), callerVolume, source, label
 *   speakers: speaker ids (tests without a profile)
 * On the speakers: profile chime (before text) → sound/track → text.
 */
private String enqueue(Map req, String logId) {
    String text = req.text ? req.text as String : null
    String sid = req.sound ? req.sound as String : null
    if (sid && !soundUrl(sid)) {
        log.warn "${APP_TITLE}: sound '${sid}' is missing or has no URL" + (text ? "; speaking the text only" : "")
        sid = null
    }
    if (!text && !sid && !req.uri) return "failed – nothing to play"
    List<Map> parts = []
    String chime = req.profile ? settings["prof_${req.profile}_chime"] as String : null
    if (text && chime && soundUrl(chime)) parts << [sound: chime]
    if (sid) parts << [sound: sid]
    if (req.uri) parts << [url: req.uri as String, secs: (req.secs ?: intSet("defaultTrackSecs", 10)) as BigDecimal, name: (req.label ?: req.uri) as String]
    if (text) parts << [text: text]
    Map item = [dni: req.dni, profile: req.profile, speakers: req.speakers ?: [], text: text, sound: sid, uri: req.uri,
                parts: parts, step: 0, callerVolume: req.callerVolume, source: req.source, logId: logId,
                label: req.label ?: text ?: (sid ? "🔊 ${soundName(sid)}" : req.uri), queuedAt: now(), played: false]
    boolean urgent = req.profile ? settings["prof_${req.profile}_priority"] == "urgent" : false
    item.urgent = urgent
    if (!urgent && req.profile) item.staleSec = intSet("prof_${req.profile}_staleSec", 30)
    ConcurrentLinkedQueue<Map> q = urgent ? qUrgent() : qNormal()
    if (urgent) {
        item.ukey = "${app.id}|${item.dni}|${item.label}".toString()
        if (!UKEYS.add(item.ukey as String)) return "skipped duplicate"
    }
    Integer max = intSet("maxQueue", 20)
    if (!urgent && q.size() >= max) { log.warn "${APP_TITLE}: queue full (${max}); dropped: ${item.label}"; return "failed – queue full" }
    q.add(item)
    logDebug "Queued ${urgent ? 'URGENT' : 'normal'} (${q.size()}): ${item.label} [${item.source ?: ''}]"
    return "queued"
}

private boolean itemDone(Map item) { ((item.step ?: 0) as Integer) >= ((item.parts ?: []) as List).size() }
private void itemFinished(Map item, String outcome = null) {
    logSet(item.logId as String, [outcome: outcome ?: (item.played ? "played" : "failed")])
}

private void clearQueue() {
    Set<String> dnis = [] as Set
    [qUrgent(), qNormal()].each { q ->
        new ArrayList<Map>(q).each { Map it ->
            if (it.ukey) UKEYS.remove(it.ukey as String)
            if (it.dni) dnis << (it.dni as String)
            logSet(it.logId as String, [outcome: "skipped – queue cleared"])
        }
        q.clear()
    }
    mapFor(BUSY).values().each { if (it.dni) dnis << (it.dni as String) }
    mapFor(BUSY).clear()
    unschedule("processQueue")
    restoreAll()
    dnis.each { refreshStatus(it) }
}

/** Plays whatever can play now, then schedules itself for when the next item can. Thread-safe. */
void processQueue() {
    if ((!qUrgent().isEmpty() || !qNormal().isEmpty()) && isTestMode()) queueToTesters()   // nothing plays in the home
    Long id = app.id
    Semaphore lock = lockFor()
    RERUN.put(id, true)                      // flag first, so a busy runner always sees our request
    while (RERUN.containsKey(id) && lock.tryAcquire()) {
        try {
            int guard = 0
            while (RERUN.remove(id) != null) {
                if (++guard > 10) { runInMillis(50, "processQueue"); break }
                queuePass()
            }
        } catch (e) {
            log.error "${APP_TITLE}: speech queue error: ${e}"
            runInMillis(1000, "processQueue")
        } finally {
            lock.release()
        }
    }
}

private void queuePass() {
    long t = now()
    boolean global = (queueMode ?: "global") == "global"
    ConcurrentHashMap<String, Map> busy = mapFor(BUSY)
    Set<String> blocked = [] as Set
    Set<String> touched = [] as Set

    // ---- urgent: ignore normal announcements (interrupt), but never interrupt another urgent one
    new ArrayList<Map>(qUrgent()).each { Map item ->
        List devs = resolveSpeakers(item)
        if (!devs) {
            qUrgent().remove(item); if (item.ukey) UKEYS.remove(item.ukey as String)
            log.warn "${APP_TITLE}: no speakers for: ${item.label}"; itemFinished(item, "failed – no speakers")
            if (item.dni) touched << (item.dni as String); return
        }
        List<String> res = global ? ["ALL"] : devs*.id*.toString()
        boolean wait = res.any { r -> blocked.contains(r) || (busy[r]?.urgent && (busy[r].until as Long) > t) }
        if (wait) { blocked.addAll(res); return }
        long end = playItem(item, devs, true)
        if (itemDone(item)) {
            qUrgent().remove(item)
            if (item.ukey) UKEYS.remove(item.ukey as String)
            itemFinished(item)
        }
        markBusy(busy, res, devs, end, true, item.dni as String, true)
        if (item.dni) touched << (item.dni as String)
        blocked.addAll(res)
        t = now()
    }

    // ---- normal: wait for anything playing on the same resource, keep order, drop stale ones
    new ArrayList<Map>(qNormal()).each { Map item ->
        Integer stale = (item.staleSec ?: 0) as Integer
        boolean started = ((item.step ?: 0) as Integer) > 0          // a started message always finishes
        if (!started && stale > 0 && t - (item.queuedAt as Long) > stale * 1000L) {
            qNormal().remove(item)
            logInfo "Skipped (waited over ${stale}s): ${item.label}" + (item.source ? " [${item.source}]" : "")
            itemFinished(item, "skipped stale")
            if (item.dni) touched << (item.dni as String)
            return
        }
        List devs = resolveSpeakers(item)
        if (!devs) {
            qNormal().remove(item); log.warn "${APP_TITLE}: no speakers for: ${item.label}"
            itemFinished(item, "failed – no speakers"); if (item.dni) touched << (item.dni as String); return
        }
        List<String> res = global ? ["ALL"] : devs*.id*.toString()
        boolean wait = res.any { r -> blocked.contains(r) || (busy[r] && (busy[r].until as Long) > t) }
        if (wait) { blocked.addAll(res); return }
        long end = playItem(item, devs, false)
        if (itemDone(item)) { qNormal().remove(item); itemFinished(item) }
        markBusy(busy, res, devs, end, false, item.dni as String, (item.sound || item.uri) as boolean)
        if (item.dni) touched << (item.dni as String)
        blocked.addAll(res)
        t = now()
    }

    // ---- forget finished entries, restore volumes on speakers that are done
    new ArrayList<String>(busy.keySet()).each { k ->
        Map b = busy[k]
        if (b && (b.until as Long) <= t) { busy.remove(k); if (b.dni) touched << (b.dni as String) }
    }
    restoreDue(busy)
    touched.each { refreshStatus(it) }

    // ---- wake up when the next thing can happen (also to mark finished devices idle)
    boolean waiting = !qUrgent().isEmpty() || !qNormal().isEmpty() || !mapFor(RESTORE).isEmpty() || !busy.isEmpty()
    if (waiting) {
        Long next = busy.values().collect { it.until as Long }.findAll { it > t }.min()
        long delay = next ? Math.max(50L, next - now()) : 1000L
        runInMillis(delay, "processQueue")
    }
}

/** Mark resources busy until 'end'. In global mode speakers are also marked, so volume restore and stop() know which ones are in use. */
private void markBusy(ConcurrentHashMap<String, Map> busy, List<String> res, List devs, long end, boolean urgent, String dni, boolean alarm) {
    Map v = [until: end, urgent: urgent, dni: dni, alarm: alarm]       // alarm: urgent or a sound / track item (Stop all stops these)
    res.each { busy[it] = v }
    if (res == ["ALL"]) devs.each { busy[it.id.toString()] = v }
}

private List resolveSpeakers(Map item) {
    Map byId = allSpeakers().collectEntries { [(it.id.toString()): it] }
    if (item.profile) return realDevs(settings["prof_${item.profile}_spk"]).unique { it.id }
    return (item.speakers ?: []).collect { byId[it.toString()] }.findAll { it }.unique { it.id }
}

/** status / queueLength attributes of a Switchboard speaker device */
private void refreshStatus(String dni) {
    if (!dni) return
    def child = sbChild(dni)
    if (!child) return
    long t = now()
    int n = (new ArrayList<Map>(qUrgent()) + new ArrayList<Map>(qNormal())).count { it.dni == dni } as int
    boolean playing = mapFor(BUSY).values().any { it.dni == dni && (it.until as Long) > t }
    childEvent(child, "status", playing ? "playing" : (n ? "queued" : "idle"))
    childEvent(child, "queueLength", n)
}

/** Volume for an item: profile day/night, fixed, or what the rule asked for (null = don't change volume) */
private Integer itemVolume(Map item) {
    String pid = item.profile as String
    if (!pid) return null
    String vm = settings["prof_${pid}_volMode"] ?: "daynight"
    Integer v
    if (vm == "fixed") v = intSet("prof_${pid}_fixedVol", 60)
    else if (vm == "caller") {
        v = item.callerVolume != null ? item.callerVolume as Integer : null
        if (v == null && item.dni) v = toInt(sbChild(item.dni as String)?.currentValue("volume"))
    } else v = isNight() ? intSet("prof_${pid}_nightVol", 40) : intSet("prof_${pid}_dayVol", 70)
    return clampVol(v)
}
/** Volume for an item's chime / sound / track parts: the profile's own sound volume, or the same as the words.
 *  Not when the words leave the volume alone (rule sent none): the words would then play at the sound's volume. */
private Integer soundVolume(Map item, Integer wordsVol) {
    String pid = item.profile as String
    String sm = pid ? (settings["prof_${pid}_sndVolMode"] ?: "same") : "same"
    if (wordsVol == null && ((item.parts ?: []) as List).any { it.text }) return null
    if (sm == "fixed") return clampVol(intSet("prof_${pid}_sndFixedVol", 60))
    if (sm == "daynight") return clampVol(isNight() ? intSet("prof_${pid}_sndNightVol", 40) : intSet("prof_${pid}_sndDayVol", 70))
    return wordsVol
}

/**
 * Plays the item's next part (chime, sound, track or text) on all devices and moves it on one step.
 * Parts no speaker can play are skipped. Returns the time (epoch ms) until which the speakers are busy.
 */
private long playItem(Map item, List devs, boolean urgent) {
    List parts = (item.parts ?: []) as List
    Integer vol = itemVolume(item)
    if (((item.step ?: 0) as Integer) == 0 && item.dni) {
        def child = sbChild(item.dni as String)
        childEvent(child, "status", "playing")
    }
    while (((item.step ?: 0) as Integer) < parts.size()) {
        Map part = parts[(item.step ?: 0) as Integer] as Map
        item.step = ((item.step ?: 0) as Integer) + 1
        long end = 0L
        if (part.sound) end = playTrackOn(item, soundUrl(part.sound as String), soundSecs(part.sound as String), "🔊 ${soundName(part.sound as String)}", devs, soundVolume(item, vol))
        else if (part.url) end = playTrackOn(item, part.url as String, (part.secs ?: 10) as BigDecimal, part.name as String, devs, soundVolume(item, vol))
        else end = speakText(item, part.text as String, devs, vol, urgent)
        if (end > 0) return end
    }
    return now()
}

/**
 * Play a sound file: playTrackAndRestore(url, vol) where the driver has it (unless the speaker is set to play sounds
 * without restore), otherwise setVolume + playTrack(url) (the volume is restored once the speaker is done). Returns 0 if nothing played.
 */
private long playTrackOn(Map item, String url, BigDecimal secs, String name, List devs, Integer vol) {
    if (!url) return 0L
    long sentAt = now()
    List played = [], later = []
    devs.each { dev ->
        try {
            if (dev.hasCommand("playTrackAndRestore") && (settings["spkPlain_${dev.id}"] != true || !dev.hasCommand("playTrack"))) {
                // words follow at once: record the volume before the sound, so theirs can't pick up the sound's
                if (vol != null && ((item.step ?: 0) as Integer) < ((item.parts ?: []) as List).size()) rememberVolume(dev)
                if (vol != null) dev.playTrackAndRestore(url, vol)
                else dev.playTrackAndRestore(url)
                played << dev
            } else if (dev.hasCommand("playTrack")) {
                if (vol != null && dev.hasCommand("setVolume")) {
                    rememberVolume(dev)
                    dev.setVolume(vol)
                    later << dev
                } else {
                    dev.playTrack(url)
                    played << dev
                }
            } else {
                log.warn "${APP_TITLE}: ${dev.displayName} can't play sound files (no playTrack); skipped ${name}"
            }
        } catch (e) { log.error "${APP_TITLE}: playing ${name} failed on ${dev.displayName}: ${e}" }
    }
    if (later) {
        Integer d = later.collect { intSet("spkSetDelay_${it.id}", 700) }.max()
        if (d > 0) pauseExecution(Math.min(d, 3000))
        later.each { dev ->
            try { dev.playTrack(url); played << dev } catch (e) { log.error "${APP_TITLE}: playing ${name} failed on ${dev.displayName}: ${e}" }
        }
    }
    if (!played) return 0L
    item.played = true
    long startedAt = now()
    BigDecimal assumed = (secs ?: 5) as BigDecimal
    BigDecimal lead = played.collect { (settings["spkLead_${it.id}"] ?: 0) as BigDecimal }.max() ?: 0
    // the pause between messages goes after the last part only: a chime runs straight into its words
    boolean last = ((item.step ?: 0) as Integer) >= ((item.parts ?: []) as List).size()
    BigDecimal extra = lead + (last ? ((gapSec != null ? gapSec : 2) as BigDecimal) : 0)
    BigDecimal wait = assumed + extra
    long end = startedAt + ((wait * 1000) as long)
    logInfo "Playing ${name} (${wait}s) on ${played*.displayName.join(', ')} @${vol != null ? vol : '-'}" + (item.source ? " [${item.source}]" : "")
    // ask the speakers how long the track really is once it has started (Sonos reports trackDuration)
    runInMillis(TRACK_LEN_WAIT, "trackLengthCheck", [overwrite: false, data: [ids: played.collect { it.id.toString() }, sent: sentAt, start: startedAt,
                 end: end, assumed: assumed, extraMs: ((extra * 1000) as long), dni: (item.dni ?: "") as String, url: url, name: name]])
    return end
}

@Field static final long TRACK_LEN_WAIT = 2000L

/**
 * ~2 s after a track or sound file starts: if the speakers report its length (trackDuration) and it differs from what
 * was assumed, move that item's busy end (and so the volume restore) to start + reported length + lead + gap, then
 * let the queue reschedule its wake-up. Only BUSY entries still carrying this part's original end are changed
 * (a later part or item, a stop or Stop all is never undone).
 */
void trackLengthCheck(Map data) {
    try {
        long start = (data?.start ?: 0L) as long, oldEnd = (data?.end ?: 0L) as long, sent = (data?.sent ?: start) as long
        BigDecimal assumed = (data?.assumed ?: 0) as BigDecimal
        String dni = (data?.dni ?: "") as String
        Map byId = allSpeakers().collectEntries { [(it.id.toString()): it] }
        ConcurrentHashMap<String, Map> busy0 = mapFor(BUSY)
        List<Integer> lens = []
        ((data?.ids ?: []) as List).each { id ->
            def dev = byId[id as String]
            if (!dev) return
            // only speakers still busy with this part: one taken over by another item (an urgent one) reports that item's length
            Map b = busy0[id as String]
            if (!b || ((b.until ?: 0L) as long) != oldEnd || ((b.dni ?: "") as String) != dni) return
            Integer s = reportedTrackSecs(dev, sent, data.url as String)
            if (s) lens << s
        }
        if (!lens) { logDebug "No track length reported for ${data?.name}; keeping ${assumed}s"; return }
        Integer real = lens.max()
        if ((real - assumed).abs() <= 1) return            // speakers report whole seconds: keep a set 1.5 s
        long newEnd = start + real * 1000L + ((data.extraMs ?: 0L) as long)
        int n = 0
        Semaphore lock = lockFor()
        boolean got = false
        try { got = lock.tryAcquire(2000L, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (ignored) { }
        try {
            mapFor(BUSY).values().each { Map v ->
                if (((v.until ?: 0L) as long) == oldEnd && ((v.dni ?: "") as String) == dni) { v.until = newEnd; n++ }
            }
        } finally { if (got) lock.release() }
        if (!n) return                                     // that part already finished or was stopped
        logInfo "${data.name}: the speaker reports ${real}s (assumed ${assumed}s); the queue and volume restore follow it"
        processQueue()                                     // reschedules its wake-up for the new end
    } catch (e) { logDebug "track length check: ${e}" }
}

/** seconds the speaker reports for the track it plays, or null (unknown / blank / 0 / left over from an earlier track) */
private Integer reportedTrackSecs(dev, long sent, String url) {
    try {
        def st = null
        try { st = dev.currentState("trackDuration", true) } catch (ignored) { st = dev.currentState("trackDuration") }
        if (st == null || st.value == null) return null
        // when the driver says which track it is playing, it must be ours (not music resumed after a short clip)
        String playing = null
        ["trackUri", "currentTrackUri"].each { String a ->
            if (playing == null) { try { def x = dev.currentValue(a); if (x) playing = x.toString() } catch (ignored) { } }
        }
        if (playing == null) {
            try { def td = dev.currentValue("trackData"); if (td) { def j = parseJson(td.toString()); if (j instanceof Map && j.uri) playing = j.uri.toString() } } catch (ignored) { }
        }
        String file = (url ?: "").replaceAll(/[?#].*$/, "").tokenize("/").with { it ? it.last() : "" }
        String fileDec = file
        try { fileDec = URLDecoder.decode(file, "UTF-8") } catch (ignored) { }
        boolean ours = playing && file && (playing.contains(file) || playing.contains(fileDec))
        if (playing && file && !ours) return null
        // not updated since we sent the track: an earlier track's (unless the speaker confirms it plays our file —
        // the same file again sends no new event because the value didn't change)
        if (!ours && st.date && st.date.time < sent - 500L) return null
        Integer s = parseTrackSecs(st.value.toString())
        return (s && s <= 3600) ? s : null                                  // longer: a bogus value (e.g. ms), keep the assumed length
    } catch (e) { logDebug "trackDuration on ${dev?.displayName}: ${e}"; return null }
}

/** "0:04:13", "4:13" or "253" (seconds) → 253; null for unknown / blank / 0 */
private Integer parseTrackSecs(String v) {
    String s = (v ?: "").trim()
    if (!s || !(s ==~ /\d+(\.\d+)?(:\d{1,2}(\.\d+)?){0,2}/)) return null
    BigDecimal total = 0
    s.tokenize(":").each { String p -> total = total * 60 + (p as BigDecimal) }
    int r = (int) Math.ceil(total.doubleValue())
    return r > 0 ? r : null
}

/** Speak text on all devices; returns the time (epoch ms) until which they are busy (0 if none spoke). */
private long speakText(Map item, String text, List devs, Integer vol, boolean urgent) {
    List later = []                 // speakers that need setVolume first
    int ok = 0
    devs.each { dev ->
        String m = methodFor(dev, vol)
        try {
            if (m == "setThenSpeak") {
                rememberVolume(dev)
                dev.setVolume(vol)
                later << dev
            } else if (speakWith(dev, m, text, vol, urgent)) {
                later << dev                        // speak(text, volume) not supported: volume already set
            } else ok++
        } catch (e) { log.error "${APP_TITLE}: speak failed on ${dev.displayName}: ${e}" }
    }
    if (later) {
        Integer d = later.collect { intSet("spkSetDelay_${it.id}", 700) }.max()
        if (d > 0) pauseExecution(Math.min(d, 3000))
        later.each { dev -> try { dev.speak(text); ok++ } catch (e) { log.error "${APP_TITLE}: speak failed on ${dev.displayName}: ${e}" } }
    }
    if (!ok) return 0L
    item.played = true
    long sentAt = now()
    if (item.queuedAt) logDebug "Sent to speaker(s) ${sentAt - (item.queuedAt as Long)} ms after it was queued"
    BigDecimal secs = messageSeconds(text)
    BigDecimal lead = devs.collect { (settings["spkLead_${it.id}"] ?: 0) as BigDecimal }.max() ?: 0
    BigDecimal wait = secs + lead + ((gapSec != null ? gapSec : 2) as BigDecimal)
    logInfo "${urgent ? 'URGENT ' : ''}Speaking (${wait}s) on ${devs*.displayName.join(', ')} @${vol != null ? vol : '-'}: ${text}" +
            (item.source ? " [${item.source}]" : "")
    return sentAt + ((wait * 1000) as long)
}

String autoMethod(dev) {
    String tn = (dev.typeName ?: "").toLowerCase()
    if (dev.hasCommand("setVolumeSpeakAndRestore")) return "echo"
    if (tn.contains("chromecast") || tn.contains("google") || tn.contains("airplay") || tn.contains("homepod")) return "setThenSpeak"
    return "speakVol"
}
private String methodFor(dev, Integer vol) {
    String m = settings["spkMethod_${dev.id}"] ?: "auto"
    if (m == "auto") m = autoMethod(dev)
    if (vol == null) m = "speakOnly"
    return m
}

/** returns true when the caller still has to call speak(text) after the shared pause */
private boolean speakWith(dev, String m, String text, Integer vol, boolean urgent) {
    if (urgent && vol != null && dev.hasCommand("playHighPriorityTTS")) {      // Sonos Advanced Controller
        try {
            dev.playHighPriorityTTS(text, vol, null)
            return false
        } catch (Exception e) { log.warn "${APP_TITLE}: playHighPriorityTTS failed on ${dev.displayName} (${e}); using the normal method" }
    }
    switch (m) {
        case "echo":            dev.setVolumeSpeakAndRestore(vol, text); break
        case "playTextRestore": dev.playTextAndRestore(text, vol); break
        case "speakOnly":       dev.speak(text); break
        default: // speakVol
            try {
                dev.speak(text, vol)
            } catch (Exception e) {
                logDebug "${dev.displayName}: speak(text, volume) failed (${e}); using setVolume + speak"
                rememberVolume(dev)
                dev.setVolume(vol)
                return true
            }
    }
    return false
}

private void rememberVolume(dev) {
    String id = dev.id.toString()
    if (settings["spkRestore_${id}"] == false) return
    ConcurrentHashMap<String, Map> rs = mapFor(RESTORE)
    if (rs.containsKey(id)) return
    Map lr = LAST_RESTORED.get("${app.id}_${id}".toString())
    def cur = (lr && now() - (lr.at as Long) < 15000L) ? lr.vol : dev.currentValue("volume")
    if (cur != null) {
        try { rs.put(id, [dev: dev, vol: (cur as BigDecimal).intValue()]) } catch (ignored) { }
    }
}

/** Restore volumes on speakers that finished and have nothing else waiting. */
private void restoreDue(ConcurrentHashMap<String, Map> busy) {
    ConcurrentHashMap<String, Map> rs = mapFor(RESTORE)
    if (rs.isEmpty()) return
    Set<String> needed = [] as Set
    (new ArrayList<Map>(qUrgent()) + new ArrayList<Map>(qNormal())).each { needed.addAll(resolveSpeakers(it)*.id*.toString()) }
    new ArrayList<String>(rs.keySet()).each { String id ->
        if (!busy.containsKey(id) && !needed.contains(id)) {
            Map e = rs.remove(id)
            try {
                e?.dev?.setVolume(e.vol as Integer)
                LAST_RESTORED.put("${app.id}_${id}".toString(), [vol: e.vol, at: now()])
            } catch (ex) { log.warn "${APP_TITLE}: restore volume failed: ${ex}" }
        }
    }
}
private void restoreAll() {
    ConcurrentHashMap<String, Map> rs = mapFor(RESTORE)
    new ArrayList<String>(rs.keySet()).each { id ->
        Map e = rs.remove(id)
        try { e?.dev?.setVolume(e.vol as Integer) } catch (ignored) { }
    }
}

private BigDecimal messageSeconds(String text) {
    if ((durMode ?: "measure") == "measure") {
        try {
            Map tts = textToSpeech(text)
            if (tts?.duration != null) return (tts.duration as BigDecimal) + 1     // whole seconds; round up
        } catch (e) { logDebug "textToSpeech failed, estimating: ${e}" }
    }
    return new BigDecimal(Math.ceil(text.length() / 12.0)) + 2
}

// ---- night window (global, Settings)
private String hhmm(def v, String dflt) {
    if (!v) return dflt
    try { return toDateTime(v as String).format("HH:mm", location.timeZone) } catch (ignored) { return dflt }
}
Boolean isNight() { inTimeWindow(hhmm(nightStart, "21:00"), hhmm(nightEnd, "08:00")) }
/** true if now is between two "HH:mm" times; handles windows that cross midnight */
Boolean inTimeWindow(String startHm, String endHm) {
    TimeZone tz = location.timeZone
    Date n  = new Date()
    Date sd = timeToday(startHm, tz)
    Date ed = timeToday(endHm, tz)
    if (sd == ed) return false
    if (sd.before(ed)) return !n.before(sd) && n.before(ed)
    return !n.before(sd) || n.before(ed)
}

// ===================================================================== LIFECYCLE

void installed() {
    KINDS.values().each { state[it.stateKey as String] = state[it.stateKey as String] ?: [:] }
    state.sounds = state.sounds ?: [:]
    state.guests = state.guests ?: [:]
    atomicState.tmOn = false
    initialize()
}

void updated() {
    syncNames()
    syncSounds()
    syncGuests()
    initialize()
}

void initialize() {
    unsubscribe()
    unschedule()
    state.remove("hubCookie"); state.remove("hubCookieAt")      // the hub login is gone (0.5.0)
    cleanDrafts()
    dropOrphanSettings()
    // missing devices are NOT re-created here (see missingDevices); no parent device → its error is on the page
    if (ensureHub()) ensureGuestSwitches() else syncGuests()

    subscribe(location, "systemStart", "hubRestarted")

    // ---- stopping alarms: Safety Monitor disarm / cancel, and the Stop All switch
    if (settings.stopOnDisarm != false) {
        subscribe(location, "hsmStatus", "hsmStopStatusHandler")
        subscribe(location, "hsmAlert", "hsmStopAlertHandler")
    }
    syncStopSwitch()

    // ---- Test Mode: re-arm its timers (it has no switch; only the app turns it on)
    if (isTestMode()) {
        Long off = state.tmOffAt as Long
        long maxOff = now() + tmAutoOffMins() * 60000L
        if (!off || off > maxOff) { off = maxOff; state.tmOffAt = off }
        if (off <= now()) testModeAutoOff()
        else {
            runIn(Math.max(1L, (long) ((off - now()) / 1000)), "testModeAutoOff")
            Integer r = tmRemindMins()
            if (r > 0) runIn(r * 60, "testModeReminder")
        }
    }

    // ---- Guest Modes
    initGuests()

    // ---- sirens still sounding, and sirens still being checked after an off (the check survives a reboot / Done)
    scheduleSirenCheck()
    if (sirenVerifyOn()) sirenVerifySchedule() else atomicState.sirenVerify = [:]
    if (logEnable) runIn(1800, "logsOff")
    if (!qUrgent().isEmpty() || !qNormal().isEmpty() || !mapFor(RESTORE).isEmpty() || !mapFor(BUSY).isEmpty()) runInMillis(500, "processQueue")
    refreshMissing()
}

/** settings of items that no longer exist ("gm_g3_name" with no g3): an edit page can save its fields again right after Delete */
private void dropOrphanSettings() {
    new ArrayList<String>(settings.keySet().collect { it as String }).each { String k ->
        List p = k.split("_", 3) as List
        if (p.size() == 3 && EDITS[p[0]] && (p[1] as String) ==~ /[a-z]\d+/ && !rawMap(p[0] as String).containsKey(p[1])) {
            try { app.removeSetting(k) } catch (e) { logDebug "removeSetting ${k}: ${e}" }
        }
    }
    // empty inputs (a blank time, an empty device list) aren't in settings.keySet(): sweep every input name of each
    // id that was handed out and no longer exists, once per id (ids are never reused)
    List swept = (state.swept ?: []) as List
    int before = swept.size()
    EDITS.each { String kind, Map e ->
        Map m = rawMap(kind)
        int next = ((state["next_${kind}".toString()] ?: 1) as Integer)
        (1..<Math.max(1, next)).each { int n ->
            String id = "${e.idp}${n}".toString(), key = "${kind}_${id}".toString()
            if (!m.containsKey(id) && !swept.contains(key)) { removeItemSettings(kind, id); swept << key }
        }
    }
    if (swept.size() != before) state.swept = swept
}

/** after a hub reboot: Test Mode off, forget busy state from before the restart, re-subscribe, re-arm timers, resume the queue */
void hubRestarted(evt) {
    if (atomicState.tmOn == true) turnTestModeOff("Test Mode turned off: hub restarted")
    mapFor(BUSY).clear()
    // the in-memory queue did not survive the reboot: don't leave its messages showing "queued" forever
    // (LOCKS / RERUN are not touched: they are fresh after a reboot, and replacing the lock could let two runners play at once)
    Set<String> live = (new ArrayList<Map>(qUrgent()) + new ArrayList<Map>(qNormal())).collect { it.logId as String } as Set
    List l = logEntries()
    List lost = l.findAll { it.outcome == "queued" && !live.contains(it.id as String) }
    if (lost) { lost.each { it.outcome = "failed – hub restarted" }; atomicState.log = l }
    initialize()
    ids("prof").each { refreshStatus(itemDni("prof", it)) }
    runInMillis(1000, "processQueue")
}

void uninstalled() {
    [Q_NORMAL, Q_URGENT, BUSY, RESTORE, LOCKS, RERUN].each { it.remove(app.id) }
    String pre = "${app.id}|".toString(), pre2 = "${app.id}_".toString()
    new ArrayList<String>(UKEYS).each { if (it.startsWith(pre)) UKEYS.remove(it) }
    new ArrayList<String>(LAST_RESTORED.keySet()).each { if (it.startsWith(pre2)) LAST_RESTORED.remove(it) }
    new ArrayList<String>(TM_RECENT.keySet()).each { if (it.contains("|${app.id}|")) TM_RECENT.remove(it) }
    def h = hubDev()
    ((h?.sbChildren() ?: []) as List).each { try { h.sbDeleteChild(it.deviceNetworkId as String) } catch (e) { log.warn "${APP_TITLE}: couldn't delete ${it.displayName}: ${e}" } }
    getChildDevices().each { try { deleteChildDevice(it.deviceNetworkId) } catch (e) { log.warn "${APP_TITLE}: couldn't delete ${it.displayName}: ${e}" } }
}

void logsOff() {
    log.warn "${APP_TITLE}: debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}
private void logDebug(String m) { if (logEnable) log.debug "${APP_TITLE}: ${m}" }
private void logInfo(String m)  { if (txtEnable != false) log.info "${APP_TITLE}: ${m}" }
