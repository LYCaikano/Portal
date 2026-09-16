package moe.fuqiuluo.portal.ext

import com.baidu.location.BDLocation
import com.baidu.location.Jni
import com.baidu.mapapi.model.LatLng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random

val LatLng.wgs84: Pair<Double, Double>
    get() = Loc4j.gcj2wgs(latitude, longitude)

val BDLocation.wgs84: Pair<Double, Double>
    get() = Loc4j.gcj2wgs(latitude, longitude)

val Pair<Double, Double>.gcj02: LatLng
    get() = Loc4j.wgs2gcj(first, second).let { LatLng(it.first, it.second) }

object Loc4j {
    fun gcj2wgs(lat: Double, lon: Double): Pair<Double, Double> {
        return Jni.coorEncrypt(lon, lat, "gcj2wgs").let { it[1] to it[0] }
    }

    fun wgs2gcj(lat: Double, lon: Double): Pair<Double, Double> {
        return Jni.coorEncrypt(lon, lat, "gps2gcj").let { it[1] to it[0] }
    }
}

/**
 * 对 WGS84 坐标(first=纬度, second=经度)施加随机偏移。
 * 经纬度各自在 [-offsetCm, +offsetCm] 厘米内随机取值，offsetCm <= 0 时不偏移。
 */
fun Pair<Double, Double>.randomOffset(offsetCm: Int): Pair<Double, Double> {
    if (offsetCm <= 0) return this
    val dLatMeters = Random.nextDouble(-offsetCm.toDouble(), offsetCm.toDouble()) / 100.0
    val dLonMeters = Random.nextDouble(-offsetCm.toDouble(), offsetCm.toDouble()) / 100.0
    val newLat = first + dLatMeters / 111320.0
    val cosLat = cos(Math.toRadians(first))
    val newLon = if (abs(cosLat) < 1e-12) {
        second
    } else {
        second + dLonMeters / (111320.0 * cosLat)
    }
    return newLat to newLon
}