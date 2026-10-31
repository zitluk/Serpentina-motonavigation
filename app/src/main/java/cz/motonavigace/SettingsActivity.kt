package cz.motonavigace

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cz.motonavigace.databinding.ActivitySettingsBinding

/** Nastavení: vzhled, typ motocyklu, jazyk a informace o aplikaci. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.theme.title.setText(R.string.settings_theme)
        b.bike.title.setText(R.string.settings_bike_type)
        b.language.title.setText(R.string.settings_language_app)
        b.about.title.text = getString(R.string.app_full_name)
        b.about.summary.text = getString(R.string.about_version, versionName())

        b.theme.root.setOnClickListener {
            choose(R.string.settings_theme, AppTheme.entries.map { getString(it.label) }, Settings.theme.ordinal) {
                // Změna tmavého režimu obrazovky znovu vytvoří; když se vzhled nezmění, obnovíme text sami
                Settings.theme = AppTheme.entries[it]
                refresh()
            }
        }
        b.bike.root.setOnClickListener {
            choose(R.string.settings_bike_type, BikeType.entries.map { getString(it.label) }, Settings.bike.ordinal) {
                Settings.bike = BikeType.entries[it]
                refresh()
            }
        }
        b.language.root.setOnClickListener {
            choose(R.string.settings_language_app, AppLanguage.entries.map { getString(it.label) }, Settings.language.ordinal) {
                Settings.language = AppLanguage.entries[it]
            }
        }
        b.about.root.setOnClickListener { showAbout() }
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        b.theme.summary.setText(Settings.theme.label)
        val bike = Settings.bike
        b.bike.summary.text = getString(bike.label) + "\n" + getString(bike.description)
        b.language.summary.setText(Settings.language.label)
    }

    private fun choose(title: Int, items: List<String>, checked: Int, onChosen: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which != checked) onChosen(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_full_name) + " " + versionName())
            .setMessage(HtmlCompat.fromHtml(getString(R.string.about_text), HtmlCompat.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun versionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
}
