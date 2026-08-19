package com.cuttercommand.app

import kotlinx.coroutines.*
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

/**
 * TCP client for the cutter.
 *
 * The jog pad, Pause, Stop, and Test commands below are reverse engineered
 * from the decompiled main-window class of an older E4A-built Android app
 * for a similar machine (not from vendor docs) - verify against your
 * cutter's actual behaviour, as these may or may not be commands this
 * machine actually recognizes.
 *
 * The plot-file send itself, however, follows a confirmed-working reference
 * (a Python script that talks to this exact cutter over TCP): connect with
 * a short timeout so an unreachable/powered-off machine fails fast, then
 * send the file's raw text as a single write (UTF-8, with a trailing
 * newline) with a longer timeout to allow for large files - no preamble,
 * no handshake, no staged/split send. That confirmed-working shape is why
 * this class no longer sends a BD:100,10/11/12 speed/pressure preamble, or
 * uses the old ";;;PGSTART" / PGREADY / PGOK staged-send flow - the plot
 * file is sent exactly as loaded from disk.
 *
 * A bare space " " is still sent once a second as a keepalive while
 * connected (not logged - it would flood the log once a second for no
 * useful reason). "RSVER;" is still sent 3x on connect to request the
 * firmware version, logged as usual.
 *
 * Every command sent, and everything received, is reported to
 * Listener.onLog() as "Sent: <comment> -> <command>" / "Received: <data>"
 * so the log is a full audit trail. The plot-file payload itself is
 * truncated in the log (not on the wire) past ~200 chars, since a full
 * HPGL file can be large and would make the log unusable - see
 * truncateForLog().
 */
class PlotterClient(private val listener: Listener) {

    interface Listener {
        fun onStatus(text: String)          // "Off" / "Connected" / "Wait" / "UP OK" / "UP Err" ...
        fun onVersion(version: String)
        fun onLog(line: String)
    }

