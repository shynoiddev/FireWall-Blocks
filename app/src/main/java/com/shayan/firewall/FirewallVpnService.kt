package com.shayan.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class FirewallVpnService : VpnService() {

    private val TAG = "FirewallVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    
    private lateinit var connectivityManager: ConnectivityManager
    
    private var isWifiActive: Boolean = false
    private var currentNetwork: Network? = null

    private val isReconfiguring = AtomicBoolean(false)

    companion object {
        const val ACTION_STOP = "com.shayan.firewall.STOP_VPN"
        const val ACTION_REFRESH = "com.shayan.firewall.REFRESH_VPN"

        fun stopVpn(context: Context) {
            val intent = Intent(context, FirewallVpnService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d(TAG, "NetworkCallback: onAvailable")
            currentNetwork = network
            val oldWifiState = isWifiActive
            updateCurrentNetworkState()
            
            if (isWifiActive != oldWifiState) {
                 Log.i(TAG, "Network type changed on-available. Reconfiguring VPN.")
                 updateVpnConfiguration()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.d(TAG, "NetworkCallback: onLost")
            if (network == currentNetwork) {
                 currentNetwork = null
                 if (!updateCurrentNetworkState()) {
                      Log.w(TAG, "No active network. Closing interface.")
                      try {
                          vpnInterface?.close()
                          vpnInterface = null
                      } catch (e: Exception) {}
                 }
            }
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            Log.d(TAG, "NetworkCallback: onCapabilitiesChanged")
            val oldWifiState = isWifiActive
            updateCurrentNetworkState()
            
            if (isWifiActive != oldWifiState) {
                Log.i(TAG, "Network type changed. Reconfiguring VPN.")
                updateVpnConfiguration()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun createNotification(): Notification {
        val channelId = "FirewallVpnChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Firewall Status", NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("FireWall Blocks")
            .setContentText("VPN is active")
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received stop command")
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                Log.i(TAG, "Received refresh command (from UI toggle)")
                updateVpnConfiguration()
            }
            else -> {
                Log.i(TAG, "VPN Service starting (from master toggle or refresh)")
                updateCurrentNetworkState()
                updateVpnConfiguration()
            }
        }
        
        return START_STICKY
    }

    private fun stopVpn() {
        Log.i(TAG, "VPN Service stopping...")
        stopForeground(true)
        stopSelf()
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface on stopVpn", e)
        }
    }
    
    private fun updateCurrentNetworkState(): Boolean {
        val network = currentNetwork ?: connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        isWifiActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellularActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        
        Log.d(TAG, "Network state updated: isWifi=$isWifiActive, isCellular=$isCellularActive")
        return isWifiActive || isCellularActive
    }

    private fun updateVpnConfiguration() {
        if (!isReconfiguring.compareAndSet(false, true)) {
            Log.w(TAG, "Already reconfiguring, skipping call.")
            return
        }

        Log.d(TAG, "Starting VPN configuration...")

        try {
            val prefs = FirewallPreferences(this)
            
            if (!updateCurrentNetworkState()) {
                 Log.w(TAG, "updateVpnConfiguration called, but no active network. Closing interface.")
                 try {
                     vpnInterface?.close()
                     vpnInterface = null
                 } catch (e: Exception) {}
                 return
            }
    
            val blockedPackages = prefs.getBlockedPackagesForNetwork(FirewallMode.VPN, isWifiActive)
    
            if (blockedPackages.isEmpty()) {
                Log.i(TAG, "No apps are blocked for the current network ($isWifiActive). Closing VPN interface.")
                try {
                    vpnInterface?.close()
                    vpnInterface = null
                } catch (e: Exception) {}
                return 
            }
            
            
            try {
                if (vpnInterface != null) {
                    vpnInterface?.close()
                    vpnInterface = null // Set to null *after* closing
                    Log.d(TAG, "Closed old VPN interface.")
                }
    
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress("10.1.10.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
    
                val activeNetwork = currentNetwork ?: connectivityManager.activeNetwork
                if (activeNetwork != null) {
                     builder.setUnderlyingNetworks(arrayOf(activeNetwork))
                     Log.d(TAG, "Binding VPN to network: $activeNetwork")
                } else {
                     Log.w(TAG, "No active network to bind to.")
                }
    
                for (pkgName in blockedPackages) {
                    try {
                        builder.addAllowedApplication(pkgName)
                        Log.d(TAG, "BLOCKING (routing to VPN): $pkgName")
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Package not found to block: $pkgName", e)
                    }
                }
    
                vpnInterface = builder.establish()
                
                if(vpnInterface == null) {
                     Log.e(TAG, "Failed to establish VPN interface. builder.establish() returned null.")
                } else {
                    Log.i(TAG, "VPN interface established successfully for ${if (isWifiActive) "Wi-Fi" else "Data"}. Blocked ${blockedPackages.size} apps.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error establishing VPN interface", e)
            }
        } finally {
            isReconfiguring.set(false)
            Log.d(TAG, "Finished VPN configuration.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "VPN Service destroyed, unregistering callback and closing interface.")
        
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
             Log.e(TAG, "Error unregistering network callback", e)
        }
        
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface in onDestroy", e)
        }
    }
}


