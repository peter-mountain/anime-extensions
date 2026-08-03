package aniyomi.lib.dailymotionextractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class DailyQuality(
    val qualities: Auto? = null,
    val subtitles: Subtitle? = null,
    val error: Error? = null,
) {
    @Serializable
    data class Error(
        val type: String? = null,
        val title: String? = null,
        val message: String? = null,
        val code: String? = null,
    )
}

@Serializable
data class Auto(val auto: List<Item>) {
    @Serializable
    data class Item(val type: String, val url: String)
}

@Serializable
data class Subtitle(
    @Serializable(with = SubtitleListSerializer::class)
    val data: List<SubtitleDto>,
)

@Serializable
data class SubtitleDto(val label: String, val urls: List<String>)

object SubtitleListSerializer :
    JsonTransformingSerializer<List<SubtitleDto>>(ListSerializer(SubtitleDto.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonArray(element.values.toList())
        else -> JsonArray(emptyList())
    }
}
