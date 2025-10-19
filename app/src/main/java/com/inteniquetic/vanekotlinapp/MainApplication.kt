package com.inteniquetic.vanekotlinapp

import android.app.Application
import com.inteniquetic.vanekotlin.Vane

class MainApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        Vane.initialize()
    }
}