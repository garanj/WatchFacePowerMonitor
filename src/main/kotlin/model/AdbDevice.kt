package model

import evalBash
import kotlinx.serialization.Serializable
import wakerApkPath

/**
 * Represents a Wear device that can be connected to via ADB.
 */
@Serializable
data class AdbDevice(
    val name: String,
    val addressAndPort: String
) {
    private val maxConnectAttempts = 20

    // Timeout for screen to go from interactive to ambient/off.
    private val screenTimeoutMillis = 15000

    private val adbPath = "/usr/bin/adb"
    private val adbConnectTemplate = "$adbPath connect %s"
    private val adbDisconnect = "$adbPath disconnect $addressAndPort"
    private val adbDevices = "$adbPath devices"

    // Template for expected substring in the response from `adb devices` that indicates that a given device is
    // connected.
    private val adbConnectedDevice = "$addressAndPort\tdevice"

    private val adbBatteryLevel = "$adbPath -s $addressAndPort shell cat /sys/class/power_supply/battery/capacity"
    private val adbInstallApk = "$adbPath -s $addressAndPort install -r -g %s"
    private val adbInstallBundle = "$adbPath -s $addressAndPort install-multiple -r -g %s/*.apk"
    private val adbUnInstallApk = "$adbPath -s $addressAndPort uninstall %s"
    private val adbPermissionTemplate = "$adbPath -s $addressAndPort shell pm grant %s %s"
    private val adbStartActivityTemplate =
        "$adbPath -s $addressAndPort shell am start -n \"%s\" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"

    private val adbSetBrightnessModeAuto =
        "$adbPath -s $addressAndPort shell settings put system screen_brightness_mode %d"
    private val adbSetBrightnessLevel = "$adbPath -s $addressAndPort shell settings put system screen_brightness %d"
    private val adbSetAmbientMode = "$adbPath -s $addressAndPort shell settings put global ambient_enabled %d"
    private val adbSetScreenTimeout = "$adbPath -s $addressAndPort shell settings put system screen_off_timeout"

    private val adbGetBrandCommand = "$adbPath -s $addressAndPort shell getprop ro.product.brand"

    private val adbReboot = "$adbPath -s $addressAndPort reboot"

    // Offbody - specific actions for different manufacturers
    // "1" means offbody disabled
    private val adbSamsungSetOffBody = "$adbPath -s $addressAndPort shell am " +
            "broadcast -a com.samsung.android.hardware.sensormanager.service.OFFBODY_DETECTOR --ei force_set %d"

    // On Pixel Watch, set to -1 to stop screen timeout
    private val adbSetAmbientPluggedTimeout =
        "$adbPath -s $addressAndPort shell settings put global ambient_plugged_timeout_min %d"

    // The waker package: See https://www.github.com/garanj/waker - an APK that periodically wakes up the screen on the
    // watch, to emulate usage of a watch by a person.
    val wakerPackage = "com.garan.waker"
    val wakerComponent = "$wakerPackage/.WakerActivity"
    val wakerInterval =
        "$adbPath -s $addressAndPort shell am broadcast -a com.garan.waker.SET_WAKEUP_INTERVAL --ei interval_seconds %d $wakerPackage"

    private val enableWaker =
        "$adbPath -s $addressAndPort shell am broadcast -a com.garan.waker.SET_WAKEUP_ON $wakerPackage"

    // The waker APK can also record the battery level each time it wakes up. This broadcast returns a list of battery
    // levels in the result.
    private val getWakerBatteryLevels =
        "$adbPath -s $addressAndPort shell am broadcast -a com.garan.waker.GET_BATTERY_LEVELS $wakerPackage"

    private val adbSetWatchFace = ("adb -s $addressAndPort shell am broadcast " +
            "-a com.google.android.wearable.app.DEBUG_SURFACE --es operation " +
            "set-watchface --ecn component \"%s\"")

    fun connect(): String? {
        val connectCommand = adbConnectTemplate.format(addressAndPort)
        return connectCommand.evalBash().getOrDefault("")
    }

    fun disconnect() = adbDisconnect.evalBash()

    fun getCurrentBatteryLevel(): Int {
        checkConnected()
        val maybeLevel = adbBatteryLevel.evalBash().getOrDefault("")
        return maybeLevel.trim().toInt()
    }

    fun installApk(path: String) {
        checkConnected()
        adbInstallApk.format(path).evalBash()
    }

    fun installBundle(dirPath: String) {
        checkConnected()
        println(adbInstallBundle.format(dirPath))
        println(adbInstallBundle.format(dirPath).evalBash().getOrThrow())
    }

    fun uninstallPackage(packageName: String) {
        checkConnected()
        adbUnInstallApk.format(packageName).evalBash()
    }

    fun setPermission(packageName: String, permission: String) {
        checkConnected()
        adbPermissionTemplate.format(packageName, permission).evalBash()
    }

    fun startActivity(componentName: String) {
        checkConnected()
        adbStartActivityTemplate.format(componentName).evalBash()
    }

    fun reboot() = adbReboot.evalBash()

    private fun checkConnected() {
        var attempts = 0
        val devices = adbDevices.evalBash().getOrDefault("")
        if (!devices.contains(adbConnectedDevice)) {
            println("$name is not connected, connecting...")
            var connectMessage = connect()
            while (connectMessage?.contains("failed") == true && attempts++ < maxConnectAttempts) {
                if (attempts == maxConnectAttempts) {
                    throw RuntimeException("Couldn't connect to device: $name")
                }
                Thread.sleep(1000)
                println("$name is not connected, connecting... ")
                connectMessage = connect()
            }
        }
    }

    fun installWaker() {
        checkConnected()
        installApk(wakerApkPath)
        // The waker package needs this permission set explicitly
        setPermission(wakerPackage, "android.permission.SYSTEM_ALERT_WINDOW")
        startActivity(wakerComponent)
    }

    fun uninstallWaker() {
        checkConnected()
        uninstallPackage(wakerPackage)
    }

    fun enableWaker() {
        checkConnected()
        enableWaker.evalBash()
    }

    fun setWakerInterval(intervalSeconds: Int) {
        checkConnected()
        wakerInterval.format(intervalSeconds).evalBash()
    }

    /**
     * Provides an alternative measure of battery drain: The waker logs the battery level each time the screen wakes and
     * this can be retrieved via a broadcast.
     *
     * This function calculates the rate of drain between the first time the battery dips below 100, until the last
     * reading available, working on the basis that sometimes on some devices the value "100" can be misleading.
     */
    fun getWakerBatteryDrainEstimate(): Double {
        checkConnected()
        val result = getWakerBatteryLevels.evalBash().getOrDefault("")
        val regex = "data=\"([^\"]+)".toRegex()
        var approximateDrain = 0.0

        val match = regex.find(result)
        if (match != null && match.groupValues.size == 2) {
            val pairs = match.groupValues[1].split(",")
            val readings = pairs.map {
                val parts = it.split(":")
                Pair<Long, Int>(parts[0].toLong(), parts[1].toInt())
            }
            val firstNonFullReading = readings.first { it.second < 100 }
            val lastReading = readings.last()
            if (lastReading.first > firstNonFullReading.first) {
                approximateDrain =
                    (firstNonFullReading.second - lastReading.second).toDouble() / (lastReading.first - firstNonFullReading.first)
            }
        }
        return approximateDrain
    }

    fun setWatchface(componentName: String) {
        checkConnected()
        adbSetWatchFace.format(componentName).evalBash()
    }

    fun setTrialEnvironment(isAmbient: Boolean) {
        checkConnected()
        val ambient = if (isAmbient) 1 else 0
        adbSetAmbientMode.format(ambient).evalBash()
        // Set Brightness mode - 0 means manual, 1 means auto
        adbSetBrightnessModeAuto.format(0).evalBash()
        adbSetBrightnessLevel.format(255).evalBash()

        adbSetScreenTimeout.format(screenTimeoutMillis).evalBash()

        val deviceBrand = adbGetBrandCommand.evalBash().getOrDefault("")

        // Different brands require different additional commands to set up the environment, notably to ensure that the
        // device is emulating "offbody" as correctly as possible.
        if (deviceBrand.contains("samsung", true)) {
            performSamsungSpecificSetupCommands()
        } else if (deviceBrand.contains("google", true)) {
            performGoogleSpecificSetupCommands()
        }
    }

    private fun performSamsungSpecificSetupCommands() {
        // 1 Means disable offbody
        adbSamsungSetOffBody.format(1).evalBash()
    }

    private fun performGoogleSpecificSetupCommands() {
        // -1 Turns timeout off
        adbSetAmbientPluggedTimeout.format(-1).evalBash()
    }
}