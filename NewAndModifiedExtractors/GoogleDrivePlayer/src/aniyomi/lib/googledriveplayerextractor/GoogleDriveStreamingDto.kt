package aniyomi.lib.googledriveplayerextractor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GoogleDriveStreamingResponse(
    @SerialName("mediaStreamingData") val mediaStreamingData: MediaStreamingData,
)

@Serializable
data class MediaStreamingData(
    @SerialName("formatStreamingData") val formatStreamingData: FormatStreamingData? = null,
    @SerialName("serializedHouseBrandPlayerResponse") val serializedHouseBrandPlayerResponse: String? = null,
)

@Serializable
data class FormatStreamingData(
    @SerialName("progressiveTranscodes") val progressiveTranscodes: List<ProgressiveTranscode> = emptyList(),
    @SerialName("adaptiveTranscodes") val adaptiveTranscodes: List<AdaptiveTranscode> = emptyList(),
)

@Serializable
data class ProgressiveTranscode(
    val itag: Int,
    val url: String,
    @SerialName("transcodeMetadata") val transcodeMetadata: TranscodeMetadata,
)

@Serializable
data class AdaptiveTranscode(
    val itag: Int,
    val url: String,
    @SerialName("transcodeMetadata") val transcodeMetadata: AdaptiveTranscodeMetadata,
)

@Serializable
data class TranscodeMetadata(
    val height: Int,
)

@Serializable
data class AdaptiveTranscodeMetadata(
    @SerialName("mimeType") val mimeType: String,
    val height: Int,
    @SerialName("maxContainerBitrate") val maxContainerBitrate: Int,
    @SerialName("audioCodecString") val audioCodecString: String? = null,
)

// DTOs for the nested serializedHouseBrandPlayerResponse (YouTube-like player response)
@Serializable
data class HouseBrandPlayerResponse(
    @SerialName("streamingData") val streamingData: HouseBrandStreamingData? = null,
)

@Serializable
data class HouseBrandStreamingData(
    @SerialName("formats") val formats: List<HouseBrandFormat> = emptyList(),
    @SerialName("adaptiveFormats") val adaptiveFormats: List<HouseBrandFormat> = emptyList(),
    @SerialName("expiresInSeconds") val expiresInSeconds: String? = null,
)

@Serializable
data class HouseBrandFormat(
    val itag: Int? = null,
    val url: String? = null,
    @SerialName("mimeType") val mimeType: String? = null,
    @SerialName("qualityLabel") val qualityLabel: String? = null,
    @SerialName("contentLength") val contentLength: String? = null,
    @SerialName("width") val width: Int? = null,
    @SerialName("height") val height: Int? = null,
    @SerialName("bitrate") val bitrate: Int? = null,
)
