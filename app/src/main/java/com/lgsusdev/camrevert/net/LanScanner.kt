package com.lgsusdev.camrevert.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Escáner simple de la red local: sondea el subnet /24 de la IP configurada
 * probando los puertos RTSP típicos de estas cámaras.
 *
 * Es un sondeo activo y discreto (solo connect TCP con timeout corto), sin
 * broadcast ni permisos especiales.
 */
object LanScanner {

    data class FoundDevice(val ip: String, val openPorts: List<Int>)

    /**
     * @param baseIp IP de la cámara configurada; se extrae su /24
     * @param ports puertos a sondear
     */
    suspend fun scan(
        baseIp: String,
        ports: List<Int> = listOf(554, 10554, 10555, 81),
        timeoutMs: Int = 400,
        onProgress: suspend (String) -> Unit = {},
    ): List<FoundDevice> = withContext(Dispatchers.IO) {
        val parts = baseIp.split(".")
        if (parts.size != 4) return@withContext emptyList()
        val prefix = parts.take(3).joinToString(".") + "."

        val hosts = (1..254).map { prefix + it }
        coroutineScope {
            hosts.map { host ->
                async {
                    val open = ports.filter { port -> isOpen(host, port, timeoutMs) }
                    if (open.isNotEmpty()) {
                        onProgress(host)
                        FoundDevice(host, open)
                    } else {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (t: Throwable) {
            false
        }
    }
}
