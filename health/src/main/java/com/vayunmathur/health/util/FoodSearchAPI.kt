package com.vayunmathur.health.util

import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object FoodSearchAPI {

    @Serializable data class SearchResult(val id: Long, @SerialName("display_name") val displayName: String)

    suspend fun searchIngredients(query: String): List<SearchResult> {
        return try {
            NetworkClient.getJson("https://api.vayunmathur.com/api/food/search?q=$query")
        } catch (e: Exception) {
            android.util.Log.e("FoodSearchAPI", "Search Error: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getIngredientData(id: Long, displayName: String): Ingredient? {
        return try {
            val nutritionData: NutritionData =
                    NetworkClient.getJson("https://api.vayunmathur.com/api/food/data/$id")

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
