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
 * [콜 확인]: 상세 화면의 출발지·도착지로 실제 도로(차량) 거리·시간을 카카오에 물어본다.
 *   픽업 = 내 위치 → 출발지, 운행 = 출발지 → 도착지
 * 보내는 것: 출발지·도착지 이름, 내 위치 좌표뿐 (금액 등 다른 콜 내용은 보내지 않음)
 */
object KakaoRoute {
    data class Point(val x: Double, val y: Double, val name: String)
    data class Leg(val meters: Int, val seconds: Int) {
        val minutes: Int get() = Math.round(seconds / 60.0).toInt()
        val km: String get() = String.format(java.util.Locale.US, "%.1fkm", meters / 1000.0)
    }
    data class Result(
        val detail: CallDetail,
        val pickup: Leg?,            // 위치를 못 잡으면 null
        val ride: Leg,
        val pickupNote: String?,     // 픽업을 못 구한 이유
        val origin: Point,
        val dest: Point,
    ) {
        val totalMin: Int get() = (pickup?.minutes ?: 0) + ride.minutes
        val perMinute: Double? get() = if (totalMin > 0) detail.price / totalMin else null
    }

    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun check(ctx: Context, detail: CallDetail, key: String, done: (Result?, String?) -> Unit) {
        val app = ctx.applicationContext
        pool.execute {
            val out = runCatching {
                if (key.isBlank()) throw IllegalStateException("카카오 키가 없어요. 콜 계산기 설정 → 콜 확인에서 넣어 주세요")
                // 내 위치와 좌표 찾기를 동시에
                val locF = pool.submit<Location?> { currentLocation(app) }
                val loc = runCatching { locF.get(5, TimeUnit.SECONDS) }.getOrNull()
                val near = loc?.let { Point(it.longitude, it.latitude, "내 위치") }
                val oF = pool.submit<Point?> { geocode(detail.origin, key, near) }
                val o = oF.get(8, TimeUnit.SECONDS) ?: throw IllegalStateException("출발지를 지도에서 못 찾았어요: ${detail.origin}")
                val d = geocode(detail.destination, key, o) ?: throw IllegalStateException("도착지를 지도에서 못 찾았어요: ${detail.destination}")
                // 두 구간 길찾기를 동시에
                val rideF = pool.submit<Leg?> { directions(o, d, key) }
                val pickF = pool.submit<Leg?> { near?.let { directions(it, o, key) } }
                val ride = rideF.get(8, TimeUnit.SECONDS) ?: throw IllegalStateException("운행 길찾기에 실패했어요")
                val pick = runCatching { pickF.get(8, TimeUnit.SECONDS) }.getOrNull()
                val note = when {
                    near == null && !hasLocationPermission(app) -> "위치 권한이 없어서 픽업은 빼고 계산했어요"
                    near == null -> "내 위치를 못 잡아서 픽업은 빼고 계산했어요"
                    pick == null -> "픽업 길찾기에 실패해서 빼고 계산했어요"
                    else -> null
                }
                Result(detail, pick, ride, note, o, d)
            }
            main.post {
                out.onSuccess { done(it, null) }.onFailure { done(null, it.message ?: "계산에 실패했어요") }
            }
        }
    }

    /** 픽업 거리 결과. road 가 null 이면 추정값(직선 × 1.3) */
    data class Pickup(
        val straightKm: Double?,   // 직선 거리 (내 위치 → 출발지, 또는 콜 앱이 알려 준 값)
        val roadKm: Double?,       // 실제 도로 거리 (카카오 길찾기)
        val roadMin: Int?,
        val origin: String,        // 지도에서 찾은 출발지 이름
        val note: String?,         // 추정인 이유 등
    ) {
        val estimatedKm: Double? get() = straightKm?.let { it * DETOUR }
    }

    /** 도시에서 실제 도로 거리는 보통 직선의 1.2~1.4배 */
    const val DETOUR = 1.3

