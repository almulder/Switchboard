/**
 *  Switchboard
 *
 *  Delivers announcements, push notifications and siren alerts on behalf of any other app.
 *  Automations (webCoRE pistons, Rule Machine rules, HSM…) send to Switchboard virtual devices;
 *  Switchboard decides how, when and whether the real speakers, phones and sirens are used:
 *  one queue for every speaker, volume by day/night, Guest Mode, Test Mode and a message log.
 *
 *  Requires Hubitat platform 2.5.0 or newer and the three Switchboard drivers
 *  (Switchboard Speaker, Switchboard Notifier, Switchboard Siren).
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

@Field static final String APP_VERSION = "0.1.4"
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
@Field static final String NS          = "almulder"
@Field static final String APP_TITLE   = "Switchboard"
@Field static final int    LOG_MAX     = 200

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
    description: "Announcements, notifications and sirens for every app: one speech queue, day/night volume, Guest Mode, Test Mode and a message log.",
    category: "Convenience",
    menu: "Automations",
    iconUrl: "", iconX2Url: "",
    singleInstance: true,
    installOnOpen: true
)

preferences {
    page(name: "mainPage")
    page(name: "profilesPage")
    page(name: "notifyPage")
    page(name: "sirensPage")
    page(name: "soundsPage")
    page(name: "speakersPage")
    page(name: "testPage")
    page(name: "guestPage")
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
/** TEST MODE / GUEST MODE pills (empty when both are off) */
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
        paragraph warn("Delete this ${what}? " + (kind == "snd" ? "Profiles using it as a chime stop playing it." :
                       (kind == "gm" ? "Its switch (if Switchboard made it) is deleted too, and profiles, notifications and sirens forget it." :
                       "Its Switchboard device is deleted too; rules using it will need another device.")))
        input "btnDelYes_${key}", "button", title: "Yes, delete", width: 3
        input "btnDelNo", "button", title: "Cancel", width: 3
    } else {
        input "btnDel_${key}", "button", title: "Delete this ${what}…"
    }
}

// ===================================================================== PAGES

def mainPage() {
    syncNames()
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph uiCss() + uiHeader(APP_TITLE, "v${APP_VERSION} · announcements, notifications and sirens", modePills())
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (state.gmErr) { paragraph warn(state.gmErr as String); state.remove("gmErr") }
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
            if (!guestIds()) paragraph note("No Guest Modes yet.")
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
        }
        section(uiTitle(null, "Latest messages")) {
            List l = logEntries()
            paragraph l ? logTable(l.take(10)) : note("Nothing yet.")
            href "logPage", title: "Message log", description: "All ${l.size()} entries, with filters"
        }
        section(uiTitle(null, "Setup")) {
            href "profilesPage", title: "Announcement Profiles" + tip("Each profile is one Switchboard speaker device for your rules, e.g. 'Announce – Doors'. The profile picks the real speakers, volume, priority and Guest Mode behaviour."),
                 description: "Speakers, volume, priority · ${ids('prof').size()} profile(s)"
            href "notifyPage", title: "Notifications" + tip("Each one is a Switchboard notification device, e.g. 'Notify – Everyone'. Rules send to it; it forwards to the phones you pick."),
                 description: "Phones and push · ${ids('ntf').size()} device(s)"
            href "sirensPage", title: "Sirens", description: "Real sirens behind each Switchboard siren · ${ids('sir').size()} device(s)"
            href "soundsPage", title: "Sound files", description: "Chimes and sounds to play · ${soundIds().size()} sound(s)"
            href "speakersPage", title: "Speakers & queue", description: "How each speaker speaks, queue timing · ${allSpeakers().size()} speaker(s)"
            href "testPage", title: "Test Mode", description: isTestMode() ? "ON" : "Off"
            href "guestPage", title: "Guest Modes", description: guestIds() ? "${guestIds().size()} Guest Mode(s), ${guestsOn().size()} on" : "None yet"
            href "logPage", title: "Message log", description: "What was played, skipped or sent, and by which rule"
            href "settingsPage", title: "Settings", description: "Night time, who is home, rule lookup, logging"
        }
    }
}

private String queueStatus() {
    int qn = qNormal().size(), qu = qUrgent().size()
    long t = now()
    List playing = mapFor(BUSY).findAll { k, v -> k != "ALL" && (v.until as Long) > t && v.dni }.collect { k, v -> v.dni as String }.unique()
    List names = playing.collect { getChildDevice(it)?.displayName ?: it }
    String s = names ? uiPill("Playing: ${names.join(', ')}", "on") + " " : ""
    s += (qn + qu) ? uiPill("Waiting: ${qn} normal, ${qu} urgent", qu ? "warn" : "info") : (names ? "" : uiOk("Queue is empty"))
    return s
}