    // Used for the short protocol commands (RSVER, jog, pause, stop, test,
    // keepalive) - all pure ASCII, so this choice is inconsequential for them.
    // Android/ICU normally ships GBK, but fall back to GB18030 (a GBK
    // superset) if a given device build doesn't include it.
    private val charset: Charset = try {
        Charset.forName("GBK")
    } catch (e: Exception) {
        Charset.forName("GB18030")
    }

    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null
    private var scope: CoroutineScope? = null
    private var keepAliveJob: Job? = null
    private var readJob: Job? = null

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(host: String, port: Int) {
        disconnect()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                socket = s
                out = BufferedOutputStream(s.getOutputStream())

                withContext(Dispatchers.Main) {
                    listener.onStatus("Connected")
                    listener.onLog("Connected to cutter - OK")
                }

                // Original app queries version 3x on connect for reliability.
                repeat(3) { sendRaw("RSVER;", "request firmware version") }

                startKeepAlive(sc)
                startReadLoop(sc, s.getInputStream())
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    listener.onStatus("Off")
                    listener.onLog("Connect failed: ${e.message}")
                }
            }
        }
    }

    fun disconnect() {
        keepAliveJob?.cancel()
        readJob?.cancel()
        scope?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
        listener.onStatus("Off")
    }

    private fun startKeepAlive(sc: CoroutineScope) {
        keepAliveJob = sc.launch {
            while (isActive) {
                delay(1000)
                if (isConnected) sendRaw(" ", "keepalive", log = false)
            }
        }
    }

    private fun startReadLoop(sc: CoroutineScope, input: InputStream) {
        readJob = sc.launch {
            val buf = ByteArray(4096)
            try {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val newChunk = String(buf, 0, n, charset)
                    withContext(Dispatchers.Main) {
                        listener.onLog("Received: ${truncateForLog(newChunk)}")
                    }

                    val verIdx = newChunk.indexOf("VER=")
                    if (verIdx != -1) {
                        val end = newChunk.indexOf(";", verIdx)
                        if (end != -1) {
                            val version = newChunk.substring(verIdx + 4, end)
                            withContext(Dispatchers.Main) { listener.onVersion(version) }
                        }
                    }
                }
            } catch (e: Exception) {
                // socket closed / network drop
            }
            withContext(Dispatchers.Main) { listener.onStatus("Off") }
        }
    }

    // A full plot file can be large - logging it verbatim would make the
    // log unusable (and slow). Truncate what's shown in the log only; the
    // full data still goes out over the socket untouched.
    private fun truncateForLog(s: String): String {
        val cleaned = s.replace("\n", " ").replace("\r", "")
        return if (cleaned.length > 200) {
            cleaned.take(200) + "... [truncated, ${s.length} chars total]"
        } else {
            cleaned
        }
    }

    // Actual socket writes must happen off the main thread - Android throws
    // NetworkOnMainThreadException for any network I/O called directly from
    // a UI click/touch listener. jog/pause/stop/test/keepalive funnel
    // through this, so hopping onto the connection's IO-dispatcher scope
    // here fixes it everywhere at once instead of wrapping every caller.
    //
    // `comment` is a short plain-English description of what the command
    // does, logged alongside the actual command text sent. Pass
    // log = false for high-frequency, low-value traffic (just the keepalive).
    private fun sendRaw(text: String, comment: String, log: Boolean = true) {
        val sc = scope ?: return
        sc.launch {
            try {
                out?.write(text.toByteArray(charset))
                out?.flush()
                if (log) {
                    withContext(Dispatchers.Main) {
                        listener.onLog("Sent: $comment -> ${truncateForLog(text)}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { listener.onLog("Send failed ($comment): ${e.message}") }
            }
        }
    }

    /**
     * Sends the loaded plot file's content exactly as-is: one write, UTF-8,
     * with a trailing newline - matching the confirmed-working Python
     * reference (`send_hpgl()`). No BD: preamble, no PGSTART staging.
     *
     * Java sockets don't support a write-side timeout the way Python's
     * blocking socket.settimeout() does (Socket.setSoTimeout() only governs
     * reads) - withTimeout() below reproduces that 90s send-timeout
     * behavior: if the write genuinely stalls (e.g. a dead/blackholed
     * connection), the coroutine is cancelled and the socket force-closed
     * rather than hanging indefinitely.
     */
    fun sendProgram(content: String) {
        val sc = scope ?: run {
            listener.onLog("Send failed: not connected")
            return
        }
        listener.onStatus("Wait")
        sc.launch {
            try {
                withTimeout(90_000) {
                    out?.write((content + "\n").toByteArray(Charsets.UTF_8))
                    out?.flush()
                }
                withContext(Dispatchers.Main) {
                    val byteCount = content.toByteArray(Charsets.UTF_8).size
                    listener.onLog("Sent: plot file -> ${truncateForLog(content)} [$byteCount bytes]")
                    listener.onStatus("UP OK")
                }
            } catch (e: TimeoutCancellationException) {
                try { socket?.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    listener.onLog("Send failed: timed out after 90s")
                    listener.onStatus("UP Err")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    listener.onLog("Send failed: ${e.message}")
                    listener.onStatus("UP Err")
                }
            }
        }
    }

    // --- manual jog / control panel, ported from the original app's 前/后/左/右 ---
    // (button-press / button-release handlers) and the pause, stop and test
    // buttons. Command strings and the leading-";" quirks are copied
    // exactly from the decompiled original - do not "clean them up". Left
    // in place per instruction, though unlike the plot-file send above,
    // these are NOT confirmed against this machine's actual protocol.

    enum class JogDirection(val code: Int) {
        LEFT(1), RIGHT(2), FORWARD(3), BACK(4)
    }

    /** Call on ACTION_DOWN for a jog button. */
    fun jogPress(direction: JogDirection) {
        sendRaw(";BD:100,${direction.code};", "jog start (${direction.name.lowercase()})")
    }

    /** Call on ACTION_UP / ACTION_CANCEL for a jog button. */
    fun jogRelease() {
        sendRaw(";BD:100,0;", "jog stop")
    }

    fun pause() {
        sendRaw(";BD:100,7;", "pause")
    }

    fun stop() {
        sendRaw(";BD:100,6;", "stop")
    }

    /** Triggers the plotter's self-test. */
    fun test() {
        sendRaw(";SYSTEST8,0;", "run self-test")
    }
}
