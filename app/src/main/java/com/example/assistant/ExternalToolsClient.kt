package com.example.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WeatherResult(
    val locationName: String,
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int,
    val windKmh: Double,
    val conditionLabel: String,
    val highC: Double,
    val lowC: Double
)

data class WebSearchResult(
    val query: String,
    val heading: String,
    val summary: String,
    val sourceUrl: String,
    val relatedTopics: List<String>
)

class ExternalToolsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchWeatherByCity(city: String): Result<WeatherResult> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(city.trim(), "UTF-8")
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
            val geoJson = executeGet(geoUrl)
            val results = geoJson.optJSONArray("results")
            require(results != null && results.length() > 0) {
                "Could not find weather coordinates for '$city'."
            }
            val first = results.getJSONObject(0)
            val lat = first.getDouble("latitude")
            val lon = first.getDouble("longitude")
            val resolvedName = buildString {
                append(first.getString("name"))
                val country = first.optString("country", "")
                if (country.isNotBlank()) append(", $country")
            }
            fetchWeatherByCoordinates(lat, lon, resolvedName).getOrThrow()
        }
    }

    suspend fun fetchWeatherByCoordinates(
        latitude: Double,
        longitude: Double,
        label: String = "Current Location"
    ): Result<WeatherResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url =
                "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
            val json = executeGet(url)
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val code = current.optInt("weather_code", 0)
            WeatherResult(
                locationName = label,
                temperatureC = current.getDouble("temperature_2m"),
                feelsLikeC = current.optDouble("apparent_temperature", current.getDouble("temperature_2m")),
                humidityPercent = current.optInt("relative_humidity_2m", 0),
                windKmh = current.optDouble("wind_speed_10m", 0.0),
                conditionLabel = mapWmoCode(code),
                highC = daily.getJSONArray("temperature_2m_max").optDouble(0, current.getDouble("temperature_2m")),
                lowC = daily.getJSONArray("temperature_2m_min").optDouble(0, current.getDouble("temperature_2m"))
            )
        }
    }

    suspend fun searchWeb(query: String): Result<WebSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQuery = query.trim()
            require(cleanQuery.isNotEmpty()) { "Search query cannot be empty." }
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")

            // 1. DuckDuckGo Instant Answer API
            val ddgUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val ddgJson = executeGet(ddgUrl)
            val abstractText = ddgJson.optString("AbstractText", "")
            val heading = ddgJson.optString("Heading", "").ifBlank { cleanQuery }
            val sourceUrl = ddgJson.optString("AbstractURL", "")
            val topics = mutableListOf<String>()
            val related = ddgJson.optJSONArray("RelatedTopics")
            if (related != null) {
                for (i in 0 until minOf(related.length(), 4)) {
                    val item = related.optJSONObject(i) ?: continue
                    val text = item.optString("Text", "")
                    if (text.isNotBlank()) topics.add(text)
                }
            }

            if (abstractText.isNotBlank()) {
                return@runCatching WebSearchResult(
                    query = cleanQuery,
                    heading = heading,
                    summary = abstractText,
                    sourceUrl = sourceUrl,
                    relatedTopics = topics
                )
            }

            // 2. Fallback to Wikipedia REST summary for encyclopedic queries
            val wikiSlug = URLEncoder.encode(cleanQuery.replace(" ", "_"), "UTF-8")
            val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$wikiSlug"
            val wikiRes = runCatching { executeGet(wikiUrl) }.getOrNull()
            val wikiExtract = wikiRes?.optString("extract", "").orEmpty()
            val wikiPageUrl = wikiRes?.optJSONObject("content_urls")
                ?.optJSONObject("desktop")
                ?.optString("page", "").orEmpty()

            if (wikiExtract.isNotBlank()) {
                return@runCatching WebSearchResult(
                    query = cleanQuery,
                    heading = wikiRes?.optString("title", cleanQuery) ?: cleanQuery,
                    summary = wikiExtract,
                    sourceUrl = wikiPageUrl,
                    relatedTopics = topics
                )
            }

            WebSearchResult(
                query = cleanQuery,
                heading = heading,
                summary = if (topics.isNotEmpty()) {
                    topics.joinToString("\n• ", prefix = "• ")
                } else {
                    "No instant summary returned for '$cleanQuery'."
                },
                sourceUrl = sourceUrl.ifBlank { "https://duckduckgo.com/?q=$encoded" },
                relatedTopics = topics
            )
        }
    }

    private fun executeGet(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "BumbleAI-Android/1.0")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            }
            return JSONObject(body.ifBlank { "{}" })
        }
    }

    private fun mapWmoCode(code: Int): String = when (code) {
        0 -> "Clear Sky"
        1, 2 -> "Partly Cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain Showers"
        85, 86 -> "Snow Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Mixed Conditions ($code)"
    }
}
