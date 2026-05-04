package com.vayunmathur.health.util

import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object FoodSearchAPI {
    private val client =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                            Json {
                                ignoreUnknownKeys = true
                                coerceInputValues = true
                            }
                    )
                }
            }

    @Serializable data class SearchResult(val id: Long, val display_name: String)

    suspend fun searchIngredients(query: String): List<SearchResult> {
        return try {
            val response =
                    client.get("https://api.vayunmathur.com/api/food/search") {
                        parameter("q", query)
                    }
            if (response.status.value !in 200..299) {
                android.util.Log.e("FoodSearchAPI", "API Error: ${response.status}")
            }
            response.body()
        } catch (e: Exception) {
            android.util.Log.e("FoodSearchAPI", "Search Error: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getIngredientData(id: Long, displayName: String): Ingredient? {
        return try {
            val nutritionData: NutritionData =
                    client.get("https://api.vayunmathur.com/api/food/data/$id").body()

            Ingredient(
                    id = id.toString(),
                    originalName = displayName,
                    nutritionData = nutritionData
            )
        } catch (e: Exception) {
            android.util.Log.e("FoodSearchAPI", "Fetch Data Error: ${e.message}", e)
            null
        }
    }
}
