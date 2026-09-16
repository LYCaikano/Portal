package moe.fuqiuluo.portal.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.fuqiuluo.portal.android.coro.CoroutineController
import moe.fuqiuluo.portal.android.coro.CoroutineRouteMock
import moe.fuqiuluo.portal.ext.Loc4j
import moe.fuqiuluo.portal.ext.accuracy
import moe.fuqiuluo.portal.ext.altitude
import moe.fuqiuluo.portal.ext.randomOffset
import moe.fuqiuluo.portal.ext.reportDuration
import moe.fuqiuluo.portal.ext.routeEndSpeed
import moe.fuqiuluo.portal.ext.routeRandomOffset
import moe.fuqiuluo.portal.ext.routeStartSpeed
import moe.fuqiuluo.portal.ext.speed
import moe.fuqiuluo.portal.service.MockServiceHelper
import moe.fuqiuluo.portal.ui.mock.HistoricalLocation
import moe.fuqiuluo.portal.ui.mock.HistoricalRoute
import moe.fuqiuluo.portal.ui.mock.Rocker
import moe.fuqiuluo.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic
import kotlin.math.abs

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private lateinit var rockerJob: Job
    private lateinit var routeMockJob: Job
    var isRockerLocked = false
    var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null

    private var cachedRoute: HistoricalRoute? = null
    private var cachedRouteCumulativeDistances: DoubleArray = DoubleArray(0)

    companion object {
        /**
         * 转弯减速的最大比例（遇到接近180°的急转弯时最多减速50%）
         */
        private const val MAX_TURN_SLOWDOWN = 0.5

        /**
         * 进入转弯减速区的提前距离：按当前速度行驶的秒数
         */
        private const val TURN_SLOWDOWN_SECONDS = 3.0
    }


    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (!::rockerJob.isInitialized || rockerJob.isCancelled) {
            rockerCoroutineController.pause()
            val applicationContext = activity.applicationContext
            rockerJob = GlobalScope.launch {
                do {
                    rockerCoroutineController.controlledCoroutine()
                    val delayTime = applicationContext.reportDuration.toLong().coerceAtLeast(1L)
                    delay(delayTime)

                    CrashReport.setUserSceneTag(applicationContext, 261773)
                    if(!MockServiceHelper.move(locationManager!!, applicationContext.speed * delayTime / 1000.0, FakeLoc.bearing)) {
                        Log.e("MockServiceViewModel", "Failed to move")
                    }

//                    if (MockServiceHelper.broadcastLocation(locationManager!!)) {
//                        Log.d("MockServiceViewModel", "Broadcast location")
//                    } else {
//                        Log.e("MockServiceViewModel", "Failed to broadcast location")
//                    }
                } while (isActive)
            }
        }

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        if (!::routeMockJob.isInitialized || routeMockJob.isCancelled) {
            routeMockCoroutine.pause()
            val applicationContext = activity.applicationContext
            routeMockJob = GlobalScope.launch {
                do {
                    routeMockCoroutine.routeMockCoroutine()
                    val delayTime = applicationContext.reportDuration.toLong().coerceAtLeast(1L)
                    delay(delayTime)
                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        MockServiceHelper.setLocation(
                            locationManager!!,
                            selectedRoute!!.route[0].first,
                            selectedRoute!!.route[0].second
                        )
                        routeStage++
                    }
                    val route = selectedRoute!!.route

                    // 按路线进度计算当前速度：从初始速度到末速度梯度递减
                    // （实际移动距离上仍保留 moveLocation 内置的 ±20% 随机抖动）
                    var tickSpeed = applicationContext.routeStartSpeed
                    if (routeStage < route.size) {
                        val currentLocation = MockServiceHelper.getLocation(locationManager!!)
                        val currentLat = currentLocation!!.first
                        val currentLon = currentLocation.second
                        val cumulative = routeCumulativeDistances(selectedRoute!!)
                        val totalDistance = cumulative.last()
                        val distToTarget = Geodesic.WGS84.Inverse(
                            currentLat, currentLon,
                            route[routeStage].first, route[routeStage].second
                        ).s12
                        val remaining = distToTarget + (totalDistance - cumulative[routeStage])
                        val progress = if (totalDistance > 0) {
                            ((totalDistance - remaining) / totalDistance).coerceIn(0.0, 1.0)
                        } else {
                            1.0
                        }
                        tickSpeed = applicationContext.routeStartSpeed +
                                (applicationContext.routeEndSpeed - applicationContext.routeStartSpeed) * progress

                        // 转弯减速：前方路径点转弯越急（转弯半径越小），速度降低越多
                        if (routeStage + 1 < route.size && distToTarget < tickSpeed * TURN_SLOWDOWN_SECONDS) {
                            val turnPoint = route[routeStage]
                            val nextPoint = route[routeStage + 1]
                            val inAzimuth = Geodesic.WGS84.Inverse(
                                currentLat, currentLon, turnPoint.first, turnPoint.second
                            ).azi1
                            val outAzimuth = Geodesic.WGS84.Inverse(
                                turnPoint.first, turnPoint.second, nextPoint.first, nextPoint.second
                            ).azi1
                            var deflection = abs(outAzimuth - inAzimuth) % 360.0
                            if (deflection > 180.0) {
                                deflection = 360.0 - deflection
                            }
                            tickSpeed *= 1.0 - MAX_TURN_SLOWDOWN * (deflection / 180.0)
                        }
                    }

                    // 处理所有已到达的阶段
                    while (routeStage < route.size) {
                        val target = route[routeStage]
                        val location = MockServiceHelper.getLocation(locationManager!!)
                        val currentLat = location!!.first
                        val currentLon = location.second

                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        // 判断距离是否小于1米（可根据需要调整阈值）
                        if (inverse.s12 < 1.0) {
                            // 精确设置位置到目标点并进入下一阶段
                            MockServiceHelper.setLocation(
                                locationManager!!,
                                target.first,
                                target.second
                            )
                            routeStage++
                        } else if (inverse.s12 < tickSpeed * delayTime / 1000.0) {
                            // 如果距离小于一个上报间隔内的移动距离，直接移动到目标点
                            MockServiceHelper.setLocation(
                                locationManager!!,
                                target.first,
                                target.second
                            )
                            routeStage++

                        } else {
                            break
                        }
                    }

                    // 检查是否已完成所有阶段
                    if (routeStage >= route.size) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        // 重设阶段
                        routeStage = 0
                        break // 退出循环
                    }

                    // 处理当前目标点的移动
                    val target = route[routeStage]
                    val location = MockServiceHelper.getLocation(locationManager!!)
                    val currentLat = location!!.first
                    val currentLon = location.second

                    val inverse = Geodesic.WGS84.Inverse(
                        currentLat,
                        currentLon,
                        target.first,
                        target.second
                    )
                    var azimuth = inverse.azi1
                    if (azimuth < 0) {
                        azimuth += 360
                    }

                    Log.d("MockServiceViewModel", "从 $currentLat, $currentLon 移动到 ${target.first}, ${target.second}, 方位角: $azimuth, 当前速度: $tickSpeed")
                    // 同步上报速度，使上报的速度字段与实际移动速度一致
                    MockServiceHelper.setSpeed(locationManager!!, tickSpeed.toFloat())
                    if (!MockServiceHelper.move(
                            locationManager!!,
                            tickSpeed * delayTime / 1000.0,
                            azimuth
                        )
                    ) {
                        Log.e("MockServiceViewModel", "移动失败")
                    }

                    // 按设置为当前位置添加随机偏移（单位：厘米，0 表示关闭）
                    val offsetCm = applicationContext.routeRandomOffset
                    if (offsetCm > 0) {
                        MockServiceHelper.getLocation(locationManager!!)?.let { pos ->
                            val (offsetLat, offsetLon) = pos.randomOffset(offsetCm)
                            MockServiceHelper.setLocation(locationManager!!, offsetLat, offsetLon)
                        }
                    }
                } while (isActive)
            }
        }

        return rocker
    }

    fun isServiceStart(): Boolean {
        return locationManager != null && MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(
            locationManager!!
        )
    }

    /**
     * 计算路线上每个路径点距离起点的累计距离（米），结果按路线缓存
     */
    private fun routeCumulativeDistances(route: HistoricalRoute): DoubleArray {
        if (cachedRoute == route && cachedRouteCumulativeDistances.size == route.route.size) {
            return cachedRouteCumulativeDistances
        }
        val cumulative = DoubleArray(route.route.size)
        for (i in 1 until route.route.size) {
            val prev = route.route[i - 1]
            val next = route.route[i]
            cumulative[i] = cumulative[i - 1] + Geodesic.WGS84.Inverse(
                prev.first, prev.second, next.first, next.second
            ).s12
        }
        cachedRoute = route
        cachedRouteCumulativeDistances = cumulative
        return cumulative
    }
}