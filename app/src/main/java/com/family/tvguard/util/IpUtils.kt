package com.family.tvguard.util

import java.net.Inet4Address
import java.net.NetworkInterface

object IpUtils {
    fun localIpv4(): String? {
        return try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return null
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
