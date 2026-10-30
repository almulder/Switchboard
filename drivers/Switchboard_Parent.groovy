/**
 *  Switchboard Parent
 *
 *  The one "Switchboard" device the Switchboard app creates. Every other Switchboard
 *  device (Announce, Notify, Siren, Presence, the Mode and Stop All switches) is its child,
 *  so they sit together under one entry in the device list. This driver only passes
 *  requests from its children up to the app, and does the app's child-device work
 *  (create, delete, relabel, update attributes).
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
 *  0.1.2 - 2026-10-29 - Passes on Switchboard Presence arrived() / departed().
 *  0.1.1 - 2026-10-26 - Passes on when a child (or this device) is deleted, so the app can show it as missing.
 *  0.1.0 - 2026-10-26 - Initial build.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.1.2"
@Field static final String NS = "almulder"

metadata {
    definition(name: "Switchboard Parent", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
    }
}

def installed() {
    log.info "${device.displayName}: installed (Switchboard Parent ${DRIVER_VERSION})"
}

def uninstalled() {
    try { parent?.sbChildRemoved(device.deviceNetworkId) } catch (ignored) { }    // all its children are gone too
}

def updated() {
    log.info "${device.displayName}: preferences saved (Switchboard Parent ${DRIVER_VERSION})"
    unschedule("logsOff")
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.info "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ---------------------------------------------------------------- children → app

def sbSpeak(String dni, Map req)      { toApp("sbSpeak")  { it.sbSpeak(dni, req) } }
def sbStop(String dni)                { toApp("sbStop")   { it.sbStop(dni) } }
def sbVolume(String dni, Integer v)   { toApp("sbVolume") { it.sbVolume(dni, v) } }
def sbNotify(String dni, String text) { toApp("sbNotify") { it.sbNotify(dni, text) } }
def sbSiren(String dni, String mode)  { toApp("sbSiren")  { it.sbSiren(dni, mode) } }
def sbSwitch(String dni, String v)    { toApp("sbSwitch") { it.sbSwitch(dni, v) } }
def sbPresence(String dni, String v)  { toApp("sbPresence") { it.sbPresence(dni, v) } }
def sbChildRemoved(String dni)        { toApp("sbChildRemoved") { it.sbChildRemoved(dni) } }

private void toApp(String what, Closure c) {
    def p = parent
    if (!p) {
        log.warn "${device.displayName}: ${what} not delivered: this device has no Switchboard app parent. Create it from the Switchboard app."
        return
    }
    if (logEnable) log.debug "${device.displayName}: ${what}"
    c(p)       // errors go back to the child, which logs them
}

// ---------------------------------------------------------------- app → children

def sbChild(String dni) { getChildDevice(dni) }
List sbChildren()       { getChildDevices() ?: [] }

/** creates a child; throws if the hub refuses (the app shows the error) */
def sbAddChild(String driver, String dni, String label) {
    def d = getChildDevice(dni)
    if (d) return d
    return addChildDevice(NS, driver, dni, [name: label, label: label, isComponent: false])
}

/** deletes a child; throws if the hub refuses (still used by a rule) */
def sbDeleteChild(String dni) {
    if (getChildDevice(dni)) deleteChildDevice(dni)
}

def sbRelabel(String dni, String label) {
    def d = getChildDevice(dni)
    if (d && d.label != label) d.setLabel(label)
}

def sbEvent(String dni, String name, value) {
    getChildDevice(dni)?.sendEvent(name: name, value: value)
}
