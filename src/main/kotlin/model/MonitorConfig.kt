package model

import kotlinx.serialization.Serializable

/**
 * Configuration for the app, loaded from "config.json".
 */
@Serializable
data class MonitorConfig(
    // Should be a copy of https://docs.google.com/spreadsheets/d/1Ui_VnH5E-X6VP2czrmVyyExu-1BRSnvRv6y4vcmXhiw/edit
    val spreadsheetId: String,
    val trialTimeMinutes: Int,
    // The waker APK, as built from http://www.github.com/garanj/waker must be available on Drive for download
    val wakerApkDriveFileId: String
)