    /**
     * [콜 확인]: 내 위치 → 출발지 도로 거리(km)만.
     * 길찾기가 막혀 있으면 (사용 신청 승인 전) 직선 × 1.3 으로 추정.
     */
    fun pickup(ctx: Context, detail: CallDetail, key: String, done: (Pickup?, String?) -> Unit) {
        val app = ctx.applicationContext
        pool.execute {
            val out: Result<Pickup> = runCatching {
                val loc = currentLocation(app)
                val me = loc?.let { Point(it.longitude, it.latitude, "내 위치") }
                // 키가 없거나, 위치를 못 잡으면 → 콜 앱이 보여 준 직선 거리로 추정만
                if (key.isBlank() || me == null) {
                    val why = when {
                        key.isBlank() -> "카카오 키가 없어서 추정값이에요 (설정 → 콜 확인에서 키 넣기)"
                        !hasLocationPermission(app) -> "위치 권한이 없어서 추정값이에요"
                        else -> "내 위치를 못 잡아서 추정값이에요"
                    }
                    if (detail.pickupKm == null) throw Problem(why.replace("추정값이에요", "거리를 알 수 없어요"))
                    return@runCatching Pickup(detail.pickupKm, null, null, detail.origin, why)
                }
                val o = try {
                    geocode(detail.origin, key, me)
                } catch (e: Problem) {
                    throw Problem("지도 검색(출발지 찾기)이 막혔어요 · ${e.message}")
                } ?: throw Problem("출발지를 지도에서 못 찾았어요: ${detail.origin}")
                val straight = distanceKm(me, o)
                val leg = try {
                    directions(me, o, key)
                } catch (e: Problem) {
                    return@runCatching Pickup(straight, null, null, o.name,
                        "카카오 길찾기 사용 승인 전이라 추정값이에요 (${e.code})")
                }
                if (leg == null) Pickup(straight, null, null, o.name, "길찾기에 실패해서 추정값이에요")
                else Pickup(straight, leg.meters / 1000.0, leg.minutes, o.name, null)
            }
            main.post {
                out.onSuccess { done(it, null) }
                    .onFailure { done(null, (it as? Problem)?.message ?: "계산에 실패했어요") }
            }
        }
    }

    /** 사용자에게 보여 줄 문제 (개발용 글자 없이). code 는 HTTP 상태 */
    class Problem(msg: String, val code: String = "") : Exception(msg)

    /** 두 점 사이 직선 거리 km */
    fun distanceKm(a: Point, b: Point): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.y - a.y)
        val dLon = Math.toRadians(b.x - a.x)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(a.y)) * Math.cos(Math.toRadians(b.y)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    /** 장소 이름 → 좌표. 가게·아파트 이름으로 먼저, 안 되면 점점 짧게 (동 이름까지) */
    private fun geocode(place: String, key: String, near: Point?): Point? {
        for (q in DetailParser.searchQueries(place)) {
            val enc = URLEncoder.encode(q, "UTF-8")
            val nearQ = near?.let { "&x=${it.x}&y=${it.y}&radius=20000" } ?: ""
            val kw = get("https://dapi.kakao.com/v2/local/search/keyword.json?size=1&query=$enc$nearQ", key)
                ?: if (near != null) get("https://dapi.kakao.com/v2/local/search/keyword.json?size=1&query=$enc", key) else null
            firstPoint(kw)?.let { return it }
            if (near != null) {
                firstPoint(get("https://dapi.kakao.com/v2/local/search/keyword.json?size=1&query=$enc", key))?.let { return it }
            }
            firstPoint(get("https://dapi.kakao.com/v2/local/search/address.json?size=1&query=$enc", key))?.let { return it }
        }
        return null
    }

    private fun firstPoint(json: JSONObject?): Point? {
        val docs = json?.optJSONArray("documents") ?: return null
        if (docs.length() == 0) return null
        val d = docs.getJSONObject(0)
        val name = d.optString("place_name").ifEmpty { d.optString("address_name") }
        return Point(d.getString("x").toDouble(), d.getString("y").toDouble(), name)
    }

    /** 카카오모빌리티 길찾기 (차량, 추천 경로) */
    private fun directions(o: Point, d: Point, key: String): Leg? {
        val j = get(
            "https://apis-navi.kakaomobility.com/v1/directions?origin=${o.x},${o.y}&destination=${d.x},${d.y}&priority=RECOMMEND&summary=true",
            key,
        ) ?: return null
        val r = j.optJSONArray("routes")?.optJSONObject(0) ?: return null
        if (r.optInt("result_code", -1) != 0) {
            // 출발지와 도착지가 너무 가까우면 (5m 이내) 0분
            return if (r.optInt("result_code") == 104) Leg(0, 0) else null
        }
        val sum = r.optJSONObject("summary") ?: return null
        return Leg(sum.optInt("distance"), sum.optInt("duration"))
    }

    private fun get(url: String, key: String): JSONObject? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 6000
        c.setRequestProperty("Authorization", "KakaoAK $key")
        val code = c.responseCode
        if (code == 401 || code == 403) {
            c.disconnect()
            throw Problem("카카오가 거부했어요 (HTTP $code)", "HTTP $code")
        }
        if (code != 200) { c.disconnect(); return@runCatching null }
        val body = c.inputStream.bufferedReader().readText()
        c.disconnect()
        JSONObject(body)
    }.getOrElse { if (it is Problem) throw it else null }

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
