package com.v2ray.ang.util

import android.util.Log
import com.v2ray.ang.AppConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Utility object for checking if network ports are available.
 * Used to detect port conflicts before starting v2ray services.
 */
object PortChecker {

    private const val TAG = AppConfig.TAG

    /**
     * Checks if a specific port is available on localhost.
     * @param port The port number to check.
     * @return True if the port is available, false if it's in use.
     */
    fun isPortAvailable(port: Int): Boolean {
        var serverSocket: ServerSocket? = null
        return try {
            serverSocket = ServerSocket(port)
            serverSocket.reuseAddress = true
            true
        } catch (e: IOException) {
            Log.w(TAG, "Port $port is already in use: ${e.message}")
            false
        } finally {
            serverSocket?.close()
        }
    }

    /**
     * Checks if both the SOCKS port and local DNS port are available.
     * @param socksPort The SOCKS proxy port (default: 10808).
     * @param dnsPort The local DNS port (default: 10853).
     * @return True if both ports are available, false if any is in use.
     */
    fun areV2rayPortsAvailable(
        socksPort: Int = AppConfig.PORT_SOCKS.toInt(),
        dnsPort: Int = AppConfig.PORT_LOCAL_DNS.toInt()
    ): Boolean {
        val socksAvailable = isPortAvailable(socksPort)
        val dnsAvailable = isPortAvailable(dnsPort)
        return socksAvailable && dnsAvailable
    }

    /**
     * Gets a list of ports that are currently in use.
     * @param socksPort The SOCKS proxy port (default: 10808).
     * @param dnsPort The local DNS port (default: 10853).
     * @return A list of port numbers that are already in use.
     */
    fun getOccupiedPorts(
        socksPort: Int = AppConfig.PORT_SOCKS.toInt(),
        dnsPort: Int = AppConfig.PORT_LOCAL_DNS.toInt()
    ): List<Int> {
        val occupiedPorts = mutableListOf<Int>()
        if (!isPortAvailable(socksPort)) {
            occupiedPorts.add(socksPort)
        }
        if (!isPortAvailable(dnsPort)) {
            occupiedPorts.add(dnsPort)
        }
        return occupiedPorts
    }

    /**
     * Checks if a port is occupied by attempting to connect to it.
     * This is a more aggressive check that actually tries to establish a connection.
     * @param port The port number to check.
     * @param timeout Connection timeout in milliseconds.
     * @return True if the port is occupied and responding, false otherwise.
     */
    fun isPortOccupied(port: Int, timeout: Int = 1000): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), timeout)
            socket.close()
            Log.d(TAG, "Port $port is occupied and responding")
            true
        } catch (e: IOException) {
            Log.d(TAG, "Port $port check failed: ${e.message}")
            false
        }
    }
}
