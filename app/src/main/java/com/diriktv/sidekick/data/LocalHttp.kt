package com.diriktv.sidekick.data

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Just enough HTTP for the small servers a phone talks to on the home network. */
object LocalHttp {
    class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)

    /** Requests handled at once; anyone else on the network gets turned away rather than tying up threads. */
    private const val MAX_CLIENTS = 6
    /** The whole request (headers and body) must arrive within this, however slowly it trickles in. */
    private const val REQUEST_DEADLINE_MS = 8_000L
    private const val MAX_HEAD = 16_000
    private const val MAX_BODY = 10_000

    /**
     * A tiny server: one thread accepts, a small bounded pool answers. [handle] runs per
     * connection and must not throw (failures are swallowed so a phone dropping off can't crash the app).
     */
    class Server(private val name: String, private val handle: (Socket) -> Unit) {
        private var socket: ServerSocket? = null
        private val pool = ThreadPoolExecutor(0, MAX_CLIENTS, 30, TimeUnit.SECONDS, SynchronousQueue())

        val port get() = socket?.localPort ?: 0
        val running get() = socket?.isClosed == false

        /** Listens on the first free port of [ports] (0 = any). Returns false if none could be opened. */
        fun start(ports: List<Int>): Boolean {
            if (running) return true
            val server = ports.firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() } ?: return false
            socket = server
            Thread({
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
                    try {
                        pool.execute { runCatching { client.use { handle(it) } } }
                    } catch (e: RejectedExecutionException) {
                        runCatching { client.close() } // busy: drop it
                    }
                }
            }, name).apply { isDaemon = true }.start()
            return true
        }

        fun stop() {
            runCatching { socket?.close() }
            socket = null
        }
    }

    /** Compares a request's key with the real one without giving away, by timing, how much of it matched. */
    fun keyMatches(given: String?, secret: String): Boolean =
        given != null && secret.isNotEmpty() && MessageDigest.isEqual(given.toByteArray(), secret.toByteArray())

    /** At most [max] hits per [windowMs]; shared by everyone, since the callers are all on one home network. */
    class RateLimit(private val max: Int, private val windowMs: Long = 60_000) {
        private val hits = ArrayDeque<Long>()

        @Synchronized fun allow(): Boolean {
            val now = System.currentTimeMillis()
            while (hits.isNotEmpty() && now - hits.first() > windowMs) hits.removeFirst()
            if (hits.size >= max) return false
            hits.addLast(now)
            return true
        }
    }

    /** Reads one request: headers up to the blank line, then exactly Content-Length bytes of body. */
    fun read(s: Socket, maxBody: Int = MAX_BODY): Request? {
        val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS
        fun timeLeft() = (deadline - System.currentTimeMillis()).toInt()
        val input: InputStream = BufferedInputStream(s.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            if (timeLeft() <= 0 || head.length > MAX_HEAD) return null
            s.soTimeout = timeLeft().coerceAtLeast(1)
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
        }
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        val target = parts.getOrNull(1) ?: "/"
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        // Bigger than allowed: refuse it, rather than reading part of it as if it were all.
        if (length > maxBody) return null
        val bodyBytes = ByteArray(length.coerceIn(0, maxBody))
        var read = 0
        while (read < bodyBytes.size) {
            if (timeLeft() <= 0) return null
            s.soTimeout = timeLeft().coerceAtLeast(1)
            val n = input.read(bodyBytes, read, bodyBytes.size - read)
            if (n < 0) break
            read += n
        }
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            it.substringBefore('=') to runCatching { java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("")
        }
        return Request(parts.getOrNull(0).orEmpty(), target.substringBefore('?'), query, String(bodyBytes, 0, read, Charsets.UTF_8))
    }

    fun respond(s: Socket, code: Int, type: String, body: String) = respondBytes(s, code, type, body.toByteArray(Charsets.UTF_8))

    fun respondBytes(s: Socket, code: Int, type: String, bytes: ByteArray, cache: Boolean = false, extraHeaders: String = "") {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 429 -> "Too Many Requests"; else -> "Not Found" }
        // no-referrer: the key is in the page's address, and it loads images from other sites.
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: ${if (cache) "max-age=86400" else "no-store"}\r\nReferrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n${extraHeaders}Connection: close\r\n\r\n"
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
