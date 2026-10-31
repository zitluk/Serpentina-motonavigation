package cz.motonavigace

import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Stejné chování na Androidu 8 až 17: okno kreslí pod systémové lišty (od Androidu 15
 * je to povinné a od cílení na API 36 nejde vypnout) a obsah se odsadí sám –
 * z boků, nad spodní lištu a nad klávesnici. Nahoře jen o tolik, kolik obsah zasahuje
 * pod stavový řádek a horní lištu; podle verze Androidu ho tam AppCompat někdy posune sám.
 */
fun AppCompatActivity.setupEdgeToEdge(content: View) {
    enableEdgeToEdge()
    val top = content.paddingTop
    val left = content.paddingLeft
    val right = content.paddingRight
    val bottom = content.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        )
        v.updatePadding(left = left + bars.left, right = right + bars.right, bottom = bottom + bars.bottom)
        v.post {
            // Spodní hrana action baru (nebo stavového řádku) v souřadnicích okna
            val barBottom = findViewById<View>(androidx.appcompat.R.id.action_bar_container)
                ?.takeIf { it.isShown }
                ?.let { IntArray(2).also(it::getLocationInWindow)[1] + it.height }
                ?: bars.top
            val contentTop = IntArray(2).also(v::getLocationInWindow)[1]
            v.updatePadding(top = top + (barBottom - contentTop).coerceAtLeast(0))
        }
        insets
    }
}
