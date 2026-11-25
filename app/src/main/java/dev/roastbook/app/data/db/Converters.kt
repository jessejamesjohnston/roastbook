package dev.roastbook.app.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room type converters: string lists as JSON arrays and enums by name. A null, blank or unreadable
 * list column reads as an empty list rather than failing the query.
 */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer())

    @TypeConverter fun listToJson(v: List<String>?): String = json.encodeToString(listSerializer, v ?: emptyList())
    @TypeConverter fun jsonToList(v: String?): List<String> =
        if (v.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(listSerializer, v) }.getOrDefault(emptyList())

    @TypeConverter fun stateToString(v: CoffeeState): String = v.name
    @TypeConverter fun stringToState(v: String): CoffeeState = CoffeeState.valueOf(v)
    @TypeConverter fun roleToString(v: PhotoRole): String = v.name
    @TypeConverter fun stringToRole(v: String): PhotoRole = PhotoRole.valueOf(v)
    @TypeConverter fun sourceToString(v: ExtractionSource): String = v.name
    @TypeConverter fun stringToSource(v: String): ExtractionSource = ExtractionSource.valueOf(v)
}
