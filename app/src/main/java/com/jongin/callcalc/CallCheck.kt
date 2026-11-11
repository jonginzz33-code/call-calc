package com.jongin.callcalc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.jongin.callcalc.core.CallDetail
import com.jongin.callcalc.core.DetailParser
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * [콜 확인] 계산: ① 내 위치 → 출발지(픽업), ② 출발지 → 도착지(운행), 총 시간, 분당 금액.
 * TMAP(SK오픈API) 장소검색 + 자동차 경로안내(실시간 교통).
 * 빠르게: 출발지·도착지 찾기를 동시에, 두 구간 길찾기도 동시에. 나오는 대로 onUpdate 로 알려 준다.
 * 보내는 것: 출발지·도착지 이름, 내 위치 좌표뿐 (금액 등은 보내지 않음)
 */
object CallCheck {
    data class Point(val x: Double, val y: Double, val name: String) // x 경도, y 위도

    /** 한 구간. estimated = true 면 추정값 */
    data class Leg(val km: Double?, val min: Int?, val estimated: Boolean, val note: String? = null)

    data class Result(
        val detail: CallDetail,
        val originName: String,
        val destName: String,
        val pickup: Leg?,    // null = 아직 계산 중
        val ride: Leg?,
        val done: Boolean,
    ) {
        val totalMin: Int? get() {
            val p = pickup?.min ?: return null
            val r = ride?.min ?: return null
            return p + r
        }
        val perMinute: Double? get() = totalMin?.takeIf { it > 0 }?.let { detail.price / it }
    }

    /** 도시에서 실제 도로 거리는 보통 직선의 1.2~1.4배 */
    const val DETOUR = 1.3

    class Problem(msg: String) : Exception(msg)

    private const val API = "https://apis.openapi.sk.com/tmap"
    private val pool = Executors.newFixedThreadPool(6)
    private val main = Handler(Looper.getMainLooper())

    /**
     * 계산 시작. onUpdate 는 메인 스레드에서 여러 번 (픽업 먼저, 그다음 운행) 불린다. 마지막은 done = true.
     * minPerKm: 추정할 때 쓰는 픽업 1km당 분 (설정값)
     */
    fun run(detail: CallDetail, key: String, me: Location?, minPerKm: Double, onUpdate: (Result) -> Unit) {
        pool.execute {
            var cur = Result(detail, detail.origin, detail.destination, null, null, false)
            fun post(r: Result) { cur = r; main.post { onUpdate(r) } }

            val my = me?.let { Point(it.longitude, it.latitude, "내 위치") }
            // 키가 없으면 전부 추정
            if (key.isBlank()) {
                post(cur.copy(pickup = estimatePickup(detail, null, minPerKm, "TMAP 키가 없어서 추정 (설정 → 콜 확인)"),
                    ride = estimateRide(detail, "TMAP 키가 없어서 콜 앱 안내 시간"), done = true))
                return@execute
            }
            // 출발지·도착지 찾기 동시에
            val oF = pool.submit<Point?> { findPlace(detail.origin, key, my) }
            val dF = pool.submit<Point?> { findPlace(detail.destination, key, my) }
            val (o, oErr) = get(oF)
            // ① 픽업 길찾기 (출발지 찾자마자 시작)
            val pickF: Future<Pair<Int, Int>?>? = if (o != null && my != null) pool.submit<Pair<Int, Int>?> { carRoute(my, o, key) } else null
            val (d, dErr) = get(dF)
            // ② 운행 길찾기
            val rideF: Future<Pair<Int, Int>?>? = if (o != null && d != null) pool.submit<Pair<Int, Int>?> { carRoute(o, d, key) } else null
            cur = cur.copy(originName = o?.name ?: detail.origin, destName = d?.name ?: detail.destination)

            // ① 결과 먼저 보여 주기
            val pickup = when {
                my == null -> estimatePickup(detail, null, minPerKm,
                    if (!hasLocationPermission()) "위치 권한이 없어서 추정" else "내 위치를 못 잡아서 추정")
                o == null -> estimatePickup(detail, null, minPerKm, oErr ?: "출발지를 지도에서 못 찾아서 추정")
                else -> {
                    val (r, err) = get(pickF!!)
                    if (r != null) Leg(r.first / 1000.0, Math.round(r.second / 60.0).toInt(), false)
                    else estimatePickup(detail, distanceKm(my, o), minPerKm, err ?: "픽업 길찾기 실패로 추정")
                }
            }
            post(cur.copy(pickup = pickup))

            // ② 운행
            val ride = when {
                o == null || d == null -> estimateRide(detail, (dErr ?: oErr) ?: "도착지를 지도에서 못 찾아서 콜 앱 안내 시간")
                else -> {
                    val (r, err) = get(rideF!!)
                    if (r != null) Leg(r.first / 1000.0, Math.round(r.second / 60.0).toInt(), false)
                    else estimateRide(detail, err ?: "운행 길찾기 실패로 콜 앱 안내 시간")
                }
            }
            post(cur.copy(pickup = pickup, ride = withVia(detail, ride), done = true))
        }
    }

