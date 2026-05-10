package com.vayunmathur.maps.util
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.Serializable

@Serializable
data class PlaceMatchRequest(val name: String, val lat: Double, val lon: Double)

@Serializable
data class PlaceIdResponse(val id: String)

@Serializable
data class PlaceRatingRequest(val id: String)

@Serializable
data class PlaceRatingResponse(val rating: Float, val userRatingCount: Int)

@Serializable
data class FullPlaceInfo(val id: String, val rating: Float, val userRatingCount: Int)

object Reviews {
    private const val BASE_URL = "https://api.vayunmathur.com"

    /**
     * Chained call: 1. Match OSM data to Google ID -> 2. Get Stars from ID
     */
    suspend fun getRatingForOsmLocation(name: String, lat: Double, lon: Double): FullPlaceInfo? {
        return try {
            // Step 1: Get the Google Place ID from your server
            val idResponse: PlaceIdResponse = NetworkClient.callJson(
                url = "$BASE_URL/maps/place_match",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = PlaceMatchRequest(name, lat, lon)
            )

            val placeId = idResponse.id

            // Step 2: Get the Rating using that ID
            val ratingResponse: PlaceRatingResponse = NetworkClient.callJson(
                url = "$BASE_URL/maps/place_rating",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = PlaceRatingRequest(placeId)
            )

            FullPlaceInfo(
                id = placeId,
                rating = ratingResponse.rating,
                userRatingCount = ratingResponse.userRatingCount
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null // Handle errors or 404s gracefully
        }
    }
}
