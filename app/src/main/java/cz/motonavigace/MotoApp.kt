package cz.motonavigace

import android.app.Application

class MotoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Storage.init(this)
        Settings.init(this)
        SearchIndex.init(this)
        Catalog.init(this)
        // Po aktualizaci aplikace (změněné aliasy v manifestu) srovná ikonu s nastavením
        Settings.applyLauncherIcon(this)
    }
}
