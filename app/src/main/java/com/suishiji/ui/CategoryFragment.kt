package com.suishiji.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.suishiji.util.ToastManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.suishiji.App
import com.suishiji.R
import com.suishiji.databinding.FragmentCategoryBinding
import com.suishiji.db.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CategoryFragment : Fragment() {
    private var _binding: FragmentCategoryBinding? = null
    private val binding get() = _binding!!
    private var isIncome = false
    private var statMode = 1 // 0=day, 1=month, 2=year
    private var year: Int = 0
    private var month: Int = 0
    private var selectedDay: Int = 0

    private val dayFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
    private val monthFormat = SimpleDateFormat("yyyy年MM月", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HomeFragment.pendingCategoryType?.let {
            isIncome = it == "income"
            HomeFragment.pendingCategoryType = null
        }
        // Sync date from HomeFragment if available
        if (HomeFragment.pendingYear >= 0) {
            year = HomeFragment.pendingYear
            month = HomeFragment.pendingMonth
            selectedDay = HomeFragment.pendingSelectedDay
            HomeFragment.pendingYear = -1
            HomeFragment.pendingMonth = -1
            HomeFragment.pendingSelectedDay = -1
        } else {
            val cal = Calendar.getInstance()
            year = cal.get(Calendar.YEAR)
            month = cal.get(Calendar.MONTH)
            selectedDay = cal.get(Calendar.DAY_OF_MONTH)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategoryBinding.inflate(inflater, container, false)

        // Segment toggle
        binding.btnExpense.setOnClickListener { switchType(false) }
        binding.btnIncome.setOnClickListener { switchType(true) }

        // Add button
        binding.btnAddCategory.setOnClickListener { showAddCategoryDialog() }

        // Period switcher
        setupPeriodSwitcher()

        // Set initial indicator position
        binding.segmentIndicator.post {
            val containerWidth = resources.displayMetrics.widthPixels - dp(32) - dp(4)
            val tabWidth = containerWidth / 2
            binding.segmentIndicator.layoutParams = android.widget.FrameLayout.LayoutParams(
                tabWidth, ViewGroup.LayoutParams.MATCH_PARENT
            )
            binding.segmentIndicator.translationX = if (isIncome) tabWidth.toFloat() else 0f
        }

        if (isIncome) {
            switchType(true)
        } else {
            switchType(false)
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // 始终从 HomeFragment 读取日期并重新加载，确保数据同步
        HomeFragment.pendingCategoryType?.let {
            isIncome = it == "income"
            HomeFragment.pendingCategoryType = null
        }
        if (HomeFragment.pendingYear >= 0) {
            year = HomeFragment.pendingYear
            month = HomeFragment.pendingMonth
            selectedDay = HomeFragment.pendingSelectedDay
            HomeFragment.pendingYear = -1
            HomeFragment.pendingMonth = -1
            HomeFragment.pendingSelectedDay = -1
        }
        if (HomeFragment.pendingPeriodMode >= 0) {
            statMode = HomeFragment.pendingPeriodMode
            HomeFragment.pendingPeriodMode = -1
            setPeriod(statMode)
        }
        if (HomeFragment.needsRefresh) {
            HomeFragment.needsRefresh = false
        }
        android.util.Log.d("Suishiji", "CategoryFragment onResume: year=$year month=$month day=$selectedDay statMode=$statMode isIncome=$isIncome")
        // 每次 onResume 都重新加载，确保数据与 HomeFragment 同步
        loadStats()
        loadCategories()
        val white = requireContext().getColor(R.color.white)
        val unselected = requireContext().getColor(R.color.segment_unselected)
        binding.btnExpense.setTextColor(if (!isIncome) white else unselected)
        binding.btnIncome.setTextColor(if (isIncome) white else unselected)
        binding.btnExpense.setBackgroundResource(if (!isIncome) R.drawable.bg_segment_selected else android.R.color.transparent)
        binding.btnIncome.setBackgroundResource(if (isIncome) R.drawable.bg_segment_selected else android.R.color.transparent)
    }

    private fun setupPeriodSwitcher() {
        val periodViews = listOf(binding.tabDay, binding.tabMonth, binding.tabYear)
        val indicator = binding.statPeriodIndicator

        binding.tabDay.setOnClickListener { setPeriod(0) }
        binding.tabMonth.setOnClickListener { setPeriod(1) }
        binding.tabYear.setOnClickListener { setPeriod(2) }

        // Set initial indicator position
        indicator.post {
            val containerWidth = resources.displayMetrics.widthPixels - dp(32) - dp(4)
            val tabWidth = containerWidth / 3
            indicator.layoutParams = android.widget.FrameLayout.LayoutParams(
                tabWidth, ViewGroup.LayoutParams.MATCH_PARENT
            )
            indicator.translationX = (statMode * tabWidth).toFloat()
        }
        setPeriod(statMode)
    }

    private fun setPeriod(mode: Int) {
        statMode = mode
        val white = requireContext().getColor(R.color.white)
        val unselected = requireContext().getColor(R.color.segment_unselected)
        val periodViews = listOf(binding.tabDay, binding.tabMonth, binding.tabYear)
        periodViews.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == mode) white else unselected)
            tv.textSize = if (i == mode) 14f else 13f
        }

        binding.statPeriodIndicator.post {
            val containerWidth = resources.displayMetrics.widthPixels - dp(32) - dp(4)
            val tabWidth = containerWidth / 3
            binding.statPeriodIndicator.animate()
                .translationX((mode * tabWidth).toFloat())
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        loadStats()
    }

    private fun switchType(income: Boolean) {
        isIncome = income
        val white = requireContext().getColor(R.color.white)
        val unselected = requireContext().getColor(R.color.segment_unselected)

        binding.btnExpense.setTextColor(if (income) unselected else white)
        binding.btnIncome.setTextColor(if (income) white else unselected)
        binding.btnExpense.setBackgroundResource(if (!income) R.drawable.bg_segment_selected else android.R.color.transparent)
        binding.btnIncome.setBackgroundResource(if (income) R.drawable.bg_segment_selected else android.R.color.transparent)

        binding.segmentIndicator.post {
            val containerWidth = resources.displayMetrics.widthPixels - dp(32) - dp(4)
            val tabWidth = containerWidth / 2
            val targetX = if (income) tabWidth.toFloat() else 0f
            binding.segmentIndicator.animate()
                .translationX(targetX)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        loadCategories()
        loadStats()
    }

    private fun getPeriodRange(): Triple<Long, Long, String> {
        val cal = Calendar.getInstance()
        return when (statMode) {
            0 -> {
                cal.set(year, month, selectedDay, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 1)
                Triple(start, cal.timeInMillis, dayFormat.format(java.util.Date(start)))
            }
            1 -> {
                cal.set(year, month, 1, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                Triple(start, cal.timeInMillis, monthFormat.format(java.util.Date(start)))
            }
            2 -> {
                cal.set(year, 0, 1, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                Triple(start, cal.timeInMillis, "${year}年")
            }
            else -> Triple(0L, 0L, "")
        }
    }

    private fun loadStats() {
        val type = if (isIncome) "income" else "expense"
        val (start, end, label) = getPeriodRange()
        android.util.Log.d("Suishiji", "loadStats: type=$type statMode=$statMode year=$year month=$month day=$selectedDay start=$start end=$end label=$label")
        val dao = (requireActivity().application as App).database.transactionDao()

        CoroutineScope(Dispatchers.IO).launch {
            val total = if (isIncome) dao.getIncomeSum(start, end) else dao.getExpenseSum(start, end)
            val cats = dao.getCategorySums(type, start, end)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                binding.tvStatTotal.text = "¥%.2f".format(total)

                if (cats.isEmpty() || total <= 0) {
                    binding.barsContainer.removeAllViews()
                    binding.tvNoData.visibility = View.VISIBLE
                    binding.pieCard.visibility = View.GONE
                    loadMap()
                    return@withContext
                }

                binding.tvNoData.visibility = View.GONE
                buildCategoryBars(binding.barsContainer, cats, total)
                updatePieChart(binding.pieCard, binding.pieChart, binding.pieLegend, cats)
                loadMap()
            }
        }
    }

    private fun buildCategoryBars(container: LinearLayout, cats: List<com.suishiji.db.CategorySum>, total: Double) {
        container.removeAllViews()

        for (cat in cats) {
            val percent = (cat.total / total * 100).toInt()
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val labelRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            labelRow.addView(TextView(requireContext()).apply {
                text = cat.category; textSize = 13f
                setTextColor(requireContext().getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            labelRow.addView(TextView(requireContext()).apply {
                text = "¥%.2f  %d%%".format(cat.total, percent); textSize = 12f
                setTextColor(requireContext().getColor(R.color.text_disabled))
            })
            row.addView(labelRow)

            val barBg = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat(); setColor(requireContext().getColor(R.color.card_bg_alt))
                }
            }
            barBg.addView(View(requireContext()).apply {
                val w = ((percent.coerceIn(1, 100)) / 100f * (resources.displayMetrics.widthPixels - dp(48))).toInt()
                layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(if (isIncome) ContextCompat.getColor(requireContext(), R.color.income) else ContextCompat.getColor(requireContext(), R.color.expense))
                }
            })
            row.addView(barBg)
            container.addView(row)
        }
    }

    private fun updatePieChart(card: View, chart: com.suishiji.view.RingChartView, legend: LinearLayout, cats: List<com.suishiji.db.CategorySum>) {
        val data = cats.map { it.category to it.total }
        chart.setData(data)

        legend.removeAllViews()
        for ((color, text) in chart.getLegend()) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            })
            row.addView(TextView(requireContext()).apply {
                this.text = text
                textSize = 11f
                setTextColor(requireContext().getColor(R.color.text_primary))
                setPadding(dp(6), 0, 0, 0)
            })
            legend.addView(row)
        }
        card.visibility = View.VISIBLE
    }

    private fun loadCategories() {
        val catDao = (requireActivity().application as App).database.categoryDao()
        binding.categoryList.removeAllViews()
        CoroutineScope(Dispatchers.IO).launch {
            val cats = catDao.getByType(isIncome)
            withContext(Dispatchers.Main) {
                if (cats.isEmpty()) {
                    binding.categoryList.addView(TextView(requireContext()).apply {
                        text = "暂无分类"
                        textSize = 14f
                        setTextColor(requireContext().getColor(R.color.text_disabled))
                        gravity = Gravity.CENTER
                        setPadding(0, dp(24), 0, dp(24))
                    })
                    return@withContext
                }
                for (cat in cats) {
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(12), 0, dp(12))
                    }
                    row.addView(TextView(requireContext()).apply {
                        text = cat.name
                        textSize = 15f
                        setTextColor(requireContext().getColor(R.color.text_primary))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    // Edit button
                    row.addView(TextView(requireContext()).apply {
                        text = "编辑"
                        textSize = 13f
                        setTextColor(requireContext().getColor(R.color.primary))
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        setOnClickListener { showEditCategoryDialog(cat) }
                    })
                    // Delete button
                    row.addView(TextView(requireContext()).apply {
                        text = "删除"
                        textSize = 13f
                        setTextColor(requireContext().getColor(R.color.expense))
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        setOnClickListener {
                            AlertDialog.Builder(requireContext())
                                .setTitle("确认删除")
                                .setMessage("删除「${cat.name}」分类")
                                .setPositiveButton("删除") { _, _ ->
                                    CoroutineScope(Dispatchers.IO).launch {
                                        catDao.delete(cat)
                                        withContext(Dispatchers.Main) {
                                            loadCategories()
                                            loadStats()
                                            ToastManager.show(requireContext(), "已删除")
                                        }
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    })
                    binding.categoryList.addView(row)
                    // Divider
                    binding.categoryList.addView(View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                        setBackgroundColor(requireContext().getColor(R.color.border))
                    })
                }
            }
        }
    }

    private fun showEditCategoryDialog(cat: Category) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(0))
        }
        layout.addView(TextView(requireContext()).apply { text = "名称" })
        val nameEdit = android.widget.EditText(requireContext()).apply {
            setText(cat.name); textSize = 15f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(requireContext().getColor(R.color.card_bg_alt))
            }
        }
        layout.addView(nameEdit)
        layout.addView(TextView(requireContext()).apply { text = "关键词(逗号分隔)"; setPadding(0, dp(12), 0, 0) })
        val kwEdit = android.widget.EditText(requireContext()).apply {
            setText(cat.keywords); textSize = 15f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(requireContext().getColor(R.color.card_bg_alt))
            }
        }
        layout.addView(kwEdit)

        AlertDialog.Builder(requireContext())
            .setTitle("编辑分类")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val newName = nameEdit.text.toString().trim()
                if (newName.isNotBlank()) {
                    val catDao = (requireActivity().application as App).database.categoryDao()
                    CoroutineScope(Dispatchers.IO).launch {
                        catDao.update(cat.copy(name = newName, keywords = kwEdit.text.toString().trim()))
                        withContext(Dispatchers.Main) {
                            loadCategories()
                            loadStats()
                            ToastManager.show(requireContext(), "已保存")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddCategoryDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(0))
        }
        layout.addView(TextView(requireContext()).apply { text = "名称" })
        val nameEdit = android.widget.EditText(requireContext()).apply {
            textSize = 15f; hint = "输入分类名称"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(requireContext().getColor(R.color.card_bg_alt))
            }
        }
        layout.addView(nameEdit)
        layout.addView(TextView(requireContext()).apply { text = "关键词(逗号分隔)"; setPadding(0, dp(12), 0, 0) })
        val kwEdit = android.widget.EditText(requireContext()).apply {
            textSize = 15f; hint = "如: 星巴克,咖啡"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(requireContext().getColor(R.color.card_bg_alt))
            }
        }
        layout.addView(kwEdit)

        AlertDialog.Builder(requireContext())
            .setTitle("新增分类")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val newName = nameEdit.text.toString().trim()
                if (newName.isNotBlank()) {
                    val catDao = (requireActivity().application as App).database.categoryDao()
                    CoroutineScope(Dispatchers.IO).launch {
                        catDao.insert(Category(
                            name = newName,
                            icon = "label",
                            isIncome = isIncome,
                            keywords = kwEdit.text.toString().trim()
                        ))
                        withContext(Dispatchers.Main) {
                            loadCategories()
                            loadStats()
                            ToastManager.show(requireContext(), "已添加「$newName」")
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun loadMap() {
        if (_binding == null) return
        val dao = (requireActivity().application as App).database.transactionDao()
        val (start, end, _) = getPeriodRange()
        CoroutineScope(Dispatchers.IO).launch {
            val allTxns = dao.getWithLocationBetween(start, end)
            val txns = allTxns.filter { it.type == if (isIncome) "income" else "expense" }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                if (txns.isEmpty()) {
                    binding.webView.loadData(
                        "<html><body style='background:#F2F2F7;color:#8E8E93;text-align:center;padding-top:20vh;font-size:15px'>暂无地点记录<br>记账时开启定位会自动记录</body></html>",
                        "text/html", "UTF-8"
                    )
                    return@withContext
                }

                val lat = txns.map { it.latitude!! }.average()
                val lon = txns.map { it.longitude!! }.average()

                val points = txns.take(200).joinToString(",") { t ->
                    val name = "${t.category} ¥${String.format("%.0f", t.amount)}"
                    val addr = (t.address ?: "").replace("\\", "\\\\").replace("'", "\\'").replace("\"", "").replace("\n", "")
                    val color = if (t.type == "expense") "#E74C3C" else "#27AE60"
                    val sign = if (t.type == "expense") "-" else "+"
                    "{lat:${t.latitude},lng:${t.longitude},name:\"$name\",addr:\"$addr\",color:\"$color\",amount:\"$sign¥${String.format("%.2f", t.amount)}\"}"
                }

                val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
<style>
html,body,#map{margin:0;padding:0;width:100%;height:100%}
.cluster{background:#007AFF;color:#fff;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:13px;font-weight:bold;box-shadow:0 2px 8px rgba(0,0,0,.15)}
.info-box{background:#fff;color:#000;padding:10px 14px;border-radius:12px;box-shadow:0 2px 12px rgba(0,0,0,.12);font-size:13px;min-width:160px}
.info-box .title{color:#000;font-size:14px;font-weight:600}
.info-box .amount{margin-top:4px;font-size:14px}
.info-box .addr{color:#8E8E93;font-size:12px;margin-top:4px}
</style>
</head>
<body>
<div id="map"></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script src="https://unpkg.com/leaflet.markercluster@1.5.3/dist/leaflet.markercluster.js"></script>
<link rel="stylesheet" href="https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.css"/>
<link rel="stylesheet" href="https://unpkg.com/leaflet.markercluster@1.5.3/dist/MarkerCluster.Default.css"/>
<script>
var data = [$points];

var map = L.map('map', {zoomControl: false}).setView([$lat, $lon], 14);

L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
    maxZoom: 18,
    subdomains: ['1','2','3','4'],
    attribution: '&copy; 高德地图'
}).addTo(map);

var markers = L.markerClusterGroup({
    maxClusterRadius: 50,
    spiderfyOnMaxZoom: true,
    showCoverageOnHover: false,
    iconCreateFunction: function(cluster) {
        var count = cluster.getChildCount();
        var size = count < 10 ? 36 : count < 100 ? 44 : 52;
        return L.divIcon({
            html: '<div class="cluster" style="width:'+size+'px;height:'+size+'px">'+count+'</div>',
            className: '',
            iconSize: [size, size]
        });
    }
});

data.forEach(function(d) {
    var colorIcon = L.divIcon({
        html: '<svg viewBox="0 0 24 34" width="24" height="34"><path d="M12 0C5.4 0 0 5.4 0 12c0 9 12 22 12 22s12-13 12-22C24 5.4 18.6 0 12 0z" fill="'+d.color+'"/><circle cx="12" cy="11" r="5" fill="#fff"/></svg>',
        className: '',
        iconSize: [24, 34],
        iconAnchor: [12, 34],
        popupAnchor: [0, -34]
    });
    var marker = L.marker([d.lat, d.lng], {icon: colorIcon});
    marker.bindPopup('<div class="info-box"><div class="title">' + d.name + '</div><div class="amount" style="color:' + d.color + '">' + d.amount + '</div><div class="addr">' + d.addr + '</div></div>', {closeButton: false, offset: [0, -24]});
    markers.addLayer(marker);
});

map.addLayer(markers);

if (data.length > 1) {
    var bounds = L.latLngBounds(data.map(function(d){return [d.lat, d.lng]}));
    map.fitBounds(bounds, {padding: [40, 40]});
}
</script>
</body>
</html>
                """.trimIndent()

                binding.webView.settings.javaScriptEnabled = true
                binding.webView.settings.domStorageEnabled = true
                binding.webView.settings.allowFileAccess = true
                binding.webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                binding.webView.webViewClient = android.webkit.WebViewClient()
                binding.webView.webChromeClient = android.webkit.WebChromeClient()
                binding.webView.setOnTouchListener { v, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                binding.webView.loadDataWithBaseURL("https://unpkg.com/", html, "text/html", "UTF-8", null)
            }
        }
    }

    override fun onDestroyView() {
        binding.webView?.destroy()
        super.onDestroyView()
        _binding = null
    }
}
