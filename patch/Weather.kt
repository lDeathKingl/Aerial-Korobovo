package app.orchard.tv.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Compatible with Aerial Launcher v1.95 Weather public API.
enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }
data class WeatherNow(
    val celsius: Int,
    val kind: WeatherKind,
    val day: Boolean,
    val high: Int? = null,
    val low: Int? = null,
)

/**
 * Weather from wttr.in only. Default position: the village of Korobovo,
 * Leninsky urban district, Moscow Oblast (approximate village centre).
 * Does not call Open-Meteo or its geocoding service.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val DEFAULT_PLACE = "Коробово"
    private const val DEFAULT_LAT = 55.5336f
    private const val DEFAULT_LON = 37.8213f
    private const val PATCH_CONFIGURED = "wttr_korobovo_v2"
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    private const val NONE = Int.MIN_VALUE

    var now by mutableStateOf<WeatherNow?>(null); private set
    var place by mutableStateOf<String?>(null); private set
    var chosen by mutableStateOf(false); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var failed = false
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Only on the first launch of this patch: replace the previously
        // auto-detected Moscow/Chinese city with Korobovo.
        if (!prefs.getBoolean(PATCH_CONFIGURED, false)) {
            prefs.edit {
                putBoolean(PATCH_CONFIGURED, true)
                putBoolean("chosen", true)
                putFloat("lat", DEFAULT_LAT)
                putFloat("lon", DEFAULT_LON)
                putString("place", DEFAULT_PLACE)
                putBoolean("checked_against_cities", true)
                remove("fetched_at")
            }
        }
        place = prefs.getString("place", DEFAULT_PLACE)
        chosen = prefs.getBoolean("chosen", true)
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching {
                WeatherKind.valueOf(prefs.getString("kind", null) ?: "")
            }.getOrNull()
            if (kind != null) now = WeatherNow(
                celsius = prefs.getInt("celsius", 0),
                kind = kind,
                day = prefs.getBoolean("day", true),
                high = prefs.getInt("high", NONE).takeIf { it != NONE },
                low = prefs.getInt("low", NONE).takeIf { it != NONE },
            )
        }
    }

    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastAttempt
        if (since >= REFRESH_MS || (failed && (networkCameUp || since >= RETRY_MS))) {
            refresh()
        }
    }

    private fun refresh() {
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching { fetch() }
                    .onFailure { Log.w(TAG, "wttr.in weather unavailable", it) }
                    .getOrNull()
            }
            place = prefs.getString("place", DEFAULT_PLACE)
            failed = reading == null
            if (reading != null) {
                now = reading
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                }
            } else if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) >= STALE_MS) {
                now = null
            }
        }
    }

    private fun fetch(): WeatherNow {
        var lat = prefs.getFloat("lat", DEFAULT_LAT)
        var lon = prefs.getFloat("lon", DEFAULT_LON)
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        // Only if the owner explicitly taps "Use network location".
        if (!prefs.getBoolean("chosen", true) && guessedLongAgo) {
            locate()?.let { guess ->
                lat = guess.lat
                lon = guess.lon
                prefs.edit {
                    putFloat("lat", lat)
                    putFloat("lon", lon)
                    putString("place", guess.city ?: DEFAULT_PLACE)
                    putLong("located_at", System.currentTimeMillis())
                }
            }
        }
        if (lat.isNaN() || lon.isNaN()) {
            lat = DEFAULT_LAT
            lon = DEFAULT_LON
        }
        val json = JSONObject(get("https://wttr.in/$lat,$lon?format=j2"))
        return parseWeather(json)
    }

    private fun parseWeather(json: JSONObject): WeatherNow {
        val current = json.getJSONArray("current_condition").getJSONObject(0)
        val temp = current.getString("temp_C").toDouble().roundToInt()
        val code = current.optString("weatherCode").toIntOrNull()
        val today = json.optJSONArray("weather")?.optJSONObject(0)
        val high = today?.optString("maxtempC")?.toDoubleOrNull()?.roundToInt()
        val low = today?.optString("mintempC")?.toDoubleOrNull()?.roundToInt()
        val sun = today?.optJSONArray("astronomy")?.optJSONObject(0)
        return WeatherNow(
            celsius = temp,
            kind = kindOfWttr(code),
            day = isDay(sun),
            high = high,
            low = low,
        )
    }

    /** WorldWeatherOnline-style codes returned by wttr.in, not WMO codes. */
    private fun kindOfWttr(code: Int?): WeatherKind = when (code) {
        113 -> WeatherKind.Clear
        116 -> WeatherKind.PartlyCloudy
        119, 122 -> WeatherKind.Cloudy
        143, 248, 260 -> WeatherKind.Fog
        179, 182, 185, 227, 230, 281, 284, 323, 326, 329, 332,
        335, 338, 350, 362, 365, 368, 371, 374, 377, 392, 395 -> WeatherKind.Snow
        200, 386, 389 -> WeatherKind.Thunder
        else -> WeatherKind.Rain
    }

    private fun isDay(sun: JSONObject?): Boolean {
        if (sun == null) return true
        return runCatching {
            val formatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
            val sunrise = LocalTime.parse(sun.getString("sunrise"), formatter)
            val sunset = LocalTime.parse(sun.getString("sunset"), formatter)
            val current = LocalTime.now(ZoneId.of("Europe/Moscow"))
            !current.isBefore(sunrise) && current.isBefore(sunset)
        }.getOrDefault(true)
    }

    private data class Guess(val lat: Float, val lon: Float, val city: String?)
    private fun locate(): Guess? {
        return runCatching {
            val data = JSONObject(get("https://ipwho.is/"))
            if (!data.optBoolean("success", true)) return null
            Guess(
                data.getDouble("latitude").toFloat(),
                data.getDouble("longitude").toFloat(),
                data.optString("city").ifBlank { null },
            )
        }.onFailure { Log.w(TAG, "could not locate network", it) }.getOrNull()
    }

    /** Called by the existing phone-settings page; no Open-Meteo lookup. */
    suspend fun choose(name: String): Boolean {
        val typed = name.trim()
        if (typed.isEmpty()) return false
        val korobovo = typed.equals("Коробово", ignoreCase = true) ||
            typed.equals("Korobovo", ignoreCase = true)
        val position: Pair<Float, Float> = if (korobovo) {
            DEFAULT_LAT to DEFAULT_LON
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val encoded = URLEncoder.encode(typed, "UTF-8")
                    val json = JSONObject(get("https://wttr.in/$encoded?format=j2"))
                    // Validate weather response before changing the saved location.
                    json.getJSONArray("current_condition").getJSONObject(0).getString("temp_C")
                    val nearest = json.getJSONArray("nearest_area").getJSONObject(0)
                    nearest.getString("latitude").toFloat() to
                        nearest.getString("longitude").toFloat()
                }.onFailure { Log.w(TAG, "wttr.in did not find $typed", it) }.getOrNull()
            } ?: return false
        }
        prefs.edit {
            putFloat("lat", position.first)
            putFloat("lon", position.second)
            putString("place", if (korobovo) DEFAULT_PLACE else typed)
            putBoolean("chosen", true)
            putBoolean("checked_against_cities", true)
            remove("fetched_at")
        }
        chosen = true
        place = prefs.getString("place", DEFAULT_PLACE)
        now = null
        refresh()
        return true
    }

    /** Preserve the original phone page's "Use network location" action. */
    fun useNetworkLocation() {
        prefs.edit {
            putBoolean("chosen", false)
            remove("lat")
            remove("lon")
            remove("located_at")
            remove("fetched_at")
        }
        chosen = false
        now = null
        refresh()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        // wttr.in can stream JSON slowly on some networks; keep it off the UI thread.
        c.readTimeout = 45_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Aerial-Korobovo/1.0 (Android TV weather)")
        return try {
            val status = c.responseCode
            if (status !in 200..299) error("wttr.in HTTP $status")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
