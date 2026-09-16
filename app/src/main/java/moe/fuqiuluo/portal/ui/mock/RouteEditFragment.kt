package moe.fuqiuluo.portal.ui.mock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONArray
import com.alibaba.fastjson2.JSONObject
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.LogoPosition
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import moe.fuqiuluo.portal.MainActivity
import moe.fuqiuluo.portal.Portal
import moe.fuqiuluo.portal.R
import moe.fuqiuluo.portal.bdmap.locateMe
import moe.fuqiuluo.portal.bdmap.setMapConfig
import moe.fuqiuluo.portal.databinding.FragmentRouteEditBinding
import moe.fuqiuluo.portal.ext.gcj02
import moe.fuqiuluo.portal.ext.jsonHistoricalRoutes
import moe.fuqiuluo.portal.ext.mapType
import moe.fuqiuluo.portal.ext.wgs84
import moe.fuqiuluo.portal.ui.viewmodel.BaiduMapViewModel
import moe.fuqiuluo.portal.ui.viewmodel.HomeViewModel
import kotlin.math.abs
import kotlin.random.Random


class RouteEditFragment : Fragment() {
    private var _binding: FragmentRouteEditBinding? = null
    private val binding get() = _binding!!

    private val routeEditViewModel by viewModels<HomeViewModel>()
    private lateinit var mLocationClient: LocationClient
    private val baiduMapViewModel by activityViewModels<BaiduMapViewModel>()

    private var mPoints: ArrayList<Pair<Double, Double>> = arrayListOf()
    private var isDrawing = false
    private var drawingSessionStarted = false
    private var strokeActive = false
    private var lastPoint: Pair<Double, Double>? = null
    private var lastTouchPoint: Point? = null
    private val strokePointCounts = ArrayDeque<Int>()

    companion object {
        /**
         * 画线时相邻两个采样点的最小屏幕距离（像素）
         */
        private const val MIN_DRAW_STEP_PX = 10
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteEditBinding.inflate(inflater, container, false)

        // 修复从其他 Fragment 切换回来后 Fab 状态异常的问题
        routeEditViewModel.mFabOpened = false

        with(baiduMapViewModel) {
            isExists = true
            baiduMap = binding.bmapView.map
        }

        with(binding.bmapView) {
            showZoomControls(true)
            showScaleControl(true)
            logoPosition = LogoPosition.logoPostionRightTop
        }

        with(binding.bmapView.map) {
            setMapStatus(MapStatusUpdateFactory.zoomTo(19f))

            mapType = context?.mapType ?: BaiduMap.MAP_TYPE_NORMAL
            compassPosition = Point(50, 50)
            setCompassEnable(true)
            uiSettings.isCompassEnabled = true
            uiSettings.isOverlookingGesturesEnabled = true
            uiSettings.isScrollGesturesEnabled = true
            isMyLocationEnabled = true

            setMapConfig(
                baiduMapViewModel.perspectiveState,
                if (Random.nextBoolean()) moe.fuqiuluo.portal.R.drawable.icon_my_location else null
            )

            setOnMapClickListener(object : BaiduMap.OnMapClickListener {
                override fun onMapClick(loc: LatLng) {
                    // 画线模式下不打标点，避免清除已绘制的路线
                    if (isDrawing) return

                    // 默认获取的gcj02坐标，需要转换一下
                    baiduMapViewModel.markedLoc = loc.wgs84

                    lifecycleScope.launch {
                        baiduMapViewModel.showDetailView = false
                        baiduMapViewModel.mGeoCoder?.reverseGeoCode(
                            ReverseGeoCodeOption().location(
                                loc
                            )
                        )
                    }

                    // Fixed the issue that getting geolocation information was stuck
                    lifecycleScope.launch {
                        markMap()
                    }
                }

                override fun onMapPoiClick(poi: MapPoi) {}
            })

            setOnMapLongClickListener { loc ->
                if (loc == null) return@setOnMapLongClickListener
                // 画线模式下不打标点，避免清除已绘制的路线
                if (isDrawing) return@setOnMapLongClickListener

                // 默认获取的gcj02坐标，需要转换一下
                baiduMapViewModel.markedLoc = loc.wgs84
                lifecycleScope.launch {
                    baiduMapViewModel.showDetailView = true
                    baiduMapViewModel.mGeoCoder?.reverseGeoCode(ReverseGeoCodeOption().location(loc))
                }
                lifecycleScope.launch {
                    markMap()
                }
            }

            binding.mapTypeGroup.check(
                when (mapType) {
                    BaiduMap.MAP_TYPE_NORMAL -> moe.fuqiuluo.portal.R.id.map_type_normal
                    BaiduMap.MAP_TYPE_SATELLITE -> moe.fuqiuluo.portal.R.id.map_type_satellite
                    else -> moe.fuqiuluo.portal.R.id.map_type_normal
                }
            )
        }

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabStart,
                binding.fabRollback,
                binding.fabComplete,
                binding.fabMyLocation
            )

