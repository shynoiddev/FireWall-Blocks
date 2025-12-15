package com.shayan.firewall

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var appAdapter: AppAdapter
    private lateinit var loadingContainer: LinearLayout
    private lateinit var switchMode: SwitchMaterial
    private lateinit var buttonMasterToggle: Button
    
    // Shizuku Prompt Bar Views
    private lateinit var cardShizukuPrompt: CardView
    private lateinit var textShizukuPrompt: TextView
    private lateinit var btnPromptAction: Button
    private lateinit var btnPromptCancel: Button

    private lateinit var prefs: FirewallPreferences
    private var currentMode = FirewallMode.SHIZUKU

    private val masterAppList = mutableListOf<AppInfo>()
    private var currentSortFilterMode = SortFilterMode.NAME
    private var isSortBlockedFirst = false
    private var currentSearchQuery: String? = null

    private var actionMode: ActionMode? = null
    private var isInSelectionMode = false

    private val vpnRequestCode = 101
    private val shizukuRequestCode = 202
    
    // Handler for hiding the prompt bar automatically
    private val handler = Handler(Looper.getMainLooper())
    private val hidePromptRunnable = Runnable {
        cardShizukuPrompt.visibility = View.GONE
    }
    
    // Shizuku Package
    private val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private val SHIZUKU_GITHUB = "https://github.com/RikkaApps/Shizuku"

    private val createFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            exportSettings(it)
        }
    }

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            importSettings(it)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, getString(R.string.notification_permission_required), Toast.LENGTH_LONG).show()
            prefs.setRebootReminder(false)
            invalidateOptionsMenu() 
        }
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            ShizukuManager.clearUidCache() 
            if (requestCode == shizukuRequestCode) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    if (prefs.isShizukuEnabled()) {
                        applyAllRulesShizuku()
                    }
                } else {
                    Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val shizukuBinderListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            Log.d("MainActivity", "Shizuku Binder Received")
            ShizukuManager.clearUidCache() 
            if (prefs.isShizukuEnabled() && currentMode == FirewallMode.SHIZUKU) {
                applyAllRulesShizuku()
            }
        }
    }
    
    private val shizukuBinderDeadListener = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            Log.w("MainActivity", "Shizuku Binder Dead")
            ShizukuManager.clearUidCache()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        prefs = FirewallPreferences(this)
        
        isSortBlockedFirst = prefs.isSortBlockedFirst()
        
        loadingContainer = findViewById(R.id.loading_container)
        recyclerView = findViewById(R.id.recycler_view_apps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        setupAdapter()
        
        // Initialize Prompt Views
        cardShizukuPrompt = findViewById(R.id.card_shizuku_prompt)
        textShizukuPrompt = findViewById(R.id.text_shizuku_prompt)
        btnPromptAction = findViewById(R.id.btn_prompt_action)
        btnPromptCancel = findViewById(R.id.btn_prompt_cancel)
        
        switchMode = findViewById(R.id.switch_mode)
        buttonMasterToggle = findViewById(R.id.button_master_toggle)

        setupSwitch()
        setupMasterToggle()
        setupShizukuListeners()

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        
        // Logic: On open/resume, if Shizuku mode is enabled, try to reapply rules.
        // isUserInitiated = false:
        // - IF SUCCESS: Silent (no toast).
        // - IF FAIL (Not running/installed): SHOWS ERROR TOAST & PROMPT BAR.
        if (prefs.isShizukuEnabled()) {
             checkShizukuAndApplyAll(isUserInitiated = false)
        }
    }

    private fun setupAdapter() {
        appAdapter = AppAdapter(
            emptyList(),
            onItemClick = { app ->
                if (isInSelectionMode) {
                    toggleSelection(app)
                }
            },
            onItemLongClick = { app ->
                if (!isInSelectionMode) {
                    actionMode = startSupportActionMode(actionModeCallback)
                }
                toggleSelection(app)
            },
            onWifiClick = { app ->
                onToggleClicked(app, "wifi")
            },
            onDataClick = { app ->
                onToggleClicked(app, "data")
            }
        )
        recyclerView.adapter = appAdapter
    }

    private fun setupSwitch() {
        switchMode.setOnCheckedChangeListener { _, isChecked ->
            // Change UI mode only
            val newMode = if (isChecked) FirewallMode.VPN else FirewallMode.SHIZUKU
            currentMode = newMode
            
            switchMode.text = if (isChecked) getString(R.string.mode_vpn) else getString(R.string.mode_shizuku)

            // Update Master Button text based on the new mode's independent state
            updateMasterButton()
            
            // Reload list for the new mode's preferences
            invalidateOptionsMenu()
            loadApps()
        }
    }

    private fun setupMasterToggle() {
        updateMasterButton()

        buttonMasterToggle.setOnClickListener {
            val isEnabledInCurrentMode = prefs.isEnabledForMode(currentMode)
            val newEnabledState = !isEnabledInCurrentMode

            if (currentMode == FirewallMode.SHIZUKU) {
                prefs.setShizukuEnabled(newEnabledState)
                if (newEnabledState) {
                    checkShizukuAndApplyAll(isUserInitiated = true)
                } else {
                    checkShizukuAndRemoveAll()
                }
            } else {
                prefs.setVpnEnabled(newEnabledState)
                if (newEnabledState) {
                    startVpnService()
                } else {
                    stopVpnService()
                }
            }
            
            updateMasterButton()
        }
    }

    private fun updateMasterButton() {
        val isEnabled = prefs.isEnabledForMode(currentMode)
        buttonMasterToggle.text = if (isEnabled) {
            getString(R.string.master_disable)
        } else {
            getString(R.string.master_enable)
        }
    }

    // This method is called internally for async logic
    private fun applyAllRulesShizuku() {
         lifecycleScope.launch(Dispatchers.IO) {
            ShizukuManager.applyAllRules(this@MainActivity, prefs)
         }
    }


    private fun forceVpnRestart() {
        if (!prefs.isVpnEnabled()) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("MainActivity", "forceVpnRestart: Sending STOP")
            val stopIntent = Intent(this@MainActivity, FirewallVpnService::class.java)
            stopIntent.action = FirewallVpnService.ACTION_STOP
            startForegroundService(stopIntent)

            delay(300)

            Log.d("MainActivity", "forceVpnRestart: Sending START")
            val startIntent = Intent(this@MainActivity, FirewallVpnService::class.java)
            startForegroundService(startIntent)
        }
    }

    private fun startVpnService() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            onActivityResult(vpnRequestCode, Activity.RESULT_OK, null)
        }
    }

    private fun stopVpnService() {
        FirewallVpnService.stopVpn(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == Activity.RESULT_OK) {
            val intent = Intent(this, FirewallVpnService::class.java)
            startForegroundService(intent)
        }
    }

    private fun setupShizukuListeners() {
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun showShizukuPromptBar() {
        // Clear any existing timer
        handler.removeCallbacks(hidePromptRunnable)
        
        val isInstalled = isShizukuInstalled()
        
        // Logic to update text based on state
        if (isInstalled) {
            textShizukuPrompt.text = getString(R.string.prompt_shizuku_open)
            btnPromptAction.text = getString(R.string.prompt_action_open)
            btnPromptAction.setOnClickListener {
                try {
                    val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                    if (intent != null) {
                        startActivity(intent)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open Shizuku", Toast.LENGTH_SHORT).show()
                }
                cardShizukuPrompt.visibility = View.GONE
            }
        } else {
            textShizukuPrompt.text = getString(R.string.prompt_shizuku_download)
            btnPromptAction.text = getString(R.string.prompt_action_download)
            btnPromptAction.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_GITHUB))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
                }
                cardShizukuPrompt.visibility = View.GONE
            }
        }
        
        btnPromptCancel.setOnClickListener {
            cardShizukuPrompt.visibility = View.GONE
        }
        
        cardShizukuPrompt.visibility = View.VISIBLE
        
        // Hide after 10 seconds
        handler.postDelayed(hidePromptRunnable, 10000)
    }
    
    private fun executeOpenOrDownloadShizuku() {
         if (isShizukuInstalled()) {
             try {
                val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (intent != null) startActivity(intent)
             } catch (e: Exception) {
                 Toast.makeText(this, "Error opening Shizuku", Toast.LENGTH_SHORT).show()
             }
         } else {
             try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_GITHUB))
                startActivity(intent)
             } catch (e: Exception) {
                 Toast.makeText(this, "Error opening link", Toast.LENGTH_SHORT).show()
             }
         }
    }

    /**
     * Checks Shizuku permission and status.
     * @param showErrorUI If true, shows Toast and Prompt Bar on failure.
     */
    private fun checkShizukuPermission(showErrorUI: Boolean = false): Boolean {
        if (Shizuku.isPreV11()) {
            if (showErrorUI) Toast.makeText(this, "Shizuku version too old", Toast.LENGTH_SHORT).show()
            return false
        }

        try {
            
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && Shizuku.pingBinder()) {
                return true
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                 if (Shizuku.shouldShowRequestPermissionRationale()) {
                    if (showErrorUI) {
                        Toast.makeText(this, "Please grant permission in Shizuku", Toast.LENGTH_SHORT).show()
                        showShizukuPromptBar() // Show bar
                    }
                    return false
                } else {
                    if (showErrorUI) Shizuku.requestPermission(shizukuRequestCode)
                    return false
                }
            } else {
                 // Permission granted, but service not running
                 if (showErrorUI) {
                     Toast.makeText(this, "Shizuku not running or not found", Toast.LENGTH_SHORT).show()
                     showShizukuPromptBar() // Show bar
                 }
                 return false
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) {
                if (showErrorUI) {
                    Toast.makeText(this, "Shizuku not running or not found", Toast.LENGTH_SHORT).show()
                    showShizukuPromptBar() // Show bar
                }
            } else {
                if (showErrorUI) Toast.makeText(this, "Shizuku error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return false
        }
    }

    /**
     * Checks permissions and applies rules.
     * @param isUserInitiated If true, show "Applying rules" toast on success.
     */
    private fun checkShizukuAndApplyAll(isUserInitiated: Boolean = false) {
        // ALWAYS pass true for showErrorUI so failures are noisy (Startup & Manual)
        if (checkShizukuPermission(showErrorUI = true)) {
            // ONLY show success toast if user initiated it (Refresh/Toggle)
            if (isUserInitiated) {
                Toast.makeText(this, "Applying Shizuku rules.", Toast.LENGTH_SHORT).show()
            }
            lifecycleScope.launch(Dispatchers.IO) {
                ShizukuManager.applyAllRules(this@MainActivity, prefs)
            }
        }
    }

    private fun checkShizukuAndRemoveAll() {
        if (checkShizukuPermission(showErrorUI = true)) {
            Toast.makeText(this, "Removing Shizuku rules.", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                ShizukuManager.removeAllRules(this@MainActivity)
            }
        }
    }

    private fun checkShizukuAndApplyRule(app: AppInfo) {
        // For individual toggles
        if (checkShizukuPermission(showErrorUI = true)) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val uid = packageManager.getApplicationInfo(app.packageName, 0).uid
                    ShizukuManager.applyRule(uid, app.packageName, app.isWifiBlocked)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to get UID for ${app.packageName}", e)
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        handler.removeCallbacks(hidePromptRunnable) // Clean up handler
        super.onDestroy()
    }

    private fun loadApps() {
        actionMode?.finish()

        loadingContainer.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            masterAppList.clear()

            val packageManager = packageManager
            val packages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            for (pkgInfo in packages) {
                val app = pkgInfo.applicationInfo
                if (app == null) {
                    continue
                }

                val appName = packageManager.getApplicationLabel(app).toString()
                val appIcon = packageManager.getApplicationIcon(app)
                val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                
                val hasInternet = pkgInfo.requestedPermissions?.contains("android.permission.INTERNET") == true

                masterAppList.add(AppInfo(
                    appName = appName,
                    packageName = app.packageName,
                    appIcon = appIcon,
                    isSystemApp = isSystemApp,
                    hasInternetPermission = hasInternet,
                    isWifiBlocked = prefs.isWifiBlocked(currentMode, app.packageName),
                    isDataBlocked = prefs.isDataBlocked(currentMode, app.packageName),
                    isSelected = false
                ))
            }

            withContext(Dispatchers.Main) {
                sortAndDisplayApps()
                loadingContainer.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun sortAndDisplayApps() {
        var processedList = masterAppList.toList()

        currentSearchQuery?.let { query ->
            if (query.isNotBlank()) {
                processedList = processedList.filter {
                    it.appName.contains(query, ignoreCase = true)
                }
            }
        }

        processedList = when (currentSortFilterMode) {
            SortFilterMode.SYSTEM -> processedList.filter { it.isSystemApp }
            SortFilterMode.USER -> processedList.filter { !it.isSystemApp }
            SortFilterMode.INTERNET_ONLY -> processedList.filter { it.hasInternetPermission }
            SortFilterMode.NAME -> processedList 
        }

        val sortedList: List<AppInfo>
        if (isSortBlockedFirst) {
            sortedList = processedList.sortedWith(compareBy(
                { !(it.isWifiBlocked || it.isDataBlocked) }, 
                { it.appName.lowercase() } 
            ))
        } else {
            sortedList = processedList.sortedBy { it.appName.lowercase() }
        }

        appAdapter.updateApps(sortedList)
    }


    private fun onToggleClicked(app: AppInfo, type: String) {
        val newWifiState = if (type == "wifi") !app.isWifiBlocked else app.isWifiBlocked
        val newDataState = if (type == "data") !app.isDataBlocked else app.isDataBlocked

        val (finalWifiState, finalDataState) = if (currentMode == FirewallMode.SHIZUKU) {
            val newState = if (type == "wifi") newWifiState else newDataState
            Pair(newState, newState)
        } else {
            Pair(newWifiState, newDataState)
        }

        var targetApps = if (isInSelectionMode) {
            masterAppList.filter { it.isSelected }
        } else {
            listOf(app)
        }

        if (targetApps.isEmpty() && !isInSelectionMode) {
            targetApps = listOf(app)
        }

        val isEnabledForCurrentMode = prefs.isEnabledForMode(currentMode)

        for (targetApp in targetApps) {
            prefs.setWifiBlocked(currentMode, targetApp.packageName, finalWifiState)
            prefs.setDataBlocked(currentMode, targetApp.packageName, finalDataState)

            targetApp.isWifiBlocked = finalWifiState
            targetApp.isDataBlocked = finalDataState

            if (isEnabledForCurrentMode) {
                if (currentMode == FirewallMode.SHIZUKU) {
                    checkShizukuAndApplyRule(targetApp)
                }
            }
        }

        if (isEnabledForCurrentMode && currentMode == FirewallMode.VPN) {
            forceVpnRestart()
        }

        if (isSortBlockedFirst) {
            sortAndDisplayApps()
        } else {
            val visibleApps = appAdapter.getAppList()
            for (targetApp in targetApps) {
                val index = visibleApps.indexOf(targetApp)
                if (index != -1) {
                    appAdapter.notifyItemChanged(index)
                }
            }
        }

        if (isInSelectionMode) {
            actionMode?.finish()
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            isInSelectionMode = true
            mode?.menuInflater?.inflate(R.menu.selection_menu, menu)
            mode?.title = getString(R.string.selection_title_zero)
            findViewById<Toolbar>(R.id.toolbar).visibility = View.GONE
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.menu_select_all -> {
                    selectAllApps()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            isInSelectionMode = false
            actionMode = null
            masterAppList.forEach { it.isSelected = false }
            findViewById<Toolbar>(R.id.toolbar).visibility = View.VISIBLE
            sortAndDisplayApps()
        }
    }

    private fun toggleSelection(app: AppInfo) {
        app.isSelected = !app.isSelected

        val selectedCount = masterAppList.count { it.isSelected }

        if (selectedCount == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = when (selectedCount) {
                1 -> getString(R.string.selection_title_one)
                else -> getString(R.string.selection_title_many, selectedCount)
            }
        }

        val index = appAdapter.getAppList().indexOf(app)
        if (index != -1) {
            appAdapter.notifyItemChanged(index)
        }
    }

    private fun selectAllApps() {
        val visibleApps = appAdapter.getAppList()
        val allSelected = visibleApps.all { it.isSelected }

        visibleApps.forEach { app ->
            app.isSelected = !allSelected
        }

        val selectedCount = masterAppList.count { it.isSelected }
        actionMode?.title = getString(R.string.selection_title_many, selectedCount)
        appAdapter.updateApps(visibleApps)
    }

    private fun exportSettings(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = prefs.exportAllSettings()
                if (json == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray())
                    out.flush()
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.export_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Export failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun importSettings(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            var importSuccess = false
            try {
                val jsonString = contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).readText()
                }

                if (jsonString.isNullOrBlank()) {
                    throw Exception("File is empty or could not be read.")
                }

                importSuccess = prefs.importAllSettings(jsonString)

            } catch (e: Exception) {
                Log.e("MainActivity", "Import failed", e)
                importSuccess = false
            }

            withContext(Dispatchers.Main) {
                if (importSuccess) {
                    Toast.makeText(this@MainActivity, getString(R.string.import_success), Toast.LENGTH_SHORT).show()

                    loadApps()
                    // Update master button to reflect potentially imported state
                    updateMasterButton()
                    
                    // Re-apply rules if needed based on imported state
                    if (prefs.isVpnEnabled()) {
                        forceVpnRestart()
                    }
                    if (prefs.isShizukuEnabled()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                ShizukuManager.refreshConnectivityService()
                                delay(150)
                                ShizukuManager.applyAllRules(this@MainActivity, prefs)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error re-applying Shizuku rules after import", e)
                            }
                        }
                    }
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSortDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_sort, null)
        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_group_filter)
        val checkBox = view.findViewById<CheckBox>(R.id.checkbox_sort_blocked)

        when (currentSortFilterMode) {
            SortFilterMode.NAME -> radioGroup.check(R.id.radio_sort_name)
            SortFilterMode.SYSTEM -> radioGroup.check(R.id.radio_sort_system)
            SortFilterMode.USER -> radioGroup.check(R.id.radio_sort_user)
            SortFilterMode.INTERNET_ONLY -> radioGroup.check(R.id.radio_sort_internet)
        }
        checkBox.isChecked = isSortBlockedFirst
        
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentSortFilterMode = when (checkedId) {
                R.id.radio_sort_system -> SortFilterMode.SYSTEM
                R.id.radio_sort_user -> SortFilterMode.USER
                R.id.radio_sort_internet -> SortFilterMode.INTERNET_ONLY
                else -> SortFilterMode.NAME
            }
            sortAndDisplayApps()
        }
        
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            isSortBlockedFirst = isChecked
            prefs.setSortBlockedFirst(isSortBlockedFirst)
            sortAndDisplayApps()
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sort_dialog_title))
            .setView(view)
            
            .show()
    }


    private fun copySettings() {
        val (sourceMode, destMode, message) = if (currentMode == FirewallMode.SHIZUKU) {
            Triple(FirewallMode.VPN, FirewallMode.SHIZUKU, "Copied VPN settings to Shizuku")
        } else {
            Triple(FirewallMode.SHIZUKU, FirewallMode.VPN, "Copied Shizuku settings to VPN")
        }

        prefs.copySettings(sourceMode, destMode)
        loadApps()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchItem = menu?.findItem(R.id.menu_search)
        val searchView = searchItem?.actionView as? SearchView
        
        searchView?.queryHint = "Search apps..."
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                currentSearchQuery = query
                sortAndDisplayApps()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText
                sortAndDisplayApps()
                return true
            }
        })
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        
        // Handle Copy Text
        val copyItem = menu?.findItem(R.id.menu_copy_settings)
        if (currentMode == FirewallMode.VPN) {
            copyItem?.title = getString(R.string.menu_copy_shizuku_to_vpn)
        } else {
            copyItem?.title = getString(R.string.menu_copy_vpn_to_shizuku)
        }
        
        // Handle Dynamic Mode Options
        val shizukuActionItem = menu?.findItem(R.id.menu_shizuku_action)

        if (currentMode == FirewallMode.SHIZUKU) {
            shizukuActionItem?.isVisible = true
            
            // Set dynamic title based on installation
            if (isShizukuInstalled()) {
                shizukuActionItem?.title = getString(R.string.menu_open_shizuku)
            } else {
                shizukuActionItem?.title = getString(R.string.menu_download_shizuku)
            }
        } else {
            // VPN Mode
            shizukuActionItem?.isVisible = false
        }
        
        val reminderItem = menu?.findItem(R.id.menu_reboot_reminder)
        reminderItem?.isChecked = prefs.isRebootReminderEnabled()

        return super.onPrepareOptionsMenu(menu)
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_sort -> {
                showSortDialog()
                true
            }
            R.id.menu_refresh -> {
                if (currentMode == FirewallMode.VPN) {
                    forceVpnRestart()
                } else {
                    // Only apply rules if Shizuku is actually enabled!
                    if (prefs.isShizukuEnabled()) {
                        checkShizukuAndApplyAll(isUserInitiated = true)
                    } else {
                        Toast.makeText(this, "Shizuku mode is disabled", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            R.id.menu_shizuku_action -> {
                executeOpenOrDownloadShizuku()
                true
            }
            R.id.menu_copy_settings -> {
                copySettings()
                true
            }
            R.id.menu_export -> {
                createFileLauncher.launch("firewall_settings.json")
                true
            }
            R.id.menu_import -> {
                openFileLauncher.launch(arrayOf("application/json"))
                true
            }
            R.id.menu_reboot_reminder -> {
                val isChecked = !item.isChecked
                item.isChecked = isChecked
                prefs.setRebootReminder(isChecked)
                
                if (isChecked) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                true
            }
            R.id.menu_help -> {
                startActivity(Intent(this, HelpActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}


