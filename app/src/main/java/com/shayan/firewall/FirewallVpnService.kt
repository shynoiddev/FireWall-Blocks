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
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

class FirewallVpnService : VpnService() {

    private val TAG = "FirewallVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    
    private lateinit var connectivityManager: ConnectivityManager
    
    private var isWifiActive: Boolean = false
    private var currentNetwork: Network? = null

    // Monitoring state variables
    @Volatile private var isMonitoringMode = false
    private var packetSnifferThread: Thread? = null

    companion object {
        const val ACTION_STOP = "com.shayan.firewall.STOP_VPN"
        const val ACTION_REFRESH = "com.shayan.firewall.REFRESH_VPN"
        const val ACTION_START_MONITORING = "com.shayan.firewall.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.shayan.firewall.STOP_MONITORING"

        fun stopVpn(context: Context) {
            val intent = Intent(context, FirewallVpnService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            currentNetwork = network
            val oldWifiState = isWifiActive
            updateCurrentNetworkState()
            
            if (isWifiActive != oldWifiState && !isMonitoringMode) {
                 updateVpnConfiguration()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            if (network == currentNetwork) {
                 currentNetwork = null
                 if (!updateCurrentNetworkState() && !isMonitoringMode) {
                      closeVpnInterface()
                 }
            }
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val oldWifiState = isWifiActive
            updateCurrentNetworkState()
            
            if (isWifiActive != oldWifiState && !isMonitoringMode) {
               updateVpnConfiguration()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val notification = createNotification("VPN is active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
           startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun createNotification(contentText: String): Notification {
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
            .setContentText(contentText)
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
                if (!isMonitoringMode) updateVpnConfiguration()
            }
            ACTION_START_MONITORING -> {
                Log.i(TAG, "Starting Network Monitoring Mode")
                isMonitoringMode = true
                updateCurrentNetworkState()
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(1, createNotification("Monitoring network traffic"))
                
                Thread {
                    closeVpnInterface()
                    Thread.sleep(500)
                    updateVpnConfiguration()
                }.start()
            }
            ACTION_STOP_MONITORING -> {
                Log.i(TAG, "Stopping Network Monitoring Mode")
                isMonitoringMode = false
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(1, createNotification("VPN is active"))
                
                val prefs = FirewallPreferences(this)
                if (prefs.isVpnEnabled()) {
                    // Quick teardown and rebuild to avoid network drops when restoring standard firewall
                    Thread {
                        closeVpnInterface()
                        Thread.sleep(500)
                        updateVpnConfiguration()
                    }.start()
                } else {
                     stopVpn()
                }
            }
            else -> {
                Log.i(TAG, "VPN Service starting normal mode")
                updateCurrentNetworkState()
                updateVpnConfiguration()
            }
        }
        
        return START_STICKY
    }

    private fun stopVpn() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        closeVpnInterface()
    }
    
    private fun closeVpnInterface() {
        packetSnifferThread?.interrupt()
        packetSnifferThread = null
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
    }

    private fun updateCurrentNetworkState(): Boolean {
        val network = currentNetwork ?: connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        isWifiActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellularActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        
        return isWifiActive || isCellularActive
    }

    @Synchronized
    private fun updateVpnConfiguration() {
        try {
            if (!updateCurrentNetworkState() && !isMonitoringMode) {
                 closeVpnInterface()
                 return 
            }

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("10.1.10.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")

            val activeNetwork = currentNetwork ?: connectivityManager.activeNetwork
            if (activeNetwork != null) {
                 builder.setUnderlyingNetworks(arrayOf(activeNetwork))
            }

            if (isMonitoringMode) {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: PackageManager.NameNotFoundException) { }
            } else {
                val prefs = FirewallPreferences(this)
                val blockedPackages = prefs.getBlockedPackagesForNetwork(FirewallMode.VPN, isWifiActive)
                
                if (blockedPackages.isEmpty()) {
                    closeVpnInterface()
                    return
                }

                for (pkgName in blockedPackages) {
                    try {
                        builder.addAllowedApplication(pkgName)
                    } catch (e: PackageManager.NameNotFoundException) { }
                }
            }

            val newInterface = builder.establish()

            packetSnifferThread?.interrupt()
            packetSnifferThread = null
            try { vpnInterface?.close() } catch(e: Exception){}
            
            vpnInterface = newInterface

            if (vpnInterface != null && isMonitoringMode) {
                startPacketSniffer(FileInputStream(vpnInterface!!.fileDescriptor))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN", e)
        }
    }

    private fun startPacketSniffer(inputStream: FileInputStream) {
        packetSnifferThread = Thread {
            val buffer = ByteArray(32767)
            try {
                while (!Thread.currentThread().isInterrupted && vpnInterface != null) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        try {
                            parseAndBroadcastPacket(buffer, length)
                        } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
            }
        }
        packetSnifferThread?.start()
    }

    private fun parseAndBroadcastPacket(buffer: ByteArray, length: Int) {
        if (length < 20) return 
        
        val versionAndIHL = buffer[0].toInt() and 0xFF
        val version = versionAndIHL shr 4
        if (version != 4) return

        val ihl = versionAndIHL and 0x0F
        val ipHeaderLen = ihl * 4
        if (length < ipHeaderLen + 4) return 

        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != 6 && protocol != 17) return
        
        val srcIpBytes = buffer.copyOfRange(12, 16)
        val destIpBytes = buffer.copyOfRange(16, 20)
        
        val srcIp = InetAddress.getByAddress(srcIpBytes)
        val destIp = InetAddress.getByAddress(destIpBytes)

        val srcPort = ((buffer[ipHeaderLen].toInt() and 0xFF) shl 8) or (buffer[ipHeaderLen + 1].toInt() and 0xFF)
        val destPort = ((buffer[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (buffer[ipHeaderLen + 3].toInt() and 0xFF)

        var domainOrIp = destIp.hostAddress ?: "Unknown"

        if (protocol == 17 && destPort == 53) {
            val dnsDomain = extractDnsQuery(buffer, ipHeaderLen + 8, length)
            if (dnsDomain != null) domainOrIp = dnsDomain
        }

        var uid = -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connProtocol = if (protocol == 6) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
            try {
                uid = connectivityManager.getConnectionOwnerUid(
                    connProtocol,
                    InetSocketAddress(srcIp, srcPort),
                    InetSocketAddress(destIp, destPort)
                )
            } catch (e: Exception) { }
        }

        val intent = Intent("com.shayan.firewall.MONITOR_LOG")
        intent.setPackage(packageName)
        intent.putExtra("uid", uid)
        intent.putExtra("ipOrDomain", domainOrIp)
        sendBroadcast(intent)
    }

    private fun extractDnsQuery(buffer: ByteArray, payloadOffset: Int, totalLength: Int): String? {
        try {
            if (totalLength <= payloadOffset + 12) return null
            
            var i = payloadOffset + 12
            val domainParts = mutableListOf<String>()
            
            while (i < totalLength) {
                val len = buffer[i].toInt() and 0xFF
                if (len == 0) break 
                if ((len and 0xC0) == 0xC0) break 
                if (i + 1 + len > totalLength) break 
                
                i++
                val part = String(buffer.copyOfRange(i, i + len))
                domainParts.add(part)
                i += len
            }
            if (domainParts.isNotEmpty()) {
                return domainParts.joinToString(".")
            }
        } catch (e: Exception) {}
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) { }
        closeVpnInterface()
    }
}