            if (!routeEditViewModel.mFabOpened) {
                routeEditViewModel.mFabOpened = true

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 0f, 90f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    fab.visibility = View.VISIBLE
                    fab.alpha = 1f
                    fab.scaleX = 1f
                    fab.scaleY = 1f
                    val translationX =
                        ObjectAnimator.ofFloat(fab, "translationX", 0f, 20f + index * 8f)
                    translationX.duration = 200
                    animators.add(translationX)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            } else {
                routeEditViewModel.mFabOpened = false

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 90f, 0f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    val transX = ObjectAnimator.ofFloat(fab, "translationX", 0f, -20f - index * 8f)
                    transX.duration = 150
                    val scaleX = ObjectAnimator.ofFloat(fab, "scaleX", 1f, 0f)
                    scaleX.duration = 200
                    val scaleY = ObjectAnimator.ofFloat(fab, "scaleY", 1f, 0f)
                    scaleY.duration = 200
                    val alpha = ObjectAnimator.ofFloat(fab, "alpha", 1f, 0f)
                    alpha.duration = 200
                    animators.add(transX)
                    animators.add(scaleX)
                    animators.add(scaleY)
                    animators.add(alpha)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        subFabList.forEach { it.visibility = View.GONE }
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            }
        }

        mLocationClient = LocationClient(requireContext())
        val option = LocationClientOption()
        option.isOpenGps = true
        option.enableSimulateGps = false
        option.setIsNeedAddress(true) /* 关掉这个无法获取当前城市 */
        option.setNeedDeviceDirect(true)
        option.isLocationNotify = true
        option.setIgnoreKillProcess(true)
        option.setIsNeedLocationDescribe(false)
        option.setIsNeedLocationPoiList(false)
        option.isOpenGnss = true
        option.setIsNeedAltitude(false)
        option.locationMode = LocationClientOption.LocationMode.Hight_Accuracy

        option.setCoorType(Portal.DEFAULT_COORD_STR)
        option.setScanSpan(1000)
        mLocationClient.locOption = option
        mLocationClient.registerLocationListener(object : BDAbstractLocationListener() {
            override fun onReceiveLocation(loc: BDLocation?) {
                if (loc == null) return
                val locData = MyLocationData.Builder()
                    .accuracy(loc.radius)
                    .direction(loc.direction)
                    .latitude(loc.latitude)
                    .longitude(loc.longitude)
                    .build()

                if (loc.city != null)
                    MainActivity.mCityString = loc.city

                with(baiduMapViewModel) {
                    currentLocation = loc.wgs84
                    baiduMap.setMyLocationData(locData)
                }
            }
        })
        baiduMapViewModel.mLocationClient = mLocationClient
        mLocationClient.enableLocInForeground(1, baiduMapViewModel.mNotification)
        mLocationClient.start()

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                moe.fuqiuluo.portal.R.id.map_type_normal -> {
                    binding.bmapView.map.mapType = BaiduMap.MAP_TYPE_NORMAL
                }

                moe.fuqiuluo.portal.R.id.map_type_satellite -> {
                    binding.bmapView.map.mapType = BaiduMap.MAP_TYPE_SATELLITE
                }

                else -> {
                    Log.e("HomeFragment", "Unknown location view mode: $checkedId")
                }
            }
            context?.mapType = binding.bmapView.map.mapType
        }

        baiduMapViewModel.baiduMap.setOnMapTouchListener { event ->
            if (isDrawing) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // 新的一段笔画：按下时只记录锚点，不立即画线，
                        // 避免抬手后在其他位置点按时突然出现一条连线
                        strokeActive = true
                        strokePointCounts.addLast(0)
                        lastPoint = null
                        lastTouchPoint = null
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount > 1) {
                            // 多指操作（如缩放）时不画线，并中断当前笔画
                            strokeActive = false
                            lastPoint = null
                            lastTouchPoint = null
                        } else if (strokeActive) {
                            // 处理批量合并的历史点，快速滑动时线条也能保持连续（可以拐弯）
                            for (i in 0 until event.historySize) {
                                addDrawPoint(
                                    Point(
                                        event.getHistoricalX(i).toInt(),
                                        event.getHistoricalY(i).toInt()
                                    )
                                )
                            }
                            addDrawPoint(Point(event.x.toInt(), event.y.toInt()))
                        }
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        // 变为多指操作时中断当前笔画
                        strokeActive = false
                        lastPoint = null
                        lastTouchPoint = null
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        strokeActive = false
                        lastPoint = null // 关键修改：重置起点
                        lastTouchPoint = null
                    }
                }
            }
        }

        binding.fabStart.setOnClickListener {
            if (isDrawing) {
                // 暂停画线，恢复地图拖拽，方便移动地图后继续绘制
                isDrawing = false
                baiduMapViewModel.baiduMap.uiSettings.isScrollGesturesEnabled = true
                Toast.makeText(requireContext(), "已暂停画线，可拖动地图，再次点击继续", Toast.LENGTH_SHORT).show()
            } else {
                if (!drawingSessionStarted) {
                    // 全新的画线会话，清空上一次绘制的内容
                    mPoints = arrayListOf()
                    strokePointCounts.clear()
                    baiduMapViewModel.baiduMap.clear()
                    drawingSessionStarted = true
                }
                isDrawing = true
                // 画线时锁定地图拖拽，单指拖动即为画线
                baiduMapViewModel.baiduMap.uiSettings.isScrollGesturesEnabled = false
                Toast.makeText(requireContext(), "开始画线：单指拖动绘制路线，双指缩放地图", Toast.LENGTH_SHORT).show()
            }
            strokeActive = false
            lastPoint = null // 重置上一个点
            lastTouchPoint = null
        }

        binding.fabRollback.setOnClickListener {
            // 撤回上一段笔画并且刷新地图
            var count = 0
            while (count == 0 && strokePointCounts.isNotEmpty()) {
                count = strokePointCounts.removeLast()
            }
            if (count == 0) {
                // 没有笔画记录时（例如恢复的状态）退化为撤回一个点
                count = 1
            }
            if (mPoints.isNotEmpty()) {
                repeat(count.coerceAtMost(mPoints.size)) {
                    mPoints.removeAt(mPoints.size - 1)
                }
                refresh()
            }
        }

        binding.fabComplete.setOnClickListener {
            isDrawing = false
            drawingSessionStarted = false
            strokeActive = false
            baiduMapViewModel.baiduMap.uiSettings.isScrollGesturesEnabled = true
            // 补画各段笔画之间的连线，展示完整路线
            refresh()
            if (!showAddRouteDialog()) {
                Toast.makeText(requireContext(), "选择路线异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabMyLocation.setOnClickListener {
            baiduMapViewModel.baiduMap.locateMe()
        }

        return binding.root
    }

    private fun refresh() {
        baiduMapViewModel.baiduMap.clear() // 清除之前的所有覆盖物

        // 绘制之前记录的点到点的线
        for (i in 0 until mPoints.size - 1) {
            baiduMapViewModel.baiduMap.addOverlay(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10)
                    .points(listOf(mPoints[i].gcj02, mPoints[i + 1].gcj02))
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.bmapView.onCreate(requireContext(), savedInstanceState)
    }

    override fun onResume() {
        super.onResume()

        if (_binding != null)
            binding.bmapView.onResume()
    }

    override fun onPause() {
        super.onPause()

        if (_binding != null) {
            binding.bmapView.onPause()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (_binding != null) {
            binding.bmapView.onSaveInstanceState(outState)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        baiduMapViewModel.isExists = false
        if (mLocationClient.isStarted)
            mLocationClient.stop()
        if (_binding != null) {
            binding.bmapView.map.isMyLocationEnabled = false
        }
    }

    override fun onDestroyView() {
        // 恢复地图拖拽，避免影响其他页面的地图
        if (_binding != null) {
            binding.bmapView.map.uiSettings.isScrollGesturesEnabled = true
        }
        super.onDestroyView()
        _binding = null
    }

    /**
     * 把屏幕坐标采样为路线点并增量绘制最新的一段，
     * 避免每次触摸事件都清除并重绘全部覆盖物。
     * 每段笔画的第一个点只作为锚点记录，不与上一段笔画的终点连线。
     */
    private fun addDrawPoint(screenPoint: Point) {
        val lastTouch = lastTouchPoint
        if (lastTouch != null &&
            abs(screenPoint.x - lastTouch.x) < MIN_DRAW_STEP_PX &&
            abs(screenPoint.y - lastTouch.y) < MIN_DRAW_STEP_PX
        ) {
            return // 与上一个采样点距离太近，忽略
        }
        lastTouchPoint = Point(screenPoint)

        val point = baiduMapViewModel.baiduMap.projection
            .fromScreenLocation(screenPoint)?.wgs84 ?: return
        // 过滤非法坐标：地图倾斜或边缘时投影可能产生 NaN/超范围值
        if (!point.first.isFinite() || !point.second.isFinite() ||
            point.first !in -90.0..90.0 || point.second !in -180.0..180.0
        ) {
            Log.w("RouteEditFragment", "忽略非法坐标点: $point")
            return
        }
        val lp = lastPoint
        if (lp == null) {
            // 笔画的起点，只记录不画线
            mPoints.add(point)
            bumpStrokeCount()
        } else if (lp != point) {
            mPoints.add(point)
            drawSegment(lp, point)
            bumpStrokeCount()
        }
        lastPoint = point
    }

    private fun bumpStrokeCount() {
        if (strokePointCounts.isEmpty()) {
            strokePointCounts.addLast(1)
        } else {
            strokePointCounts.addLast(strokePointCounts.removeLast() + 1)
        }
    }

    private fun drawSegment(start: Pair<Double, Double>, end: Pair<Double, Double>) {
        baiduMapViewModel.baiduMap.addOverlay(
            PolylineOptions()
                .color(Color.argb(178, 0, 78, 255))
                .width(10)
                .points(listOf(start.gcj02, end.gcj02))
        )
    }


    private fun markMap(moveEyes: Boolean = false) = with(baiduMapViewModel) {
        val loc = markedLoc!!.gcj02
        val ooA = MarkerOptions()
            .position(loc)
            .icon(mMapIndicator)
        baiduMap.clear()
        baiduMap.addOverlay(ooA)

        if (moveEyes) {
            baiduMap.setMapStatus(MapStatusUpdateFactory.newLatLng(loc))
        }
    }

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddRouteDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        // 解析路线经纬度 JSON，兼容多种格式：
        // [{"first":39.9,"second":116.3},...]、[[39.9,116.3],...]、[{"latitude":39.9,"longitude":116.3},...] 等
        // 返回 null 表示 JSON 格式错误
        fun parseRoutePoints(json: String): kotlin.collections.List<Pair<Double, Double>>? {
            return try {
                val array = JSON.parseArray(json) ?: return null
                val result = mutableListOf<Pair<Double, Double>>()
                for (element in array) {
                    when (element) {
                        is JSONObject -> {
                            val lat = element.getDouble("first")
                                ?: element.getDouble("latitude") ?: element.getDouble("lat")
                            val lon = element.getDouble("second")
                                ?: element.getDouble("longitude") ?: element.getDouble("lng")
                                ?: element.getDouble("lon")
                            if (lat == null || lon == null) continue // 跳过无效点
                            result.add(lat to lon)
                        }

                        is JSONArray -> {
                            if (element.size < 2) continue // 跳过无效点
                            val lat = element.getDouble(0)
                            val lon = element.getDouble(1)
                            if (lat == null || lon == null) continue
                            result.add(lat to lon)
                        }

                        else -> return null
                    }
                }
                result
            } catch (e: Exception) {
                Log.e("RouteEditFragment", "路线JSON解析失败: ${json.take(500)}", e)
                null
            }
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_route, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etRouteName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val routeSetLayout = dialogView.findViewById<View>(R.id.routeSetLayout)
        val editRoute = dialogView.findViewById<TextInputEditText>(R.id.etRouteSet)
        val tvRouteSummary = dialogView.findViewById<TextView>(R.id.tvRoutePointsSummary)
        val switchCloseRoute = dialogView.findViewById<SwitchMaterial>(R.id.switchCloseRoute)

        // 手画的路线直接使用绘制的点，不暴露 JSON 配置
        val drawnPoints = mPoints.filter { checkLatLon(it.first, it.second) }
        val useDrawnPoints = drawnPoints.size >= 2
        if (useDrawnPoints) {
            routeSetLayout.visibility = View.GONE
            tvRouteSummary.visibility = View.VISIBLE
            tvRouteSummary.text = buildString {
                append("已绘制 ${drawnPoints.size} 个路径点")
                if (drawnPoints.size < mPoints.size) {
                    append("，已忽略 ${mPoints.size - drawnPoints.size} 个无效点")
                }
            }
        } else {
            // 未绘制有效路线时，允许手动粘贴路线 JSON
            tvRouteSummary.visibility = View.GONE
            editRoute.addTextChangedListener {
                if (it.isNullOrBlank()) {
                    editRoute.error = "路线经纬度不能为空"
                } else {
                    val points = parseRoutePoints(it.toString())
                    if (points == null) {
                        editRoute.error = "路线经纬度json格式错误"
                    } else if (points.size < 2) {
                        editRoute.error = "路线经纬度至少需要两个点"
                    } else if (points.any { p -> !checkLatLon(p.first, p.second) }) {
                        editRoute.error = "路线经纬度格式错误"
                    }
                }
            }
            editRoute.setText(JSON.toJSONString(mPoints))
        }

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                var name = editName.text?.toString()
                if (name.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val routePoints: kotlin.collections.List<Pair<Double, Double>>
                if (useDrawnPoints) {
                    routePoints = drawnPoints
                } else {
                    val routeJson = editRoute.text.toString()
                    val parsed = parseRoutePoints(routeJson)
                    if (parsed == null) {
                        Toast.makeText(requireContext(), "路线经纬度json格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }
                    // 过滤掉无效点（NaN、超出经纬度范围等）
                    val validPoints = parsed.filter { checkLatLon(it.first, it.second) }
                    if (validPoints.size < 2) {
                        Toast.makeText(requireContext(), "路线经纬度至少需要两个点", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }
                    if (validPoints.size < parsed.size) {
                        Toast.makeText(
                            requireContext(),
                            "已忽略 ${parsed.size - validPoints.size} 个无效点",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    routePoints = validPoints
                }

                // 闭合路线：首尾相连
                val finalPoints = if (switchCloseRoute.isChecked && routePoints.first() != routePoints.last()) {
                    routePoints + routePoints.first()
                } else {
                    routePoints
                }

                with(requireContext()) {
                    val routes = jsonHistoricalRoutes
                    val jsonArray: JSONArray = if (routes.isNotEmpty()) {
                        JSON.parseArray(routes)
                    } else {
                        JSONArray()
                    }
                    val historicalRoute = HistoricalRoute(name, finalPoints)
                    jsonArray.add(historicalRoute)
                    jsonArray.toJSONString().also {
                        jsonHistoricalRoutes = it
                    }
                }

                Toast.makeText(requireContext(), "路线已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()

        return true
    }
}