    /**
     * 경유지가 있는 콜: TMAP 은 출발 → 도착 직행만 계산하니까,
     * 콜 앱이 안내한 시간이 더 길면 그걸 쓴다.
     */
    private fun withVia(detail: CallDetail, ride: Leg): Leg {
        if (!detail.hasVia) return ride
        val app = detail.rideMin?.toInt()
        return if (app != null && (ride.min == null || app > ride.min))
            ride.copy(min = app, note = listOfNotNull("경유지 있음 · 콜 앱 안내 ${app}분 사용 (더 김)", ride.note?.takeIf { ride.estimated }).joinToString(" · "))
        else ride.copy(note = listOfNotNull("경유지 있음 · 직행 기준", ride.note).joinToString(" · "))
    }

    /** 픽업 추정: 직선 거리(내가 잰 것 또는 콜 앱이 알려 준 것) × 1.3, 시간은 직선 km × 1km당 분 */
    private fun estimatePickup(detail: CallDetail, straightKm: Double?, minPerKm: Double, why: String): Leg {
        val s = straightKm ?: detail.pickupKm
            ?: return Leg(null, null, true, "$why · 거리를 알 수 없어요")
        return Leg(s * DETOUR, Math.round(s * minPerKm).toInt(), true, why)
    }

    /** 운행 추정: 콜 앱이 알려 준 "약 ○분 운행" */
    private fun estimateRide(detail: CallDetail, why: String): Leg =
        Leg(null, detail.rideMin?.toInt(), true, if (detail.rideMin == null) "$why · 시간을 알 수 없어요" else why)

    /** Future 결과와 사용자용 오류 문구 */
    private fun <T> get(f: Future<T>): Pair<T?, String?> = try {
        f.get(10, TimeUnit.SECONDS) to null
    } catch (e: ExecutionException) {
        null to ((e.cause as? Problem)?.message ?: "계산 실패")
    } catch (e: Exception) {
        null to "시간 초과"
    }

    /** TMAP 장소검색: 가게·건물 이름으로 먼저, 안 되면 점점 짧게 (동 이름까지). 내 위치 근처 우선 */
    private fun findPlace(place: String, key: String, near: Point?): Point? {
        for (q in DetailParser.searchQueries(place)) {
            val c = near?.let { "&centerLon=${it.x}&centerLat=${it.y}" } ?: ""
            val url = "$API/pois?version=1&count=1&searchKeyword=${URLEncoder.encode(q, "UTF-8")}$c" +
                "&reqCoordType=WGS84GEO&resCoordType=WGS84GEO"
            val j = request(url, key, null) ?: continue
            val poi = j.optJSONObject("searchPoiInfo")?.optJSONObject("pois")?.optJSONArray("poi")?.optJSONObject(0) ?: continue
            val lat = poi.optString("frontLat").ifEmpty { poi.optString("noorLat") }.toDoubleOrNull() ?: continue
            val lon = poi.optString("frontLon").ifEmpty { poi.optString("noorLon") }.toDoubleOrNull() ?: continue
            return Point(lon, lat, poi.optString("name").ifEmpty { q })
        }
        return null
    }

