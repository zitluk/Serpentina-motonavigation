package cz.motonavigace

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cz.motonavigace.databinding.ActivitySettingsBinding

/** Nastavení: vzhled, typ motocyklu, jazyk, informace o aplikaci a příspěvek na vývoj. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupEdgeToEdge(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        b.theme.title.setText(R.string.settings_theme)
        b.manufacturer.title.setText(R.string.settings_manufacturer)
        b.bike.title.setText(R.string.settings_bike_type)
        b.language.title.setText(R.string.settings_language_app)
        b.markerShape.title.setText(R.string.marker_shape)
        b.markerColor.title.setText(R.string.marker_color)
        b.markerShape.root.setOnClickListener {
            GloveMenu.show(this, getString(R.string.marker_shape), MarkerShape.entries.map { shape ->
                GloveMenu.Item(getString(shape.label), shape == Settings.markerShape, markerIcon(shape, Settings.markerColor)) {
                    Settings.markerShape = shape; refresh()
                }
            })
        }
        b.markerColor.root.setOnClickListener {
            GloveMenu.show(this, getString(R.string.marker_color), MARKER_COLORS.mapIndexed { i, color ->
                GloveMenu.Item(colorNames[i], color == Settings.markerColor, markerIcon(Settings.markerShape, color)) {
                    Settings.markerColor = color; refresh()
                }
            })
        }
        b.about.title.text = getString(R.string.app_full_name)
        b.about.summary.text = getString(R.string.about_version, versionName())
        b.donate.title.setText(R.string.settings_donate)
        b.donate.summary.text = getString(R.string.donate_summary, getString(R.string.donate_account))
        // QR kód na vlastním displeji telefon nenaskenuje, proto jde účet i zkopírovat
        b.donate.root.setOnClickListener { copyDonateAccount() }
        b.donateQr.setOnClickListener { copyDonateAccount() }

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
        b.manufacturer.root.setOnClickListener {
            // První položka vrací výchozí ikonu Serpentiny
            val names = listOf("") + Settings.MANUFACTURERS.map { it.name }
            val labels = listOf(getString(R.string.manufacturer_none)) + names.drop(1)
            choose(R.string.settings_manufacturer, labels, names.indexOf(Settings.manufacturer)) {
                Settings.manufacturer = names[it]
                changeIcon()
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

    private fun changeIcon() {
        Settings.applyLauncherIcon(this)
        Toast.makeText(this, getString(R.string.icon_changed, Settings.manufacturer.ifEmpty { getString(R.string.app_name) }), Toast.LENGTH_LONG).show()
    }

    private val colorNames by lazy { resources.getStringArray(R.array.marker_color_names).toList() }

    private fun markerIcon(shape: MarkerShape, color: Int) =
        BitmapDrawable(resources, MarkerIcons.bitmap(this, shape, color))

    private fun refresh() {
        b.markerShape.summary.setText(Settings.markerShape.label)
        b.markerColor.summary.text = colorNames.getOrElse(MARKER_COLORS.indexOf(Settings.markerColor)) { "" }
        for (row in listOf(b.markerShape, b.markerColor)) {
            row.preview.setImageDrawable(markerIcon(Settings.markerShape, Settings.markerColor))
            row.preview.visibility = android.view.View.VISIBLE
        }
        b.manufacturer.summary.text = Settings.manufacturer.ifEmpty { getString(R.string.manufacturer_none) }
        b.theme.summary.setText(Settings.theme.label)
        val bike = Settings.bike
        b.bike.summary.text = getString(bike.label) + "\n" + getString(bike.description)
        b.language.summary.setText(Settings.language.label)
    }

    private fun choose(title: Int, items: List<String>, checked: Int, onChosen: (Int) -> Unit) {
        GloveMenu.show(this, getString(title), items.mapIndexed { i, text ->
            GloveMenu.Item(text, checked = i == checked) { if (i != checked) onChosen(i) }
        })
    }

    private fun showAbout() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_full_name) + " " + versionName())
            .setMessage(HtmlCompat.fromHtml(getString(R.string.about_text), HtmlCompat.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun copyDonateAccount() {
        val account = getString(R.string.donate_account).replace(" ", "")
        getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText(getString(R.string.settings_donate), account))
        // Android 13+ zobrazuje vlastní potvrzení o zkopírování
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.donate_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionName(): String = BuildConfig.VERSION_NAME
}
