package com.portalhotspot.app.server

import android.content.Context
import android.util.Log
import com.portalhotspot.app.data.AccountManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class CaptivePortalServer(
    private val context: Context,
    private val port: Int = 8080
) {
    private val TAG = "PortalServer"
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
                Log.d(TAG, "Captive Portal Web Server listening on port $port")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    threadPool.execute { handleHttpRequest(clientSocket) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Server error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        threadPool.shutdownNow()
    }

    private fun handleHttpRequest(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "unknown"
        val client = accountManager.getClient(clientIp)

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val fullUri = parts[1]
            val path = if (fullUri.contains("?")) fullUri.substring(0, fullUri.indexOf("?")) else fullUri

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            // Đọc body nếu có (POST)
            val postBody = if (method.equals("POST", ignoreCase = true) && contentLength > 0) {
                val chars = CharArray(contentLength)
                reader.read(chars, 0, contentLength)
                String(chars)
            } else ""

            // Phát hiện Captive Portal Probe của Android, Apple, Windows
            val isProbe = path.contains("generate_204") ||
                    path.contains("hotspot-detect") ||
                    path.contains("ncsi.txt") ||
                    path.contains("connecttest.txt")

            if (isProbe && !client.isAuthenticated) {
                sendRedirect(out, "http://192.168.43.1:8080/portal")
                return
            }

            if (path == "/portal" || path == "/") {
                sendHtml(out, renderPortalHtml(clientIp))
                return
            }

            if (path == "/login" && method.equals("POST", ignoreCase = true)) {
                handleLoginPost(out, postBody, clientIp)
                return
            }

            if (path == "/status") {
                sendHtml(out, renderStatusHtml(clientIp))
                return
            }

            if (path == "/logout") {
                accountManager.kickClient(clientIp)
                sendRedirect(out, "/portal")
                return
            }

            // Mặc định trả về trang portal
            sendHtml(out, renderPortalHtml(clientIp))

        } catch (e: Exception) {
            // Socket closed
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun handleLoginPost(out: OutputStream, body: String, clientIp: String) {
        val params = HashMap<String, String>()
        body.split("&").forEach { pair ->
            val kv = pair.split("=")
            if (kv.size == 2) {
                val key = URLDecoder.decode(kv[0], "UTF-8")
                val value = URLDecoder.decode(kv[1], "UTF-8")
                params[key] = value
            }
        }

        val username = params["username"]?.trim() ?: ""
        val password = params["password"]?.trim() ?: ""

        val result = accountManager.authenticate(username, password, clientIp)

        when (result) {
            is AccountManager.AuthResult.Success -> {
                sendHtml(out, renderSuccessHtml(username))
            }
            is AccountManager.AuthResult.InvalidCredentials -> {
                sendHtml(out, renderPortalHtml(clientIp, error = "Tên đăng nhập hoặc mật khẩu không đúng!"))
            }
            is AccountManager.AuthResult.AccountDisabled -> {
                sendHtml(out, renderPortalHtml(clientIp, error = "Tài khoản hiện đang bị tạm khóa."))
            }
            is AccountManager.AuthResult.QuotaExceeded -> {
                sendHtml(out, renderPortalHtml(clientIp, error = "Tài khoản đã sử dụng hết hạn mức dung lượng!"))
            }
            is AccountManager.AuthResult.DeviceConflict -> {
                sendHtml(out, renderPortalHtml(clientIp, error = result.message))
            }
        }
    }

    private fun sendHtml(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendRedirect(out: OutputStream, targetUrl: String) {
        val html = "<html><head><meta http-equiv='refresh' content='0;url=$targetUrl'></head></html>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 302 Found\r\n" +
                "Location: $targetUrl\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun renderStatusHtml(clientIp: String): String {
        val client = accountManager.getClient(clientIp)
        val user = client.username ?: "Khách"
        val account = accountManager.getAllAccounts().find { it.username == user }

        val usedMb = String.format("%.2f", client.getSessionMb())
        val totalMb = if (account != null && account.dataLimitBytes > 0) {
            String.format("%.2f MB", account.getLimitMb())
        } else {
            "Không giới hạn"
        }

        return """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Trạng Thái - Hotspot Portal</title>
                <style>
                    body { font-family: sans-serif; background: #f8fafc; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
                    .card { background: white; border-radius: 16px; padding: 24px; box-shadow: 0 4px 16px rgba(0,0,0,0.08); width: 90%; max-width: 380px; text-align: center; }
                    .badge { background: #dcfce7; color: #15803d; padding: 6px 14px; border-radius: 999px; font-weight: bold; font-size: 13px; }
                    .box { background: #f1f5f9; border-radius: 10px; padding: 16px; margin: 20px 0; text-align: left; font-size: 14px; line-height: 1.8; }
                    .btn { background: #ef4444; color: white; border: none; padding: 12px; border-radius: 8px; width: 100%; font-weight: bold; text-decoration: none; display: block; box-sizing: border-box; }
                </style>
            </head>
            <body>
                <div class="card">
                    <span class="badge">ĐÃ KẾT NỐI INTERNET</span>
                    <h2>Tài khoản: $user</h2>
                    <div class="box">
                        <strong>IP:</strong> $clientIp<br>
                        <strong>Đã dùng:</strong> $usedMb MB<br>
                        <strong>Hạn mức:</strong> $totalMb
                    </div>
                    <a href="/logout" class="btn">Đăng Xuất</a>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderPortalHtml(clientIp: String, error: String? = null): String {
        val errorBlock = if (error != null) {
            """<div class="err">$error</div>"""
        } else ""

        return """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Đăng Nhập Wi-Fi Free</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        background: linear-gradient(135deg, #1e3a8a 0%, #2563eb 100%);
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 16px;
                    }
                    .card {
                        background: #ffffff;
                        border-radius: 20px;
                        width: 100%;
                        max-width: 380px;
                        padding: 32px 24px;
                        box-shadow: 0 16px 36px rgba(0,0,0,0.25);
                        text-align: center;
                    }
                    h1 { font-size: 22px; color: #0f172a; margin-bottom: 6px; }
                    p.sub { font-size: 14px; color: #64748b; margin-bottom: 24px; }
                    .form-group { text-align: left; margin-bottom: 16px; }
                    label { display: block; font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 6px; }
                    input {
                        width: 100%;
                        padding: 12px 14px;
                        border: 1.5px solid #cbd5e1;
                        border-radius: 10px;
                        font-size: 15px;
                        outline: none;
                    }
                    input:focus { border-color: #2563eb; }
                    .btn-submit {
                        width: 100%;
                        padding: 14px;
                        background: #2563eb;
                        border: none;
                        border-radius: 10px;
                        color: white;
                        font-size: 16px;
                        font-weight: 600;
                        cursor: pointer;
                        margin-top: 8px;
                    }
                    .err {
                        background: #fee2e2;
                        border: 1px solid #f87171;
                        color: #b91c1c;
                        padding: 10px 14px;
                        border-radius: 8px;
                        font-size: 13px;
                        margin-bottom: 16px;
                        text-align: left;
                    }
                    .info { margin-top: 24px; font-size: 12px; color: #94a3b8; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>ĐĂNG NHẬP WI-FI</h1>
                    <p class="sub">Vui lòng đăng nhập tài khoản để vào mạng</p>

                    $errorBlock

                    <form method="POST" action="/login">
                        <div class="form-group">
                            <label>Tên đăng nhập</label>
                            <input type="text" name="username" placeholder="Nhập tên tài khoản..." required autofocus>
                        </div>
                        <div class="form-group">
                            <label>Mật khẩu</label>
                            <input type="password" name="password" placeholder="Nhập mật khẩu..." required>
                        </div>
                        <button type="submit" class="btn-submit">Kết Nối Ngay</button>
                    </form>

                    <div class="info">
                        IP: $clientIp | Hotspot Portal Gateway
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderSuccessHtml(username: String): String {
        return """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Thành Công</title>
                <style>
                    body { font-family: sans-serif; background: #f0fdf4; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
                    .card { background: white; border-radius: 20px; padding: 32px; box-shadow: 0 10px 25px rgba(0,0,0,0.1); width: 90%; max-width: 380px; text-align: center; }
                    h2 { color: #166534; margin-bottom: 8px; }
                    p { color: #475569; font-size: 14px; margin-bottom: 24px; }
                    .btn { display: inline-block; background: #16a34a; color: white; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-weight: bold; }
                </style>
                <script>
                    setTimeout(function() { window.location.href = "https://www.google.com"; }, 2000);
                </script>
            </head>
            <body>
                <div class="card">
                    <h2>ĐĂNG NHẬP THÀNH CÔNG!</h2>
                    <p>Xin chào <strong>$username</strong>! Bạn đã có thể truy cập Internet bình thường.</p>
                    <a href="https://www.google.com" class="btn">Bắt Đầu Lướt Web</a>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