    /** TMAP 자동차 경로안내 (추천, 교통 반영). (거리 m, 시간 초) */
    private fun carRoute(o: Point, d: Point, key: String): Pair<Int, Int>? {
        val body = JSONObject()
            .put("startX", o.x.toString()).put("startY", o.y.toString())
            .put("endX", d.x.toString()).put("endY", d.y.toString())
            .put("reqCoordType", "WGS84GEO").put("resCoordType", "WGS84GEO")
            .put("searchOption", "0")
            .put("totalValue", 2)
        val j = request("$API/routes?version=1", key, body.toString()) ?: return null
        val props = j.optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties") ?: return null
        val dist = props.optInt("totalDistance", -1)
        val time = props.optInt("totalTime", -1)
        return if (dist < 0 || time < 0) null else dist to time
    }

    /** 403·429 는 잠깐 뒤 한 번 더 (동시에 여러 번 부르면 순간적으로 막힐 수 있어서) */
    private fun request(url: String, key: String, body: String?): JSONObject? = try {
        requestOnce(url, key, body)
    } catch (e: Retry) {
        Thread.sleep(400)
        try { requestOnce(url, key, body) } catch (e2: Retry) { throw Problem(e2.message ?: "TMAP 거부") }
    }

    private class Retry(msg: String) : Exception(msg)

    private fun requestOnce(url: String, key: String, body: String?): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000
            c.readTimeout = 5000
            c.setRequestProperty("appKey", key)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            return when (val code = c.responseCode) {
                200 -> JSONObject(c.inputStream.bufferedReader().readText())
                204 -> null
                401, 403, 429 -> throw Retry("TMAP 거부 (HTTP $code${errorMessage(c)})")
                else -> throw Problem("TMAP 오류 (HTTP $code${errorMessage(c)})")
            }
        } catch (e: Problem) {
            throw e
        } catch (e: Retry) {
            throw e
        } catch (e: Exception) {
            throw Problem("인터넷 연결 확인")
        } finally {
            c.disconnect()
        }
    }

    /** TMAP 이 보낸 오류 설명 (원인을 알 수 있게 짧게) */
    private fun errorMessage(c: HttpURLConnection): String = runCatching {
        val t = c.errorStream?.bufferedReader()?.readText().orEmpty()
        val j = JSONObject(t)
        val e = j.optJSONObject("error") ?: j
        val msg = listOf(e.optString("code"), e.optString("message")).filter { it.isNotBlank() }.joinToString(" ")
        if (msg.isBlank()) "" else " · " + msg.take(80)
    }.getOrDefault("")

    fun distanceKm(a: Point, b: Point): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.y - a.y)
        val dLon = Math.toRadians(b.x - a.x)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(a.y)) * Math.cos(Math.toRadians(b.y)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    // ───────── 내 위치 미리 잡아 두기 ─────────

    private var appCtx: Context? = null
    @Volatile var lastLocation: Location? = null
        private set
    private var listening = false
    private val listener = LocationListener { loc ->
        val old = lastLocation
        if (old == null || loc.time >= old.time) lastLocation = loc
    }

    fun hasLocationPermission(ctx: Context? = appCtx): Boolean {
        val c = ctx ?: return false
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /** 감시하는 앱이 떠 있는 동안 30초마다 위치를 잡아 둔다 → [콜 확인] 때 GPS 를 기다리지 않음 */
    @SuppressLint("MissingPermission")
    fun startLocation(ctx: Context) {
        appCtx = ctx.applicationContext
        if (listening || !hasLocationPermission(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java)
        runCatching {
            for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.getLastKnownLocation(p)?.let { listener.onLocationChanged(it) }
                    lm.requestLocationUpdates(p, 30_000L, 20f, listener, Looper.getMainLooper())
                }
            }
            listening = true
        }
    }

    fun stopLocation(ctx: Context) {
        if (!listening) return
        runCatching { ctx.getSystemService(LocationManager::class.java).removeUpdates(listener) }
        listening = false
    }

    /** 지금 쓸 위치: 3분 안에 잡은 것 */
    @SuppressLint("MissingPermission")
    fun freshLocation(ctx: Context): Location? {
        lastLocation?.takeIf { System.currentTimeMillis() - it.time < 180_000 }?.let { return it }
        if (!hasLocationPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 600_000 }
            .maxByOrNull { it.time }
    }
}