// ------------------------------------------------------------------ Announcement Profiles
def profilesPage() {
    syncNames()
    dynamicPage(name: "profilesPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Announcement Profiles", "${APP_TITLE} · Setup")
            paragraph uiHint("Each profile is one speaker device for your rules. Rules speak to <b>Announce – name</b>; " +
                             "Switchboard plays it on the real speakers below, one message at a time.")
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (!ids("prof")) paragraph uiHint("No profiles yet. Add one below.")
        }
        ids("prof").each { String pid ->
            String pre = "prof_${pid}_"
            boolean urgent = settings[pre + "priority"] == "urgent"
            int n = ((settings[pre + "spk"] ?: []) as List).size()
            section("${uiTitle(null, itemName('prof', pid))}  ${n ? note("${n} speaker(s)") : uiPill('no speakers', 'warn')}" + (urgent ? " ${uiPill('URGENT', 'off')}" : ""), hideable: true, hidden: state.confirmDel != "prof_${pid}") {
                input pre + "name", "text", title: "Name", submitOnChange: true, width: 6
                input pre + "priority", "enum", title: "How important?" + tip("Urgent messages jump the queue and interrupt normal ones; use for smoke, CO, water and alarms. Urgent messages never interrupt each other."),
                      options: [normal: "Normal", urgent: "Urgent — interrupts other announcements"], defaultValue: "normal", submitOnChange: true, width: 6
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
                if (snds) input pre + "chime", "enum", title: "Chime before each message (optional)" + tip("A short sound before every spoken message on this profile. Speakers that can't play files just speak."),
                                options: snds, required: false, width: 6
                input pre + "dedupeSec", "number", title: "Skip the same message if it was played in the last (seconds)" + tip("Stops a rule that fires twice from saying the same thing twice. 0 = never skip."),
                      range: "0..3600", defaultValue: 15, width: 6
                if (!urgent) {
                    input pre + "staleSec", "number", title: "Skip if it waited longer than (seconds), 0 = never" +
                          tip("A message can wait in line while others play. Skip it if it waited this long, so you don't hear 'Front door, opened' a minute late."),
                          range: "0..3600", defaultValue: 30, width: 6
                }
                guestInputs("prof_${pid}", urgent)
                input "btnTestProf_${pid}", "button", title: "Test this profile"
                paragraph note("Used by: rules that send to the device <b>${esc(deviceLabel('prof', pid))}</b>.")
                deleteControls("prof", pid, "profile")
            }
        }
        section(uiTitle(null, "Add a profile?")) {
            input "newProfName", "text", title: "Name", required: false, submitOnChange: true, width: 6
            input "btnAdd_prof", "button", title: "Add profile"
        }
    }
}

// ------------------------------------------------------------------ Notifications
def notifyPage() {
    syncNames()
    dynamicPage(name: "notifyPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Notifications", "${APP_TITLE} · Setup")
            paragraph uiHint("Rules send to <b>Notify – name</b>; Switchboard forwards to the phones you pick.")
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (!ids("ntf")) paragraph uiHint("None yet. Add one below.")
        }
        ids("ntf").each { String nid ->
            String pre = "ntf_${nid}_"
            int n = ((settings[pre + "devs"] ?: []) as List).size()
            section("${uiTitle(null, itemName('ntf', nid))}  ${n ? note("${n} device(s)") : uiPill('no devices', 'warn')}", hideable: true, hidden: state.confirmDel != "ntf_${nid}") {
                input pre + "name", "text", title: "Name", submitOnChange: true, width: 6
                input pre + "devs", "capability.notification", title: "Send to" + tip("Hubitat app phones, Pushover, email or SMS drivers."),
                      multiple: true, required: false, submitOnChange: true
                if (((settings[pre + "devs"] ?: []) as List).size() != realDevs(settings[pre + "devs"]).size()) paragraph warn("Switchboard's own devices are ignored here. Pick real phones or notification devices.")
                if (!n) paragraph warn("Pick at least one device.")
                input pre + "when", "enum", title: "When", defaultValue: "always", width: 6,
                      options: [always: "Always", away: "Only when nobody is home"]
                if (settings[pre + "when"] == "away" && !homeSensors) paragraph warn("Pick who is home in Settings, or this always sends.")
                input pre + "dedupeSec", "number", title: "Skip the same message if it was sent in the last (seconds)", range: "0..3600", defaultValue: 15, width: 6
                guestInputs("ntf_${nid}", false)
                input pre + "prefix", "text", title: "Text added before each message (optional)", required: false, width: 6
                input pre + "pushover", "bool", title: "Mark as high priority (Pushover [H])" + tip("Adds [H] in front of the message on Pushover devices only."), defaultValue: false
                input "btnTestNtf_${nid}", "button", title: "Send a test"
                paragraph note("Used by: rules that send to the device <b>${esc(deviceLabel('ntf', nid))}</b>.")
                deleteControls("ntf", nid, "notification device")
            }
        }
        section(uiTitle(null, "Add one?")) {
            input "newNtfName", "text", title: "Name", required: false, submitOnChange: true, width: 6
            input "btnAdd_ntf", "button", title: "Add"
        }
    }
}

