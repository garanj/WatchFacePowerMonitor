package model

/**
 * Represents a watch face that can be installed and tested.
 *
 * @param name Human-friendly name
 * @param packageName Package name of the watch face
 * @param version Version of the watch face
 * @param componentString The component string, e.g. "com.example.mywatchface/.MyWatchFaceService"
 * @param driveFileId ID of the file on Drive that can be downloaded, either an APK or a Bundle (ending .zip)
 */
data class WatchFaceDefinition(
    val name: String,
    val packageName: String,
    val version: String,
    val componentString: String,
    val driveFileId: String
)