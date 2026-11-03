package cz.motonavigace

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors

/**
 * Nabídka zespodu obrazovky s velkými položkami přes celou šířku – trefí se do nich
 * i prst v motorkářské rukavici (běžné menu má položky 48 dp a úzké).
 */
object GloveMenu {

    class Item(val title: String, val checked: Boolean = false, val icon: Drawable? = null, val action: () -> Unit) {
        /** Zaškrtávací volba: ťuknutí ji přepne a nabídka zůstane otevřená. */
        var onToggle: ((Boolean) -> Unit)? = null
            private set

        companion object {
            fun checkbox(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) =
                Item(title, checked) {}.also { it.onToggle = onToggle }
        }
    }

    fun show(context: Context, title: String?, items: List<Item>) {
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
        title?.let {
            list.addView(TextView(context).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(dp(24), dp(8), dp(24), dp(12))
            })
        }
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
                    setOnCheckedChangeListener { _, on -> toggle(on) }
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
                setBackgroundResource(ripple)
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
    }
}
