package com.suishiji.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.Dialog
import android.util.Log
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.suishiji.util.ToastManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.chip.Chip
import com.suishiji.App
import com.suishiji.R
import com.suishiji.databinding.DialogAddRecordBinding
import com.suishiji.db.Transaction
import com.suishiji.util.AppLocationManager
import com.suishiji.util.LocResult
import com.suishiji.util.PreciseLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AddRecordDialog : DialogFragment() {
    private var _binding: DialogAddRecordBinding? = null
    private val binding get() = _binding!!

    var onRecordSaved: (() -> Unit)? = null

    private var year = 0
    private var month = 0
    private var selectedDay = 0

    private var addTxType = "expense"
    private var addTxCategory = ""
    private var addTxLat: Double? = null
    private var addTxLon: Double? = null
    private var addTxAddr: String? = null
    private var addTxAccuracy: Float? = null
    private var addExpenseCategories = listOf<com.suishiji.db.Category>()
    private var addIncomeCategories = listOf<com.suishiji.db.Category>()
    private var addPreciseLocator: PreciseLocator? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && isAdded) fetchLocation()
    }

    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private val autoSaveRunnable = Runnable {
        if (isAdded && binding.btnSave.isEnabled) {
            saveTransaction()
        }
    }

    private fun cancelAutoSave() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
    }

    private var editTxId: Long = 0

    companion object {
        private const val ARG_FAB_X = "fab_x"
        private const val ARG_FAB_Y = "fab_y"

        fun newInstance(fabX: Int, fabY: Int): AddRecordDialog {
            return AddRecordDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_FAB_X, fabX)
                    putInt(ARG_FAB_Y, fabY)
                }
            }
        }

        fun newInstanceForNotification(
            amount: String, merchant: String, source: String,
            type: String, category: String, date: Long,
            latitude: Double?, longitude: Double?, address: String?
        ): AddRecordDialog {
            return AddRecordDialog().apply {
                arguments = Bundle().apply {
                    putBoolean("from_notification", true)
                    putBoolean("prevent_auto_save", true)
                    putString("amount", amount)
                    putString("merchant", merchant)
                    putString("source", source)
                    putString("type", type)
                    putString("category", category)
                    putLong("date", date)
                    putDouble("latitude", latitude ?: 0.0)
                    putDouble("longitude", longitude ?: 0.0)
                    putString("address", address ?: "")
                }
            }
        }

        fun newInstanceForEdit(tx: Transaction): AddRecordDialog {
            return AddRecordDialog().apply {
                arguments = Bundle().apply {
                    putBoolean("from_edit", true)
                    putLong("edit_id", tx.id)
                    putString("amount", tx.amount.toString())
                    putString("merchant", tx.note)
                    putString("type", tx.type)
                    putString("category", tx.category)
                    putLong("date", tx.date)
                    putDouble("latitude", tx.latitude ?: 0.0)
                    putDouble("longitude", tx.longitude ?: 0.0)
                    putString("address", tx.address ?: "")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.AddRecordDialogTheme)
        arguments?.let {
            year = it.getInt("year", Calendar.getInstance().get(Calendar.YEAR))
            month = it.getInt("month", Calendar.getInstance().get(Calendar.MONTH))
            selectedDay = it.getInt("selectedDay", Calendar.getInstance().get(Calendar.DAY_OF_MONTH))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogAddRecordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnExpense.setOnClickListener { setTxType("expense") }
        binding.btnIncome.setOnClickListener { setTxType("income") }

        updateDateDisplay()
        binding.tvDate.setOnClickListener { showDatePicker() }

        binding.btnSave.isEnabled = false
        binding.btnSave.alpha = 0.4f
        binding.btnSave.setOnClickListener { saveTransaction() }

        binding.etNote.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                cancelAutoSave()
                val text = s?.toString() ?: return
                if (text.isNotBlank()) autoSelectCategory(text)
            }
        })

        binding.etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { cancelAutoSave() }
        })

        loadCategories()

        // Prefill from notification or edit arguments
        arguments?.let { args ->
            val fromNotif = args.getBoolean("from_notification", false)
            val fromEdit = args.getBoolean("from_edit", false)
            if (fromNotif || fromEdit) {
                if (fromEdit) {
                    editTxId = args.getLong("edit_id", 0)
                }
                val prefType = args.getString("type") ?: "expense"
                setTxType(prefType)

                val prefAmount = args.getString("amount") ?: ""
                binding.etAmount.setText(prefAmount)

                val prefMerchant = args.getString("merchant") ?: ""
                val prefSource = args.getString("source") ?: ""
                binding.etNote.setText(if (prefMerchant.isNotBlank()) prefMerchant else prefSource)

                val prefCategory = args.getString("category") ?: ""
                addTxCategory = prefCategory

                val prefDate = args.getLong("date", System.currentTimeMillis())
                val cal = Calendar.getInstance()
                cal.timeInMillis = prefDate
                year = cal.get(Calendar.YEAR)
                month = cal.get(Calendar.MONTH)
                selectedDay = cal.get(Calendar.DAY_OF_MONTH)
                updateDateDisplay()

                val prefLat = args.getDouble("latitude", 0.0)
                val prefLon = args.getDouble("longitude", 0.0)
                val prefAddr = args.getString("address") ?: ""
                if (prefLat != 0.0 || prefLon != 0.0) {
                    addTxLat = prefLat
                    addTxLon = prefLon
                    addTxAddr = prefAddr.ifBlank { null }
                    val addrText = addTxAddr
                    binding.tvLocation.text = if (addrText.isNullOrBlank()) "已定位" else "已定位: $addrText"
                    binding.tvLocation.visibility = View.VISIBLE
                }

                binding.btnSave.isEnabled = true
                binding.btnSave.alpha = 1f

                // Schedule auto-save for notification-sourced dialogs (unless clicking from existing notification)
                if (fromNotif && args.getBoolean("prevent_auto_save", false).not()) {
                    autoSaveHandler.postDelayed(autoSaveRunnable, 3000)
                }
            }
        }

        // Fetch location for manual add and notification (not for edit)
        if (arguments?.getBoolean("from_edit", false) != true) {
            fetchLocation()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val metrics = resources.displayMetrics
            val dialogWidth = (metrics.widthPixels * 0.88).toInt()
            window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.CENTER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.5f)
        }
    }

    private fun setTxType(type: String) {
        addTxType = type
        val white = ContextCompat.getColor(requireContext(), R.color.white)
        val unselected = ContextCompat.getColor(requireContext(), R.color.segment_unselected)
        if (type == "expense") {
            binding.btnExpense.setBackgroundResource(R.drawable.bg_segment_selected)
            binding.btnExpense.setTextColor(white)
            binding.btnIncome.setBackgroundColor(Color.TRANSPARENT)
            binding.btnIncome.setTextColor(unselected)
        } else {
            binding.btnIncome.setBackgroundResource(R.drawable.bg_segment_selected)
            binding.btnIncome.setTextColor(white)
            binding.btnExpense.setBackgroundColor(Color.TRANSPARENT)
            binding.btnExpense.setTextColor(unselected)
        }
        showCategories()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        addPreciseLocator?.stop()
        addPreciseLocator = null
        autoSaveHandler.removeCallbacks(autoSaveRunnable)
        _binding = null
    }

    private fun loadCategories() {
        val dao = (requireActivity().application as App).database.categoryDao()
        CoroutineScope(Dispatchers.IO).launch {
            val all = dao.getAll()
            val exp = all.filter { !it.isIncome }
            val inc = all.filter { it.isIncome }
            withContext(Dispatchers.Main) {
                addExpenseCategories = exp
                addIncomeCategories = inc
                showCategories()
            }
        }
    }

    private fun showCategories() {
        val cats = if (addTxType == "expense") addExpenseCategories else addIncomeCategories
        binding.chipGroup.removeAllViews()
        cats.forEach { cat ->
            val chip = Chip(requireContext())
            chip.text = cat.name
            chip.isCheckable = true
            chip.chipBackgroundColor = ContextCompat.getColorStateList(requireContext(), R.color.chip_bg_selector)
            chip.chipStrokeColor = ContextCompat.getColorStateList(requireContext(), R.color.chip_stroke_selector)
            chip.chipStrokeWidth = resources.getDimension(R.dimen.chip_stroke_width)
            chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    cancelAutoSave()
                    addTxCategory = cat.name
                    for (i in 0 until binding.chipGroup.childCount) {
                        val other = binding.chipGroup.getChildAt(i) as? Chip
                        if (other != chip) other?.isChecked = false
                    }
                }
            }
            binding.chipGroup.addView(chip)
        }
        if (cats.isNotEmpty()) {
            addTxCategory = cats.first().name
            (binding.chipGroup.getChildAt(0) as? Chip)?.isChecked = true
        }
    }

    private fun autoSelectCategory(text: String) {
        val cats = if (addTxType == "expense") addExpenseCategories else addIncomeCategories
        for (cat in cats) {
            if (cat.keywords.isNotBlank()) {
                for (kw in cat.keywords.split(",")) {
                    if (kw.isNotBlank() && text.contains(kw, ignoreCase = true)) {
                        addTxCategory = cat.name
                        for (i in 0 until binding.chipGroup.childCount) {
                            val chip = binding.chipGroup.getChildAt(i) as? Chip
                            chip?.isChecked = chip?.text == cat.name
                        }
                        return
                    }
                }
            }
        }
    }

    private fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        binding.tvLocation.text = "正在定位..."
        binding.tvLocation.setOnClickListener(null)
        binding.tvLocation.visibility = View.VISIBLE

        // 立即启用保存按钮，不等定位
        binding.btnSave.isEnabled = true
        binding.btnSave.alpha = 1f

        addPreciseLocator?.stop()
        addTxAccuracy = null
        addPreciseLocator = PreciseLocator(requireContext())
        addPreciseLocator!!.locate(object : com.suishiji.util.LocationCallback {
            override fun onResult(result: LocResult) {
                addTxAddr = result.address
                addTxLat = result.latitude
                addTxLon = result.longitude
                if (isAdded) {
                    val accText = if (result.accuracy > 0) " (${result.accuracy.toInt()}米)" else ""
                    val addrText = addTxAddr
                    binding.tvLocation.text = if (addrText.isNullOrBlank()) "已定位$accText" else "已定位$accText: $addrText"
                    binding.tvLocation.setOnClickListener { fetchLocationGps() }
                }
            }
            override fun onBetterResult(result: LocResult) {
                addTxAddr = result.address
                addTxLat = result.latitude
                addTxLon = result.longitude
                if (isAdded) {
                    val accText = if (result.accuracy > 0) " (${result.accuracy.toInt()}米)" else ""
                    val addrText = addTxAddr
                    binding.tvLocation.text = if (addrText.isNullOrBlank()) "已定位$accText" else "已定位$accText: $addrText"
                }
            }
            override fun onUpdate(result: LocResult) {
                addTxAddr = result.address
                if (isAdded && result.address != null) {
                    val accText = if (result.accuracy > 0) " (${result.accuracy.toInt()}米)" else ""
                    binding.tvLocation.text = "已定位$accText: ${result.address}"
                    binding.tvLocation.setOnClickListener { fetchLocationGps() }
                }
            }
            override fun onError(message: String) {
                if (isAdded) {
                    binding.tvLocation.text = "点击重新定位"
                    binding.tvLocation.setOnClickListener { fetchLocationGps() }
                }
            }
        })
    }

    private fun fetchLocationGps() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        binding.tvLocation.text = "GPS定位中..."
        binding.tvLocation.setOnClickListener(null)
        binding.tvLocation.visibility = View.VISIBLE

        addPreciseLocator?.stop()
        addPreciseLocator = PreciseLocator(requireContext())
        addPreciseLocator!!.locateGps(object : com.suishiji.util.LocationCallback {
            override fun onResult(result: LocResult) {
                addTxLat = result.latitude
                addTxLon = result.longitude
                addTxAccuracy = result.accuracy
                if (isAdded) {
                    val accText = if (result.accuracy > 0) " (${result.accuracy.toInt()}米)" else ""
                    val addrText = addTxAddr
                    binding.tvLocation.text = if (addrText.isNullOrBlank()) "GPS已定位$accText" else "GPS已定位$accText: $addrText"
                    binding.tvLocation.setOnClickListener { fetchLocationGps() }
                }
                AppLocationManager.searchPoi(requireContext(), result.latitude, result.longitude) { poiName ->
                    if (poiName != null) {
                        addTxAddr = poiName
                        binding.root.post {
                            if (isAdded) {
                                val accText = if (result.accuracy > 0) " (${result.accuracy.toInt()}米)" else ""
                                binding.tvLocation.text = "GPS已定位$accText: $poiName"
                            }
                        }
                    }
                }
            }
            override fun onBetterResult(result: LocResult) {
                addTxLat = result.latitude
                addTxLon = result.longitude
                addTxAccuracy = result.accuracy
                if (isAdded) {
                    val accText = if (result.accuracy > 0) " (${result.accuracy.toInt()}米)" else ""
                    val addrText = addTxAddr
                    binding.tvLocation.text = if (addrText.isNullOrBlank()) "GPS已定位$accText" else "GPS已定位$accText: $addrText"
                }
                AppLocationManager.searchPoi(requireContext(), result.latitude, result.longitude) { poiName ->
                    if (poiName != null) {
                        addTxAddr = poiName
                        binding.root.post {
                            if (isAdded) {
                                val accText = if (result.accuracy > 0) " (${result.accuracy.toInt()}米)" else ""
                                binding.tvLocation.text = "GPS已定位$accText: $poiName"
                            }
                        }
                    }
                }
            }
            override fun onUpdate(result: LocResult) {
                if (isAdded) {
                    val accText = if (result.accuracy > 0) " (${result.accuracy.toInt()}米)" else ""
                    val addrText = addTxAddr
                    binding.tvLocation.text = if (addrText.isNullOrBlank()) "GPS已定位$accText" else "GPS已定位$accText: $addrText"
                    binding.tvLocation.setOnClickListener { fetchLocationGps() }
                }
            }
            override fun onError(message: String) {
                if (isAdded) {
                    binding.tvLocation.text = "GPS定位失败，点击重试"
                    binding.tvLocation.setOnClickListener { fetchLocationGps() }
                }
            }
        })
    }

    private fun updateDateDisplay() {
        val cal = Calendar.getInstance()
        cal.set(year, month, selectedDay)
        binding.tvDate.text = SimpleDateFormat("yyyy/MM/dd", Locale.CHINA).format(cal.time)
    }

    private fun showDatePicker() {
        DatePickerDialog(requireContext(), { _, y, m, d ->
            cancelAutoSave()
            year = y; month = m; selectedDay = d
            updateDateDisplay()
        }, year, month, selectedDay).show()
    }

    private fun saveTransaction() {
        val amountStr = binding.etAmount.text.toString()
        if (amountStr.isBlank()) { ToastManager.show(requireContext(), "请输入金额"); return }
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) { ToastManager.show(requireContext(), "金额无效"); return }
        if (addTxCategory.isBlank()) { ToastManager.show(requireContext(), "请选择分类"); return }

        val cal = Calendar.getInstance()
        cal.set(year, month, selectedDay, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val note = binding.etNote.text.toString()
        val tx = Transaction(
            amount = amount,
            type = addTxType,
            category = addTxCategory,
            note = note,
            date = cal.timeInMillis,
            latitude = addTxLat,
            longitude = addTxLon,
            address = addTxAddr
        )
        val dao = (requireActivity().application as App).database.transactionDao()
        CoroutineScope(Dispatchers.IO).launch {
            var savedId = editTxId
            if (savedId > 0) {
                dao.updateById(savedId, amount, addTxType, addTxCategory, note, cal.timeInMillis, addTxLat, addTxLon, addTxAddr)
            } else {
                savedId = dao.insert(tx)
            }
            withContext(Dispatchers.Main) {
                ToastManager.show(requireContext(), "已保存")
                HomeFragment.needsRefresh = true
                // 如果没有定位，后台继续定位8秒后更新
                if (addTxLat == null && editTxId == 0L) {
                    startBackgroundLocationUpdate(savedId)
                }
                dismiss()
                onRecordSaved?.invoke()
            }
        }
    }

    private var bgLocLocator: PreciseLocator? = null

    private fun startBackgroundLocationUpdate(txId: Long) {
        val appCtx = requireContext().applicationContext
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        bgLocLocator?.stop()
        bgLocLocator = PreciseLocator(appCtx)
        bgLocLocator!!.locate(object : com.suishiji.util.LocationCallback {
            override fun onResult(result: LocResult) {
                CoroutineScope(Dispatchers.IO).launch {
                    (appCtx as com.suishiji.App).database
                        .transactionDao().updateLocation(txId, result.latitude, result.longitude, result.address)
                    Log.d("Suishiji", "后台定位更新成功: ${result.address}")
                }
            }
            override fun onBetterResult(result: LocResult) {
                CoroutineScope(Dispatchers.IO).launch {
                    (appCtx as com.suishiji.App).database
                        .transactionDao().updateLocation(txId, result.latitude, result.longitude, result.address)
                }
            }
            override fun onUpdate(result: LocResult) {
                if (result.address != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        (appCtx as com.suishiji.App).database
                            .transactionDao().updateLocation(txId, result.latitude, result.longitude, result.address)
                        Log.d("Suishiji", "后台地址更新: ${result.address}")
                    }
                }
            }
            override fun onError(message: String) {}
        })
        // 8秒后强制停止
        Handler(Looper.getMainLooper()).postDelayed({
            bgLocLocator?.stop()
            bgLocLocator = null
        }, 8000)
    }
}
