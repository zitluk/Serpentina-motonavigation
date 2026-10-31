package cz.motonavigace

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zpětná vazba pro vývojáře přes EmailJS. Aplikace sama e-mail neposílá (heslo by šlo z APK
 * vytáhnout); zprávu podle šablony pošle EmailJS ze schránky připojené v jeho účtu.
 * V účtu EmailJS musí být povolené API pro aplikace mimo prohlížeč (Account → Security).
 */
object Feedback {
    private const val ENDPOINT = "https://api.emailjs.com/api/v1.0/email/send"
    // Údaje z účtu EmailJS; veřejný klíč je určený do klientských aplikací, privátní sem nepatří
    private const val SERVICE_ID = "service_i338mhi"
    private const val TEMPLATE_ID = "template_t86ed0d"
    private const val PUBLIC_KEY = "owuXHq4mDTWHZPzmP"
    private const val TIMEOUT_MS = 20_000

    /** Odešle zprávu; [contact] je nepovinný e-mail uživatele pro odpověď. Vrací chybu, když se nepovede. */
    suspend fun send(message: String, contact: String?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(SERVICE_ID.isNotEmpty() && TEMPLATE_ID.isNotEmpty() && PUBLIC_KEY.isNotEmpty()) { "odesílání není nastavené" }
            // Názvy parametrů odpovídají proměnným v šabloně EmailJS ({{message}}, {{reply_to}}…)
            val params = JSONObject()
                .put("message", message)
                .put("reply_to", contact?.trim().orEmpty())
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("language", if (Settings.isEnglish()) "en" else "cs")
            val body = JSONObject()
                .put("service_id", SERVICE_ID)
                .put("template_id", TEMPLATE_ID)
                .put("user_id", PUBLIC_KEY)
                .put("template_params", params)

            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                // Úspěch je 200 s textem „OK“, chyba vrací důvod jako prostý text
                if (conn.responseCode !in 200..299) {
                    val reason = conn.errorStream?.bufferedReader()?.use { it.readText() }?.trim()
                    error(reason?.takeIf { it.isNotEmpty() } ?: "HTTP ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
