package cz.motonavigace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors

/**
 * Nabídka zespodu obrazovky s velkými položkami přes celou šířku – trefí se do nich
 * i prst v motorkářské rukavici (běžné menu má položky 48 dp a úzké).
 */
object GloveMenu {
    /** Nabídka aspoň takhle vysoká (podíl výšky okna) se bere jako celoobrazovková. */
    private const val FULL_SCREEN_SHARE = 0.9
    /** Krytí podbarvení zvýrazněné položky – lehký nádech, čitelný ve světlém i tmavém motivu. */
    private const val HIGHLIGHT_ALPHA = 0x2E

    class Item(
        val title: String,
        val checked: Boolean = false,
        val icon: Drawable? = null,
        /** Hlavní akce nabídky – položka je lehce podbarvená barvou aplikace. */
        val highlighted: Boolean = false,
        val action: () -> Unit,
    ) {
        /** Zaškrtávací volba: ťuknutí ji přepne a nabídka zůstane otevřená. */
        var onToggle: ((Boolean) -> Unit)? = null
            private set
        /** Zaškrtávací volby se stejnou skupinou se vylučují – zaškrtnutí jedné odškrtne ostatní. */
        var group: String? = null
            private set

        companion object {
            fun checkbox(title: String, checked: Boolean, group: String? = null, onToggle: (Boolean) -> Unit) =
                Item(title, checked) {}.also { it.onToggle = onToggle; it.group = group }
        }
    }

    /** @return otevřená nabídka – volající ji může zavřít sám nebo sledovat její zavření */
    fun show(context: Context, title: String?, items: List<Item>): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
        val divider = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0)
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        // Šipka zpět vedle názvu – jen když nabídka vyplní celou obrazovku a není vidět, že jde zavřít.
        // Řádek se šipkou má i nabídka bez názvu (hlavní menu bývá nejdelší); bez šipky je pak prázdný.
        var titleView: TextView? = null
        val back = ImageButton(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0))
            contentDescription = context.getString(R.string.back)
            setBackgroundResource(ripple)
            visibility = View.GONE
            // Jako stažení dolů – volající, které zavření bere jako „Ne“, se to dozví přes OnCancelListener
            setOnClickListener { dialog.cancel() }
        }
        list.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(8) })
            title?.let {
                addView(TextView(context).apply {
                    text = it
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setPadding(dp(24), dp(8), dp(24), dp(12))
                    titleView = this
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        val boxes = mutableListOf<Pair<Item, MaterialCheckBox>>()
        items.forEachIndexed { i, item ->
            val toggle = item.onToggle
            if (toggle != null) {
                // Velké zaškrtávací políčko odsazené pod položkou, ke které patří
                list.addView(MaterialCheckBox(context).apply {
                    text = item.title
                    isChecked = item.checked
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    minHeight = dp(64)
                    setPadding(dp(12), 0, dp(24), 0)
                    setOnCheckedChangeListener { _, on ->
                        toggle(on)
                        if (on && item.group != null) boxes.filter { (other, box) -> other !== item && other.group == item.group && box.isChecked }
                            .forEach { (_, box) -> box.isChecked = false }
                    }
                    boxes += item to this
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(40) })
                return@forEachIndexed
            }
            if (i > 0 || title != null) list.addView(View(context).apply { setBackgroundColor(divider) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            list.addView(TextView(context).apply {
                text = if (item.checked) "✓  ${item.title}" else item.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                if (item.checked) { setTextColor(primary); setTypeface(typeface, Typeface.BOLD) }
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(72)
                setPadding(dp(24), 0, dp(24), 0)
                if (item.highlighted) {
                    setBackgroundColor(ColorUtils.setAlphaComponent(primary, HIGHLIGHT_ALPHA))
                    foreground = ContextCompat.getDrawable(context, ripple)
                } else setBackgroundResource(ripple)
                item.icon?.let {
                    it.setBounds(0, 0, dp(48), dp(48))
                    setCompoundDrawablesRelative(it, null, null, null)
                    compoundDrawablePadding = dp(20)
                }
                setOnClickListener { dialog.dismiss(); item.action() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(ScrollView(context).apply { addView(list) })
        // Rovnou celá nabídka, bez polovičního stavu, který by se musel vytahovat
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            // Šipka se nastaví až po rozvržení; víc změn rozvržení před tím sloučí jedno čekající post()
            var pending = false
            val update = Runnable {
                pending = false
                // Porovnává se s prostorem, který má nabídka k dispozici (bez systémových lišt), ne s celým oknem
                val available = (sheet.parent as? View)?.height ?: sheet.rootView.height
                val full = available > 0 && sheet.height >= available * FULL_SCREEN_SHARE
                val wanted = if (full) View.VISIBLE else View.GONE
                if (back.visibility != wanted) {
                    back.visibility = wanted
                    // Název hned za šipkou, bez šipky odsazený jako položky pod ním
                    titleView?.let { t -> t.setPaddingRelative(dp(if (full) 8 else 24), t.paddingTop, t.paddingEnd, t.paddingBottom) }
                }
            }
            sheet.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (!pending && bottom - top != oldBottom - oldTop) { pending = true; sheet.post(update) }
            }
        }
        return dialog
    }
}
