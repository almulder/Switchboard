/**
 *  Switchboard Switch
 *
 *  A switch the Switchboard app creates under its "Switchboard" parent device: the
 *  Stop All switch and one per Guest Mode. Turn it on or off from a dashboard, rule or
 *  voice assistant; the switch reports the change to Switchboard through its parent.
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
    definition(name: "Switchboard Switch", namespace: "almulder", author: "Albert Mulder") {
        capability "Actuator"
        capability "Switch"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable description logging", defaultValue: true
    }
}

def installed() {
    log.info "${device.displayName}: installed (Switchboard Switch ${DRIVER_VERSION})"
    sendEvent(name: "switch", value: "off")
}

def updated() {
    log.info "${device.displayName}: preferences saved (Switchboard Switch ${DRIVER_VERSION})"
}

def on()  { setSwitch("on") }
def off() { setSwitch("off") }

private void setSwitch(String v) {
    sendEvent(name: "switch", value: v, descriptionText: "${device.displayName} was turned ${v}")
    if (txtEnable != false) log.info "${device.displayName}: ${v}"
    def p = parent
    if (!p) {
        log.warn "${device.displayName}: ${v} not reported: this switch has no Switchboard parent device. Create it from the Switchboard app."
        return
    }
    try {
        p.sbSwitch(device.deviceNetworkId, v)
    } catch (e) {
        log.warn "${device.displayName}: Switchboard could not handle ${v}: ${e}"
    }
}
