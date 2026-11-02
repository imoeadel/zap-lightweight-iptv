package com.moe.zap

import android.app.Application
import com.moe.zap.data.Repo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}
