package power

import evalBash

/**
 * Class for controlling USB power on Raspberry Pi 4 using uhubctl which must be installed on the system.
 */
class PowerControl {
    companion object {
        private val POWER_ON_CMD = "/usr/sbin/uhubctl -l 1-1 -a 1"
        private val POWER_OFF_CMD = "/usr/sbin/uhubctl -l 1-1 -a 0"

        fun powerOn() = POWER_ON_CMD.evalBash().getOrThrow()

        fun powerOff() = POWER_OFF_CMD.evalBash().getOrThrow()
    }
}