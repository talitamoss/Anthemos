// app/src/main/java/com/domain/app/weather/Forecasts.kt
package com.domain.app.weather

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Forecasts from Open-Meteo: free, no account, nothing kept about who asks
 * (docs/anthemos-2.md, "Weather"). The phone's position is sent rounded to
 * about a kilometre; a place said by name is looked up first.
 */
class Forecasts {

    class WeatherError(message: String) : Exception(message)

    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    /** Where [name] is, and what it's called. */
    fun find(name: String): Triple<Double, Double, String> {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", name).addQueryParameter("count", "1").addQueryParameter("language", "en").build()
        val first = JSONObject(get(url.toString())).optJSONArray("results")?.optJSONObject(0)
            ?: throw WeatherError("I can't find a place called $name")
        return Triple(first.getDouble("latitude"), first.getDouble("longitude"), first.getString("name"))
    }

    fun forecast(lat: Double, lng: Double, place: String?): Forecast {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", round(lat))
            .addQueryParameter("longitude", round(lng))
            .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "7")
            .build()
        val json = JSONObject(get(url.toString()))
        val current = json.optJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            DayForecast(
                date = LocalDate.parse(dates.getString(i)),
                code = daily.getJSONArray("weather_code").optInt(i),
                min = daily.getJSONArray("temperature_2m_min").optDouble(i),
                max = daily.getJSONArray("temperature_2m_max").optDouble(i),
                rainChance = daily.getJSONArray("precipitation_probability_max").optInt(i),
                rainMm = daily.getJSONArray("precipitation_sum").optDouble(i).takeIf { !it.isNaN() } ?: 0.0
            )
        }
        return Forecast(
            place = place,
            nowTemp = current?.optDouble("temperature_2m")?.takeIf { !it.isNaN() },
            nowFeels = current?.optDouble("apparent_temperature")?.takeIf { !it.isNaN() },
            nowCode = current?.optInt("weather_code"),
            days = days
        )
    }

    private fun round(d: Double) = ((d * 100).roundToInt() / 100.0).toString()

    private fun get(url: String): String = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw WeatherError("The weather service said ${response.code}")
            response.body?.string().orEmpty()
        }
    } catch (e: IOException) {
        throw WeatherError("I can't reach the weather service")
    }
}
