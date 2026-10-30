package com.diriktv.sidekick.data

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

/** Just enough HTTP for the small servers a phone talks to on the home network. */
object LocalHttp {
    class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)

    /** Reads one request: headers up to the blank line, then exactly Content-Length bytes of body. */
    fun read(s: Socket): Request? {
        s.soTimeout = 10_000
        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || head.length > 16_000) return null
            head.append(b.toChar())
        }
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        val target = parts.getOrNull(1) ?: "/"
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(length.coerceIn(0, 10_000))
        var read = 0
        while (read < bodyBytes.size) {
            val n = input.read(bodyBytes, read, bodyBytes.size - read)
            if (n < 0) break
            read += n
        }
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(parts.getOrNull(0).orEmpty(), target.substringBefore('?'), query, String(bodyBytes, 0, read, Charsets.UTF_8))
    }

    fun respond(s: Socket, code: Int, type: String, body: String) = respondBytes(s, code, type, body.toByteArray(Charsets.UTF_8))

    fun respondBytes(s: Socket, code: Int, type: String, bytes: ByteArray, cache: Boolean = false) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: ${if (cache) "max-age=86400" else "no-store"}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    /** This device's address on the home network. */
    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