// ------------------------------------------------------------------ Sirens
def sirensPage() {
    syncNames()
    dynamicPage(name: "sirensPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Sirens", "${APP_TITLE} · Setup")
            paragraph uiHint("Rules (and HSM) use <b>Siren – name</b>; Switchboard sounds the real sirens, except in Test Mode.")
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (!ids("sir")) paragraph uiHint("None yet. Add one below.")
        }
        ids("sir").each { String rid ->
            String pre = "sir_${rid}_"
            int n = ((settings[pre + "devs"] ?: []) as List).size()
            boolean on = (atomicState.sirenOffAt ?: [:]).containsKey(rid)
            section("${uiTitle(null, itemName('sir', rid))}  ${n ? note("${n} siren(s)") : uiPill('no sirens', 'warn')}" + (on ? " " + uiPill("SOUNDING", "off") : ""), hideable: true, hidden: !on && state.confirmDel != "sir_${rid}") {
                input pre + "name", "text", title: "Name", submitOnChange: true, width: 6
                input pre + "devs", "capability.alarm", title: "Real sirens" + tip("Some devices claim to be an alarm but aren't sirens (e.g. some power monitors). Pick only real sirens and strobes."),
                      multiple: true, required: false, submitOnChange: true
                if (((settings[pre + "devs"] ?: []) as List).size() != realDevs(settings[pre + "devs"]).size()) paragraph warn("Switchboard's own devices are ignored here. Pick real sirens.")
                input pre + "maxMin", "number", title: "Stop after (minutes)" + tip("Sirens are turned off after this long even if no rule turns them off. 0 = never."),
                      range: "0..60", defaultValue: 5, width: 6
                guestInputs("sir_${rid}", false)
                input "btnSirOff_${rid}", "button", title: "Turn these sirens off now"
                paragraph note("Used by: rules that use the device <b>${esc(deviceLabel('sir', rid))}</b>.")
                deleteControls("sir", rid, "siren device")
            }
        }
        section(uiTitle(null, "Add one?")) {
            input "newSirName", "text", title: "Name", required: false, submitOnChange: true, width: 6
            input "btnAdd_sir", "button", title: "Add"
        }
    }
}

// ------------------------------------------------------------------ Sound files
def soundsPage() {
    syncSounds()
    dynamicPage(name: "soundsPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Sound files", "${APP_TITLE} · Setup")
            paragraph uiHint("Named sounds that profiles play as a chime, and rules play with <b>playSound(name)</b>.") + tip(SND_URL_TIP)
            if (state.pageErr) { paragraph warn(state.pageErr as String); state.remove("pageErr") }
            if (!soundIds()) paragraph uiHint("No sounds yet. Add one below.")
        }
        if (soundIds()) section(uiTitle(null, "Where should tests play?")) {
            input "sndTestSpk", "enum", title: "Speaker", options: speakerOptions(), required: false, submitOnChange: true, width: 6
            if (!speakerOptions()) paragraph note("Pick speakers in an Announcement Profile first.")
        }
        soundIds().each { String sid ->
            String pre = "snd_${sid}_"
            section("${uiTitle(null, soundName(sid))}  ${soundUrl(sid) ? note("${soundSecs(sid)} s") : uiPill('no URL', 'warn')}", hideable: true, hidden: state.confirmDel != "snd_${sid}") {
                input pre + "name", "text", title: "Name", submitOnChange: true, width: 4
                input pre + "url", "text", title: "URL" + tip(SND_URL_TIP), submitOnChange: true, width: 6
                input pre + "secs", "number", title: "Length (seconds)" + tip("How long it plays. The next message waits this long."),
                      range: "1..3600", defaultValue: 5, submitOnChange: true, width: 2
                if (!soundUrl(sid)) paragraph warn("Enter a URL, or this sound is skipped.")
                if (settings.sndTestSpk) input "btnTestSnd_${sid}", "button", title: "Play it"
                List users = ids("prof").findAll { settings["prof_${it}_chime"] == sid }.collect { itemName("prof", it) }
                paragraph users ? note("Chime for: ${users.join(', ')}") : note("Not used as a chime.")
                deleteControls("snd", sid, "sound")
            }
        }
        section(uiTitle(null, "Add a sound?")) {
            input "newSndName", "text", title: "Name", required: false, submitOnChange: true, width: 4
            input "newSndUrl", "text", title: "URL" + tip(SND_URL_TIP), required: false, submitOnChange: true, width: 6
            input "newSndSecs", "number", title: "Length (seconds)", defaultValue: 5, range: "1..3600", required: false, submitOnChange: true, width: 2
            input "btnAddSound", "button", title: "Add sound"
        }
    }
}

