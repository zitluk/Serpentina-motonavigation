package cz.motonavigace

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kontrola nové verze na GitHubu. Nejnovější vydání má vždy APK pod stálým odkazem
 * [DOWNLOAD_URL] (stejným jako v QR kódu), číslo verze je ve značce vydání (v0.3.0).
 */
object UpdateCheck {
    private const val LATEST_API = "https://api.github.com/repos/zitluk/Serpentina-motonavigation/releases/latest"
    const val DOWNLOAD_URL = "https://github.com/zitluk/Serpentina-motonavigation/releases/latest/download/Serpentina-latest.apk"
    /** Automaticky nejvýš jednou za tuto dobu – GitHub bez přihlášení povolí 60 dotazů za hodinu. */
    private const val INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000

    private fun prefs(context: Context) = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    /** Naposledy zjištěná verze na GitHubu (null = zatím nezjištěno). */
    fun knownLatest(context: Context): String? = prefs(context).getString("latest", null)

    /** Novější verze než nainstalovaná, pokud o ní víme. */
    fun availableUpdate(context: Context): String? =
        knownLatest(context)?.takeIf { isNewer(it, BuildConfig.VERSION_NAME) }

    /** Verze, u které uživatel zavřel upozornění na hlavní obrazovce. */
    fun isDismissed(context: Context, version: String) = prefs(context).getString("dismissed", null) == version
    fun dismiss(context: Context, version: String) = prefs(context).edit { putString("dismissed", version) }

    /**
     * Zeptá se GitHubu na nejnovější verzi; bez [force] jen když od poslední kontroly
     * uběhlo [INTERVAL_MS]. Vrací zjištěnou verzi, při chybě (třeba bez internetu) chybu.
     */
    suspend fun check(context: Context, force: Boolean = false): Result<String?> = withContext(Dispatchers.IO) {
        val p = prefs(context)
        if (!force && System.currentTimeMillis() - p.getLong("checked_at", 0) < INTERVAL_MS) {
            return@withContext Result.success(p.getString("latest", null))
        }
        runCatching {
            val conn = URL(LATEST_API).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "Serpentina/${BuildConfig.VERSION_NAME}")
                check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                val tag = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getString("tag_name")
                val version = tag.removePrefix("v")
                p.edit { putString("latest", version); putLong("checked_at", System.currentTimeMillis()) }
                version
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Stažení otevře prohlížeč; ten APK stáhne a Android nabídne instalaci přes stávající verzi. */
    fun openDownload(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, DOWNLOAD_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, DOWNLOAD_URL, Toast.LENGTH_LONG).show()
        }
    }

    /** „0.10.0“ je novější než „0.9.2“ – porovnání po číselných částech, chybějící = 0. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
