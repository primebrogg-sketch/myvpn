package com.example.myvpn.vpn

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import libbox.BoxService
import libbox.InterfaceUpdateListener
import libbox.Libbox
import libbox.NetworkInterfaceIterator
import libbox.Notification as SingNotification
import libbox.PlatformInterface
import libbox.SetupOptions
import libbox.StringIterator
import libbox.TunOptions
import libbox.WIFIState

class MyVpnService : VpnService() {

    private var service: BoxService? = null
    private var connectionJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null

    sealed class ServiceStatus {
        object Idle : ServiceStatus()
        object Connected : ServiceStatus()
        data class Failed(val message: String) : ServiceStatus()
    }

    companion object {
        const val ACTION_CONNECT = "com.example.myvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.myvpn.DISCONNECT"
        const val EXTRA_CONFIG_JSON = "config_json"
        private const val NOTIF_CHANNEL = "vpn_status"
        private const val NOTIF_ID = 1

        val serviceStatus = MutableStateFlow<ServiceStatus>(ServiceStatus.Idle)
    }

    override fun onCreate() {
        super.onCreate()
        val setupOptions = SetupOptions().apply {
            basePath = filesDir.absolutePath
            workingPath = filesDir.absolutePath
            tempPath = cacheDir.absolutePath
            fixAndroidStack = true
        }
        Libbox.setup(setupOptions)
        registerNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON) ?: return START_NOT_STICKY
                startForegroundWithNotification()
                startVpn(configJson)
            }
        }
        return START_STICKY
    }

    private fun registerNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetwork = network
                android.util.Log.d("MyVPN", "Network available: $network")
                service?.resetNetwork()
            }

            override fun onLost(network: Network) {
                if (activeNetwork == network) activeNetwork = null
                android.util.Log.d("MyVPN", "Network lost: $network")
                service?.resetNetwork()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                android.util.Log.d("MyVPN", "Network capabilities changed: $network")
                service?.resetNetwork()
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
        activeNetwork = cm.activeNetwork
    }

    private fun startVpn(configJson: String) {
        connectionJob = serviceScope.launch {
            try {
                val platformInterface = object : PlatformInterface {
                    override fun openTun(options: TunOptions): Int {
                        val builder = Builder()
                            .setSession("MyVPN")
                            .setMtu(options.mtu)

                        var addr = options.inet4Address
                        while (addr != null && addr.hasNext()) {
                            val prefix = addr.next()
                            builder.addAddress(prefix.address(), prefix.prefix())
                        }
                        addr = options.inet6Address
                        while (addr != null && addr.hasNext()) {
                            val prefix = addr.next()
                            builder.addAddress(prefix.address(), prefix.prefix())
                        }

                        var route = options.inet4RouteAddress
                        var hasIpv4Route = false
                        while (route != null && route.hasNext()) {
                            val prefix = route.next()
                            builder.addRoute(prefix.address(), prefix.prefix())
                            hasIpv4Route = true
                        }
                        if (!hasIpv4Route) builder.addRoute("0.0.0.0", 0)

                        route = options.inet6RouteAddress
                        var hasIpv6Route = false
                        while (route != null && route.hasNext()) {
                            val prefix = route.next()
                            builder.addRoute(prefix.address(), prefix.prefix())
                            hasIpv6Route = true
                        }
                        if (!hasIpv6Route) builder.addRoute("::", 0)

                        builder.addDnsServer("1.1.1.1")

                        var excludePkgs = options.excludePackage
                        while (excludePkgs != null && excludePkgs.hasNext()) {
                            val pkg = excludePkgs.next()
                            runCatching { builder.addDisallowedApplication(pkg) }
                        }

                        runCatching { builder.addDisallowedApplication(packageName) }

                        var includePkgs = options.includePackage
                        while (includePkgs != null && includePkgs.hasNext()) {
                            val pkg = includePkgs.next()
                            runCatching { builder.addAllowedApplication(pkg) }
                        }

                        tunFd = builder.establish()
                        return tunFd?.fd ?: throw IllegalStateException("Failed to establish TUN")
                    }

                    override fun usePlatformAutoDetectInterfaceControl() = true

                    override fun autoDetectInterfaceControl(fd: Int) {
                        val result = runCatching { protect(fd) }
                        android.util.Log.d("MyVPN/sing-box", "protect(fd=$fd) = ${result.getOrNull()}, error: ${result.exceptionOrNull()?.message}")
                    }

                    override fun useProcFS() = false

                    override fun findConnectionOwner(
                        ipProtocol: Int, sourceAddress: String, sourcePort: Int,
                        destAddress: String, destPort: Int
                    ): Int = -1

                    override fun packageNameByUid(uid: Int) = ""

                    override fun uidByPackageName(packageName: String) = -1

                    override fun getInterfaces(): NetworkInterfaceIterator? {
                        val enumeration = runCatching { java.net.NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
                        val list = mutableListOf<libbox.NetworkInterface>()
                        while (enumeration.hasMoreElements()) {
                            val netIface = enumeration.nextElement()
                            val name = netIface.name ?: continue
                            if (name.isEmpty()) continue
                            val li = libbox.NetworkInterface()
                            li.name = name
                            li.index = netIface.index
                            li.mtu = netIface.mtu
                            val addrList = mutableListOf<String>()
                            val addresses = netIface.inetAddresses
                            while (addresses.hasMoreElements()) {
                                addrList.add(addresses.nextElement().hostAddress ?: "")
                            }
                            li.addresses = object : StringIterator {
                                val iter = addrList.iterator()
                                override fun hasNext() = iter.hasNext()
                                override fun next() = iter.next()
                                override fun len() = addrList.size
                            }
                            var flags = 0
                            if (netIface.isUp) flags = flags or 1
                            if (netIface.isLoopback) flags = flags or 4
                            if (netIface.isPointToPoint) flags = flags or 8
                            if (netIface.supportsMulticast()) flags = flags or 16
                            li.flags = flags
                            li.type = when {
                                netIface.isLoopback -> 0
                                name.startsWith("tun") -> 6
                                name.startsWith("wlan") -> 2
                                name.startsWith("eth") -> 1
                                name.startsWith("rmnet") || name.startsWith("ccmni") -> 3
                                else -> 0
                            }
                            list.add(li)
                        }
                        return object : NetworkInterfaceIterator {
                            val iter = list.iterator()
                            override fun hasNext() = iter.hasNext()
                            override fun next() = iter.next()
                        }
                    }

                    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}

                    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}

                    override fun underNetworkExtension() = false

                    override fun includeAllNetworks() = false

                    override fun clearDNSCache() {}

                    override fun readWIFIState(): WIFIState? = null

                    override fun writeLog(message: String) {
                        android.util.Log.d("MyVPN/sing-box", message)
                    }

                    override fun sendNotification(notification: SingNotification?) {}
                }

                activeNetwork?.let { network ->
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    runCatching { cm.bindProcessToNetwork(network) }
                }

                val svc = Libbox.newService(configJson, platformInterface) ?: throw IllegalStateException("newService returned null")
                svc.start()
                service = svc
                serviceStatus.value = ServiceStatus.Connected
            } catch (e: Exception) {
                android.util.Log.e("MyVPN", "Failed to start VPN", e)
                serviceStatus.value = ServiceStatus.Failed(e.message ?: "Unknown error")
                runCatching { service?.close() }
                service = null
                runCatching { tunFd?.close() }
                tunFd = null
            }
        }
    }

    private var tunFd: ParcelFileDescriptor? = null

    private fun stopVpn() {
        connectionJob?.cancel()
        connectionJob = null
        runCatching { service?.close() }
        service = null
        runCatching { tunFd?.close() }
        tunFd = null
        serviceStatus.value = ServiceStatus.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("MyVPN")
            .setContentText("Подключено")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        networkCallback?.let {
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        }
        connectionJob?.cancel()
        runCatching { service?.close() }
        runCatching { tunFd?.close() }
        serviceStatus.value = ServiceStatus.Idle
        serviceScope.cancel()
        super.onDestroy()
    }
}
