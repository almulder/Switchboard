/**
 *  Switchboard Siren
 *
 *  Virtual siren created by the Switchboard app. Automations and HSM sound this
 *  device; Switchboard decides which real sirens sound and for how long. This
 *  driver only forwards the request to the parent app and shows the requested
 *  state (the app may update the attributes afterwards).
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
 *  0.1.1 - 2026-10-26 - Tells Switchboard when it's deleted, so the app can show it as missing.
 *  0.1.0 - 2026-10-26 - Initial build.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.1.1"

metadata {
    definition(name: "Switchboard Siren", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Alarm"
        capability "Switch"

        attribute "lastMessage", "string"
        attribute "lastSource", "string"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable description logging", defaultValue: true
    }
}

def installed() {
    log.info "${device.displayName}: installed (Switchboard Siren ${DRIVER_VERSION})"
    sendEvent(name: "alarm", value: "off")
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "lastMessage", value: "none")
    sendEvent(name: "lastSource", value: "none")
}

def uninstalled() {
    try { parent?.sbChildRemoved(device.deviceNetworkId) } catch (ignored) { }    // Switchboard shows it as missing
}

def updated() {
    log.info "${device.displayName}: preferences saved (Switchboard Siren ${DRIVER_VERSION})"
    unschedule("logsOff")
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.info "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def siren()  { request("siren") }
def strobe() { request("strobe") }
def both()   { request("both") }
def on()     { request("both") }
def off()    { request("off") }

private void request(String mode) {
    sendEvent(name: "alarm", value: mode)
    sendEvent(name: "switch", value: (mode == "off") ? "off" : "on")
    if (txtEnable != false) log.info "${device.displayName}: ${mode}"
    def p = parent
    if (!p) {
        log.warn "${device.displayName}: ${mode} not delivered: this device has no Switchboard app parent. Create Switchboard devices from the Switchboard app."
        return
    }
    try {
        p.sbSiren(device.deviceNetworkId, mode)
    } catch (e) {
        log.warn "${device.displayName}: Switchboard app could not handle ${mode}: ${e}"
    }
}
