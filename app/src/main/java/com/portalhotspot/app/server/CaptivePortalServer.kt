package com.portalhotspot.app.server

import android.content.Context
import com.portalhotspot.app.data.AccountManager
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

class CaptivePortalServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val accountManager = AccountManager.getInstance(context)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val clientIp = session.remoteIpAddress ?: "unknown"
        val client = accountManager.getClient(clientIp)

        // Phát hiện Captive Portal Probe của các hệ điều hành (Android, iOS, Windows)
        val isProbe = uri.contains("generate_204") ||
                uri.contains("hotspot-detect") ||
                uri.contains("ncsi.txt") ||
                uri.contains("connecttest.txt") ||
                uri.contains("canonical.html")

        if (isProbe && !client.isAuthenticated) {
            // Chuyển hướng sang trang login
            val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            response.addHeader("Location", "http://192.168.43.1:8080/portal")
            return response
        }

        // Xử lý POST Login
        if (session.method == Method.POST && uri == "/login") {
            return handleLogin(session, clientIp)
        }

        // Trang trạng thái kết nối
        if (uri == "/status") {
            return handleStatus(clientIp)
        }

        // Trang logout
        if (uri == "/logout") {
            accountManager.kickClient(clientIp)
            val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            response.addHeader("Location", "/portal")
            return response
        }

        // Mặc định trả về trang Captive Portal
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderPortalHtml(clientIp))
    }

    private fun handleLogin(session: IHTTPSession, clientIp: String): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            // ignore
        }

        val params = session.parameters
        val username = params["username"]?.firstOrNull()?.trim() ?: ""
        val password = params["password"]?.firstOrNull()?.trim() ?: ""

        val result = accountManager.authenticate(username, password, clientIp)

        return when (result) {
            is AccountManager.AuthResult.Success -> {
                newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderSuccessHtml(username))
            }
            is AccountManager.AuthResult.InvalidCredentials -> {
                newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderPortalHtml(clientIp, error = "Tên đăng nhập hoặc mật khẩu không chính xác!"))
            }
            is AccountManager.AuthResult.AccountDisabled -> {
                newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderPortalHtml(clientIp, error = "Tài khoản này đang bị tạm khóa."))
            }
            is AccountManager.AuthResult.QuotaExceeded -> {
                newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderPortalHtml(clientIp, error = "Tài khoản đã sử dụng hết hạn mức dung lượng! Vui lòng liên hệ quản trị viên."))
            }
            is AccountManager.AuthResult.DeviceConflict -> {
                newFixedLengthResponse(Response.Status.OK, MIME_HTML, renderPortalHtml(clientIp, error = result.message))
            }
        }
    }

    private fun handleStatus(clientIp: String): Response {
        val client = accountManager.getClient(clientIp)
        val user = client.username
        val account = accountManager.getAllAccounts().find { it.username == user }

        val usedMb = String.format("%.2f", client.getSessionMb())
        val totalMb = if (account != null && account.dataLimitBytes > 0) {
            String.format("%.2f MB", account.getLimitMb())
        } else {
            "Không giới hạn"
        }

        val html = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Trạng Thái Kết Nối - Hotspot Portal</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #f1f5f9; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
                    .card { background: white; border-radius: 16px; padding: 24px; box-shadow: 0 10px 25px rgba(0,0,0,0.1); width: 90%; max-width: 400px; text-align: center; }
                    .badge { background: #dcfce7; color: #15803d; padding: 6px 12px; border-radius: 999px; font-weight: 600; font-size: 14px; }
                    .stat-box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 16px; margin: 20px 0; text-align: left; }
                    .btn-logout { background: #ef4444; color: white; border: none; padding: 12px; border-radius: 8px; width: 100%; font-weight: 600; cursor: pointer; text-decoration: none; display: block; box-sizing: border-box; }
                </style>
            </head>
            <body>
                <div class="card">
                    <span class="badge">ĐÃ KẾT NỐI INTERNET</span>
                    <h2>Tài khoản: ${user ?: "Chưa rõ"}</h2>
                    <div class="stat-box">
                        <p><strong>IP của bạn:</strong> $clientIp</p>
                        <p><strong>Dung lượng phiên:</strong> $usedMb MB</p>
                        <p><strong>Hạn mức tài khoản:</strong> $totalMb</p>
                    </div>
                    <a href="/logout" class="btn-logout">Đăng Xuất</a>
                </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun renderPortalHtml(clientIp: String, error: String? = null): String {
        val errorBlock = if (error != null) {
            """<div class="alert-error">$error</div>"""
        } else ""

        return """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Đăng Nhập Wi-Fi Free - Hotspot Portal</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                        background: linear-gradient(135deg, #1e3a8a 0%, #3b82f6 100%);
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 16px;
                    }
                    .portal-card {
                        background: #ffffff;
                        border-radius: 20px;
                        width: 100%;
                        max-width: 380px;
                        padding: 32px 24px;
                        box-shadow: 0 20px 40px rgba(0, 0, 0, 0.25);
                        text-align: center;
                    }
                    .wifi-icon {
                        width: 64px;
                        height: 64px;
                        background: #eff6ff;
                        color: #2563eb;
                        border-radius: 50%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        margin: 0 auto 16px auto;
                    }
                    h1 { font-size: 22px; color: #1e293b; margin-bottom: 8px; font-weight: 700; }
                    p.subtitle { font-size: 14px; color: #64748b; margin-bottom: 24px; }
                    .form-group { text-align: left; margin-bottom: 16px; }
                    label { display: block; font-size: 13px; font-weight: 600; color: #475569; margin-bottom: 6px; }
                    input[type="text"], input[type="password"] {
                        width: 100%;
                        padding: 12px 14px;
                        border: 1.5px solid #cbd5e1;
                        border-radius: 10px;
                        font-size: 15px;
                        outline: none;
                        transition: border-color 0.2s;
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
                        box-shadow: 0 4px 12px rgba(37, 99, 235, 0.3);
                    }
                    .btn-submit:hover { background: #1d4ed8; }
                    .alert-error {
                        background: #fee2e2;
                        border: 1px solid #f87171;
                        color: #b91c1c;
                        padding: 10px 14px;
                        border-radius: 8px;
                        font-size: 13px;
                        margin-bottom: 18px;
                        text-align: left;
                    }
                    .footer-note {
                        margin-top: 24px;
                        font-size: 12px;
                        color: #94a3b8;
                    }
                </style>
            </head>
            <body>
                <div class="portal-card">
                    <div class="wifi-icon">
                        <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M5 12.55a11 11 0 0 1 14.08 0"/>
                            <path d="M1.42 9a16 16 0 0 1 21.16 0"/>
                            <path d="M8.53 16.11a6 6 0 0 1 6.95 0"/>
                            <line x1="12" y1="20" x2="12.01" y2="20"/>
                        </svg>
                    </div>
                    <h1>ĐĂNG NHẬP WI-FI</h1>
                    <p class="subtitle">Vui lòng nhập tài khoản để truy cập Internet</p>

                    $errorBlock

                    <form method="POST" action="/login">
                        <div class="form-group">
                            <label for="username">Tên đăng nhập</label>
                            <input type="text" id="username" name="username" placeholder="Nhập tài khoản..." required autofocus>
                        </div>
                        <div class="form-group">
                            <label for="password">Mật khẩu</label>
                            <input type="password" id="password" name="password" placeholder="Nhập mật khẩu..." required>
                        </div>
                        <button type="submit" class="btn-submit">Kết Nối Ngay</button>
                    </form>

                    <div class="footer-note">
                        IP của bạn: $clientIp <br>
                        Hệ thống xác thực Hotspot Gateway
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
                <title>Đăng Nhập Thành Công!</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #f0fdf4; display: flex; justify-content: center; align-items: center; min-height: 100vh; margin: 0; }
                    .card { background: white; border-radius: 20px; padding: 32px; box-shadow: 0 10px 25px rgba(0,0,0,0.1); width: 90%; max-width: 380px; text-align: center; }
                    .icon { color: #16a34a; margin-bottom: 16px; }
                    h2 { color: #166534; margin-bottom: 8px; }
                    p { color: #4b5563; font-size: 14px; margin-bottom: 24px; }
                    .btn { display: inline-block; background: #16a34a; color: white; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-weight: 600; }
                </style>
                <script>
                    setTimeout(function() {
                        window.location.href = "https://www.google.com";
                    }, 2500);
                </script>
            </head>
            <body>
                <div class="card">
                    <div class="icon">
                        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
                            <polyline points="22 4 12 14.01 9 11.01"></polyline>
                        </svg>
                    </div>
                    <h2>ĐĂNG NHẬP THÀNH CÔNG!</h2>
                    <p>Xin chào <strong>$username</strong>! Thiết bị của bạn đã được kết nối Internet. Đang chuyển hướng...</p>
                    <a href="https://www.google.com" class="btn">Bắt Đầu Lướt Web</a>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
