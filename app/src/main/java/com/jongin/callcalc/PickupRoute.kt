package com.jongin.callcalc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jongin.callcalc.core.CallDetail
import com.jongin.callcalc.core.DetailParser
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [콜 확인]: 내 위치 → 출발지 픽업 거리.
 * TMAP (SK오픈API) 장소검색으로 출발지 좌표를 찾고, TMAP 자동차 경로안내(실시간 교통)로 도로 거리·시간을 구한다.
 * 키가 없거나 막히면 직선 거리 × 1.3 으로 추정.
 * 보내는 것: 출발지 이름, 내 위치 좌표뿐 (금액 등 다른 콜 내용은 보내지 않음)
 */
object PickupRoute {
    data class Point(val x: Double, val y: Double, val name: String) // x = 경도, y = 위도

    /** 픽업 거리 결과. roadKm 이 null 이면 추정값 */
    data class Pickup(
        val straightKm: Double?,
        val roadKm: Double?,
        val roadMin: Int?,
        val origin: String,
        val note: String?,
    ) {
        val estimatedKm: Double? get() = straightKm?.let { it * DETOUR }
    }

    /** 도시에서 실제 도로 거리는 보통 직선의 1.2~1.4배 */
    const val DETOUR = 1.3

    /** 사용자에게 보여 줄 문제 (개발용 글자 없이) */
    class Problem(msg: String) : Exception(msg)

    private const val API = "https://apis.openapi.sk.com/tmap"
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun pickup(ctx: Context, detail: CallDetail, key: String, done: (Pickup?, String?) -> Unit) {
        val app = ctx.applicationContext
        pool.execute {
            val out: kotlin.Result<Pickup> = runCatching {
                val loc = currentLocation(app)
                val me = loc?.let { Point(it.longitude, it.latitude, "내 위치") }
                if (key.isBlank() || me == null) {
                    val why = when {
                        key.isBlank() -> "TMAP 키가 없어서 추정값이에요 (설정 → 콜 확인에서 키 넣기)"
                        !hasLocationPermission(app) -> "위치 권한이 없어서 추정값이에요"
                        else -> "내 위치를 못 잡아서 추정값이에요"
                    }
                    if (detail.pickupKm == null) throw Problem(why.replace("추정값이에요", "거리를 알 수 없어요"))
                    return@runCatching Pickup(detail.pickupKm, null, null, detail.origin, why)
                }
                val o = try {
                    findPlace(detail.origin, key, me)
                } catch (e: Problem) {
                    // 장소검색이 막히면: 콜 앱이 알려 준 직선 거리로 추정
                    if (detail.pickupKm != null) return@runCatching Pickup(detail.pickupKm, null, null, detail.origin, "TMAP 장소검색 실패 (${e.message}) → 추정값이에요")
                    throw Problem("TMAP 장소검색 실패 · ${e.message}")
                } ?: run {
                    if (detail.pickupKm != null) return@runCatching Pickup(detail.pickupKm, null, null, detail.origin, "출발지를 지도에서 못 찾아서 추정값이에요")
                    throw Problem("출발지를 지도에서 못 찾았어요: ${detail.origin}")
                }
                val straight = distanceKm(me, o)
                val leg = try { carRoute(me, o, key) } catch (e: Problem) {
                    return@runCatching Pickup(straight, null, null, o.name, "TMAP 길찾기 실패 (${e.message}) → 추정값이에요")
                }
                if (leg == null) Pickup(straight, null, null, o.name, "길찾기에 실패해서 추정값이에요")
                else Pickup(straight, leg.first / 1000.0, Math.round(leg.second / 60.0).toInt(), o.name, null)
            }
            main.post {
                out.onSuccess { done(it, null) }
                    .onFailure { done(null, (it as? Problem)?.message ?: "계산에 실패했어요") }
            }
        }
    }

    /** TMAP 장소검색: 가게·건물 이름으로 먼저, 안 되면 점점 짧게 (동 이름까지). 내 위치 근처를 우선 */
    private fun findPlace(place: String, key: String, near: Point): Point? {
        for (q in DetailParser.searchQueries(place)) {
            val url = "$API/pois?version=1&count=1&searchKeyword=${URLEncoder.encode(q, "UTF-8")}" +
                "&centerLon=${near.x}&centerLat=${near.y}&reqCoordType=WGS84GEO&resCoordType=WGS84GEO"
            val j = request(url, key, null) ?: continue
            val poi = j.optJSONObject("searchPoiInfo")?.optJSONObject("pois")?.optJSONArray("poi")?.optJSONObject(0) ?: continue
            val lat = poi.optString("frontLat").ifEmpty { poi.optString("noorLat") }.toDoubleOrNull() ?: continue
            val lon = poi.optString("frontLon").ifEmpty { poi.optString("noorLon") }.toDoubleOrNull() ?: continue
            return Point(lon, lat, poi.optString("name").ifEmpty { q })
        }
        return null
    }

    /** TMAP 자동차 경로안내 (추천 경로, 교통 정보 반영). (거리 m, 시간 초) */
    private fun carRoute(o: Point, d: Point, key: String): Pair<Int, Int>? {
        val body = JSONObject()
            .put("startX", o.x.toString()).put("startY", o.y.toString())
            .put("endX", d.x.toString()).put("endY", d.y.toString())
            .put("reqCoordType", "WGS84GEO").put("resCoordType", "WGS84GEO")
            .put("searchOption", "0")
            .put("totalValue", 2) // 합계(거리·시간)만 받기
        val j = request("$API/routes?version=1", key, body.toString()) ?: return null
        val props = j.optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties") ?: return null
        val dist = props.optInt("totalDistance", -1)
        val time = props.optInt("totalTime", -1)
        return if (dist < 0 || time < 0) null else dist to time
    }

    /** body 가 있으면 POST(JSON). 결과 없음(204)이면 null */
    private fun request(url: String, key: String, body: String?): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 6000
            c.setRequestProperty("appKey", key)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            when (code) {
                200 -> return JSONObject(c.inputStream.bufferedReader().readText())
                204 -> return null
                401, 403 -> throw Problem("키가 거부됨, HTTP $code · 키와 TMAP 상품 신청을 확인해 주세요")
                429 -> throw Problem("오늘 무료 사용량을 다 썼어요, HTTP 429")
                else -> throw Problem("HTTP $code")
            }
        } catch (e: Problem) {
            throw e
        } catch (e: Exception) {
            throw Problem("인터넷 연결을 확인해 주세요")
        } finally {
            c.disconnect()
        }
    }

    /** 두 점 사이 직선 거리 km */
    fun distanceKm(a: Point, b: Point): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.y - a.y)
        val dLon = Math.toRadians(b.x - a.x)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(a.y)) * Math.cos(Math.toRadians(b.y)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    fun hasLocationPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 지금 위치. 2분 안의 마지막 위치가 있으면 그걸, 없으면 새로 잡기 (최대 4초) */
    @SuppressLint("MissingPermission")
    private fun currentLocation(ctx: Context): Location? {
        if (!hasLocationPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 120_000 }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent
        val provider = when {
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull() ?: return null
        }
        val latch = CountDownLatch(1)
        var got: Location? = null
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, pool) { got = it; latch.countDown() }
        } else {
            @Suppress("DEPRECATION")
            main.post { lm.requestSingleUpdate(provider, { got = it; latch.countDown() }, Looper.getMainLooper()) }
        }
        latch.await(4, TimeUnit.SECONDS)
        return got ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }
}
