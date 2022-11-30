package status

import APPLICATION_NAME
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AbstractPromptReceiver
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.ValueRange
import evalBash
import kotlinx.datetime.Instant
import model.AdbDevice
import model.Trial
import model.TrialStatus
import model.WatchFaceDefinition
import java.io.File
import java.io.InputStreamReader

/**
 * Control and reporting is done via Sheets and Drive.
 *
 * The [spreadsheetId] should be a copy of https://docs.google.com/spreadsheets/d/1Ui_VnH5E-X6VP2czrmVyyExu-1BRSnvRv6y4vcmXhiw/edit
 *
 * This has 3 sheets, which need to be named respectively:
 *
 * -  "Devices" - lists the available Wear OS watches that can be used for watch face tests.
 * -  "Watch Faces" - lists the details of watch faces to be tested (for example, package name etc)
 * -  "Trials" - lists what tests to perform, ie. combinations of devices and watch faces.
 */
class DriveAndSheets(val spreadsheetId: String) {
    private val TOKENS_DIRECTORY_PATH = "tokens"

    private val SCOPES = listOf(
        SheetsScopes.SPREADSHEETS,
        DriveScopes.DRIVE_READONLY
    )

    /**
     * The Credentials file downloaded from the Google Cloud console.
     */
    private val CREDENTIALS_FILE_PATH = "credentials.json"

    private val DEVICES_SHEET_NAME = "Devices"
    private val WATCH_FACES_SHEET_NAME = "Watch Faces"
    private val TRIALS_SHEET_NAME = "Trials"

    val jsonFactory by lazy { GsonFactory.getDefaultInstance() }
    val httpTransport by lazy { GoogleNetHttpTransport.newTrustedTransport() }

    private var _devices: List<AdbDevice>? = null
    private var _watchfaces: List<WatchFaceDefinition>? = null

    fun getDevices(): List<AdbDevice> {
        if (_devices == null) {
            _devices = refreshDevices()
        }
        return _devices!!
    }

    fun getWatchFaces(): List<WatchFaceDefinition> {
        if (_watchfaces == null) {
            _watchfaces = refreshWatchFaces()
        }
        return _watchfaces!!
    }

    private fun refreshDevices(): List<AdbDevice> {
        val devicesRange = sheets.Spreadsheets().Values().get(spreadsheetId, DEVICES_SHEET_NAME).execute()
        return devicesRange.getValues()
            .drop(1)
            .map {
                AdbDevice(it[0].toString(), it[1].toString())
            }
    }

    private fun refreshWatchFaces(): List<WatchFaceDefinition> {
        val wfRange = sheets.Spreadsheets().Values().get(spreadsheetId, WATCH_FACES_SHEET_NAME).execute()
        return wfRange.getValues()
            .drop(1)
            .map {
                WatchFaceDefinition(
                    name = it[0].toString(),
                    packageName = it[1].toString(),
                    version = it[2].toString(),
                    componentString = it[3].toString(),
                    driveFileId = it.getOrElse(4) { "" }.toString()
                )
            }
    }

    fun getTrials(): List<Trial> {
        val watchFacesMap = getWatchFaces().associateBy { it.name }
        val devicesMap = getDevices().associateBy { it.name }

        val trialsRange = sheets.Spreadsheets().Values().get(spreadsheetId, TRIALS_SHEET_NAME).execute()
        return trialsRange.getValues()
            .drop(1)
            .mapNotNull {
                val runId = it[0].toString().toInt()
                val device = devicesMap[it[1].toString()]
                val watchFace = watchFacesMap[it[2].toString()]
                val status = it.getOrNull(4)?.toString()?.uppercase() ?: ""
                val startTime = if (it.getOrElse(5) { "" }.toString().isNotBlank()) {
                    Instant.fromEpochSeconds(it[5].toString().toLong())
                } else null
                val startCharge = if (it.getOrElse(7) { "" }.toString().isNotBlank()) {
                    it[7].toString().toInt()
                } else null
                if (device != null && watchFace != null) {
                    Trial(
                        adbDevice = device,
                        watchFaceDefinition = watchFace,
                        enableAoD = it[3].toString().lowercase() == "on",
                        status = if (status.isNotBlank()) {
                            TrialStatus.valueOf(status)
                        } else {
                            TrialStatus.NOT_STARTED
                        },
                        runId = runId,
                        startTime = startTime,
                        startCharge = startCharge
                    )
                } else null
            }
    }

