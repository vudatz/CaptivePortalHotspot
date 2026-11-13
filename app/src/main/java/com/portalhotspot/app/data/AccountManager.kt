package com.portalhotspot.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.portalhotspot.app.models.Account
import com.portalhotspot.app.models.ConnectedClient
import java.util.concurrent.ConcurrentHashMap

class AccountManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("hotspot_accounts", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // In-memory accounts map
    private val accounts = ConcurrentHashMap<String, Account>()
    
    // In-memory active connected clients by IP
    private val activeClients = ConcurrentHashMap<String, ConnectedClient>()

    init {
        loadAccounts()
    }

    private fun loadAccounts() {
        val json = prefs.getString("accounts_list", null)
        if (!json.isNullOrEmpty()) {
            val type = object : TypeToken<Map<String, Account>>() {}.type
            val loaded: Map<String, Account> = gson.fromJson(json, type)
            accounts.putAll(loaded)
        } else {
            // Tạo sẵn 2 tài khoản mẫu
            val demoSingle = Account("khach1", "123456", isSingleDevice = true, dataLimitBytes = 500L * 1024 * 1024) // 500MB
            val demoShared = Account("chung1", "123456", isSingleDevice = false, dataLimitBytes = 1024L * 1024 * 1024) // 1GB
            accounts[demoSingle.username] = demoSingle
            accounts[demoShared.username] = demoShared
            saveAccounts()
        }
    }

    private fun saveAccounts() {
        prefs.edit().putString("accounts_list", gson.toJson(accounts)).apply()
    }

    fun getAllAccounts(): List<Account> = accounts.values.toList()

    fun addOrUpdateAccount(account: Account): Boolean {
        accounts[account.username] = account
        saveAccounts()
        return true
    }

    fun deleteAccount(username: String) {
        accounts.remove(username)
        // Đăng xuất các client đang dùng tài khoản này
        activeClients.values.filter { it.username == username }.forEach {
            it.isAuthenticated = false
            it.username = null
        }
        saveAccounts()
    }

    fun getClient(ip: String): ConnectedClient {
        return activeClients.computeIfAbsent(ip) { ConnectedClient(ip = it) }
    }

    fun getAllClients(): List<ConnectedClient> = activeClients.values.toList()

    sealed class AuthResult {
        object Success : AuthResult()
        object InvalidCredentials : AuthResult()
        object AccountDisabled : AuthResult()
        object QuotaExceeded : AuthResult()
        data class DeviceConflict(val message: String) : AuthResult()
    }

    @Synchronized
    fun authenticate(username: String, pass: String, clientIp: String): AuthResult {
        val account = accounts[username] ?: return AuthResult.InvalidCredentials
        if (account.password != pass) return AuthResult.InvalidCredentials
        if (!account.isEnabled) return AuthResult.AccountDisabled

        if (!account.hasRemainingData()) {
            return AuthResult.QuotaExceeded
        }

        // Chế độ 1 thiết bị: kiểm tra xem tài khoản này có đang bị máy khác dùng không
        if (account.isSingleDevice) {
            val boundIp = account.boundIp
            if (boundIp != null && boundIp != clientIp) {
                // Kiểm tra xem IP cũ có còn online không
                val existingClient = activeClients[boundIp]
                val isRecent = existingClient != null && (System.currentTimeMillis() - existingClient.lastActivityAt < 10 * 60 * 1000)
                if (isRecent) {
                    return AuthResult.DeviceConflict("Tài khoản này chỉ dùng cho 1 thiết bị và hiện đang đăng nhập tại IP $boundIp!")
                }
            }
            // Khóa tài khoản vào IP mới này
            account.boundIp = clientIp
        }

        val client = getClient(clientIp)
        client.username = username
        client.isAuthenticated = true
        client.lastActivityAt = System.currentTimeMillis()

        saveAccounts()
        return AuthResult.Success
    }

    @Synchronized
    fun addTraffic(clientIp: String, bytes: Long) {
        val client = activeClients[clientIp] ?: return
        client.sessionBytes += bytes
        client.lastActivityAt = System.currentTimeMillis()

        val username = client.username ?: return
        val account = accounts[username] ?: return
        account.usedBytes += bytes

        // Nếu vượt quota thì tự động ngắt xác thực
        if (!account.hasRemainingData()) {
            client.isAuthenticated = false
        }
    }

    fun kickClient(clientIp: String) {
        val client = activeClients[clientIp] ?: return
        val username = client.username
        if (username != null) {
            val account = accounts[username]
            if (account != null && account.boundIp == clientIp) {
                account.boundIp = null
            }
        }
        client.isAuthenticated = false
        client.username = null
        activeClients.remove(clientIp)
        saveAccounts()
    }

    companion object {
        @Volatile
        private var INSTANCE: AccountManager? = null

        fun getInstance(context: Context): AccountManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AccountManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
