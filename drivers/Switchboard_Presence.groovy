/**
 *  Switchboard Presence
 *
 *  Virtual presence device created by the Switchboard app: one per person ("Presence – Albert - SB"), plus the
 *  optional "Presence – Anyone Home - SB". Switchboard combines the person's real presence sensors and home Wi-Fi
 *  into this one presence and sends it here. arrived() / departed() set it by hand (for tests and dashboards) until
 *  a real source changes.
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
 *  0.1.0 - 2026-10-29 - Initial build.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.1.0"

metadata {
    definition(name: "Switchboard Presence", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Sensor"
        capability "PresenceSensor"

        command "arrived"
        command "departed"

        attribute "reason", "string"
        attribute "lastArrived", "string"
        attribute "lastDeparted", "string"
        attribute "onHomeWifi", "string"
        attribute "sources", "string"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
    }
}

def installed() {
    log.info "${device.displayName}: installed (Switchboard Presence ${DRIVER_VERSION})"
}

def uninstalled() {
    try { parent?.sbChildRemoved(device.deviceNetworkId) } catch (ignored) { }    // Switchboard shows it as missing
}

def updated() {
    log.info "${device.displayName}: preferences saved (Switchboard Presence ${DRIVER_VERSION})"
    unschedule("logsOff")
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.info "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def arrived()  { toApp("present") }
def departed() { toApp("not present") }

private void toApp(String value) {
    def p = parent
    if (!p) {
        log.warn "${device.displayName}: not changed: this device has no Switchboard app parent. Create Switchboard devices from the Switchboard app."
        return
    }
    if (logEnable) log.debug "${device.displayName}: set by hand to ${value}"
    try { p.sbPresence(device.deviceNetworkId, value) } catch (e) { log.warn "${device.displayName}: Switchboard app could not handle it: ${e}" }
}