    fun updateTrials(trials: List<Trial>) {
        val trialsMap = trials.associateBy { it.runId }
        val trialsRange = sheets.Spreadsheets().Values().get(spreadsheetId, TRIALS_SHEET_NAME).execute()
        val newRange = mutableListOf<List<Any?>>()
        trialsRange.getValues().forEachIndexed { idx, row ->
            if (idx == 0) {
                newRange.add(row)
            } else {
                val runId = row[0].toString().toInt()
                if (trialsMap.contains(runId)) {
                    println(trialsMap.get(runId)!!.toRow())
                    newRange.add(trialsMap.get(runId)!!.toRow())
                } else {
                    newRange.add(row)
                }
            }
        }
        val valueRange = ValueRange()
            .setValues(newRange)
            .setMajorDimension("ROWS")
        sheets.Spreadsheets().Values()
            .update(
                spreadsheetId,
                TRIALS_SHEET_NAME,
                valueRange
            )
            .setValueInputOption("RAW")
            .execute()
    }

    /**
     * Downloads an APK from Drive to a temporary local file. Where this file has already been downloaded to a
     * temporary file, this existing path is returned instead.
     */
    fun downloadApkToTmpFile(fileId: String): String {
        val tmpApkPath = "/tmp/wfb-$fileId.apk"
        if (File(tmpApkPath).exists()) {
            println("Using existing download for $fileId")
            return tmpApkPath
        }
        return downloadApkToPath(fileId, tmpApkPath)
    }

    fun downloadApkToPath(fileId: String, path: String): String {
        val file = File(path)
        val output = file.outputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(output)
        output.close()
        return file.absolutePath
    }

    /**
     * Downloads a Bundle and extracts it to a folder, for use with `install-multiple`.
     *
     * @return The directory path to the extracted bundle.
     */
    fun downloadBundleToTmpDir(fileId: String): String {
        val tmpBundlePath = "/tmp/wfd-$fileId"
        if (File(tmpBundlePath).exists()) {
            println("Using existing download for $fileId")
            return tmpBundlePath
        }
        File(tmpBundlePath).mkdir()
        val tmpFile = File("$tmpBundlePath/download.zip")
        val output = tmpFile.outputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(output)
        output.close()
        println("/usr/bin/unzip $tmpBundlePath/download.zip -d $tmpBundlePath/")
        "/usr/bin/unzip $tmpBundlePath/download.zip -d $tmpBundlePath/".evalBash()
        return tmpBundlePath
    }

    fun getFileName(fileId: String): String {
        val wfFile = drive.files().get(fileId).execute()
        return wfFile.name
    }

    private val sheets by lazy {
        Sheets.Builder(httpTransport, jsonFactory, getCredentials(httpTransport))
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    private val drive by lazy {
        Drive.Builder(httpTransport, jsonFactory, getCredentials(httpTransport))
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    private fun getCredentials(httpTransport: NetHttpTransport): Credential? {
        val inputStream = File(CREDENTIALS_FILE_PATH).inputStream()
        val clientSecrets = GoogleClientSecrets.load(jsonFactory, InputStreamReader(inputStream))

        val flow = GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, clientSecrets, SCOPES)
            .setDataStoreFactory(FileDataStoreFactory(File(TOKENS_DIRECTORY_PATH)))
            .setAccessType("offline")
            .build()
        return AuthorizationCodeInstalledApp(flow, OobCodeReceiver()).authorize("user")
    }
}

/**
 * Used to specify that the Auth code exchange will take place out of band and the code will be pasted back into the
 * console.
 */
class OobCodeReceiver : AbstractPromptReceiver() {
    override fun getRedirectUri() = "urn:ietf:wg:oauth:2.0:oob"
}