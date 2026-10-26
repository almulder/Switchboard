/**
 *  Switchboard Speaker
 *
 *  Virtual speaker created by the Switchboard app (one per Announcement Profile).
 *  Automations speak to this device; Switchboard decides how, when and whether
 *  the real speakers are used. This driver only parses arguments, forwards them
 *  to the parent app and shows the attributes the app sends back.
 *
 *  Requires Hubitat platform 2.5.0 or newer.
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
 *  0.1.0 - 2026-10-26 - Initial build.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.1.0"
@Field static final Integer VOLUME_STEP = 5
@Field static final Integer DEFAULT_VOLUME = 50

metadata {
    definition(name: "Switchboard Speaker", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "SpeechSynthesis"
        capability "AudioNotification"
        capability "MusicPlayer"
        capability "AudioVolume"
        capability "Refresh"

        attribute "lastMessage", "string"
        attribute "lastSource", "string"
        attribute "queueLength", "number"

        command "playSound", [[name: "Sound name*", type: "STRING", description: "Name of a sound in the Switchboard sound library"]]
        command "playAnnouncement", [[name: "Text*", type: "STRING"], [name: "Volume", type: "NUMBER"]]
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable description logging", defaultValue: true
    }
}

// ---------------------------------------------------------------- lifecycle

def installed() {
    log.info "${device.displayName}: installed (Switchboard Speaker ${DRIVER_VERSION})"
    sendEvent(name: "status", value: "idle")
    sendEvent(name: "volume", value: DEFAULT_VOLUME, unit: "%")
    sendEvent(name: "level", value: DEFAULT_VOLUME, unit: "%")
    sendEvent(name: "mute", value: "unmuted")
    sendEvent(name: "lastMessage", value: "none")
    sendEvent(name: "lastSource", value: "none")
    sendEvent(name: "queueLength", value: 0)
    sendEvent(name: "trackDescription", value: "none")
    sendEvent(name: "trackData", value: "{}")
}

def updated() {
    log.info "${device.displayName}: preferences saved (Switchboard Speaker ${DRIVER_VERSION})"
    unschedule("logsOff")
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.info "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def refresh() {
    logDebug "refresh(): attributes are kept up to date by the Switchboard app"
}

// ---------------------------------------------------------------- SpeechSynthesis

def speak(text, volume = null, voice = null) {
    sendSpeech("speak", text, volume)
}

// ---------------------------------------------------------------- AudioNotification / MusicPlayer text + tracks

def playText(text, volume = null)            { sendSpeech("playText", text, volume) }
def playTextAndRestore(text, volume = null)  { sendSpeech("playTextAndRestore", text, volume) }
def playTextAndResume(text, volume = null)   { sendSpeech("playTextAndResume", text, volume) }

def playTrack(uri, volume = null)            { sendTrack("playTrack", uri, volume) }
def playTrackAndRestore(uri, volume = null)  { sendTrack("playTrackAndRestore", uri, volume) }
def playTrackAndResume(uri, volume = null)   { sendTrack("playTrackAndResume", uri, volume) }

// Echo Speaks style: playAnnouncement(text[, volume[, restoreVolume]]) or
// playAnnouncement(text, title, volume, restoreVolume). Only the first volume is used.
def playAnnouncement(text, a2 = null, a3 = null, a4 = null) {
    Integer vol = toVolume(a2)
    if (vol == null && a2 != null) vol = toVolume(a3)   // a2 was a title
    sendSpeechParsed("playAnnouncement", text, vol)
}

// ---------------------------------------------------------------- custom

def playSound(name) {
    String s = name?.toString()?.trim()
    if (!s) { log.warn "${device.displayName}: playSound needs a sound name"; return }
    logTxt "playSound \"${s}\""
    callSpeak([cmd: "playSound", text: null, uri: null, volume: null, sound: s])
}

// ---------------------------------------------------------------- AudioVolume

def setVolume(volumelevel) {
    Integer v = toVolume(volumelevel)
    if (v == null) { log.warn "${device.displayName}: setVolume ignored, not a number: ${volumelevel}"; return }
    applyVolume(v, "setVolume")
}

def volumeUp() {
    applyVolume(Math.min(100, currentVolume() + VOLUME_STEP), "volumeUp")
}

def volumeDown() {
    applyVolume(Math.max(0, currentVolume() - VOLUME_STEP), "volumeDown")
}

def mute() {
    Integer cur = currentVolume()
    if (cur > 0) state.preMuteVolume = cur
    sendEvent(name: "mute", value: "muted")
    logTxt "mute"
    callVolume(0)
}

def unmute() {
    Integer v = (state.preMuteVolume != null) ? (state.preMuteVolume as Integer) : currentVolume()
    if (v <= 0) v = DEFAULT_VOLUME
    sendEvent(name: "mute", value: "unmuted")
    logTxt "unmute (volume ${v})"
    applyVolume(v, "unmute")
}

def stop() {
    logTxt "stop"
    def p = parent
    if (!p) { warnNoParent("stop"); return }
    try {
        p.sbStop(device.deviceNetworkId)
    } catch (e) {
        log.warn "${device.displayName}: Switchboard app could not handle stop: ${e}"
    }
}

// ---------------------------------------------------------------- MusicPlayer commands that are not used

def setLevel(volumelevel, duration = null) { setVolume(volumelevel) }

def play()                  { notUsed("play") }
def pause()                 { notUsed("pause") }
def nextTrack()             { notUsed("nextTrack") }
def previousTrack()         { notUsed("previousTrack") }
def setTrack(uri)           { notUsed("setTrack") }
def restoreTrack(uri)       { notUsed("restoreTrack") }
def resumeTrack(uri)        { notUsed("resumeTrack") }

// ---------------------------------------------------------------- helpers

private void sendSpeech(String cmd, text, volume) {
    sendSpeechParsed(cmd, text, toVolume(volume))
}

private void sendSpeechParsed(String cmd, text, Integer vol) {
    String t = text?.toString()
    if (t == null || t.trim().isEmpty()) { log.warn "${device.displayName}: ${cmd} called without text, ignored"; return }
    logTxt "${cmd} \"${t}\"${vol != null ? " (volume ${vol})" : ""}"
    callSpeak([cmd: cmd, text: t, uri: null, volume: vol, sound: null])
}

private void sendTrack(String cmd, uri, volume) {
    String u = uri?.toString()?.trim()
    if (!u) { log.warn "${device.displayName}: ${cmd} called without a track URI, ignored"; return }
    Integer vol = toVolume(volume)
    logTxt "${cmd} ${u}${vol != null ? " (volume ${vol})" : ""}"
    callSpeak([cmd: cmd, text: null, uri: u, volume: vol, sound: null])
}

private void applyVolume(Integer v, String cmd) {
    sendEvent(name: "volume", value: v, unit: "%")
    sendEvent(name: "level", value: v, unit: "%")
    logTxt "${cmd} → volume ${v}"
    callVolume(v)
}

private void callSpeak(Map req) {
    def p = parent
    if (!p) { warnNoParent(req.cmd as String); return }
    try {
        p.sbSpeak(device.deviceNetworkId, req)
    } catch (e) {
        log.warn "${device.displayName}: Switchboard app could not handle ${req.cmd}: ${e}"
    }
}

private void callVolume(Integer v) {
    def p = parent
    if (!p) { warnNoParent("volume"); return }
    try {
        p.sbVolume(device.deviceNetworkId, v)
    } catch (e) {
        log.warn "${device.displayName}: Switchboard app could not handle volume ${v}: ${e}"
    }
}

private Integer currentVolume() {
    Integer v = toVolume(device.currentValue("volume"))
    return (v == null) ? DEFAULT_VOLUME : v
}

private Integer toVolume(v) {
    if (v == null) return null
    try {
        String s = v.toString().trim()
        if (!s) return null
        Integer i = Math.round(new BigDecimal(s).doubleValue()) as Integer
        return Math.max(0, Math.min(100, i))
    } catch (e) {
        return null
    }
}

private void notUsed(String cmd) {
    log.info "${device.displayName}: ${cmd} is not used by a Switchboard speaker (ignored)"
}

private void warnNoParent(String cmd) {
    log.warn "${device.displayName}: ${cmd} not delivered: this device has no Switchboard app parent. Create Switchboard devices from the Switchboard app."
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

private void logTxt(String msg) {
    if (txtEnable != false) log.info "${device.displayName}: ${msg}"
}