// ------------------------------------------------------------------ Speakers & queue
def speakersPage() {
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
def testPage() {
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
def guestPage() {
    syncGuests()
    dynamicPage(name: "guestPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Guest Modes", "${APP_TITLE}")
            paragraph uiHint("Make one Guest Mode per kind of visit (e.g. <i>Guest Mode Paul</i>, <i>Housekeeper</i>). " +
                             "Each profile, notification and siren picks which ones make it play, which make it stay silent, and what it does the rest of the time. Play always wins.") +
                      tip("Several Guest Modes can be on at once. Each has its own switch that dashboards, webCoRE and Rule Machine can turn on and off.")
            if (state.gmErr) { paragraph warn(state.gmErr as String); state.remove("gmErr") }
            if (!guestIds()) paragraph uiHint("No Guest Modes yet. Add one below.")
        }
        guestIds().each { String gid ->
            String pre = "gm_${gid}_"
            boolean on = isGuestOn(gid)
            Long end = ((state.gmOffAt ?: [:]) as Map)[gid] as Long
            String src = gmSrc(gid)
            section("${uiTitle(null, guestName(gid))}  " + (on ? uiPill("ON", "info") + (end ? " " + note("until ${fmtTime(end)}") : "") : uiPill("off", "off")), hideable: true, hidden: !on && state.confirmDel != "gm_${gid}") {
                if (src != "modes") {
                    if (on) input "btnGmOff_${gid}", "button", title: "Turn off", width: 3
                    else input "btnGmOn_${gid}", "button", title: "Turn on", width: 3
                }
                input pre + "name", "text", title: "Name" + tip("Renaming also renames its switch."), submitOnChange: true, width: 6
                input pre + "source", "enum", title: "Turned on by", submitOnChange: true, defaultValue: "create", width: 6,
                      options: [create: "Its own switch (recommended)", existing: "A switch I already have", modes: "Hub mode(s)"]
                if (src == "create") {
                    paragraph note("Switch: <b>${esc(guestName(gid))}</b>" + (getChildDevice(gmDni(gid)) ? "" : " (created when you press Done)"))
                } else if (src == "existing") {
                    input pre + "switch", "capability.switch", title: "Switch", required: false, width: 6
                    if (!settings[pre + "switch"]) paragraph warn("Pick a switch, or this Guest Mode can't turn on.")
                } else {
                    input pre + "modes", "mode", title: "On while the hub is in", multiple: true, required: false, width: 6
                    paragraph note("Your mode rules turn it on and off; Switchboard can't.")
                }
                if (src != "modes") {
                    input pre + "endDate", "date", title: "Guests leave on", required: false, submitOnChange: true, width: 4
                    input pre + "endTime", "time", title: "at", required: false, submitOnChange: true, width: 4
                    if (settings[pre + "endDate"] && settings[pre + "endTime"]) input "btnGmUntil_${gid}", "button", title: "On until then", width: 4
                    input pre + "defaultHours", "number", title: "If turned on another way (dashboard, rule), turn off after (hours)" +
                          tip("0 = stay on until something turns it off."), defaultValue: 24, range: "0..720", width: 6
                }
                input pre + "remindTime", "time", title: "Daily reminder at (blank = none)", required: false, width: 4
                input pre + "remindDevs", "capability.notification", title: "Remind", multiple: true, required: false, width: 8
                List users = []
                KINDS.keySet().each { String kind -> ids(kind).each { String id ->
                    String p = "${kind}_${id}"
                    if (((settings["${p}_guestQuiet"] ?: []) as List).contains(gid) || ((settings["${p}_guestPlay"] ?: []) as List).contains(gid)) users << deviceLabel(kind, id)
                } }
                paragraph users ? note("Used by: ${users.join(', ')}") : note("Not used by any profile, notification or siren yet.")
                deleteControls("gm", gid, "Guest Mode")
            }
        }
        section(uiTitle(null, "Add a Guest Mode?")) {
            input "newGuestName", "text", title: "Name", required: false, submitOnChange: true, width: 6, defaultValue: guestIds() ? null : "Guest Mode -SB"
            input "btnAddGuest", "button", title: "Add Guest Mode"
            paragraph note("Its switch is created right away.")
        }
    }
}
// ------------------------------------------------------------------ Message log
def logPage() {
    dynamicPage(name: "logPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        List all = logEntries()
        section {
            paragraph pageHead("Message log", "${APP_TITLE} · last ${LOG_MAX} messages")
        }
        section {
            input "logDev", "enum", title: "Device", options: all.collect { it.dev as String }.findAll { it }.unique().sort(),
                  required: false, submitOnChange: true, width: 4
            input "logOutcome", "enum", title: "Outcome", options: all.collect { it.outcome as String }.findAll { it }.unique().sort(),
                  required: false, submitOnChange: true, width: 4
            List l = all.findAll { (!logDev || it.dev == logDev) && (!logOutcome || it.outcome == logOutcome) }
            paragraph note("<b>From:</b> " + RULE_SYSTEMS.findAll { it[1] != "SB" }.collect { "${it[1]} = ${it[2]}" }.join(" · ") +
                           ". Any other app shows its own type, e.g. <i>Rule Forge – Office Door</i>.")
            paragraph l ? logTable(l) : note("Nothing to show.")
            input "btnClearLog", "button", title: "Clear log"
        }
    }
}

private String logTable(List rows) {
    String h = "<table class='sb-log'><tr><th>Time</th><th>Device</th><th>Message</th><th>Outcome</th><th>From</th></tr>"
    rows.each { Map e ->
        String oc = (e.outcome ?: "") as String
        String kind = oc.startsWith("played") || oc.startsWith("sent") ? "on" : (oc.startsWith("failed") ? "off" : (oc.startsWith("test") || oc.startsWith("queued") ? "warn" : "info"))
        h += "<tr><td style='white-space:nowrap'>${fmtShort(e.t as Long)}</td><td>${esc(e.dev as String)}</td>" +
             "<td>${esc(e.kind as String)}: ${esc(e.text as String)}</td><td>${uiPill(oc, kind)}</td><td>${esc((e.source ?: '') as String)}</td></tr>"
    }
    return h + "</table>"
}

