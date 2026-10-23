package app.mauri.cucuto.core.data.db

import androidx.room3.ColumnTypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Column converters. Lists are stored as JSON arrays so they stay readable in a database dump. */
internal object Converters {
    private val stringList = ListSerializer(String.serializer())

    @ColumnTypeConverter
    fun stringListToJson(value: List<String>): String = Json.encodeToString(stringList, value)

    @ColumnTypeConverter
    fun jsonToStringList(value: String): List<String> = Json.decodeFromString(stringList, value)
}
