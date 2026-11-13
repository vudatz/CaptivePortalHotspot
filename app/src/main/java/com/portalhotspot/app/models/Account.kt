package com.portalhotspot.app.models

data class Account(
    val username: String,
    val password: String,
    val isSingleDevice: Boolean = true, // true: Chỉ 1 máy, false: Dùng chung
    val dataLimitBytes: Long = 0L,      // 0 = Không giới hạn, > 0 = giới hạn số Bytes
    var usedBytes: Long = 0L,           // Tổng số bytes đã sử dụng
    var boundIp: String? = null,        // IP thiết bị đang giữ phiên đăng nhập (cho Single Device)
    var isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun hasRemainingData(): Boolean {
        if (dataLimitBytes <= 0L) return true
        return usedBytes < dataLimitBytes
    }

    fun getUsedMb(): Double = usedBytes / (1024.0 * 1024.0)
    fun getLimitMb(): Double = dataLimitBytes / (1024.0 * 1024.0)
}

data class ConnectedClient(
    val ip: String,
    var username: String? = null,
    val connectedAt: Long = System.currentTimeMillis(),
    var lastActivityAt: Long = System.currentTimeMillis(),
    var sessionBytes: Long = 0L,
    var isAuthenticated: Boolean = false
) {
    fun getSessionMb(): Double = sessionBytes / (1024.0 * 1024.0)
}
