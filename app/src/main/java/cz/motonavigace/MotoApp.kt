package cz.motonavigace

import android.app.Application

class MotoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Storage.init(this)
        Settings.init(this)
    }
}
