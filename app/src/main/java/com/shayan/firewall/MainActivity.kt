package com.shayan.firewall

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
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
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

    private lateinit var prefs: FirewallPreferences
    private var currentMode = FirewallMode.SHIZUKU

    private val masterAppList = mutableListOf<AppInfo>()
    private var currentSortFilterMode = SortFilterMode.NAME
    private var currentSearchQuery: String? = null

    private var actionMode: ActionMode? = null
    private var isInSelectionMode = false

    private val vpnRequestCode = 101
    private val shizukuRequestCode = 202

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

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == shizukuRequestCode) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    if (prefs.isFirewallEnabled()) {
                        applyAllRules()
                    }
                } else {
                    Toast.makeText(this, "Shizuku permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val shizukuBinderListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            Log.d("MainActivity", "Shizuku Binder Received")
            if (prefs.isFirewallEnabled() && currentMode == FirewallMode.SHIZUKU) {
                applyAllRules()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()

        // Bypass hidden API restrictions where needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        prefs = FirewallPreferences(this)

        loadingContainer = findViewById(R.id.loading_container)
        recyclerView = findViewById(R.id.recycler_view_apps)
        recyclerView.layoutManager = LinearLayoutManager(this)

        setupAdapter()
        switchMode = findViewById(R.id.switch_mode)
        buttonMasterToggle = findViewById(R.id.button_master_toggle)

        setupSwitch()
        setupMasterToggle()
        setupShizukuListeners()

        loadApps()
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
            val newMode = if (isChecked) FirewallMode.VPN else FirewallMode.SHIZUKU

            if (newMode == FirewallMode.SHIZUKU) {
                stopVpnService()
            }

            currentMode = newMode
            switchMode.text = if (isChecked) getString(R.string.mode_vpn) else getString(R.string.mode_shizuku)

            invalidateOptionsMenu()
            loadApps()

            onMasterToggleChanged(prefs.isFirewallEnabled())
        }
    }

    private fun setupMasterToggle() {
        updateMasterButton(prefs.isFirewallEnabled())

        buttonMasterToggle.setOnClickListener {
            val isCurrentlyEnabled = prefs.isFirewallEnabled()
            val newEnabledState = !isCurrentlyEnabled

            prefs.setFirewallEnabled(newEnabledState)
            updateMasterButton(newEnabledState)
            onMasterToggleChanged(newEnabledState)
        }
    }

    private fun updateMasterButton(isEnabled: Boolean) {
        buttonMasterToggle.text = if (isEnabled) {
            getString(R.string.master_disable)
        } else {
            getString(R.string.master_enable)
        }
    }

    private fun onMasterToggleChanged(isEnabled: Boolean) {
        if (isEnabled) {
            applyAllRules()
        } else {
            removeAllRules()
        }
    }

    private fun applyAllRules() {
        if (currentMode == FirewallMode.VPN) {
            startVpnService()
        } else {
            checkShizukuAndApplyAll()
        }
    }

    private fun removeAllRules() {
        if (currentMode == FirewallMode.VPN) {
            stopVpnService()
        } else {
            checkShizukuAndRemoveAll()
        }
    }

    private fun forceVpnRestart() {
        if (!prefs.isFirewallEnabled() || currentMode != FirewallMode.VPN) {
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
    }

    private fun checkShizukuPermission(): Boolean {
        if (Shizuku.isPreV11()) {
            Toast.makeText(this, "Shizuku version too old", Toast.LENGTH_SHORT).show()
            return false
        }

        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return true
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, "Please grant permission in Shizuku", Toast.LENGTH_SHORT).show()
                return false
            } else {
                Shizuku.requestPermission(shizukuRequestCode)
                return false
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) {
                Toast.makeText(this, "Shizuku not running or not found", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Shizuku error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return false
        }
    }

    private fun checkShizukuAndApplyAll() {
        if (checkShizukuPermission()) {
            Toast.makeText(this, "Applying Shizuku rules.", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                ShizukuManager.applyAllRules(this@MainActivity, prefs)
            }
        }
    }

    private fun checkShizukuAndRemoveAll() {
        if (checkShizukuPermission()) {
            Toast.makeText(this, "Removing Shizuku rules.", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                ShizukuManager.removeAllRules(this@MainActivity)
            }
        }
    }

    private fun checkShizukuAndApplyRule(app: AppInfo) {
        if (checkShizukuPermission()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val uid = packageManager.getApplicationInfo(app.packageName, 0).uid
                    ShizukuManager.applyRule(uid, app.isWifiBlocked)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to get UID for ${app.packageName}", e)
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        super.onDestroy()
    }

    private fun loadApps() {
        actionMode?.finish()

        loadingContainer.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            masterAppList.clear()

            val packageManager = packageManager
            val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            for (app in packages) {
                val appName = packageManager.getApplicationLabel(app).toString()
                val appIcon = packageManager.getApplicationIcon(app)
                val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                masterAppList.add(AppInfo(
                    appName = appName,
                    packageName = app.packageName,
                    appIcon = appIcon,
                    isSystemApp = isSystemApp,
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

        val sortedList = when (currentSortFilterMode) {
            SortFilterMode.NAME ->
                processedList.sortedBy { it.appName.lowercase() }
            SortFilterMode.SYSTEM ->
                processedList.filter { it.isSystemApp }.sortedBy { it.appName.lowercase() }
            SortFilterMode.USER ->
                processedList.filter { !it.isSystemApp }.sortedBy { it.appName.lowercase() }
            SortFilterMode.BLOCKED ->
                processedList.sortedWith(compareBy(
                    { !(it.isWifiBlocked || it.isDataBlocked) },
                    { it.appName.lowercase() }
                ))
        }

        appAdapter.updateApps(sortedList)
    }

    private fun onToggleClicked(app: AppInfo, type: String) {
        val newWifiState = if (type == "wifi") !app.isWifiBlocked else app.isWifiBlocked
        val newDataState = if (type == "data") !app.isDataBlocked else app.isDataBlocked

        val (finalWifiState, finalDataState) = if (currentMode == FirewallMode.SHIZUKU) {
            // Link wifi & data in Shizuku mode if separate control isn't implemented
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

        for (targetApp in targetApps) {
            prefs.setWifiBlocked(currentMode, targetApp.packageName, finalWifiState)
            prefs.setDataBlocked(currentMode, targetApp.packageName, finalDataState)

            targetApp.isWifiBlocked = finalWifiState
            targetApp.isDataBlocked = finalDataState

            if (currentMode == FirewallMode.SHIZUKU) {
                checkShizukuAndApplyRule(targetApp)
            }
        }

        if (currentMode == FirewallMode.VPN) {
            forceVpnRestart()
        }

        if (currentSortFilterMode == SortFilterMode.BLOCKED) {
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

    // Export settings to JSON
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

    // Import settings from JSON
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
                    if (prefs.isFirewallEnabled()) {
                        if (currentMode == FirewallMode.VPN) {
                            forceVpnRestart()
                        } else {
                            // Important: refresh Shizuku service cache then re-apply rules
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
                    }
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showSortDialog() {
        val items = arrayOf(
            getString(R.string.sort_by_name),
            getString(R.string.sort_show_system),
            getString(R.string.sort_show_user),
            getString(R.string.sort_show_blocked)
        )

        val currentSelection = when (currentSortFilterMode) {
            SortFilterMode.NAME -> 0
            SortFilterMode.SYSTEM -> 1
            SortFilterMode.USER -> 2
            SortFilterMode.BLOCKED -> 3
        }

        AlertDialog.Builder(this)
            .setTitle("Sort & Filter")
            .setSingleChoiceItems(items, currentSelection) { dialog, which ->
                currentSortFilterMode = when (which) {
                    1 -> SortFilterMode.SYSTEM
                    2 -> SortFilterMode.USER
                    3 -> SortFilterMode.BLOCKED
                    else -> SortFilterMode.NAME
                }
                sortAndDisplayApps()
                dialog.dismiss()
            }
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

    // Menu and search
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchItem = menu?.findItem(R.id.menu_search)
        val searchView = searchItem?.actionView as? SearchView
        // Use an inline hint to avoid missing resource crash
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_sort -> {
                showSortDialog()
                true
            }
            R.id.menu_refresh -> {
                // Force re-apply rules for current mode
                if (currentMode == FirewallMode.VPN) {
                    forceVpnRestart()
                } else {
                    checkShizukuAndApplyAll()
                }
                true
            }
            R.id.menu_copy_settings -> {
                copySettings()
                true
            }
            R.id.menu_export -> {
                // Launch create document
                createFileLauncher.launch("firewall_settings.json")
                true
            }
            R.id.menu_import -> {
                openFileLauncher.launch(arrayOf("application/json"))
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