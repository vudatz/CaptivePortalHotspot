package com.portalhotspot.app

import android.app.Application
import com.portalhotspot.app.data.AccountManager

class HotspotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AccountManager.getInstance(this)
    }
}
