// app/src/main/java/com/domain/app/focus/WeatherFocus.kt
package com.domain.app.focus

import com.domain.app.focus.PlacesFocus.withLocation
import com.domain.app.place.Here
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command
import com.domain.app.weather.Forecasts
import com.domain.app.weather.Weather
import com.domain.app.weather.WeatherAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Weather: Open-Meteo's forecast, here or for a place said (docs/anthemos-2.md, "Weather"). */
object WeatherFocus {

    private val forecasts = Forecasts()

    fun focus() = Focus(
        "weather", "The forecast, here or for a place said", listOf(
            command(
                "weather.ask", "The weather today or on a day, rain, how warm",
                listOf("what's the weather like", "will it rain tomorrow", "how hot will it be on Saturday", "what's the weather in Melbourne this weekend"),
                { s -> Weather.parse(s.command, s.now)?.let { VoiceRequest.Weather(s.transcript, it) } }
            ) { ask(it.ask) }
        )
    )

    private suspend fun Turn.ask(ask: WeatherAsk) {
        val place = ask.place ?: return withLocation {
            val here = Here.now(context) ?: return@withLocation reply("I can't tell where you are right now")
            answer(ask) { forecasts.forecast(here.lat, here.lng, null) }
        }
        answer(ask) {
            val (lat, lng, name) = forecasts.find(place)
            forecasts.forecast(lat, lng, name)
        }
    }

    private suspend fun Turn.answer(ask: WeatherAsk, fetch: () -> com.domain.app.weather.Forecast) {
        val forecast = try {
            withContext(Dispatchers.IO) { fetch() }
        } catch (e: Exception) {
            return reply(e.message ?: "I can't reach the weather service")
        }
        reply(Weather.answer(ask, forecast, LocalDate.now()))
    }
}
