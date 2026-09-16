package com.transcribbio.shared.sync

import com.transcribbio.shared.model.DeviceKind
import com.transcribbio.shared.model.LectureStatus
import kotlinx.serialization.Serializable

/** Constants shared by the desktop sync server and the phone/watch clients. */
object SyncProtocol {
    const val SERVICE_TYPE = "_transcribbio._tcp"          // Android NsdManager form
    const val SERVICE_TYPE_JMDNS = "_transcribbio._tcp.local."

    const val TOKEN_HEADER = "X-Transcribbio-Token"
    const val TITLE_HEADER = "X-Lecture-Title"
    const val LANGUAGE_HEADER = "X-Lecture-Language"
    const val SOURCE_HEADER = "X-Source-Device"
    const val RECORDED_AT_HEADER = "X-Recorded-At"
    const val FILENAME_HEADER = "X-File-Name"
    const val OFFSET_HEADER = "X-Upload-Offset"            // for resumable uploads

    const val TXT_NAME = "name"
    const val TXT_DEVICE_ID = "id"

    // Wear OS Data Layer: watch streams a recording to the phone over this channel path.
    const val WEAR_RECORDING_PATH = "/transcribbio/recording"
}

@Serializable
data class PingDto(
    val app: String = "transcribbio",
    val desktopName: String,
    val version: String,
    val desktopDeviceId: String,
)

@Serializable
data class PairRequestDto(
    val deviceId: String,
    val deviceName: String,
    val kind: DeviceKind,
)

@Serializable
data class PairResponseDto(
    val token: String,
    val desktopName: String,
    val desktopDeviceId: String,
)

@Serializable
data class LectureSummaryDto(
    val id: String,
    val title: String,
    val status: LectureStatus,
    val createdAtMillis: Long,
    val durationS: Double,
    val language: String,
    val hasTranscript: Boolean,
    val hasMaterials: Boolean,
)

@Serializable
data class LectureListDto(val lectures: List<LectureSummaryDto>)

@Serializable
data class UploadResultDto(
    val lectureId: String,
    val message: String = "",
)

/** How much of a resumable upload the desktop already has (bytes). */
@Serializable
data class UploadStatusDto(val bytesReceived: Long)
