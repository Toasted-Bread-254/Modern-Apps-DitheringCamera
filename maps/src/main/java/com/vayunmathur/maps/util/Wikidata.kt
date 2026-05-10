package com.vayunmathur.maps.util
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

object Wikidata {
    suspend fun get(wikidata: String): Wikidata {
        return NetworkClient.getJson("https://www.wikidata.org/w/rest.php/wikibase/v1/entities/items/${wikidata}")
    }

    @Serializable
    data class Wikidata(
        val id: String,
        val statements: Map<String, List<Statement>>,
        val sitelinks: Map<String, Sitelink>
    ) {
        fun getProperty(property: String) = statements[property]?.first()?.value?.content?.jsonPrimitive?.content
        fun getWikipedia() = sitelinks["enwiki"]?.url

        @Serializable
        data class Statement(
            val id: String,
            val value: Value,
        ) {
            @Serializable
            data class Value(val content: JsonElement? = null)
        }
        @Serializable
        data class Sitelink(
            val url: String,
        )
    }
}