// ------------------------------------------------------------------ Settings
def settingsPage() {
    dynamicPage(name: "settingsPage", title: "", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph pageHead("Settings", "${APP_TITLE} · Setup")
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
        section(uiTitle(null, "Sounds from rules")) {
            input "defaultTrackSecs", "number", title: "Length of a playTrack sound that isn't in Sound files (seconds)" + tip("The queue waits this long before the next message."),
                  range: "1..3600", defaultValue: 10, width: 6
        }
        section(uiTitle(null, "Which rule sent it?")) {
            input "srcLookup", "bool", title: "Find which rule sent each message" + tip("Shortly after a message arrives, Switchboard reads the device's event list on this hub to find the rule or app that sent it, and shows it in the log. It never delays the message. A message starting with [Name] uses that name instead."),
                  defaultValue: true, submitOnChange: true
            if (srcLookup != false) {
                input "hubUser", "text", title: "Hub username (optional)" + tip("Only needed if Hub Login Security is on."), required: false, width: 6
                input "hubPass", "password", title: "Hub password (optional)", required: false, width: 6
            }
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
        case "btnAdd_prof": addItem("prof", newProfName as String); app.removeSetting("newProfName"); return
        case "btnAdd_ntf":  addItem("ntf", newNtfName as String);   app.removeSetting("newNtfName");  return
        case "btnAdd_sir":  addItem("sir", newSirName as String);   app.removeSetting("newSirName");  return
        case "btnAddSound": addSound(); return
        case "btnDelNo":    state.remove("confirmDel"); return
        case "btnClearQ":   clearQueue(); return
        case "btnClearLog": withStateLock { atomicState.log = [] }; return
        case "btnTmOn":     turnTestModeOn(); return
        case "btnTmOff":    turnTestModeOff("Test Mode is OFF. Announcements, notifications and sirens are back to normal."); return
        case "btnAddGuest": addGuest(); return
    }
    if (btn.startsWith("btnGmOn_"))    { guestOn(btn - "btnGmOn_", false); return }
    if (btn.startsWith("btnGmUntil_")) { guestOn(btn - "btnGmUntil_", true); return }
    if (btn.startsWith("btnGmOff_"))   { guestOff(btn - "btnGmOff_"); return }
    if (btn.startsWith("btnDelYes_")) {
        List p = (btn - "btnDelYes_").split("_", 2) as List
        state.remove("confirmDel")
        if (p.size() == 2) {
            if (p[0] == "snd") deleteSound(p[1] as String)
            else if (p[0] == "gm") deleteGuest(p[1] as String)
            else deleteItem(p[0] as String, p[1] as String)
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
List<String> ids(String kind) {
    Map m = itemMap(kind)
    return m.keySet().sort { a, b -> itemName(kind, a as String).toLowerCase() <=> itemName(kind, b as String).toLowerCase() } as List
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
    if (!kind || !itemMap(kind).containsKey(p[3])) return null
    return [kind: kind, id: p[3]]
}

private void addItem(String kind, String rawName) {
    String n = cleanName(rawName)
    if (!n) { state.pageErr = "Enter a name first."; return }
    Map m = itemMap(kind)
    String counter = "next_${kind}".toString()
    Integer next = (state[counter] ?: 1) as Integer
    String id = "${KINDS[kind].idp}${next}".toString()
    while (m.containsKey(id)) { next++; id = "${KINDS[kind].idp}${next}".toString() }
    state[counter] = next + 1
    m[id] = [name: n]
    state[KINDS[kind].stateKey as String] = m
    app.updateSetting("${kind}_${id}_name", [type: "text", value: n])
    ensureDevice(kind, id, n)
    logInfo "Added ${KINDS[kind].what} '${n}'"
}

/** creates the item's virtual device if missing; returns it */
private def ensureDevice(String kind, String id, String name = null) {
    String dni = itemDni(kind, id)
    def d = getChildDevice(dni)
    if (d) return d
    String label = "${KINDS[kind].label}${name ?: itemName(kind, id)}".toString()
    try {
        d = addChildDevice(NS, KINDS[kind].driver as String, dni, [name: label, label: label, isComponent: false])
    } catch (e) {
        log.error "${APP_TITLE}: couldn't create '${label}': ${e}"
        state.pageErr = "Couldn't create the device \"${label}\". Is the \"${KINDS[kind].driver}\" driver installed in Drivers Code? (${e.message})"
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
    try { if (getChildDevice(dni)) deleteChildDevice(dni) } catch (e) {
        log.warn "${APP_TITLE}: couldn't delete device ${dni}: ${e}"
        state.pageErr = "Couldn't delete the device. If a rule still uses it, remove it from the rule first. (${e.message})"
        return
    }
    m.remove(id)
    state[KINDS[kind].stateKey as String] = m
    List keys = settings.keySet().findAll { it.startsWith("${kind}_${id}_") } as List
    keys.each { app.removeSetting(it as String) }
}

/** names from settings → state, relabel devices, re-create missing ones */
private void syncNames() {
    KINDS.keySet().each { String kind ->
        Map m = itemMap(kind)
        m.each { id, v ->
            String n = cleanName(settings["${kind}_${id}_name"] as String)
            if (n) v.name = n
        }
        state[KINDS[kind].stateKey as String] = m
        m.keySet().each { String id ->
            def d = getChildDevice(itemDni(kind, id))
            String want = deviceLabel(kind, id)
            if (d && d.label != want) { try { d.setLabel(want) } catch (e) { logDebug "relabel ${want}: ${e}" } }
        }
    }
}
private void ensureChildren() {
    KINDS.keySet().each { String kind -> itemMap(kind).keySet().each { String id -> ensureDevice(kind, id) } }
}

private void childEvent(def child, String name, def value) {
    if (child == null || value == null) return
    try { child.sendEvent(name: name, value: value) } catch (e) { logDebug "sendEvent ${name} on ${child}: ${e}" }
}

// ===================================================================== SOUND FILES

List<String> soundIds() {
    Map m = state.sounds ?: [:]
    return m.keySet().sort { a, b -> soundName(a as String).toLowerCase() <=> soundName(b as String).toLowerCase() } as List
}
private void addSound() {
    String n = cleanName(newSndName as String)
    String u = (newSndUrl ?: "").toString().trim()
    if (!n || !u) { state.pageErr = "Enter a name and a URL."; return }
    Map m = state.sounds ?: [:]
    Integer next = (state.next_snd ?: 1) as Integer
    String sid = "s${next}"
    while (m.containsKey(sid)) { next++; sid = "s${next}" }
    state.next_snd = next + 1
    Integer secs = newSndSecs ? Math.max(1, newSndSecs as Integer) : 5
    m[sid] = [name: n, url: u, secs: secs]
    state.sounds = m
    app.updateSetting("snd_${sid}_name", [type: "text", value: n])
    app.updateSetting("snd_${sid}_url", [type: "text", value: u])
    app.updateSetting("snd_${sid}_secs", [type: "number", value: secs])
    ["newSndName", "newSndUrl", "newSndSecs"].each { app.removeSetting(it) }
    logInfo "Added sound '${n}'"
}
private void deleteSound(String sid) {
    Map m = state.sounds ?: [:]
    m.remove(sid); state.sounds = m
    ["snd_${sid}_name", "snd_${sid}_url", "snd_${sid}_secs"].each { app.removeSetting(it as String) }
    ids("prof").each { pid -> if (settings["prof_${pid}_chime"] == sid) app.removeSetting("prof_${pid}_chime") }
}
private void syncSounds() {
    Map m = state.sounds ?: [:]
    m.each { sid, s ->
        String n = cleanName(settings["snd_${sid}_name"] as String); if (n) s.name = n
        if (settings["snd_${sid}_url"] != null) s.url = settings["snd_${sid}_url"].toString().trim()
        if (settings["snd_${sid}_secs"]) s.secs = Math.max(1, settings["snd_${sid}_secs"] as Integer)
    }
    state.sounds = m
}
String soundUrl(String sid) {
    if (!sid || !state.sounds?.get(sid)) return null
    String u = (settings["snd_${sid}_url"] != null ? settings["snd_${sid}_url"] : state.sounds[sid].url) as String
    return u?.trim() ?: null
}
Integer soundSecs(String sid) {
    def v = settings["snd_${sid}_secs"] ?: state.sounds?.get(sid)?.secs
    return v ? Math.max(1, v as Integer) : 5
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
    def child = getChildDevice(dni)
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
    Integer secs = null
    if (sndName) {
        kind = "sound"; sid = soundByName(sndName)
        label = "🔊 ${sid ? soundName(sid) : sndName}"
    } else if (uri) {
        kind = "sound"; sid = soundByUrl(uri)
        secs = sid ? soundSecs(sid) : intSet("defaultTrackSecs", 10)
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
    logInfo "Stop from ${getChildDevice(dni)?.displayName ?: dni}: ${n} waiting removed" + (keys ? ", playing message stopped" : "")
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
    def child = getChildDevice(dni)                 // the driver already shows volume / level / mute
    logDebug "${child?.displayName ?: dni}: volume ${v} stored" + (settings["prof_${k.id}_volMode"] == "caller" ? " (used for its announcements)" : " (not used: profile sets its own volume)")
}

/** Notifier deviceNotification(text) */
void sbNotify(String dni, String rawText) {
    Map k = parseDni(dni)
    if (!k || k.kind != "ntf") { log.warn "${APP_TITLE}: notification from unknown device ${dni}"; return }
    String nid = k.id as String
    def child = getChildDevice(dni)
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
    def child = getChildDevice(dni)
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
        childEvent(child, "lastMessage", "Guest Mode — sirens not sounded")
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
    realDevs(settings["sir_${rid}_devs"]).each { dev ->
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
    return ok
}
private void sirensOff(String rid, String why) {
    sirenCommand(rid, "off")
    sirenOffAtSet(rid, null)
    String dni = itemDni("sir", rid)
    def child = getChildDevice(dni)
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
void srcLookupRun(Map data) {
    try {
        if (hubUser && hubPass && (!state.hubCookie || now() - ((state.hubCookieAt ?: 0) as Long) > 6 * 3600000L)) {
            asynchttpPost("srcLoginResp", [uri: "http://127.0.0.1:8080", path: "/login",
                          requestContentType: "application/x-www-form-urlencoded",
                          body: [username: hubUser, password: hubPass, submit: "Login"], timeout: 10], data)
        } else {
            srcFetch(data)
        }
    } catch (e) { logDebug "source lookup: ${e}"; applySource(data, "unknown") }
}
void srcLoginResp(resp, Map data) {
    try {
        Map h = (resp.headers ?: [:]) as Map
        String ck = h.find { kk, v -> kk?.toString()?.equalsIgnoreCase("Set-Cookie") }?.value?.toString()
        if (ck) { state.hubCookie = ck.split(";")[0]; state.hubCookieAt = now() }
        else logDebug "hub login: no cookie (status ${resp.status})"
    } catch (e) { logDebug "hub login: ${e}" }
    try { srcFetch(data) } catch (e) { logDebug "source lookup: ${e}"; applySource(data, "unknown") }
}
private void srcFetch(Map data) {
    Map p = [uri: "http://127.0.0.1:8080/device/eventsJson/${data.devId}".toString(), timeout: 10]
    if (state.hubCookie) p.headers = [Cookie: state.hubCookie as String]
    asynchttpGet("srcLookupResp", p, data)
}
/** Undocumented endpoint: every step is guarded and anything unexpected gives "unknown". */
void srcLookupResp(resp, Map data) {
    String src = null
    try {
        int st = resp.status as int
        if (st == 401 || st == 403) state.remove("hubCookie")
        if (st == 200) {
            def body = null
            try { body = resp.json } catch (ignored) { }
            if (body == null) { try { body = parseJson(resp.data as String) } catch (ignored) { } }
            if (body == null && hubUser) state.remove("hubCookie")         // probably the login page
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
    if (state.hubCookie) p.headers = [Cookie: state.hubCookie as String]
    try { asynchttpGet("srcTypeResp", p, data + [appId: appId, srcName: name]) }
    catch (e) { logDebug "rule system lookup: ${e}"; applySource(data, name) }
}
void srcTypeResp(resp, Map data) {
    String type = null
    try {
        if ((resp.status as int) == 200) {
            def body = resp.json
            type = (body?.installedApp?.name ?: body?.installedApp?.appType?.name) as String      // e.g. "webCoRE Piston", "Rule-5.1"
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
    def child = data?.dni ? getChildDevice(data.dni as String) : null
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
    return g.keySet().sort { a, b -> guestName(a as String).toLowerCase() <=> guestName(b as String).toLowerCase() } as List
}
String guestName(String gid) {
    cleanName(settings["gm_${gid}_name"] as String) ?: ((state.guests ?: [:])[gid]?.name ?: "(deleted Guest Mode)") as String
}
Map guestOptions() { guestIds().collectEntries { [(it): guestName(it)] } }
private String gmDni(String gid) { "SB-${app.id}-gm-${gid}".toString() }
private String gmSrc(String gid) { (settings["gm_${gid}_source"] ?: "create") as String }

/** the switch behind a Guest Mode (null for hub modes) */
def gmDevice(String gid) {
    String s = gmSrc(gid)
    return s == "create" ? getChildDevice(gmDni(gid)) : (s == "existing" ? settings["gm_${gid}_switch"] : null)
}
Boolean isGuestOn(String gid) {
    if (!(state.guests ?: [:]).containsKey(gid)) return false
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
    Map g = (state.guests ?: [:]) as Map
    if (!g) return null
    List play  = ((settings["${prefix}_guestPlay"] ?: []) as List).findAll { g.containsKey(it) }
    List quiet = ((settings["${prefix}_guestQuiet"] ?: []) as List).findAll { g.containsKey(it) && !play.contains(it) }
    if (play.any { isGuestOn(it as String) }) return null
    String q = quiet.find { isGuestOn(it as String) } as String
    if (q) return "skipped – ${guestName(q)}"
    return settings["${prefix}_guestOther"] == "silent" ? "skipped – no Guest Mode says play" : null
}
/** the Guest Mode pickers for a profile / notifier / siren (Play / Quiet / the rest of the time) */
private void guestInputs(String prefix, boolean urgent) {
    Map opts = guestOptions()
    if (!opts) { paragraph note("Guest Modes: none yet (add them on the Guest Modes page)."); return }
    List play = ((settings["${prefix}_guestPlay"] ?: []) as List)
    input "${prefix}_guestPlay", "enum", title: "Play while any of these Guest Modes are on" +
          tip("Play always wins: if one of these is on, it plays even when a 'Stay silent' mode is on too."),
          options: opts, multiple: true, required: false, submitOnChange: true, width: 6
    input "${prefix}_guestQuiet", "enum", title: "Stay silent while any of these are on" +
          tip(urgent ? "Safety alerts should keep playing during Guest Mode." : "For example bedroom doors while guests sleep there."),
          options: opts.findAll { k, v -> !play.contains(k) }, multiple: true, required: false, width: 6
    input "${prefix}_guestOther", "enum", title: "The rest of the time" +
          tip("Silent = only plays while a Guest Mode above says Play, e.g. a 'Housekeeper is here' notifier."),
          options: [play: "Play", silent: "Silent (only plays while a Guest Mode above says Play)"], defaultValue: "play", required: false, width: 6
    if (urgent && (settings["${prefix}_guestQuiet"] || settings["${prefix}_guestOther"] == "silent")) paragraph warn("Safety alerts should keep playing during Guest Mode.")
}

private void addGuest() {
    String n = cleanName(newGuestName as String)
    if (!n && !guestIds()) n = "Guest Mode -SB"
    if (!n) { state.gmErr = "Enter a name first."; return }
    Map g = (state.guests ?: [:]) as Map
    Integer next = (state.next_gm ?: 1) as Integer
    String gid = "g${next}"
    while (g.containsKey(gid)) { next++; gid = "g${next}" }
    state.next_gm = next + 1
    g[gid] = [name: n]
    state.guests = g
    app.updateSetting("gm_${gid}_name", [type: "text", value: n])
    app.removeSetting("newGuestName")
    ensureGuestSwitch(gid)
    initGuest(gid)
    logInfo "Added Guest Mode '${n}'"
}
private void deleteGuest(String gid) {
    Map g = (state.guests ?: [:]) as Map
    if (!g.containsKey(gid)) return
    // switch first: if the hub refuses (still used by a rule), keep the Guest Mode so it can be deleted again later
    if (getChildDevice(gmDni(gid))) {
        try { deleteChildDevice(gmDni(gid)) } catch (e) { state.gmErr = "Couldn't delete its switch. If a rule uses it, remove it there first. (${e.message})"; return }
    }
    g.remove(gid); state.guests = g
    Map off = (state.gmOffAt ?: [:]) as Map; off.remove(gid); state.gmOffAt = off
    Map was = (state.gmWasOn ?: [:]) as Map; was.remove(gid); state.gmWasOn = was
    Map rn = (state.gmRemindNext ?: [:]) as Map; rn.remove(gid); state.gmRemindNext = rn
    List keys = settings.keySet().findAll { it.startsWith("gm_${gid}_") } as List
    keys.each { app.removeSetting(it as String) }
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

/** Switchboard's own Guest Mode virtual switches (normal, non-component), labelled with the mode's name */
private void ensureGuestSwitch(String gid) {
    def d = getChildDevice(gmDni(gid))
    String label = guestName(gid)
    if (gmSrc(gid) == "create") {
        if (!d) {
            try { addChildDevice("hubitat", "Virtual Switch", gmDni(gid), [name: label, label: label, isComponent: false]) }
            catch (e) { log.error "${APP_TITLE}: couldn't create the '${label}' switch: ${e}"; state.gmErr = "Couldn't create the switch \"${label}\". (${e.message})" }
        } else if (d.label != label) {
            try { d.setLabel(label) } catch (e) { logDebug "relabel ${label}: ${e}" }
        }
    } else if (d) {
        try { deleteChildDevice(gmDni(gid)) } catch (e) { log.warn "${APP_TITLE}: couldn't delete the unused '${label}' switch: ${e}" }
    }
}
private void ensureGuestSwitches() {
    syncGuests()
    guestIds().each { ensureGuestSwitch(it) }
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
    if (!hub) { def d = gmDevice(gid); if (d) subscribe(d, "switch", "guestSwitchHandler") }
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
    if (req.uri) parts << [url: req.uri as String, secs: (req.secs ?: intSet("defaultTrackSecs", 10)) as Integer, name: (req.label ?: req.uri) as String]
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
        markBusy(busy, res, devs, end, true, item.dni as String)
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
        markBusy(busy, res, devs, end, false, item.dni as String)
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
private void markBusy(ConcurrentHashMap<String, Map> busy, List<String> res, List devs, long end, boolean urgent, String dni) {
    Map v = [until: end, urgent: urgent, dni: dni]
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
    def child = getChildDevice(dni)
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
        if (v == null && item.dni) v = toInt(getChildDevice(item.dni as String)?.currentValue("volume"))
    } else v = isNight() ? intSet("prof_${pid}_nightVol", 40) : intSet("prof_${pid}_dayVol", 70)
    return clampVol(v)
}

/**
 * Plays the item's next part (chime, sound, track or text) on all devices and moves it on one step.
 * Parts no speaker can play are skipped. Returns the time (epoch ms) until which the speakers are busy.
 */
private long playItem(Map item, List devs, boolean urgent) {
    List parts = (item.parts ?: []) as List
    Integer vol = itemVolume(item)
    if (((item.step ?: 0) as Integer) == 0 && item.dni) {
        def child = getChildDevice(item.dni as String)
        childEvent(child, "status", "playing")
    }
    while (((item.step ?: 0) as Integer) < parts.size()) {
        Map part = parts[(item.step ?: 0) as Integer] as Map
        item.step = ((item.step ?: 0) as Integer) + 1
        long end = 0L
        if (part.sound) end = playTrackOn(item, soundUrl(part.sound as String), soundSecs(part.sound as String), "🔊 ${soundName(part.sound as String)}", devs, vol)
        else if (part.url) end = playTrackOn(item, part.url as String, (part.secs ?: 10) as Integer, part.name as String, devs, vol)
        else end = speakText(item, part.text as String, devs, vol, urgent)
        if (end > 0) return end
    }
    return now()
}

/**
 * Play a sound file: playTrackAndRestore(url, vol) where the driver has it, otherwise
 * setVolume + playTrack(url) (the volume is restored once the speaker is done). Returns 0 if nothing played.
 */
private long playTrackOn(Map item, String url, Integer secs, String name, List devs, Integer vol) {
    if (!url) return 0L
    List played = [], later = []
    devs.each { dev ->
        try {
            if (dev.hasCommand("playTrackAndRestore")) {
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
    BigDecimal lead = played.collect { (settings["spkLead_${it.id}"] ?: 0) as BigDecimal }.max() ?: 0
    BigDecimal wait = (secs ?: 5) + lead + ((gapSec != null ? gapSec : 2) as BigDecimal)
    logInfo "Playing ${name} (${wait}s) on ${played*.displayName.join(', ')} @${vol != null ? vol : '-'}" + (item.source ? " [${item.source}]" : "")
    return now() + ((wait * 1000) as long)
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
    ensureGuestSwitches()
    ensureChildren()

    subscribe(location, "systemStart", "hubRestarted")

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

    // ---- sirens still sounding
    scheduleSirenCheck()
    if (logEnable) runIn(1800, "logsOff")
    if (!qUrgent().isEmpty() || !qNormal().isEmpty() || !mapFor(RESTORE).isEmpty() || !mapFor(BUSY).isEmpty()) runInMillis(500, "processQueue")
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
    getChildDevices().each { try { deleteChildDevice(it.deviceNetworkId) } catch (e) { log.warn "${APP_TITLE}: couldn't delete ${it.displayName}: ${e}" } }
}

void logsOff() {
    log.warn "${APP_TITLE}: debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}
private void logDebug(String m) { if (logEnable) log.debug "${APP_TITLE}: ${m}" }
private void logInfo(String m)  { if (txtEnable != false) log.info "${APP_TITLE}: ${m}" }
