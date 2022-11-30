import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import model.AdbDevice
import model.MonitorConfig
import model.Trial
import model.TrialStatus
import power.PowerControl
import status.DriveAndSheets
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

val APPLICATION_NAME = "WatchFacePowerMonitor"

lateinit var driveAndSheets: DriveAndSheets
lateinit var config: MonitorConfig

// Path to the waker APK - when the Pi is up, this will downloaded to this location once and used in all Trials.
const val wakerApkPath = "/tmp/waker.apk"

// File used to check whether an instance of the app is already running.
const val lockFile = "/tmp/watch_face_power_monitor.lock"

fun main(args: Array<String>) {
    if (isRunning()) {
        println("Already running, exiting...")
        exitProcess(0)
    }
    setRunningLock()

    config = loadConfig()
    driveAndSheets = DriveAndSheets(config.spreadsheetId)
    checkAndGetWaker()

    val trials = driveAndSheets.getTrials().filter {
        it.status != TrialStatus.COMPLETED
    }

    when {
        trials.any { it.status == TrialStatus.PREPARING } -> processPreparing(trials)
        trials.any { it.status == TrialStatus.IN_PROGRESS } -> processInProgress(trials)
        trials.any { it.status == TrialStatus.NOT_STARTED } -> processNotStarted(trials)
        else -> {
            println("Nothing to do!")
            // (Make sure the power is on anyway)
            PowerControl.powerOn()
        }
    }

    unsetRunningLock()
}

fun loadConfig(): MonitorConfig {
    val inputStream = File("config.json").inputStream()
    return Json.decodeFromStream<MonitorConfig>(inputStream)
}

fun processNotStarted(trials: List<Trial>) {
    val devices = driveAndSheets.getDevices()
    val allocatedDevices = mutableSetOf<AdbDevice>()
    val newTrials = mutableListOf<Trial>()
    trials.forEach {
        if (devices.size != allocatedDevices.size) {
            if (!allocatedDevices.contains(it.adbDevice)) {
                allocatedDevices.add(it.adbDevice)
                newTrials.add(it)
            }
        }
    }
    if (newTrials.isNotEmpty()) {
        prepareTrials(newTrials)
    }
}

fun processInProgress(trials: List<Trial>) {
    println("Checking in progress trials...")
    val inProgressTrials = trials.filter { it.status == TrialStatus.IN_PROGRESS }
    if (inProgressTrials.isNotEmpty()) {
        val trialTimeHasElapsed = checkTrialTimeHasElapsed(inProgressTrials)
        if (trialTimeHasElapsed) {
            println("Trial time has completed!")
            val completedTrials = finalizeTrials(inProgressTrials)
            driveAndSheets.updateTrials(completedTrials)

            // Remove the watch face completely once the trial is done
            inProgressTrials.forEach {
                it.adbDevice.uninstallWaker()
                if (it.watchFaceDefinition.driveFileId.isNotBlank()) {
                    // Only uninstall non-system watch faces
                    it.adbDevice.uninstallPackage(it.watchFaceDefinition.packageName)
                }
            }

            rebootAllTrials(inProgressTrials)
        }
    }
}

fun processPreparing(trials: List<Trial>) {
    val preparingTrials = trials.filter { it.status == TrialStatus.PREPARING }
    if (preparingTrials.isNotEmpty()) {
        // USB power needs to be on to ensure Wifi connectivity when doing a battery check. It should be on anyway as
        // preparing means charging the battery.
        PowerControl.powerOn()
        val fullyPrepared = checkFullyPrepared(preparingTrials)

        if (fullyPrepared) {
            startTrials(preparingTrials)
            markTrialsAsStarted(preparingTrials)
            PowerControl.powerOff()
        }
    }
}

fun prepareTrials(trials: List<Trial>) {
    PowerControl.powerOn()
    val preparingTrials = trials.map {
        it.copy(status = TrialStatus.PREPARING)
    }
    driveAndSheets.updateTrials(preparingTrials)
}

fun markTrialsAsStarted(trials: List<Trial>) {
    val startTime = Clock.System.now()
    val startedTrials = trials.map {
        it.copy(
            startCharge = 100,
            startTime = startTime,
            status = TrialStatus.IN_PROGRESS
        )
    }
    driveAndSheets.updateTrials(startedTrials)
}

fun startTrials(trials: List<Trial>) {
    trials.forEach { trial ->
        // If the Drive ID is blank, this means that it is a system watch face which shouldn't be installed (or
        // uninstalled).
        if (trial.watchFaceDefinition.driveFileId.isNotBlank()) {
            installWatchFacePackage(trial)
        }

        println("${trial.adbDevice.name}: Setting watch face...")
        trial.adbDevice.setWatchface(trial.watchFaceDefinition.componentString)

        println("${trial.adbDevice.name}: Setting watch environment...")
        trial.adbDevice.setTrialEnvironment(trial.enableAoD)

        println("${trial.adbDevice.name}: Installing waker agent...")
        trial.adbDevice.uninstallWaker()
        trial.adbDevice.installWaker()
        trial.adbDevice.enableWaker()

        trial.adbDevice.disconnect()
    }
}

fun installWatchFacePackage(trial: Trial) {
    println("${trial.adbDevice.name}: Uninstalling watch face, if already present")
    trial.adbDevice.uninstallPackage(trial.watchFaceDefinition.packageName)
    Thread.sleep(3000)
    println("${trial.adbDevice.name}: Installing watch face...")
    val fileName = driveAndSheets.getFileName(trial.watchFaceDefinition.driveFileId)
    if (fileName.endsWith(".zip", ignoreCase = true)) {
        val watchFaceDirPath = driveAndSheets.downloadBundleToTmpDir(trial.watchFaceDefinition.driveFileId)
        trial.adbDevice.installBundle(watchFaceDirPath)
    } else {
        val watchFaceFilePath = driveAndSheets.downloadApkToTmpFile(trial.watchFaceDefinition.driveFileId)
        trial.adbDevice.installApk(watchFaceFilePath)
    }
}

fun finalizeTrials(trials: List<Trial>): List<Trial> {
    // Power needed to get the connection to retrieve battery levels
    PowerControl.powerOn()
    Thread.sleep(3000)
    PowerControl.powerOff()
    PowerControl.powerOn()
    val completedTrials = mutableListOf<Trial>()
    trials.forEach { trial ->
        val batteryLevel = trial.adbDevice.getCurrentBatteryLevel()
        val approximateDrain = trial.adbDevice.getWakerBatteryDrainEstimate()
        val endTime = Clock.System.now()
        completedTrials.add(
            trial.copy(
                status = TrialStatus.COMPLETED,
                endCharge = batteryLevel,
                endTime = endTime,
                approximateDrain = approximateDrain
            )
        )
    }
    return completedTrials
}

fun rebootAllTrials(trials: List<Trial>) = trials.forEach {
    it.adbDevice.reboot()
}

fun checkFullyPrepared(trials: List<Trial>) = trials.all {
    it.adbDevice.getCurrentBatteryLevel() >= 100
}

fun checkTrialTimeHasElapsed(trials: List<Trial>) = trials.all {
    it.startTime != null && (Clock.System.now().minus(it.startTime) > config.trialTimeMinutes.minutes)
}

fun checkAndGetWaker() {
    if (!File(wakerApkPath).exists()) {
        println("Downloading waker...")
        driveAndSheets.downloadApkToPath(config.wakerApkDriveFileId, wakerApkPath)
    }
}

fun setRunningLock() = File(lockFile).createNewFile()

fun unsetRunningLock() = File(lockFile).delete()

fun isRunning() = File(lockFile).exists()