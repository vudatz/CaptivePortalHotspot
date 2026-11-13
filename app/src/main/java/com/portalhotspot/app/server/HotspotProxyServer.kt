package com.portalhotspot.app.server

import android.content.Context
import android.util.Log
import com.portalhotspot.app.data.AccountManager
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class HotspotProxyServer(
    private val context: Context,
    private val port: Int = 8282
) {
    private val TAG = "HotspotProxy"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()
    private val accountManager = AccountManager.getInstance(context)

    fun start() {
        if (isRunning) return
        isRunning = true
        threadPool.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Proxy Server started on port $port")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    threadPool.execute { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Proxy error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        threadPool.shutdownNow()
    }

    private fun handleClient(clientSocket: Socket) {
        val clientIp = clientSocket.inetAddress.hostAddress ?: "unknown"
        val client = accountManager.getClient(clientIp)

        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // Đọc dòng đầu tiên của HTTP Request
            val requestLine = readLine(clientIn) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val uri = parts[1]

            // KIỂM TRA ĐĂNG NHẬP
            if (!client.isAuthenticated) {
                if (method.equals("CONNECT", ignoreCase = true)) {
                    // HTTPS CONNECT: Đóng kết nối để trình duyệt fallback về HTTP hoặc mở captive
                    clientSocket.close()
                    return
                } else {
                    // HTTP Request: Redirect về Captive Portal
                    val redirectHtml = "<html><head><meta http-equiv='refresh' content='0;url=http://192.168.43.1:8080/portal'></head></html>"
                    val redirectResponse = "HTTP/1.1 302 Found\r\n" +
                            "Location: http://192.168.43.1:8080/portal\r\n" +
                            "Content-Type: text/html\r\n" +
                            "Content-Length: ${redirectHtml.length}\r\n" +
                            "Connection: close\r\n\r\n" +
                            redirectHtml
                    clientOut.write(redirectResponse.toByteArray())
                    clientOut.flush()
                    clientSocket.close()
                    return
                }
            }

            // ĐÃ ĐĂNG NHẬP: CHUYỂN TIẾP RA INTERNET
            if (method.equals("CONNECT", ignoreCase = true)) {
                // Xử lý HTTPS CONNECT: host:port
                val hostPort = uri.split(":")
                val host = hostPort[0]
                val targetPort = if (hostPort.size > 1) hostPort[1].toInt() else 443

                // Bỏ qua các header còn lại của client
                while (true) {
                    val line = readLine(clientIn)
                    if (line.isNullOrEmpty()) break
                }

                // Kết nối tới máy chủ đích
                val targetSocket = Socket(host, targetPort)
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                clientOut.flush()

                // Tunnel 2 chiều và đếm bytes
                tunnelStreams(clientSocket, targetSocket, clientIp)
            } else {
                // Xử lý HTTP Request thường
                var host = ""
                var targetPort = 80
                val headers = StringBuilder("$requestLine\r\n")

                while (true) {
                    val line = readLine(clientIn) ?: break
                    if (line.isEmpty()) {
                        headers.append("\r\n")
                        break
                    }
                    if (line.startsWith("Host:", ignoreCase = true)) {
                        val hostVal = line.substring(5).trim()
                        val hParts = hostVal.split(":")
                        host = hParts[0]
                        if (hParts.size > 1) targetPort = hParts[1].toInt()
                    }
                    headers.append(line).append("\r\n")
                }

                if (host.isNotEmpty()) {
                    val targetSocket = Socket(host, targetPort)
                    val targetOut = targetSocket.getOutputStream()
                    val headerBytes = headers.toString().toByteArray()
                    targetOut.write(headerBytes)
                    targetOut.flush()
                    accountManager.addTraffic(clientIp, headerBytes.size.toLong())

                    tunnelStreams(clientSocket, targetSocket, clientIp)
                }
            }
        } catch (e: Exception) {
            // Client ngắt kết nối
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    private fun tunnelStreams(clientSocket: Socket, targetSocket: Socket, clientIp: String) {
        val clientIn = clientSocket.getInputStream()
        val clientOut = clientSocket.getOutputStream()
        val targetIn = targetSocket.getInputStream()
        val targetOut = targetSocket.getOutputStream()

        // Thread 1: Client -> Target (Upload)
        val t1 = Thread {
            try {
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (clientIn.read(buffer).also { bytesRead = it } != -1) {
                    val client = accountManager.getClient(clientIp)
                    if (!client.isAuthenticated) break // Hết quota hoặc bị kick

                    targetOut.write(buffer, 0, bytesRead)
                    targetOut.flush()
                    accountManager.addTraffic(clientIp, bytesRead.toLong())
                }
            } catch (e: Exception) {} finally {
                try { targetSocket.close() } catch (e: Exception) {}
                try { clientSocket.close() } catch (e: Exception) {}
            }
        }

        // Thread 2: Target -> Client (Download)
        val t2 = Thread {
            try {
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (targetIn.read(buffer).also { bytesRead = it } != -1) {
                    val client = accountManager.getClient(clientIp)
                    if (!client.isAuthenticated) break // Hết quota hoặc bị kick

                    clientOut.write(buffer, 0, bytesRead)
                    clientOut.flush()
                    accountManager.addTraffic(clientIp, bytesRead.toLong())
                }
            } catch (e: Exception) {} finally {
                try { targetSocket.close() } catch (e: Exception) {}
                try { clientSocket.close() } catch (e: Exception) {}
            }
        }

        t1.start()
        t2.start()

        try {
            t1.join()
            t2.join()
        } catch (e: Exception) {}
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\r'.code) {
                // skip
            } else if (c == '\n'.code) {
                return sb.toString()
            } else {
                sb.append(c.toChar())
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }
}
