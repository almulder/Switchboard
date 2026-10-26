/**
 *  Switchboard Notifier
 *
 *  Virtual notification device created by the Switchboard app. Automations send
 *  notifications to this device; Switchboard decides which phones get them and
 *  when. This driver only forwards the text to the parent app and shows the
 *  attributes the app sends back.
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

metadata {
    definition(name: "Switchboard Notifier", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Notification"

        attribute "lastMessage", "string"
        attribute "lastSource", "string"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable description logging", defaultValue: true
    }
}

def installed() {
    log.info "${device.displayName}: installed (Switchboard Notifier ${DRIVER_VERSION})"
    sendEvent(name: "lastMessage", value: "none")
    sendEvent(name: "lastSource", value: "none")
}

def updated() {
    log.info "${device.displayName}: preferences saved (Switchboard Notifier ${DRIVER_VERSION})"
    unschedule("logsOff")
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.info "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

def deviceNotification(text) {
    String t = text?.toString()
    if (t == null || t.trim().isEmpty()) { log.warn "${device.displayName}: deviceNotification called without text, ignored"; return }
    if (txtEnable != false) log.info "${device.displayName}: notification \"${t}\""
    def p = parent
    if (!p) {
        log.warn "${device.displayName}: notification not delivered: this device has no Switchboard app parent. Create Switchboard devices from the Switchboard app."
        return
    }
    try {
        p.sbNotify(device.deviceNetworkId, t)
    } catch (e) {
        log.warn "${device.displayName}: Switchboard app could not handle the notification: ${e}"
    }
}
