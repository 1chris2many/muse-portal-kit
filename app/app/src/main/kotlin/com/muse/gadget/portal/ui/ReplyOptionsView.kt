package com.muse.gadget.portal.ui

import android.content.Context
import android.widget.LinearLayout
import com.muse.gadget.options.ReplyOptionsGate
import com.muse.gadget.options.ReplyOptionsPolicy

/** Offline component, not wired to a server payload. Caller must also validate every tap with
 * ReplyOptionsGate.select on the gate's executor before submitting exact text as a local input.
 * Clear the gate AND this view on background/reconnect/privacy clear. No WebView or network here.
 */
class ReplyOptionsView(
    context: Context,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : LinearLayout(context) {
    private var generation = 0L
    private var expiration: Runnable? = null

    init {
        orientation = VERTICAL
        visibility = GONE
    }

    fun render(card: ReplyOptionsGate.Card, onTap: (token: Long, index: Int, shownLabel: String) -> Unit) {
        clear()
        val labels = when (val parsed = ReplyOptionsPolicy.checkLabels(card.labels)) {
            is ReplyOptionsPolicy.Parse.Ok -> parsed.labels.toList()
            is ReplyOptionsPolicy.Parse.Rejected -> return
        }
        val remaining = card.expiresAtMs - now()
        if (remaining !in 1..ReplyOptionsPolicy.CARD_LIFETIME_MS) return
        val renderGeneration = generation
        visibility = VISIBLE
        labels.forEachIndexed { index, label ->
            val button = PortalActionButton(context, label).apply {
                // Full label stays readable, not an ellipsized action the owner cannot inspect.
                maxLines = Int.MAX_VALUE
                ellipsize = null
                setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
                setOnClickListener {
                    if (generation != renderGeneration) return@setOnClickListener
                    if (now() >= card.expiresAtMs) { clear(); return@setOnClickListener }
                    val visibleLabel = text.toString()
                    if (visibleLabel != label) { clear(); return@setOnClickListener }
                    clear() // One tap, including a retained old View reference or rapid double tap.
                    onTap(card.token, index, visibleLabel)
                }
            }
            addView(button, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = context.dp(8)
            })
        }
        expiration = Runnable { if (generation == renderGeneration) clear() }.also { postDelayed(it, remaining) }
    }

    fun clear() {
        generation++
        expiration?.let { removeCallbacks(it) }
        expiration = null
        removeAllViews()
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        clear()
        super.onDetachedFromWindow()
    }
}
