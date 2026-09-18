package com.example.input_ds.media

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Keeps Android's system resolver as the first choice and falls back to AliDNS DoH only when the
 * active network's DNS server cannot resolve a host. Bootstrap IPs avoid depending on the broken
 * resolver to reach the DoH endpoint itself.
 */
internal object MediaNetwork {
    fun createClient(): OkHttpClient {
        val bootstrapClient = OkHttpClient.Builder().build()
        val dnsOverHttps = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://dns.alidns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                ipv4Address("223.5.5.5", 223, 5, 5, 5),
                ipv4Address("223.6.6.6", 223, 6, 6, 6)
            )
            .build()

        val fallbackDns = Dns { hostname ->
            try {
                Dns.SYSTEM.lookup(hostname)
            } catch (systemFailure: UnknownHostException) {
                try {
                    dnsOverHttps.lookup(hostname)
                } catch (dohFailure: UnknownHostException) {
                    dohFailure.addSuppressed(systemFailure)
                    throw dohFailure
                }
            }
        }
        return bootstrapClient.newBuilder().dns(fallbackDns).build()
    }

    private fun ipv4Address(host: String, a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(
            host,
            byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
        )
}
