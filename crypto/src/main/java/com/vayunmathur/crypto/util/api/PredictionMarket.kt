package com.vayunmathur.crypto.util.api
import com.vayunmathur.crypto.data.TokenInfo
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.Serializable

object PredictionMarket {

    @Serializable
    data class Event(
        val title: String,
        val category: String,
        val ticker: String,
        val seriesTicker: String,
        val imageUrl: String,
        val volume: Long,
        val rules: String,
        val markets: List<Market>
    ) {
        @Serializable
        data class Market(
            val subtitle: String,
            val chance: Double,
            val closeTime: Long,
            val yesPrice: Double,
            val noPrice: Double,
            val yesMint: String,
            val noMint: String
        ) {
            fun isOpen() = closeTime > System.currentTimeMillis() / 1000
        }

        fun anyMarketOpen() = markets.any { it.isOpen() }
    }

    suspend fun getPredictionMarkets(): List<Event> {
        return NetworkClient.getJson("https://api.vayunmathur.com/crypto/prediction_market_events")
    }

    suspend fun makeOrder(market: Event.Market, yes: Boolean, amount: Double, publicKey: String): PendingOrder {
        val outputMint = if (yes) market.yesMint else market.noMint
        val inputMint = TokenInfo.USDC.mintAddress
        val slippageBps = 50

        val url = "https://api.vayunmathur.com/crypto/prediction_market/order?" +
            "inputMint=$inputMint&" +
            "outputMint=$outputMint&" +
            "amount=${(amount*1000000).toInt()}&" +
            "slippageBps=$slippageBps&" +
            "userPublicKey=$publicKey"

        return NetworkClient.getJson(url)
    }
}
