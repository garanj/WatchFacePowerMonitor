package model

import kotlinx.datetime.Instant

data class Trial(
    // Each Trial must have a unique numeric ID as specified in the spreadsheet.
    val runId: Int,
    val adbDevice: AdbDevice,
    val watchFaceDefinition: WatchFaceDefinition,
    val enableAoD: Boolean,
    val status: TrialStatus = TrialStatus.NOT_STARTED,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val startCharge: Int? = null,
    val endCharge: Int? = null,
    val approximateDrain: Double? = null
) {
    fun toRow() = listOf(
        runId,
        adbDevice.name,
        watchFaceDefinition.name,
        if (enableAoD) "On" else "Off",
        status.name,
        startTime?.epochSeconds ?: "",
        endTime?.epochSeconds ?: "",
        startCharge ?: "",
        endCharge ?: "",
        approximateDrain ?: ""
    )
}

enum class TrialStatus {
    NOT_STARTED,
    PREPARING,
    IN_PROGRESS,
    COMPLETED
}