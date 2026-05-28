package com.shayan.firewall

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class NetworkMonitoringActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var monitoringAdapter: MonitoringAdapter
    private lateinit var textEmptyLog: TextView
    private lateinit var switchMode: SwitchMaterial
    private lateinit var buttonToggle: Button

    private lateinit var prefs: FirewallPreferences
    private var currentMode = FirewallMode.SHIZUKU

    private val logs = mutableListOf<MonitoringLog>()
    private var displayLogs = mutableListOf<MonitoringLog>()
    
    // UI states
    private var isPaused = false
    private var excludeBlacklisted = false
    private var searchQuery: String? = null
    private var currentSortFilterMode = SortFilterMode.NAME
    private var isSortBlockedFirst = false
    private var isSortBlockedLast = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Modern ActivityResultLauncher for VPN permissions
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startMonitoringService()
        } else {
            Toast.makeText(this, "VPN Permission required for monitoring", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // Thread-safe cache
    private val appDetailCache = ConcurrentHashMap<Int, AppDetail>()

    private data class AppDetail(
        val packageName: String,
        val appName: String,
        val icon: Drawable?,
        val isSystem: Boolean,
        val isEnabled: Boolean,
        val isUninstalled: Boolean,
        val hasInternetPermission: Boolean
    )

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isPaused) return

            val uid = intent.getIntExtra("uid", -1)
            val ipOrDomain = intent.getStringExtra("ipOrDomain") ?: return
            
            if (uid == android.os.Process.myUid()) return

            lifecycleScope.launch(Dispatchers.Default) {
                val detail = getAppDetail(uid) ?: return@launch
                
                // Fetch live toggle states depending on current UI switch
                val isWifiBlocked = prefs.isWifiBlocked(currentMode, detail.packageName)
                val isDataBlocked = prefs.isDataBlocked(currentMode, detail.packageName)

                val logEntry = MonitoringLog(
                    uid = uid,
                    packageName = detail.packageName,
                    appName = detail.appName,
                    appIcon = detail.icon,
                    isSystemApp = detail.isSystem,
                    isEnabled = detail.isEnabled,
                    isUninstalled = detail.isUninstalled,
                    hasInternetPermission = detail.hasInternetPermission,
                    domainOrIp = ipOrDomain,
                    timestamp = timeFormat.format(Date()),
                    isWifiBlocked = isWifiBlocked,
                    isDataBlocked = isDataBlocked
                )

                withContext(Dispatchers.Main) {
                    // Prevent memory crash if the user leaves it running for hours
                    if (logs.size > 2000) logs.removeAt(0)
                    
                    logs.add(logEntry)
                    applyFiltersAndSort()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_monitoring)

        prefs = FirewallPreferences(this)
        isSortBlockedFirst = prefs.isSortBlockedFirst()
        isSortBlockedLast = prefs.isSortBlockedLast()

        val modeKey = intent.getStringExtra("EXTRA_START_MODE")
        currentMode = if (modeKey == FirewallMode.VPN.key) FirewallMode.VPN else FirewallMode.SHIZUKU

        setupToolbar()
        setupUI()
        prepareAndStartVpnMonitoring()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar_monitoring)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        switchMode = findViewById(R.id.switch_mode_monitoring)
        buttonToggle = findViewById(R.id.button_monitoring_toggle)

        switchMode.isChecked = (currentMode == FirewallMode.VPN)
        switchMode.text = if (switchMode.isChecked) getString(R.string.mode_vpn) else getString(R.string.mode_shizuku)

        switchMode.setOnCheckedChangeListener { _, isChecked ->
            currentMode = if (isChecked) FirewallMode.VPN else FirewallMode.SHIZUKU
            switchMode.text = if (isChecked) getString(R.string.mode_vpn) else getString(R.string.mode_shizuku)
            
            logs.forEach { log ->
                log.isWifiBlocked = prefs.isWifiBlocked(currentMode, log.packageName)
                log.isDataBlocked = prefs.isDataBlocked(currentMode, log.packageName)
            }
            applyFiltersAndSort()
        }

        buttonToggle.setOnClickListener {
            isPaused = !isPaused
            buttonToggle.text = if (isPaused) getString(R.string.monitoring_resume) else getString(R.string.monitoring_pause)
        }
    }

    private fun setupUI() {
        recyclerView = findViewById(R.id.recycler_view_monitoring)
        textEmptyLog = findViewById(R.id.text_empty_log)
        recyclerView.layoutManager = LinearLayoutManager(this)

        monitoringAdapter = MonitoringAdapter(
            displayLogs,
            onWifiClick = { log -> onToggleClicked(log, "wifi") },
            onDataClick = { log -> onToggleClicked(log, "data") }
        )
        recyclerView.adapter = monitoringAdapter
    }

    private fun prepareAndStartVpnMonitoring() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startMonitoringService()
        }
    }

    private fun startMonitoringService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                logReceiver,
                IntentFilter("com.shayan.firewall.MONITOR_LOG"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(logReceiver, IntentFilter("com.shayan.firewall.MONITOR_LOG"))
        }

        val intent = Intent(this, FirewallVpnService::class.java)
        intent.action = FirewallVpnService.ACTION_START_MONITORING
        startForegroundService(intent)
    }

    private fun stopMonitoringAndExit() {
        val intent = Intent(this, FirewallVpnService::class.java)
        intent.action = FirewallVpnService.ACTION_STOP_MONITORING
        startForegroundService(intent)
        finish()
    }

    override fun onBackPressed() {
        stopMonitoringAndExit()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun onToggleClicked(log: MonitoringLog, type: String) {
        val newWifiState = if (type == "wifi") !log.isWifiBlocked else log.isWifiBlocked
        val newDataState = if (type == "data") !log.isDataBlocked else log.isDataBlocked

        val (finalWifiState, finalDataState) = if (currentMode == FirewallMode.SHIZUKU) {
            val newState = if (type == "wifi") newWifiState else newDataState
            Pair(newState, newState)
        } else {
            Pair(newWifiState, newDataState)
        }

        prefs.setWifiBlocked(currentMode, log.packageName, finalWifiState)
        prefs.setDataBlocked(currentMode, log.packageName, finalDataState)

        logs.forEach { 
            if (it.packageName == log.packageName) {
                it.isWifiBlocked = finalWifiState
                it.isDataBlocked = finalDataState
            }
        }

        if (currentMode == FirewallMode.SHIZUKU && prefs.isShizukuEnabled() && !log.isUninstalled) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    ShizukuManager.applyRule(log.uid, log.packageName, finalWifiState)
                } catch (e: Exception) {
                    Log.e("NetworkMonitoring", "Failed to apply Shizuku rule", e)
                }
            }
        }

        applyFiltersAndSort()
    }

    private fun applyFiltersAndSort() {
        var filtered = logs.toList()

        if (excludeBlacklisted) {
            // Exclude any item blocked on either connection
            filtered = filtered.filter { !(it.isWifiBlocked || it.isDataBlocked) }
        }

        searchQuery?.let { query ->
            if (query.isNotBlank()) {
                filtered = filtered.filter {
                    it.appName.contains(query, ignoreCase = true) || 
                    it.domainOrIp.contains(query, ignoreCase = true)
                }
            }
        }
        
        filtered = when (currentSortFilterMode) {
            SortFilterMode.SYSTEM -> filtered.filter { it.isSystemApp && !it.isUninstalled }
            SortFilterMode.USER -> filtered.filter { !it.isSystemApp && !it.isUninstalled }
            SortFilterMode.INTERNET_ONLY -> filtered.filter { it.hasInternetPermission && !it.isUninstalled }
            SortFilterMode.DISABLED -> filtered.filter { !it.isEnabled && !it.isUninstalled }
            SortFilterMode.UNINSTALLED -> filtered.filter { it.isUninstalled }
            SortFilterMode.NAME -> filtered 
        }

        if (isSortBlockedFirst) {
            filtered = filtered.sortedWith(compareBy(
                { !(it.isWifiBlocked || it.isDataBlocked) },
                { it.timestamp }
            ))
        } else if (isSortBlockedLast) {
            filtered = filtered.sortedWith(compareBy(
                { (it.isWifiBlocked || it.isDataBlocked) },
                { it.timestamp }
            ))
        }

        val isAtBottom = !recyclerView.canScrollVertically(1)

        displayLogs.clear()
        displayLogs.addAll(filtered)
        monitoringAdapter.updateLogs(displayLogs)

        textEmptyLog.visibility = if (displayLogs.isEmpty()) View.VISIBLE else View.GONE

        if (isAtBottom && displayLogs.isNotEmpty()) {
            recyclerView.scrollToPosition(displayLogs.size - 1)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_monitoring, menu)
        
        val searchItem = menu?.findItem(R.id.menu_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = "Search apps or domains..."
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchQuery = query
                applyFiltersAndSort()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText
                applyFiltersAndSort()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_sort -> {
                showSortDialog()
                return true
            }
            R.id.menu_refresh -> { 
                applyFiltersAndSort()
                return true
            }
            R.id.menu_stop_monitoring -> { 
                stopMonitoringAndExit()
                return true
            }
            R.id.menu_exclude_blacklisted -> { 
                excludeBlacklisted = !excludeBlacklisted
                item.title = if (excludeBlacklisted) getString(R.string.menu_include_blacklisted) else getString(R.string.menu_exclude_blacklisted)
                applyFiltersAndSort()
                return true
            }
            R.id.menu_clear_list -> { 
                logs.clear()
                applyFiltersAndSort()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showSortDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_sort, null)
        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_group_filter)
        val checkBoxFirst = view.findViewById<CheckBox>(R.id.checkbox_sort_blocked)
        val checkBoxLast = view.findViewById<CheckBox>(R.id.checkbox_sort_blocked_last)

        val radioUninstalled = view.findViewById<RadioButton>(R.id.radio_sort_uninstalled)
        radioUninstalled.paintFlags = radioUninstalled.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

        when (currentSortFilterMode) {
            SortFilterMode.NAME -> radioGroup.check(R.id.radio_sort_name)
            SortFilterMode.SYSTEM -> radioGroup.check(R.id.radio_sort_system)
            SortFilterMode.USER -> radioGroup.check(R.id.radio_sort_user)
            SortFilterMode.INTERNET_ONLY -> radioGroup.check(R.id.radio_sort_internet)
            SortFilterMode.DISABLED -> radioGroup.check(R.id.radio_sort_disabled)
            SortFilterMode.UNINSTALLED -> radioGroup.check(R.id.radio_sort_uninstalled)
        }
        
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentSortFilterMode = when (checkedId) {
                R.id.radio_sort_system -> SortFilterMode.SYSTEM
                R.id.radio_sort_user -> SortFilterMode.USER
                R.id.radio_sort_internet -> SortFilterMode.INTERNET_ONLY
                R.id.radio_sort_disabled -> SortFilterMode.DISABLED
                R.id.radio_sort_uninstalled -> SortFilterMode.UNINSTALLED
                else -> SortFilterMode.NAME
            }
            applyFiltersAndSort()
        }

        checkBoxFirst.setOnCheckedChangeListener(null)
        checkBoxLast.setOnCheckedChangeListener(null)
        
        checkBoxFirst.isChecked = isSortBlockedFirst
        checkBoxLast.isChecked = isSortBlockedLast
        
        checkBoxFirst.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkBoxLast.isChecked = false
                isSortBlockedLast = false
                prefs.setSortBlockedLast(false)
            }
            isSortBlockedFirst = isChecked
            prefs.setSortBlockedFirst(isChecked)
            applyFiltersAndSort()
        }

        checkBoxLast.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                checkBoxFirst.isChecked = false
                isSortBlockedFirst = false
                prefs.setSortBlockedFirst(false)
            }
            isSortBlockedLast = isChecked
            prefs.setSortBlockedLast(isChecked)
            applyFiltersAndSort()
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sort_dialog_title))
            .setView(view)
            .show()
    }

    private fun getAppDetail(uid: Int): AppDetail? {
        appDetailCache[uid]?.let { return it }

        if (uid < 0) return null // Unresolved packets
        if (uid == 0) return null // Root / Linux Kernel traffic

        val packageName: String // Deliberately left uninitialized to match your original flow
        var appName = ""
        var icon: Drawable? = ContextCompat.getDrawable(this, android.R.drawable.sym_def_app_icon)
        var isSystem = false
        var isEnabled = true
        var isUninstalled = true
        var hasInternet = false

        val packages = packageManager.getPackagesForUid(uid)
        if (packages != null && packages.isNotEmpty()) {
            packageName = packages[0]
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                appName = packageManager.getApplicationLabel(appInfo).toString()
                icon = packageManager.getApplicationIcon(appInfo)
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                isEnabled = appInfo.enabled
                isUninstalled = false
                
                val pkgInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                hasInternet = pkgInfo.requestedPermissions?.contains("android.permission.INTERNET") == true
            } catch (e: PackageManager.NameNotFoundException) { }
        } else if (uid == 1000) {
            packageName = "android"
            appName = "Android System"
            isSystem = true
            isUninstalled = false
            hasInternet = true
        } else {
            return null
        }

        val detail = AppDetail(packageName, appName, icon, isSystem, isEnabled, isUninstalled, hasInternet)
        appDetailCache[uid] = detail
        return detail
    }
}