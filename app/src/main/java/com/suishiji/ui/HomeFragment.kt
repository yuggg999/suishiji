package com.suishiji.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.FrameLayout
import com.suishiji.util.ToastManager
import android.widget.LinearLayout
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.suishiji.App
import com.suishiji.R
import com.suishiji.adapter.TransactionAdapter
import com.suishiji.databinding.FragmentHomeBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.suishiji.db.Category
import com.suishiji.db.CategorySum
import com.suishiji.db.FixedExpense
import com.suishiji.db.Transaction
import com.suishiji.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment() {
    companion object {
        var pendingCategoryType: String? = null
        var pendingYear: Int = -1
        var pendingMonth: Int = -1
        var pendingSelectedDay: Int = -1
        var pendingPeriodMode: Int = -1
        var needsRefresh = false
        private var lastFabClickTime = 0L
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var year: Int = 0
    private var month: Int = 0
    private var selectedDay: Int = 0
    private lateinit var listAdapter: TransactionAdapter
    private var transactionDates = setOf<Long>()
    private var dailyExpenseMap = mapOf<Int, Double>()
    private var periodMode = 1 // 0=day, 1=month, 2=year
    private val dayClickCount = HashMap<String, Int>()
    private val dayClickTimestamps = HashMap<String, Long>()
    private val dayFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
    private val monthFormat = SimpleDateFormat("yyyy年MM月", Locale.CHINA)

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importCsv(uri) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val cal = Calendar.getInstance()
        year = cal.get(Calendar.YEAR)
        month = cal.get(Calendar.MONTH)
        selectedDay = cal.get(Calendar.DAY_OF_MONTH)

        // List adapter
        listAdapter = TransactionAdapter(
            onAddressClick = { t ->
                if (t.latitude != null && t.longitude != null) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("查看位置")
                        .setMessage(t.address ?: t.category)
                        .setPositiveButton("打开高德地图") { _, _ ->
                            val uri = Uri.parse("androidamap://viewMap?sourceApplication=suishiji&poiname=${Uri.encode(t.address ?: t.category)}&lat=${t.latitude}&lon=${t.longitude}&dev=0")
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.autonavi.minimap")
                            }
                            try {
                                startActivity(intent)
                            } catch (_: Exception) {
                                val webUri = Uri.parse("https://uri.amap.com/marker?position=${t.longitude},${t.latitude}&name=${Uri.encode(t.address ?: t.category)}&coordinate=gaode")
                                startActivity(Intent(Intent.ACTION_VIEW, webUri))
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            },
            onEdit = { t ->
                if (t.id < 0) {
                    val realId = -t.id - 1000000L
                    CoroutineScope(Dispatchers.IO).launch {
                        val fe = (requireActivity().application as App).database.fixedExpenseDao().getAll().find { it.id == realId }
                        withContext(Dispatchers.Main) {
                            if (fe != null) showFixedExpenseEditDialog(fe) { loadPeriodData(); loadMonthData() }
                        }
                    }
                } else {
                    showEditDialog(t)
                }
            },
            onSelectionChanged = { count, type ->
                if (count > 0) {
                    binding.batchCount.text = "已选择 $count 项"
                    binding.batchBar.visibility = View.VISIBLE
                    binding.fabAdd.hide()
                } else {
                    binding.batchBar.visibility = View.GONE
                    binding.fabAdd.show()
                    listAdapter.isBatchMode = false
                    listAdapter.batchType = null
                    listAdapter.notifyDataSetChanged()
                }
            }
        )
        binding.rvRecords.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecords.adapter = listAdapter

        // Batch actions
        binding.btnBatchTag.setOnClickListener { showBatchTagDialog() }
        binding.btnBatchDelete.setOnClickListener { confirmBatchDelete() }

        // Back press exits batch mode
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (listAdapter.isBatchMode) {
                listAdapter.clearSelection()
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        // Swipe to delete
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.adapterPosition
                val item = listAdapter.currentList[pos]
                listAdapter.notifyItemChanged(pos)
                if (item.id < 0) {
                    val realId = -item.id - 1000000L
                    // 固定支出右滑：跳过本月
                    val currentMonthKey = "%04d-%02d".format(year, month + 1)
                    CoroutineScope(Dispatchers.IO).launch {
                        val dao = (requireActivity().application as App).database.fixedExpenseDao()
                        val fe = dao.getById(realId)
                        if (fe != null) {
                            val isSkipped = fe.skippedMonths.split(",").contains(currentMonthKey)
                            val label = if (isSkipped) "恢复本月" else "跳过本月"
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(requireContext())
                                    .setTitle(label)
                                    .setMessage("确定要${label}这笔固定支出吗？")
                                    .setPositiveButton(label) { _, _ ->
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val newSkipped = if (isSkipped) {
                                                fe.skippedMonths.split(",").filter { it.isNotBlank() && it != currentMonthKey }.joinToString(",")
                                            } else {
                                                val parts = fe.skippedMonths.split(",").filter { it.isNotBlank() }
                                                (parts + currentMonthKey).joinToString(",")
                                            }
                                            dao.update(fe.copy(skippedMonths = newSkipped))
                                            withContext(Dispatchers.Main) { loadPeriodData(); loadMonthData() }
                                        }
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                        }
                    }
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("确认删除")
                        .setMessage("确定要删除这条记录吗？")
                        .setPositiveButton("删除") { _, _ ->
                            val dao = (requireActivity().application as App).database.transactionDao()
                            CoroutineScope(Dispatchers.IO).launch {
                                dao.deleteById(item.id)
                                withContext(Dispatchers.Main) {
                                    loadPeriodData()
                                    loadMonthData()
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvRecords)

        // Month navigation
        binding.btnPrev.setOnClickListener { switchMonth(-1) }
        binding.btnNext.setOnClickListener { switchMonth(1) }

        // Month text click → show year/month picker popup
        binding.tvMonth.setOnClickListener { showMonthPicker(it) }

        // Settings
        binding.btnSettings.setOnClickListener { showSettings() }

        // Quick add fixed expense → management dialog
        binding.btnFixedAdd.setOnClickListener { showFixedExpenseManagementDialog() }

        // Set initial tab colors
        val white = requireContext().getColor(R.color.white)
        val unselected = requireContext().getColor(R.color.segment_unselected)
        binding.tabDay.setTextColor(if (periodMode == 0) white else unselected)
        binding.tabMonth.setTextColor(if (periodMode == 1) white else unselected)
        binding.tabYear.setTextColor(if (periodMode == 2) white else unselected)

        // Set initial indicator position
        binding.segmentIndicator.post {
            val container = binding.segmentContainer
            val containerWidth = container.width - dp(4) // subtract padding
            val tabWidth = containerWidth / 3
            binding.segmentIndicator.layoutParams = android.widget.FrameLayout.LayoutParams(
                tabWidth,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            binding.segmentIndicator.translationX = (periodMode * tabWidth + dp(2)).toFloat()
        }

        // Swipe on segment control to switch day/month/year, double-click for pie chart
        var segStartX = 0f
        var segStartY = 0f
        var segSwiping = false
        var segLastClickTime = 0L
        var segLastClickMode = periodMode
        val segClickHandler = android.os.Handler(android.os.Looper.getMainLooper())

        val segTouchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    segStartX = event.rawX
                    segStartY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - segStartX
                    val dy = event.rawY - segStartY
                    if (Math.abs(dx) < 10 && Math.abs(dy) < 10) {
                        val now = System.currentTimeMillis()
                        if (now - segLastClickTime < 300) {
                            segClickHandler.removeCallbacksAndMessages(null)
                            segLastClickTime = 0L
                            showPieChartDialog(segLastClickMode)
                        } else {
                            segLastClickTime = now
                            val mode = when (v.id) {
                                R.id.tab_day -> 0
                                R.id.tab_month -> 1
                                R.id.tab_year -> 2
                                else -> periodMode
                            }
                            segLastClickMode = mode
                            segClickHandler.postDelayed({
                                if (segLastClickTime != 0L) {
                                    setPeriod(mode)
                                }
                            }, 150)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        binding.tabDay.setOnTouchListener(segTouchListener)
        binding.tabMonth.setOnTouchListener(segTouchListener)
        binding.tabYear.setOnTouchListener(segTouchListener)

        // Long-press budget to edit
        binding.budgetCard.setOnLongClickListener {
            showBudgetSetting(
                requireContext().getSharedPreferences("suishiji", android.content.Context.MODE_PRIVATE),
                null
            )
            true
        }

        // Calendar swipe to switch month
        binding.calendarSwipe.onSwipeListener = { direction -> switchMonth(direction) }

        // Calendar arrows
        binding.calBtnPrev.setOnClickListener { switchMonth(-1) }
        binding.calBtnNext.setOnClickListener { switchMonth(1) }

        // FAB - add record
        binding.fabAdd.setOnClickListener { showAddSheet() }

        // Summary card click → navigate to category tab via bottom nav
        binding.expenseSection.setOnClickListener {
            pendingCategoryType = "expense"
            requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                .selectedItemId = R.id.categoryFragment
        }
        binding.incomeSection.setOnClickListener {
            pendingCategoryType = "income"
            requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                .selectedItemId = R.id.categoryFragment
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateAll()
        checkBirthday()
    }

    private fun checkBirthday() {
        val prefs = requireContext().getSharedPreferences("suishiji", android.content.Context.MODE_PRIVATE)
        val now = Calendar.getInstance()
        val todayMonth = now.get(Calendar.MONTH) + 1
        val todayDay = now.get(Calendar.DAY_OF_MONTH)

        val bMonth = prefs.getInt("birthday_month", -1)
        val bDay = prefs.getInt("birthday_day", -1)
        val isUserBirthday = bMonth > 0 && todayMonth == bMonth && todayDay == bDay
        val isSep23 = todayMonth == 9 && todayDay == 23

        // Both on same day: first normal, then special
        if (isSep23 && isUserBirthday) {
            val normalShown = prefs.getBoolean("birthday_shown_today", false)
            val specialShown = prefs.getBoolean("hidden_birthday_shown_today", false)
            if (!normalShown) {
                prefs.edit().putBoolean("birthday_shown_today", true).apply()
                showBirthdayCelebration()
            } else if (!specialShown) {
                prefs.edit().putBoolean("hidden_birthday_shown_today", true).apply()
                showBirthdayCelebration(isSpecial = true)
            }
            return
        }

        // Sep 23 only
        if (isSep23) {
            val hiddenShown = prefs.getBoolean("hidden_birthday_shown_today", false)
            if (!hiddenShown) {
                prefs.edit().putBoolean("hidden_birthday_shown_today", true).apply()
                showBirthdayCelebration(isSpecial = true)
            }
        } else {
            prefs.edit().putBoolean("hidden_birthday_shown_today", false).apply()
        }

        // User birthday only
        if (isUserBirthday) {
            val alreadyShown = prefs.getBoolean("birthday_shown_today", false)
            if (!alreadyShown) {
                prefs.edit().putBoolean("birthday_shown_today", true).apply()
                showBirthdayCelebration()
            }
        } else {
            prefs.edit().putBoolean("birthday_shown_today", false).apply()
        }
    }

    private fun updateAll() {
        binding.tvMonth.text = "${year}年${month + 1}月"
        loadMonthData()
        loadPeriodData()
    }

    private fun switchMonth(direction: Int) {
        val cal = binding.calendarSwipe
        val slideOut = direction * 1f  // slide left for next, right for prev

        // Slide out calendar
        cal.animate()
            .translationX(-slideOut * cal.width)
            .alpha(0.3f)
            .setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                // Update month
                month += direction
                if (month > 11) { month = 0; year++ }
                if (month < 0) { month = 11; year-- }
                selectedDay = 1
                pendingYear = year
                pendingMonth = month
                pendingSelectedDay = selectedDay
                updateAll()

                // Reset position to opposite side, then slide in
                cal.translationX = slideOut * cal.width
                cal.alpha = 0.3f
                cal.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(250)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                    .start()

                // Toast
                ToastManager.show(requireContext(), "${year}年${month + 1}月")
            }
            .start()
    }

    private fun showMonthPicker(anchor: View) {
        val dialogView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(0))
        }

        val yearPicker = android.widget.NumberPicker(requireContext()).apply {
            minValue = 2020
            maxValue = 2030
            value = year
            layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
            setNumberPickerTextColor(Color.BLACK)
        }
        val monthPicker = android.widget.NumberPicker(requireContext()).apply {
            minValue = 1
            maxValue = 12
            value = month + 1
            displayedValues = arrayOf("1月","2月","3月","4月","5月","6月","7月","8月","9月","10月","11月","12月")
            layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
            setNumberPickerTextColor(Color.BLACK)
        }

        dialogView.addView(yearPicker)
        dialogView.addView(TextView(requireContext()).apply {
            text = "年"; textSize = 16f
            setTextColor(requireContext().getColor(R.color.text_disabled))
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(8), 0)
        })
        dialogView.addView(monthPicker)

        AlertDialog.Builder(requireContext())
            .setTitle("选择年月")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                year = yearPicker.value
                month = monthPicker.value - 1
                selectedDay = 1
                updateAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun android.widget.NumberPicker.setNumberPickerTextColor(color: Int) {
        try {
            val selectorWheelPaintField = this::class.java.getDeclaredField("mSelectorWheelPaint")
            selectorWheelPaintField.isAccessible = true
            selectorWheelPaintField.get(this).let { (it as android.graphics.Paint).color = color }
            val f = this::class.java.getDeclaredField("mInputText")
            f.isAccessible = true
            (f.get(this) as android.widget.EditText).setTextColor(color)
            invalidate()
        } catch (_: Exception) {}
    }

    private fun showEditDialog(t: Transaction) {
        val dialog = AddRecordDialog.newInstanceForEdit(t)
        dialog.onRecordSaved = {
            loadMonthData()
            loadPeriodData()
        }
        dialog.show(childFragmentManager, "edit_record")
    }

    fun openFromNotification(
        amount: String, merchant: String, source: String,
        type: String, category: String, date: Long,
        latitude: Double?, longitude: Double?, address: String?
    ) {
        val dialog = AddRecordDialog.newInstanceForNotification(
            amount, merchant, source, type, category, date,
            latitude, longitude, address
        )
        dialog.onRecordSaved = {
            loadMonthData()
            loadPeriodData()
        }
        dialog.show(childFragmentManager, "notification_record")
    }

    fun openForEdit(
        editId: Long, amount: String, merchant: String, source: String,
        type: String, category: String, date: Long,
        latitude: Double?, longitude: Double?, address: String?
    ) {
        // 从数据库加载真实数据（包含自动保存时获取的定位）
        CoroutineScope(Dispatchers.IO).launch {
            val dao = (requireActivity().application as App).database.transactionDao()
            val tx = dao.getById(editId) ?: com.suishiji.db.Transaction(
                id = editId, amount = amount.toDoubleOrNull() ?: 0.0,
                type = type, category = category, note = "$source: $merchant",
                date = date, latitude = latitude, longitude = longitude, address = address
            )
            withContext(Dispatchers.Main) {
                val dialog = AddRecordDialog.newInstanceForEdit(tx)
                dialog.onRecordSaved = {
                    loadMonthData()
                    loadPeriodData()
                }
                dialog.show(childFragmentManager, "edit_record")
            }
        }
    }

    private fun confirmBatchDelete() {
        val count = listAdapter.selectedIds.size
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除选中的 $count 条记录吗？")
            .setPositiveButton("删除") { _, _ ->
                val ids = listAdapter.selectedIds.toList()
                val dao = (requireActivity().application as App).database.transactionDao()
                CoroutineScope(Dispatchers.IO).launch {
                    dao.deleteByIds(ids)
                    withContext(Dispatchers.Main) {
                        listAdapter.clearSelection()
                        loadPeriodData()
                        loadMonthData()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchTagDialog() {
        val batchType = listAdapter.batchType
        CoroutineScope(Dispatchers.IO).launch {
            val cats = (requireActivity().application as App).database.categoryDao().getByType(batchType == "income").map { it.name }
            val catNames = if (cats.isNotEmpty()) cats else listOf("餐饮", "购物", "交通", "娱乐", "住宿", "日用", "医疗", "教育", "通讯", "服饰", "美容", "运动", "其它")
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(requireContext())
                    .setTitle("修改标签")
                    .setItems(catNames.toTypedArray()) { _, which ->
                        val newCat = catNames[which]
                        val ids = listAdapter.selectedIds.toList()
                        val dao = (requireActivity().application as App).database.transactionDao()
                        CoroutineScope(Dispatchers.IO).launch {
                            dao.updateCategoryByIds(ids, newCat)
                            withContext(Dispatchers.Main) {
                                listAdapter.clearSelection()
                                loadPeriodData()
                                loadMonthData()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun showSettings() {
        val prefs = requireContext().getSharedPreferences("suishiji", android.content.Context.MODE_PRIVATE)
        val logEnabled = prefs.getBoolean("log_enabled", false)
        val darkMode = prefs.getBoolean("dark_mode", false)

        val textPrimary = requireContext().getColor(R.color.text_primary)
        val textDisabled = requireContext().getColor(R.color.text_disabled)

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(0))
        }

        fun addDivider() {
            layout.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12); bottomMargin = dp(12)
                }
                setBackgroundColor(requireContext().getColor(R.color.border))
            })
        }

        // Dark mode toggle
        val darkRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        darkRow.addView(TextView(requireContext()).apply {
            text = "深色模式"
            textSize = 15f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val darkSwitch = android.widget.Switch(requireContext()).apply {
            isChecked = darkMode
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("dark_mode", isChecked).apply()
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (isChecked) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }
        darkRow.addView(darkSwitch)
        layout.addView(darkRow)

        addDivider()

        // Log toggle
        val logRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        logRow.addView(TextView(requireContext()).apply {
            text = "调试日志"
            textSize = 15f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val logSwitch = android.widget.Switch(requireContext()).apply {
            isChecked = logEnabled
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("log_enabled", isChecked).apply()
            }
        }
        logRow.addView(logSwitch)
        layout.addView(logRow)

        addDivider()

        // Accessibility service - Toggle Switch
        val a11yRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        a11yRow.addView(TextView(requireContext()).apply {
            text = "支付识别"
            textSize = 15f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val enabledServices = android.provider.Settings.Secure.getString(
            requireContext().contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val serviceComponent = "${requireContext().packageName}/com.suishiji.service.PaymentNotificationService"
        val a11yEnabled = enabledServices != null && enabledServices.contains(serviceComponent)
        val a11ySwitch = android.widget.Switch(requireContext()).apply {
            isChecked = a11yEnabled
            setOnCheckedChangeListener { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        a11yRow.addView(a11ySwitch)
        layout.addView(a11yRow)

        addDivider()

        // Notification listener - Toggle Switch
        val notifListenerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        notifListenerRow.addView(TextView(requireContext()).apply {
            text = "退款识别"
            textSize = 15f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val enabledListeners = android.provider.Settings.Secure.getString(
            requireContext().contentResolver,
            "enabled_notification_listeners"
        )
        val listenerComponent = "${requireContext().packageName}/com.suishiji.service.RefundNotificationListener"
        val listenerEnabled = enabledListeners != null && enabledListeners.contains(listenerComponent)
        val notifListenerSwitch = android.widget.Switch(requireContext()).apply {
            isChecked = listenerEnabled
            setOnCheckedChangeListener { _, _ ->
                startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
        notifListenerRow.addView(notifListenerSwitch)
        layout.addView(notifListenerRow)

        addDivider()

        // View logs
        layout.addView(TextView(requireContext()).apply {
            text = "查看日志"
            textSize = 15f
            setTextColor(textPrimary)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showLogViewer() }
        })

        addDivider()

        // Monthly budget
        layout.addView(TextView(requireContext()).apply {
            val currentBudget = prefs.getFloat("monthly_budget", 0f)
            text = if (currentBudget > 0) "月度预算  ¥%.0f".format(currentBudget) else "月度预算  未设置"
            textSize = 15f
            setTextColor(textPrimary)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { showBudgetSetting(prefs, this) }
        })

        addDivider()

        layout.addView(TextView(requireContext()).apply {
            text = "导出数据 (CSV)"
            textSize = 15f
            setTextColor(textPrimary)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { exportCsv() }
        })

        addDivider()

        // Import
        layout.addView(TextView(requireContext()).apply {
            text = "导入数据 (CSV)"
            textSize = 15f
            setTextColor(textPrimary)
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { importLauncher.launch(arrayOf("text/*", "text/csv", "*/*")) }
        })

        addDivider()

        // Clear data
        layout.addView(TextView(requireContext()).apply {
            text = "清除所有数据"
            textSize = 15f
            setTextColor(requireContext().getColor(R.color.expense))
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("确认清除")
                    .setMessage("将删除所有记账记录，此操作不可恢复")
                    .setPositiveButton("清除") { _, _ ->
                        CoroutineScope(Dispatchers.IO).launch {
                            (requireActivity().application as App).database
                                .openHelper.writableDatabase.execSQL("DELETE FROM transactions")
                            val prefs = requireContext().getSharedPreferences("suishiji", android.content.Context.MODE_PRIVATE)
                            prefs.edit()
                                .remove("birthday_month")
                                .remove("birthday_day")
                                .apply()
                            withContext(Dispatchers.Main) {
                                updateAll()
                                ToastManager.show(requireContext(), "已清除")
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })

        addDivider()

        // Version
        val versionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: Exception) { "1.1.1" }
        layout.addView(TextView(requireContext()).apply {
            text = "随时记 v$versionName"
            textSize = 13f
            setTextColor(textDisabled)
            gravity = Gravity.CENTER
        })

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            addView(layout)
        }

        val settingsDialog = AlertDialog.Builder(requireContext())
            .setTitle("设置")
            .setView(scrollView)
            .setPositiveButton("确定", null)
            .create()

        settingsDialog.show()

        // 设置高度为屏幕 2/3
        settingsDialog.window?.let { window ->
            val layoutParams = window.attributes
            val displayMetrics = resources.displayMetrics
            layoutParams.height = (displayMetrics.heightPixels * 2 / 3)
            window.attributes = layoutParams
        }
    }

    private fun showFixedExpenseManagementDialog() {
        val textPrimary = requireContext().getColor(R.color.text_primary)
        val textDisabled = requireContext().getColor(R.color.text_disabled)
        val primary = requireContext().getColor(R.color.primary)
        val borderColor = requireContext().getColor(R.color.border)
        val expenseColor = requireContext().getColor(R.color.expense)

        val scrollLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }

        // Custom title row: title + add button
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        titleRow.addView(TextView(requireContext()).apply {
            text = "固定支出管理"
            textSize = 18f
            setTextColor(textPrimary)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val addBtn = TextView(requireContext()).apply {
            gravity = Gravity.CENTER
            text = "+"
            textSize = 26f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            background = GradientDrawable().apply {
                cornerRadius = dp(21).toFloat(); setColor(primary)
            }
            elevation = 4f
        }
        titleRow.addView(addBtn)
        scrollLayout.addView(titleRow)

        val countText = TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(textDisabled)
            setPadding(0, dp(4), 0, 0)
        }
        scrollLayout.addView(countText)

        val topDivider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12); bottomMargin = dp(12)
            }
            setBackgroundColor(borderColor)
        }
        scrollLayout.addView(topDivider)

        val itemsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollLayout.addView(itemsContainer)

        fun refreshList() {
            itemsContainer.removeAllViews()
            countText.text = "共 0 项"
            CoroutineScope(Dispatchers.IO).launch {
                val fixedExpenses = (requireActivity().application as App).database.fixedExpenseDao().getAll()
                withContext(Dispatchers.Main) {
                    countText.text = "共 ${fixedExpenses.size} 项"
                    if (fixedExpenses.isEmpty()) {
                        itemsContainer.addView(TextView(requireContext()).apply {
                            text = "暂无固定支出"
                            textSize = 14f
                            setTextColor(textDisabled)
                            gravity = Gravity.CENTER
                            setPadding(0, dp(32), 0, dp(32))
                        })
                    } else {
                        var first = true
                        fixedExpenses.sortedBy { it.dayOfMonth }.forEach { fe ->
                            if (!first) {
                                itemsContainer.addView(View(requireContext()).apply {
                                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                                    setBackgroundColor(borderColor)
                                })
                            }
                            first = false
                            val item = LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, dp(12), 0, dp(12))
                            }
                            // Main row: day + amount (left), edit/delete (right)
                            val mainRow = LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            }
                            mainRow.addView(TextView(requireContext()).apply {
                                text = "每月${fe.dayOfMonth}日  ¥${"%.0f".format(fe.amount)}"
                                textSize = 15f
                                setTextColor(textPrimary)
                                typeface = Typeface.DEFAULT_BOLD
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            })
                            mainRow.addView(TextView(requireContext()).apply {
                                text = "编辑"
                                textSize = 13f
                                setTextColor(primary)
                                setPadding(dp(12), dp(4), dp(12), dp(4))
                                setOnClickListener {
                                    showFixedExpenseEditDialog(fe) { refreshList() }
                                }
                            })
                            mainRow.addView(TextView(requireContext()).apply {
                                text = "删除"
                                textSize = 13f
                                setTextColor(expenseColor)
                                setPadding(dp(4), dp(4), dp(4), dp(4))
                                setOnClickListener {
                                    AlertDialog.Builder(requireContext())
                                        .setTitle("确认删除")
                                        .setMessage("确定要删除这条固定支出吗？")
                                        .setPositiveButton("删除") { _, _ ->
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val dao = (requireActivity().application as App).database.fixedExpenseDao()
                                                dao.deleteById(fe.id)
                                                withContext(Dispatchers.Main) {
                                                    refreshList()
                                                    loadPeriodData(); loadMonthData()
                                                }
                                            }
                                        }
                                        .setNegativeButton("取消", null)
                                        .show()
                                }
                            })
                            item.addView(mainRow)
                            // Sub row: category + note
                            val catNote = buildString {
                                append(fe.category)
                                if (fe.note.isNotBlank()) append("  ·  ${fe.note}")
                            }
                            item.addView(TextView(requireContext()).apply {
                                text = catNote
                                textSize = 12f
                                setTextColor(textDisabled)
                                setPadding(0, dp(3), 0, 0)
                            })
                            itemsContainer.addView(item)
                        }
                    }
                }
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(scrollLayout)
            .create()
        dialog.show()
        dialog.window?.let { w ->
            val lp = w.attributes
            lp.height = (resources.displayMetrics.heightPixels * 2 / 3)
            w.attributes = lp
        }

        addBtn.setOnClickListener {
            showFixedExpenseEditDialog(null) { refreshList() }
        }

        refreshList()
    }

    private fun showFixedExpenseEditDialog(existing: FixedExpense?, onSaved: () -> Unit) {
        val textPrimary = requireContext().getColor(R.color.text_primary)
        val textDisabled = requireContext().getColor(R.color.text_disabled)
        val cardBg = requireContext().getColor(R.color.card_bg_alt)
        val borderColor = requireContext().getColor(R.color.border)
        val whiteColor = requireContext().getColor(R.color.white)
        val expenseColor = requireContext().getColor(R.color.expense)

        val dayInput = android.widget.EditText(requireContext()).apply {
            textSize = 14f
            hint = "1~28"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(cardBg)
            }
            if (existing != null) setText(existing.dayOfMonth.toString())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val t = s?.toString() ?: return
                    if (t.isEmpty()) return
                    val n = t.toIntOrNull()
                    if (n == null || n < 1 || n > 28) {
                        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(this@apply.windowToken, 0)
                        AlertDialog.Builder(requireContext())
                            .setTitle("提示")
                            .setMessage("只能在1~28之间")
                            .setPositiveButton("确定", null)
                            .show()
                        this@apply.setText("")
                    }
                }
            })
        }
        val amountInput = android.widget.EditText(requireContext()).apply {
            textSize = 14f
            hint = "金额"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(cardBg)
            }
            if (existing != null) setText(existing.amount.toString())
        }
        var selectedCategory = existing?.category ?: ""

        // Preview card (declared early so updatePreview can reference it)
        val previewCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(whiteColor)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16); bottomMargin = dp(8)
            }
        }
        val previewDay = TextView(requireContext()).apply { textSize = 12f; setTextColor(textDisabled) }
        val previewAmount = TextView(requireContext()).apply {
            textSize = 24f; setTextColor(expenseColor); typeface = Typeface.DEFAULT_BOLD
        }
        val previewCategory = TextView(requireContext()).apply { textSize = 13f; setTextColor(textPrimary); setPadding(0, dp(4), 0, 0) }
        val previewNote = TextView(requireContext()).apply { textSize = 12f; setTextColor(textDisabled) }
        previewCard.addView(previewDay)
        previewCard.addView(previewAmount)
        previewCard.addView(previewCategory)
        previewCard.addView(previewNote)

        val noteInput = android.widget.EditText(requireContext()).apply {
            textSize = 14f
            hint = "备注（可选）"
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat(); setColor(cardBg)
            }
            if (existing != null) setText(existing.note)
        }

        fun updatePreview() {
            val dayText = dayInput.text.toString()
            val amountText = amountInput.text.toString()
            val noteText = noteInput.text.toString().trim()
            previewDay.text = if (dayText.isNotBlank()) "每月${dayText}日" else "每月？日"
            previewAmount.text = if (amountText.isNotBlank()) "¥${"%.2f".format(amountText.toDoubleOrNull() ?: 0.0)}" else "¥0.00"
            previewCategory.text = if (selectedCategory.isNotBlank()) selectedCategory else "（请选择标签）"
            previewCategory.setTextColor(if (selectedCategory.isNotBlank()) textPrimary else textDisabled)
            previewNote.text = if (noteText.isNotBlank()) noteText else ""
            previewNote.visibility = if (noteText.isNotBlank()) View.VISIBLE else View.GONE
        }

        val chipGroup = ChipGroup(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        CoroutineScope(Dispatchers.IO).launch {
            val cats = (requireActivity().application as App).database.categoryDao().getAll().filter { !it.isIncome }
            withContext(Dispatchers.Main) {
                cats.forEach { cat ->
                    val chip = Chip(requireContext()).apply {
                        text = cat.name
                        isCheckable = true
                        chipBackgroundColor = ContextCompat.getColorStateList(requireContext(), R.color.chip_bg_selector)
                        chipStrokeColor = ContextCompat.getColorStateList(requireContext(), R.color.chip_stroke_selector)
                        chipStrokeWidth = resources.getDimension(R.dimen.chip_stroke_width)
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) selectedCategory = cat.name
                            else if (selectedCategory == cat.name) selectedCategory = ""
                            updatePreview()
                        }
                        if (cat.name == existing?.category) isChecked = true
                    }
                    chipGroup.addView(chip)
                }
            }
        }

        // Live preview updates
        val tw = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updatePreview() }
        }
        dayInput.addTextChangedListener(tw)
        amountInput.addTextChangedListener(tw)
        noteInput.addTextChangedListener(tw)

        updatePreview()

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12); bottomMargin = dp(4)
            }
            setBackgroundColor(borderColor)
        }

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(0))
            addView(TextView(requireContext()).apply { text = "每月几号"; textSize = 13f; setPadding(0, 0, 0, dp(4)) })
            addView(dayInput)
            addView(TextView(requireContext()).apply { text = "金额"; textSize = 13f; setPadding(0, dp(8), 0, dp(4)) })
            addView(amountInput)
            addView(TextView(requireContext()).apply { text = "标签"; textSize = 13f; setPadding(0, dp(8), 0, dp(4)) })
            addView(chipGroup)
            addView(TextView(requireContext()).apply { text = "备注"; textSize = 13f; setPadding(0, dp(8), 0, dp(4)) })
            addView(noteInput)
            addView(divider)
            addView(TextView(requireContext()).apply { text = "预览"; textSize = 12f; setTextColor(textDisabled) })
            addView(previewCard)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(if (existing != null) "编辑固定支出" else "新增固定支出")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val day = dayInput.text.toString().toIntOrNull()
                val amount = amountInput.text.toString().toDoubleOrNull()
                val cat = selectedCategory
                if (day == null || day < 1 || day > 28) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("提示")
                        .setMessage("固定支出号数需在 1~28 之间")
                        .setPositiveButton("确定", null)
                        .show()
                    dayInput.setText("")
                    return@setPositiveButton
                }
                if (amount == null || cat.isBlank()) return@setPositiveButton
                CoroutineScope(Dispatchers.IO).launch {
                    val dao = (requireActivity().application as App).database.fixedExpenseDao()
                    if (existing != null) {
                        dao.update(existing.copy(dayOfMonth = day, amount = amount, category = cat, note = noteInput.text.toString().trim()))
                    } else {
                        dao.insert(FixedExpense(dayOfMonth = day, amount = amount, category = cat, note = noteInput.text.toString().trim()))
                    }
                    withContext(Dispatchers.Main) { onSaved() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
            .window?.let { w ->
                val lp = w.attributes
                lp.height = (resources.displayMetrics.heightPixels * 2 / 3)
                w.attributes = lp
            }
    }

    private fun showBudgetSetting(prefs: android.content.SharedPreferences, budgetTextView: TextView?) {
        val currentBudget = prefs.getFloat("monthly_budget", 0f)
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(0))
        }
        layout.addView(TextView(requireContext()).apply {
            text = "每月预算金额（设为0关闭）"
            textSize = 13f
            setTextColor(requireContext().getColor(R.color.text_disabled))
        })
        val edit = android.widget.EditText(requireContext()).apply {
            setText(if (currentBudget > 0) currentBudget.toInt().toString() else "")
            hint = "如: 3000"
            textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(requireContext().getColor(R.color.card_bg_alt))
            }
        }
        layout.addView(edit)

        AlertDialog.Builder(requireContext())
            .setTitle("月度预算")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val amount = edit.text.toString().toFloatOrNull() ?: 0f
                prefs.edit().putFloat("monthly_budget", amount).apply()
                budgetTextView?.text = if (amount > 0) "月度预算  ¥%.0f".format(amount) else "月度预算  未设置"
                updateAll()
                ToastManager.show(requireContext(), if (amount > 0) "预算已设为¥%.0f".format(amount) else "预算已关闭")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportCsv() {
        val dao = (requireActivity().application as App).database.transactionDao()
        CoroutineScope(Dispatchers.IO).launch {
            val txns = dao.getAll()
            if (txns.isEmpty()) {
                withContext(Dispatchers.Main) {
                    ToastManager.show(requireContext(), "没有数据可导出")
                }
                return@launch
            }
            val csv = StringBuilder()
            csv.appendLine("amount,type,category,note,date,latitude,longitude,address")
            for (t in txns) {
                csv.appendLine("${t.amount},${t.type},${t.category},${t.note.replace(",", "，")},${t.date},${t.latitude ?: ""},${t.longitude ?: ""},${(t.address ?: "").replace(",", "，")}")
            }
            val file = java.io.File(requireContext().getExternalFilesDir(null), "随时记_导出_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(java.util.Date())}.csv")
            file.writeText(csv.toString(), Charsets.UTF_8)
            withContext(Dispatchers.Main) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(), "${requireContext().packageName}.fileprovider", file
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(intent, "导出数据"))
                ToastManager.show(requireContext(), "已导出 ${txns.size} 条记录")
            }
        }
    }

    private fun importCsv(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val input = requireContext().contentResolver.openInputStream(uri) ?: return@launch
                val bytes = input.readBytes()
                input.close()

                if (bytes.size < 4) {
                    withContext(Dispatchers.Main) { ToastManager.show(requireContext(), "文件为空") }
                    return@launch
                }

                val db = (requireActivity().application as App).database
                val dao = db.transactionDao()
                val catDao = db.categoryDao()
                val txns = mutableListOf<com.suishiji.db.Transaction>()

                // 检测文件格式：xlsx 以 PK (ZIP) 开头
                val isXlsx = bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

                val lines: List<String>
                if (isXlsx) {
                    lines = parseXlsx(bytes)
                } else {
                    // 尝试 UTF-8，如果不是则用 GBK（支付宝CSV为GBK编码）
                    val utf8Text = bytes.toString(Charsets.UTF_8)
                    val hasChineseHeader = utf8Text.contains("交易时间") && utf8Text.contains("金额")
                    if (hasChineseHeader) {
                        lines = utf8Text.lines().filter { it.isNotBlank() }
                    } else {
                        val gbk = java.nio.charset.Charset.forName("GBK")
                        lines = bytes.toString(gbk).lines().filter { it.isNotBlank() }
                    }
                }

                if (lines.size < 2) {
                    withContext(Dispatchers.Main) { ToastManager.show(requireContext(), "文件为空") }
                    return@launch
                }

                // 检测是否为微信/支付宝账单格式（表头可能不在第一行）
                val headerLine = lines.firstOrNull {
                    it.contains("交易时间") && it.contains("金额") &&
                        (it.contains("交易类型") || it.contains("交易分类"))
                }
                val headerIndex = if (headerLine != null) lines.indexOf(headerLine) else -1
                if (headerIndex >= 0 && headerLine != null) {
                    // 微信：交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
                    // 支付宝：交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
                    val headerParts = parseCsvLine(headerLine)
                    val headerMap = mutableMapOf<String, Int>()
                    headerParts.forEachIndexed { index, name -> headerMap[name.trim()] = index }

                    val allCats = catDao.getByType(false)
                    val allIncomeCats = catDao.getByType(true)

                    for (i in (headerIndex + 1) until lines.size) {
                        val parts = parseCsvLine(lines[i])
                        if (parts.size < 6) continue

                        val timeStr = headerMap["交易时间"]?.let { parts.getOrNull(it) } ?: continue
                        val txType = headerMap["交易类型"]?.let { parts.getOrNull(it) }
                            ?: headerMap["交易分类"]?.let { parts.getOrNull(it) } ?: ""
                        val merchant = headerMap["交易对方"]?.let { parts.getOrNull(it) } ?: ""
                        val direction = headerMap["收/支"]?.let { parts.getOrNull(it) } ?: ""
                        val amountStr = headerMap["金额(元)"]?.let { parts.getOrNull(it) }
                            ?: headerMap["金额"]?.let { parts.getOrNull(it) } ?: continue
                        val status = headerMap["当前状态"]?.let { parts.getOrNull(it) }
                            ?: headerMap["交易状态"]?.let { parts.getOrNull(it) } ?: ""
                        val remark = headerMap["备注"]?.let { parts.getOrNull(it) } ?: ""

                        // 跳过无效记录
                        if (direction.isBlank() || direction == "/") continue
                        if (status.contains("退款") || status.contains("关闭")) continue

                        val amount = amountStr.replace("¥", "").toDoubleOrNull() ?: continue
                        if (amount <= 0) continue

                        // 解析时间
                        val date = parseWechatTime(timeStr) ?: continue

                        // 确定类型
                        val type = if (direction == "收入") "income" else "expense"

                        // 自动分类
                        val category = matchCategory(merchant, txType, type == "income", allCats, allIncomeCats)

                        // 构建备注：交易对方 · 交易类型
                        val note = buildString {
                            append(merchant)
                            if (txType.isNotBlank() && txType != merchant) {
                                append(" · ").append(txType)
                            }
                            if (remark.isNotBlank() && remark != "/") {
                                append(" · ").append(remark)
                            }
                        }

                        txns.add(com.suishiji.db.Transaction(
                            amount = amount,
                            type = type,
                            category = category,
                            note = note,
                            date = date,
                            createdAt = date
                        ))
                    }
                } else {
                    // 原有 CSV 格式：amount,type,category,note,date,latitude,longitude,address
                    for (i in 1 until lines.size) {
                        val parts = parseCsvLine(lines[i])
                        if (parts.size >= 6) {
                            txns.add(com.suishiji.db.Transaction(
                                amount = parts[0].toDoubleOrNull() ?: continue,
                                type = parts[1],
                                category = parts[2],
                                note = parts[3],
                                date = parts[4].toLongOrNull() ?: continue,
                                latitude = parts[5].toDoubleOrNull(),
                                longitude = if (parts.size > 6) parts[6].toDoubleOrNull() else null,
                                address = if (parts.size > 7) parts[7] else null
                            ))
                        }
                    }
                }

                if (txns.isNotEmpty()) {
                    // 去重：按金额+类型+时间+备注判断
                    val unique = txns.filter { txn ->
                        dao.countDuplicate(txn.amount, txn.type, txn.date, txn.note) == 0
                    }
                    if (unique.isNotEmpty()) {
                        dao.insertAll(unique)
                    }
                    val skipped = txns.size - unique.size
                    withContext(Dispatchers.Main) {
                        val msg = buildString {
                            append("已导入 ${unique.size} 条记录")
                            if (skipped > 0) append("，跳过 $skipped 条重复记录")
                        }
                        ToastManager.show(requireContext(), msg)
                        updateAll()
                    }
                } else {
                    withContext(Dispatchers.Main) { ToastManager.show(requireContext(), "未找到可导入的记录") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    ToastManager.show(requireContext(), "导入失败: ${e.message}")
                }
            }
        }
    }

    private fun parseXlsx(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val zipEntries = mutableMapOf<String, ByteArray>()
        val sharedStrings = mutableListOf<String>()

        // 解析 ZIP
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    zipEntries[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }

        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false

        // 解析 styles.xml，识别日期格式的 xf 索引
        val dateXfIndices = mutableSetOf<Int>()
        val stylesBytes = zipEntries["xl/styles.xml"]
        if (stylesBytes != null) {
            try {
                val doc = factory.newDocumentBuilder().parse(stylesBytes.inputStream())

                // 收集日期相关的 numFmtId（自定义格式）
                val dateNumFmtIds = mutableSetOf<Int>()
                val numFmtNodes = doc.getElementsByTagName("numFmt")
                for (i in 0 until numFmtNodes.length) {
                    val el = numFmtNodes.item(i) as org.w3c.dom.Element
                    val code = el.getAttribute("formatCode").lowercase()
                    if (code.contains("yy") || code.contains("mm-dd") || code.contains("hh:mm") || code.contains("m/d")) {
                        dateNumFmtIds.add(el.getAttribute("numFmtId").toIntOrNull() ?: -1)
                    }
                }

                // 内置日期格式 ID：14-22, 27-36, 45-47
                val builtInDateFmtIds = setOf(
                    14, 15, 16, 17, 18, 19, 20, 21, 22,
                    27, 28, 29, 30, 31, 32, 33, 34, 35, 36,
                    45, 46, 47
                )

                // 解析 cellXfs，找到使用日期格式的 xf 索引
                val cellXfsNode = doc.getElementsByTagName("cellXfs").item(0)
                if (cellXfsNode != null) {
                    val xfNodes = (cellXfsNode as org.w3c.dom.Element).getElementsByTagName("xf")
                    for (i in 0 until xfNodes.length) {
                        val el = xfNodes.item(i) as org.w3c.dom.Element
                        val numFmtId = el.getAttribute("numFmtId").toIntOrNull() ?: 0
                        if (numFmtId in dateNumFmtIds || numFmtId in builtInDateFmtIds) {
                            dateXfIndices.add(i)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 解析 sharedStrings.xml
        val ssBytes = zipEntries["xl/sharedStrings.xml"]
        if (ssBytes != null) {
            val doc = factory.newDocumentBuilder().parse(ssBytes.inputStream())
            val siNodes = doc.getElementsByTagName("si")
            for (i in 0 until siNodes.length) {
                val si = siNodes.item(i)
                val textParts = mutableListOf<String>()
                val tNodes = (si as org.w3c.dom.Element).getElementsByTagName("t")
                for (j in 0 until tNodes.length) {
                    textParts.add(tNodes.item(j).textContent ?: "")
                }
                sharedStrings.add(textParts.joinToString(""))
            }
        }

        // 解析 sheet1.xml
        val sheetBytes = zipEntries["xl/worksheets/sheet1.xml"]
        if (sheetBytes != null) {
            val doc = factory.newDocumentBuilder().parse(sheetBytes.inputStream())
            val rows = doc.getElementsByTagName("row")
            for (r in 0 until rows.length) {
                val row = rows.item(r) as org.w3c.dom.Element
                val cells = row.getElementsByTagName("c")
                val rowData = mutableListOf<String>()
                var maxCol = 0
                for (c in 0 until cells.length) {
                    val cell = cells.item(c) as org.w3c.dom.Element
                    val ref = cell.getAttribute("r") // e.g. "A1"
                    val colIdx = ref.replace(Regex("[0-9]"), "").fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
                    val t = cell.getAttribute("t") // type: s=shared string
                    val sIdx = cell.getAttribute("s").toIntOrNull() ?: 0
                    val vNode = cell.getElementsByTagName("v")
                    val value = if (vNode.length > 0) vNode.item(0).textContent ?: "" else ""

                    val cellValue = if (t == "s" && value.isNotEmpty()) {
                        val idx = value.toIntOrNull() ?: 0
                        if (idx < sharedStrings.size) sharedStrings[idx] else value
                    } else if (sIdx in dateXfIndices && value.isNotEmpty()) {
                        // 日期格式单元格，将 Excel 序列号转为日期字符串
                        val serial = value.toDoubleOrNull()
                        if (serial != null && serial > 0) excelSerialToDateTime(serial) else value
                    } else {
                        value
                    }

                    while (rowData.size <= colIdx) rowData.add("")
                    rowData[colIdx] = cellValue
                    if (colIdx > maxCol) maxCol = colIdx
                }
                if (rowData.isNotEmpty()) {
                    result.add(rowData.joinToString(","))
                }
            }
        }

        return result
    }

    private fun excelSerialToDateTime(serial: Double): String {
        val cal = java.util.GregorianCalendar(java.util.Locale.US)
        cal.set(1899, java.util.Calendar.DECEMBER, 30, 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_MONTH, serial.toInt())
        val millis = ((serial - serial.toInt()) * 24.0 * 60 * 60 * 1000).toLong()
        cal.add(java.util.Calendar.MILLISECOND, millis.toInt())
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
        return sdf.format(cal.time)
    }

    private fun parseWechatTime(timeStr: String): Long? {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            sdf.parse(timeStr)?.time
        } catch (e: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy/M/d HH:mm:ss", java.util.Locale.CHINA)
                sdf.parse(timeStr)?.time
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun matchCategory(merchant: String, txType: String, isIncome: Boolean, expenseCats: List<com.suishiji.db.Category>, incomeCats: List<com.suishiji.db.Category>): String {
        // 支付宝交易分类映射到应用分类
        if (txType.isNotBlank()) {
            val mapped = mapAlipayCategory(txType, isIncome)
            if (mapped != null) return mapped
        }
        if (merchant.isBlank()) return if (isIncome) "其它收入" else "其它"
        val cats = if (isIncome) incomeCats else expenseCats
        for (cat in cats) {
            if (cat.keywords.isNotBlank()) {
                for (kw in cat.keywords.split(",")) {
                    if (kw.isNotBlank() && merchant.contains(kw, ignoreCase = true)) {
                        return cat.name
                    }
                }
            }
        }
        return if (isIncome) "其它收入" else "其它"
    }

    private fun mapAlipayCategory(txType: String, isIncome: Boolean): String? {
        val t = txType.trim()
        if (isIncome) {
            return when {
                t.contains("工资") || t.contains("劳务") -> "工资"
                t.contains("兼职") || t.contains("副业") -> "兼职"
                t.contains("理财") || t.contains("投资") -> "理财"
                t.contains("转账") || t.contains("红包") -> "其它收入"
                else -> null
            }
        } else {
            return when {
                t.contains("餐饮") || t.contains("美食") -> "食物"
                t.contains("日用") || t.contains("百货") || t.contains("家居") || t.contains("家装") -> "生活"
                t.contains("交通") || t.contains("出行") -> "交通"
                t.contains("住宿") || t.contains("酒店") -> "住宿"
                t.contains("休闲") || t.contains("娱乐") || t.contains("文化") -> "娱乐"
                t.contains("转账") || t.contains("红包") || t.contains("充值") || t.contains("缴费") || t.contains("投资") || t.contains("理财") -> "其它"
                else -> null
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun setPeriod(mode: Int) {
        periodMode = mode
        val white = requireContext().getColor(R.color.white)
        val unselected = requireContext().getColor(R.color.segment_unselected)

        // Selected: white text on blue, Unselected: dark text on gray
        binding.tabDay.setTextColor(if (mode == 0) white else unselected)
        binding.tabMonth.setTextColor(if (mode == 1) white else unselected)
        binding.tabYear.setTextColor(if (mode == 2) white else unselected)

        // Animate indicator to selected tab
        binding.segmentIndicator.post {
            val container = binding.segmentContainer
            val containerWidth = container.width - dp(4) // subtract padding
            val tabWidth = containerWidth / 3

            binding.segmentIndicator.animate()
                .translationX((mode * tabWidth + dp(2)).toFloat())
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        loadPeriodData()
        loadMonthData()
    }

    private fun loadMonthData() {
        val txDao = (requireActivity().application as App).database.transactionDao()
        val feDao = (requireActivity().application as App).database.fixedExpenseDao()
        val (monthStart, monthEnd) = getMonthRange()

        CoroutineScope(Dispatchers.IO).launch {
            val expense = txDao.getExpenseSum(monthStart, monthEnd)
            val income = txDao.getIncomeSum(monthStart, monthEnd)
            val dates = txDao.getDatesWithTransactions(monthStart, monthEnd)
            val dailySums = txDao.getDailyExpenseSums(monthStart, monthEnd)
            val fixedExpenses = feDao.getAll()

            val dayCal = Calendar.getInstance()
            val txDates = dates.map { ts ->
                dayCal.timeInMillis = ts
                dayCal.get(Calendar.DAY_OF_MONTH).toLong()
            }.toSet()

            val feDates = fixedExpenses
                .filter { !it.skippedMonths.split(",").contains("%04d-%02d".format(year, month + 1)) }
                .map { it.dayOfMonth.toLong() }.toSet()

            // Build daily expense map (day of month -> total)
            val dailyMap = mutableMapOf<Int, Double>()
            dailySums.forEach { ds ->
                dayCal.timeInMillis = ds.date
                val day = dayCal.get(Calendar.DAY_OF_MONTH)
                dailyMap[day] = (dailyMap[day] ?: 0.0) + ds.total
            }
            // Add fixed expenses to daily map
            val currentMonthKeyForMap = "%04d-%02d".format(year, month + 1)
            fixedExpenses.forEach { fe ->
                if (!fe.skippedMonths.split(",").contains(currentMonthKeyForMap)) {
                    dailyMap[fe.dayOfMonth] = (dailyMap[fe.dayOfMonth] ?: 0.0) + fe.amount
                }
            }

            val totalExpense = expense + fixedExpenses
                .filter { !it.skippedMonths.split(",").contains(currentMonthKeyForMap) }
                .sumOf { it.amount }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                binding.summaryCard.visibility = View.VISIBLE
                binding.tvExpense.text = "¥%.2f".format(totalExpense)
                binding.tvIncome.text = "¥%.2f".format(income)
                binding.tvBalance.text = "¥%.2f".format(income - totalExpense)

                transactionDates = txDates + feDates
                dailyExpenseMap = dailyMap

                buildCalendarGrid()
                updateBudgetCard(totalExpense)
            }
        }
    }

    private fun loadPeriodData() {
        val txDao = (requireActivity().application as App).database.transactionDao()
        val feDao = (requireActivity().application as App).database.fixedExpenseDao()
        val (start, end, label) = getPeriodRange()

        CoroutineScope(Dispatchers.IO).launch {
            val data = txDao.getBetween(start, end).toMutableList()
            val expense = txDao.getExpenseSum(start, end)
            val income = txDao.getIncomeSum(start, end)
            val fixedExpenses = feDao.getAll()

            val cal = Calendar.getInstance()
            val currentMonthKey = "%04d-%02d".format(year, month + 1)
            var feExpense = 0.0
            fixedExpenses.forEach { fe ->
                if (fe.skippedMonths.split(",").contains(currentMonthKey)) return@forEach
                cal.set(year, month, fe.dayOfMonth, 12, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val feTime = cal.timeInMillis
                if (feTime in start until end) {
                    data.add(Transaction(
                        id = -(fe.id + 1000000L),
                        amount = fe.amount,
                        type = "expense",
                        category = fe.category,
                        note = "[固定] ${fe.note}",
                        date = feTime,
                        createdAt = feTime
                    ))
                    feExpense += fe.amount
                }
            }
            data.sortByDescending { it.createdAt }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                listAdapter.setData(data)
                binding.tvPeriodLabel.text = label
                binding.tvPeriodExpense.text = "支出¥%.0f".format(expense + feExpense)
                binding.tvPeriodIncome.text = "收入¥%.0f".format(income)
                binding.tvNoRecords.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
                binding.rvRecords.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun getPeriodRange(): Triple<Long, Long, String> = getPeriodRangeByMode(periodMode)

    private fun getMonthRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(year, month, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return Pair(start, cal.timeInMillis)
    }

    private fun getPeriodRangeByMode(mode: Int): Triple<Long, Long, String> {
        val cal = Calendar.getInstance()
        return when (mode) {
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
            else -> getPeriodRangeByMode(1)
        }
    }

    private fun getDayStart(day: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month, day, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // --- Calendar ---

    private fun buildCalendarGrid() {
        binding.calendarGrid.removeAllViews()
        val cal = Calendar.getInstance()
        cal.set(year, month, 1)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val startOffset = if (firstDayOfWeek == Calendar.SUNDAY) 6 else firstDayOfWeek - Calendar.MONDAY

        val today = Calendar.getInstance()
        val isCurrentMonth = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) == month
        val todayDay = today.get(Calendar.DAY_OF_MONTH)

        val avgDaily = if (dailyExpenseMap.isNotEmpty()) dailyExpenseMap.values.sum() / daysInMonth else 0.0

        for (i in 0 until 42) {
            val dayNum = i - startOffset + 1
            val cellView = if (dayNum in 1..daysInMonth) {
                createDayCell(dayNum, dayNum == todayDay && isCurrentMonth, dayNum == selectedDay, avgDaily)
            } else {
                View(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(0, dp(44))
                }
            }
            binding.calendarGrid.addView(cellView, GridLayout.LayoutParams().apply {
                width = 0; height = dp(44)
                columnSpec = GridLayout.spec(i % 7, 1f)
                rowSpec = GridLayout.spec(i / 7)
                setMargins(1, 1, 1, 1)
            })
        }
    }

    private fun createDayCell(day: Int, isToday: Boolean, isSelected: Boolean, avgDaily: Double): View {
        val prefs = requireContext().getSharedPreferences("suishiji", android.content.Context.MODE_PRIVATE)
        val bMonth = prefs.getInt("birthday_month", -1)
        val bDay = prefs.getInt("birthday_day", -1)
        val isBirthday = (month + 1 == bMonth && day == bDay)

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (isSelected) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat(); setColor(ContextCompat.getColor(requireContext(), R.color.calendar_selected))
                }
            } else if (isBirthday) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat(); setColor(ContextCompat.getColor(requireContext(), R.color.calendar_birthday))
                }
            }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val isHiddenSep23 = (month + 1 == 9 && day == 23)
                val key = "${year}_${month}_${day}"
                val now = System.currentTimeMillis()
                val lastClick = dayClickTimestamps[key] ?: 0L

                // Reset count if gap > 400ms
                val count = if (now - lastClick > 400) {
                    dayClickCount[key] = 0
                    0
                } else {
                    dayClickCount[key] ?: 0
                }
                val newCount = count + 1
                dayClickCount[key] = newCount
                dayClickTimestamps[key] = now

                when {
                    // Hidden Sep 23: 5 clicks trigger lover celebration
                    isHiddenSep23 && newCount >= 5 -> {
                        dayClickCount.remove(key)
                        dayClickTimestamps.remove(key)
                        showBirthdayCelebration(isSpecial = true)
                    }
                    isHiddenSep23 && newCount == 4 -> {
                        ToastManager.show(requireContext(), "再点一次...")
                    }
                    // User-set birthday: 7 clicks trigger celebration
                    isBirthday && newCount >= 7 -> {
                        dayClickCount.remove(key)
                        dayClickTimestamps.remove(key)
                        showBirthdayCelebration()
                    }
                    isBirthday && newCount == 6 -> {
                        ToastManager.show(requireContext(), "再点一次...")
                    }
                    // Normal day: double-click navigates to category summary
                    !isBirthday && !isHiddenSep23 && newCount >= 2 -> {
                        dayClickCount.remove(key)
                        dayClickTimestamps.remove(key)
                        selectedDay = day
                        setPeriod(0)
                        pendingYear = year
                        pendingMonth = month
                        pendingSelectedDay = day
                        pendingCategoryType = "expense"
                        pendingPeriodMode = 0
                        android.util.Log.d("Suishiji", "双击日期: year=$year month=$month day=$day selectedDay=$selectedDay periodMode=$periodMode")
                        requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                            .selectedItemId = R.id.categoryFragment
                    }
                    // Normal day: single click
                    else -> {
                        selectedDay = day
                        setPeriod(0)
                        buildCalendarGrid()
                    }
                }
            }
            setOnLongClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("设置生日")
                    .setMessage("将 ${month + 1}月${day}日 设为你的生日？")
                    .setPositiveButton("确定") { _, _ ->
                        prefs.edit()
                            .putInt("birthday_month", month + 1)
                            .putInt("birthday_day", day)
                            .apply()
                        ToastManager.show(requireContext(), "生日已设为${month + 1}月${day}日")
                        buildCalendarGrid()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        layout.addView(TextView(requireContext()).apply {
            text = day.toString(); textSize = 14f; gravity = Gravity.CENTER
            setTextColor(when {
                isSelected -> Color.WHITE
                isBirthday && !isSelected -> Color.parseColor("#FF6F00")
                isToday -> ContextCompat.getColor(requireContext(), R.color.calendar_today)
                else -> requireContext().getColor(R.color.text_primary)
            })
            if (isToday) setTypeface(null, Typeface.BOLD)
        })

        if (isBirthday) {
            layout.addView(TextView(requireContext()).apply {
                text = "🎂"; textSize = 8f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(10)).apply { topMargin = dp(1) }
            })
        } else if (dailyExpenseMap.containsKey(day)) {
            val daySpending = dailyExpenseMap[day] ?: 0.0
            val dotColor = if (daySpending > avgDaily) ContextCompat.getColor(requireContext(), R.color.calendar_dot_high) else ContextCompat.getColor(requireContext(), R.color.calendar_dot_low)
            layout.addView(View(requireContext()).apply {
                setBackgroundColor(dotColor)
                layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).apply { topMargin = dp(2) }
            })
        }
        return layout
    }

    // --- Birthday ---

    private fun showBirthdayCelebration(isSpecial: Boolean = false) {
        val celebrationView = com.suishiji.view.CelebrationView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
        }

        val emoji = TextView(requireContext()).apply {
            text = if (isSpecial) "💖" else "🎂"
            textSize = 42f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            alpha = 0f
            scaleX = 0.3f
            scaleY = 0.3f
            translationY = -dp(40).toFloat()
        }

        val msg = TextView(requireContext()).apply {
            text = if (isSpecial) "宝贝生日快乐！" else "生日快乐！"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor(if (isSpecial) "#E91E63" else "#FF6F00"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            alpha = 0f
            translationY = dp(40).toFloat()
            setPadding(0, dp(8), 0, 0)
        }

        val sub = TextView(requireContext()).apply {
            text = if (isSpecial) "遇见你是最美好的事，爱你！" else "愿你每天都开心！"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(requireContext().getColor(if (isSpecial) R.color.primary else R.color.text_disabled))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            alpha = 0f
            translationY = dp(30).toFloat()
            setPadding(0, dp(4), 0, 0)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(emoji)
            addView(msg)
            addView(sub)
        }

        val root = FrameLayout(requireContext()).apply {
            addView(celebrationView)
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(null)
            .setView(root)
            .setPositiveButton(if (isSpecial) "爱你！" else "天天开心！", null)
            .create()

        dialog.show()
        celebrationView.post { celebrationView.start() }

        emoji.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(500)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.5f))
            .start()

        fun bounceLoop() {
            if (!dialog.isShowing) return
            emoji.animate()
                .translationY(-dp(8).toFloat())
                .setDuration(600)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction {
                    if (!dialog.isShowing) return@withEndAction
                    emoji.animate()
                        .translationY(0f)
                        .setDuration(600)
                        .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction { bounceLoop() }
                        .start()
                }
                .start()
        }
        emoji.postDelayed({ bounceLoop() }, 600)

        msg.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(200)
            .setDuration(450)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f))
            .start()

        sub.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(400)
            .setDuration(450)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f))
            .start()

        dialog.setOnDismissListener { celebrationView.stop() }
    }

    // --- Budget ---

    private fun updateBudgetCard(monthExpense: Double) {
        val prefs = requireContext().getSharedPreferences("suishiji", android.content.Context.MODE_PRIVATE)
        val budget = prefs.getFloat("monthly_budget", 0f).toDouble()
        if (budget <= 0) {
            binding.budgetCard.visibility = View.GONE
            return
        }
        binding.budgetCard.visibility = View.VISIBLE
        val remain = budget - monthExpense
        val percent = (monthExpense / budget * 100).toInt().coerceIn(0, 100)

        binding.tvBudgetRemainCard.text = if (remain >= 0) "剩余¥%.0f".format(remain) else "超支¥%.0f".format(-remain)
        binding.tvBudgetRemainCard.setTextColor(requireContext().getColor(if (remain >= 0) R.color.income else R.color.expense))
        binding.tvBudgetSpent.text = "已用¥%.0f".format(monthExpense)
        binding.tvBudgetTotal.text = "预算¥%.0f".format(budget)

        val barColor = when {
            percent >= 100 -> requireContext().getColor(R.color.expense)
            percent >= 80 -> Color.parseColor("#FF9500")
            else -> requireContext().getColor(R.color.primary)
        }
        binding.budgetProgress.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(barColor)
        }
        binding.budgetProgress.post {
            val totalWidth = (binding.budgetProgress.parent as View).width
            binding.budgetProgress.layoutParams = FrameLayout.LayoutParams(
                (totalWidth * percent / 100f).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    // --- Pie Chart Dialog ---

    private fun showPieChartDialog(mode: Int = periodMode) {
        val suffixes = arrayOf("日", "月", "年")
        val (start, end, _) = getPeriodRangeByMode(mode)

        val dao = (requireActivity().application as App).database.transactionDao()
        CoroutineScope(Dispatchers.IO).launch {
            val expCats = dao.getCategorySums("expense", start, end)
            val incCats = dao.getCategorySums("income", start, end)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext

                val dialog = AlertDialog.Builder(requireContext()).create()

                val root = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                }

                val title = TextView(requireContext()).apply {
                    text = "分类汇总（${suffixes[mode]}）"
                    textSize = 18f
                    setTextColor(requireContext().getColor(R.color.text_primary))
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(16))
                }
                root.addView(title)

                // Expense section
                if (expCats.isNotEmpty()) {
                    val expTitle = TextView(requireContext()).apply {
                        text = "支出分类"
                        textSize = 14f
                        setTextColor(requireContext().getColor(R.color.expense))
                        setPadding(0, 0, 0, dp(8))
                    }
                    root.addView(expTitle)

                    val expRow = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val expChart = com.suishiji.view.RingChartView(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(130), dp(130))
                    }
                    expChart.setData(expCats.map { it.category to it.total })
                    expRow.addView(expChart)

                    val expLegend = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(dp(12), 0, 0, 0)
                    }
                    for ((color, text) in expChart.getLegend()) {
                        val row = LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(2), 0, dp(2))
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
                        expLegend.addView(row)
                    }
                    expRow.addView(expLegend)
                    root.addView(expRow)
                }

                // Income section
                if (incCats.isNotEmpty()) {
                    val incTitle = TextView(requireContext()).apply {
                        text = "收入分类"
                        textSize = 14f
                        setTextColor(requireContext().getColor(R.color.income))
                        setPadding(0, dp(12), 0, dp(8))
                    }
                    root.addView(incTitle)

                    val incRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val incChart = com.suishiji.view.RingChartView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(130), dp(130))
            }
            incChart.setData(incCats.map { it.category to it.total })
            incRow.addView(incChart)

            val incLegend = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(12), 0, 0, 0)
            }
            for ((color, text) in incChart.getLegend()) {
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
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
                incLegend.addView(row)
            }
            incRow.addView(incLegend)
            root.addView(incRow)
        }

        if (expCats.isEmpty() && incCats.isEmpty()) {
            root.addView(TextView(requireContext()).apply {
                text = "暂无数据"
                textSize = 14f
                setTextColor(requireContext().getColor(R.color.text_disabled))
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
        }

        dialog.setView(root)
        dialog.show()

        // Animate in
        root.alpha = 0f
        root.scaleX = 0.8f
        root.scaleY = 0.8f
        root.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(250)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
            .start()
            }
        }
    }

    // --- Utils ---

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    // --- Log Viewer ---

    private fun showLogViewer() {
        val logs = LogManager.getLogs()
        val logText = TextView(requireContext()).apply {
            text = logs.ifEmpty { "暂无日志" }
            textSize = 11f
            setTextColor(requireContext().getColor(R.color.text_primary))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            typeface = android.graphics.Typeface.MONOSPACE
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod()
        }

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            addView(logText)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(400)
            )
        }

        AlertDialog.Builder(requireContext())
            .setTitle("运行日志")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清除") { _, _ ->
                LogManager.clearLogs()
                ToastManager.show(requireContext(), "日志已清除")
            }
            .show()
    }

    // --- Add Record Bottom Sheet ---

    private fun showAddSheet() {
        val now = System.currentTimeMillis()
        if (now - lastFabClickTime < 1000) return
        lastFabClickTime = now

        val dialog = AddRecordDialog().apply {
            arguments = Bundle().apply {
                putInt("year", year)
                putInt("month", month)
                putInt("selectedDay", selectedDay)
            }
            onRecordSaved = {
                loadMonthData()
                loadPeriodData()
            }
        }
        dialog.show(childFragmentManager, "add_record")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
