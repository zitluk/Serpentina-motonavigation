package cz.motonavigace

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Spouštěcí aktivita za ikonou aplikace. Sama nic nezobrazuje, jen otevře MainActivity.
 * Smysl má její styl z manifestu: podle něj systém hned po ťuknutí na ikonu nakreslí
 * úvodní obrazovku – s logem výrobce, jehož ikonu uživatel zvolil. Úvodní okno se pak
 * předá MainActivity a zůstane, dokud se nenačte.
 */
abstract class StartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
    }
}

// Jedna třída na ikonu – každá má v manifestu styl s logem svého výrobce
class StartDefault : StartActivity()
class StartAprilia : StartActivity()
class StartBenelli : StartActivity()
class StartBmw : StartActivity()
class StartCfmoto : StartActivity()
class StartDucati : StartActivity()
class StartHarleyDavidson : StartActivity()
class StartHonda : StartActivity()
class StartHusqvarna : StartActivity()
class StartIndian : StartActivity()
class StartJawa : StartActivity()
class StartKawasaki : StartActivity()
class StartKtm : StartActivity()
class StartMotoGuzzi : StartActivity()
class StartMvAgusta : StartActivity()
class StartPiaggio : StartActivity()
class StartRoyalEnfield : StartActivity()
class StartSuzuki : StartActivity()
class StartTriumph : StartActivity()
class StartVespa : StartActivity()
class StartYamaha : StartActivity()
