package com.muse.gadget.portal.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Visual language shared by the Portal's touch-first foreground interface. */
object PortalPalette {
    val BACKGROUND: Int = Color.rgb(10, 15, 23)
    val SURFACE: Int = Color.rgb(19, 29, 42)
    val SURFACE_ELEVATED: Int = Color.rgb(27, 40, 57)
    val SURFACE_MUTED: Int = Color.rgb(38, 54, 74)
    val BORDER: Int = Color.rgb(55, 76, 100)
    val TEXT_PRIMARY: Int = Color.rgb(242, 247, 253)
    val TEXT_SECONDARY: Int = Color.rgb(174, 191, 211)
    val ACCENT: Int = Color.rgb(125, 230, 199)
    val ACCENT_DEEP: Int = Color.rgb(31, 112, 99)
    val LILAC: Int = Color.rgb(175, 145, 255)
    val WARNING: Int = Color.rgb(255, 183, 105)
    val DANGER: Int = Color.rgb(255, 132, 132)
    val RIPPLE: Int = Color.argb(48, 255, 255, 255)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
fun Context.spPx(value: Float): Float = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_SP,
    value,
    resources.displayMetrics,
)

fun Context.portalSurface(
    color: Int,
    radiusDp: Int = 20,
    strokeColor: Int? = null,
    strokeWidthDp: Int = 1,
): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    if (strokeColor != null) setStroke(dp(strokeWidthDp), strokeColor)
}

private fun Context.portalRipple(background: GradientDrawable): RippleDrawable = RippleDrawable(
    ColorStateList.valueOf(PortalPalette.RIPPLE),
    background,
    null,
)

enum class PortalActionTone { PRIMARY, SECONDARY, QUIET, DANGER }

/** Large, high-contrast action affordance with a 48dp minimum touch target. */
class PortalActionButton(
    context: Context,
    label: String,
    private val tone: PortalActionTone = PortalActionTone.SECONDARY,
) : TextView(context) {
    init {
        text = label
        gravity = Gravity.CENTER
        isAllCaps = false
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        minHeight = context.dp(48)
        minWidth = context.dp(48)
        setPadding(context.dp(16), 0, context.dp(16), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        applyTone()
    }

    private fun applyTone() {
        val (fill, textColor, border) = when (tone) {
            PortalActionTone.PRIMARY -> Triple(PortalPalette.ACCENT_DEEP, PortalPalette.TEXT_PRIMARY, null)
            PortalActionTone.SECONDARY -> Triple(PortalPalette.SURFACE_MUTED, PortalPalette.TEXT_PRIMARY, PortalPalette.BORDER)
            PortalActionTone.QUIET -> Triple(Color.TRANSPARENT, PortalPalette.TEXT_SECONDARY, null)
            PortalActionTone.DANGER -> Triple(Color.rgb(103, 46, 57), PortalPalette.TEXT_PRIMARY, null)
        }
        setTextColor(textColor)
        background = context.portalRipple(context.portalSurface(fill, 14, border))
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.42f
    }
}

/** Compact connection label that makes the device state readable at a glance. */
class PortalStatusChip(context: Context) : TextView(context) {
    init {
        gravity = Gravity.CENTER
        minHeight = context.dp(32)
        setPadding(context.dp(12), 0, context.dp(12), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
        setStatus("CONNECTING", PortalPalette.LILAC)
    }

    fun setStatus(label: String, color: Int) {
        text = label
        setTextColor(color)
        background = context.portalSurface(Color.argb(34, Color.red(color), Color.green(color), Color.blue(color)), 16,
            Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)))
    }
}

/** A custom draw target avoids the small, default Android button on a large Portal screen. */
class PortalTalkButton(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var listening = false
    private var touchPressed = false

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Hold to talk"
        minimumHeight = context.dp(84)
    }

    fun setListening(active: Boolean) {
        listening = active
        contentDescription = when {
            !isEnabled -> "Voice input unavailable while connecting"
            active -> "Listening. Release to send voice note"
            else -> "Hold to talk"
        }
        invalidate()
    }

    fun setTouchPressed(active: Boolean) {
        touchPressed = active
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        contentDescription = when {
            !enabled -> "Voice input unavailable while connecting"
            listening -> "Listening. Release to send voice note"
            else -> "Hold to talk"
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = context.dp(88)
        val width = resolveSize(context.dp(280), widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val inset = context.dp(if (touchPressed) 3 else 0).toFloat()
        val bounds = RectF(inset, inset, width - inset, height - inset)
        val enabled = isEnabled
        val fillColor = when {
            !enabled -> PortalPalette.SURFACE_MUTED
            listening -> PortalPalette.ACCENT
            else -> PortalPalette.ACCENT_DEEP
        }
        val primaryText = if (listening) PortalPalette.BACKGROUND else PortalPalette.TEXT_PRIMARY
        val secondaryText = if (listening) Color.rgb(20, 66, 59) else PortalPalette.TEXT_SECONDARY
        fill.color = fillColor
        canvas.drawRoundRect(bounds, height / 2f, height / 2f, fill)
        if (enabled && !listening) {
            stroke.color = Color.argb(150, Color.red(PortalPalette.ACCENT), Color.green(PortalPalette.ACCENT), Color.blue(PortalPalette.ACCENT))
            stroke.strokeWidth = context.dp(1).toFloat()
            canvas.drawRoundRect(bounds, height / 2f, height / 2f, stroke)
        }

        val iconX = min(width * 0.2f, context.dp(150).toFloat())
        val iconY = height / 2f
        if (listening) {
            fill.color = Color.argb(45, 12, 38, 35)
            canvas.drawCircle(iconX, iconY, context.dp(27).toFloat(), fill)
        }
        drawMic(canvas, iconX, iconY, primaryText)

        val labelStart = iconX + context.dp(48)
        textPaint.color = primaryText
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = context.spPx(20f)
        val headline = when {
            !enabled -> "Voice console is connecting"
            listening -> "Listening… release to send"
            else -> "Hold to talk"
        }
        canvas.drawText(headline, labelStart, iconY - context.dp(3), textPaint)
        textPaint.color = secondaryText
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.textSize = context.spPx(13f)
        val detail = when {
            !enabled -> "Secure connection required"
            listening -> "Your note is sent when you release"
            else -> "Press and hold for a private voice note"
        }
        canvas.drawText(detail, labelStart, iconY + context.dp(20), textPaint)

        if (listening) {
            val end = width - context.dp(27)
            stroke.color = Color.argb(130, 12, 65, 56)
            stroke.strokeWidth = context.dp(3).toFloat()
            for (index in 0 until 4) {
                val x = end - index * context.dp(8)
                val bar = context.dp(9 + index * 4).toFloat()
                canvas.drawLine(x, iconY - bar / 2f, x, iconY + bar / 2f, stroke)
            }
        }
    }

    private fun drawMic(canvas: Canvas, x: Float, y: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = context.dp(3).toFloat()
        val head = RectF(x - context.dp(7), y - context.dp(17), x + context.dp(7), y + context.dp(2))
        canvas.drawRoundRect(head, context.dp(7).toFloat(), context.dp(7).toFloat(), stroke)
        val arc = RectF(x - context.dp(14), y - context.dp(7), x + context.dp(14), y + context.dp(15))
        canvas.drawArc(arc, 0f, 180f, false, stroke)
        canvas.drawLine(x, y + context.dp(15), x, y + context.dp(22), stroke)
        canvas.drawLine(x - context.dp(7), y + context.dp(22), x + context.dp(7), y + context.dp(22), stroke)
    }
}

/**
 * The primary Portal screen. It intentionally uses platform Views rather than a newer UI toolkit so it
 * remains compatible with Android 9 Portal firmware, while using density-aware measurements throughout.
 */
class PortalDashboard(context: Context) : FrameLayout(context) {
    val connectionDetail: TextView
    val deviceStatus: DeviceStatusView
    val audioState: TextView
    val cameraState: TextView
    val messageInput: EditText
    val sendButton: PortalActionButton
    val talkButton: PortalTalkButton
    val settingsButton: PortalActionButton
    val clearButton: PortalActionButton
    val stopAudioButton: PortalActionButton
    val cameraButton: PortalActionButton
    val micState: TextView

    private val statusChip: PortalStatusChip
    private val audioTitle: TextView
    private val cameraTitle: TextView
    private val transcript: LinearLayout
    private val transcriptScroll: ScrollView
    private val emptyState: LinearLayout
    private val composer: LinearLayout
    val typingControlsVisible: Boolean get() = composer.visibility == View.VISIBLE

    init {
        setBackgroundColor(PortalPalette.BACKGROUND)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(18))
        }
        addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("Muse", 29f, PortalPalette.TEXT_PRIMARY, medium = true))
        brand.addView(text("Muse on Portal", 13f, PortalPalette.TEXT_SECONDARY).apply {
            setPadding(0, context.dp(2), 0, 0)
        })
        header.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusChip = PortalStatusChip(context)
        header.addView(statusChip)
        settingsButton = PortalActionButton(context, "Settings", PortalActionTone.QUIET)
        header.addView(settingsButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(48)).apply {
            leftMargin = context.dp(6)
        })
        content.addView(header)

        connectionDetail = text("Opening the secure connection…", 13f, PortalPalette.TEXT_SECONDARY).apply {
            setPadding(0, context.dp(6), 0, context.dp(12))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(connectionDetail)

        val privacyCard = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(12), context.dp(10), context.dp(12))
            background = context.portalSurface(PortalPalette.SURFACE, 18, PortalPalette.BORDER)
        }
        val privacyCopy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        privacyCopy.addView(text("PRIVATE PORTAL SESSION", 11f, PortalPalette.ACCENT, medium = true).apply { letterSpacing = 0.08f })
        privacyCopy.addView(text("Only replies linked to this Portal are shown. Visible content clears automatically.", 13f, PortalPalette.TEXT_SECONDARY).apply {
            setPadding(0, context.dp(3), 0, 0)
            maxLines = 2
        })
        privacyCard.addView(privacyCopy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        clearButton = PortalActionButton(context, "Clear", PortalActionTone.QUIET)
        privacyCard.addView(clearButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(48)).apply {
            leftMargin = context.dp(8)
        })
        content.addView(privacyCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(12)
        })

        deviceStatus = DeviceStatusView(context)
        content.addView(deviceStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(96)).apply {
            bottomMargin = context.dp(12)
        })

        val quickActions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        audioTitle = text("SPEAKER", 11f, PortalPalette.LILAC, medium = true).apply { letterSpacing = 0.08f }
        audioState = text("Checking on-device voice…", 13f, PortalPalette.TEXT_SECONDARY).apply { maxLines = 2 }
        stopAudioButton = PortalActionButton(context, "Stop", PortalActionTone.QUIET)
        quickActions.addView(quickCard(audioTitle, audioState, stopAudioButton), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        cameraTitle = text("CAMERA", 11f, PortalPalette.WARNING, medium = true).apply { letterSpacing = 0.08f }
        cameraState = text("Ready when the app is open", 13f, PortalPalette.TEXT_SECONDARY).apply { maxLines = 2 }
        cameraButton = PortalActionButton(context, "Share photo", PortalActionTone.SECONDARY)
        quickActions.addView(quickCard(cameraTitle, cameraState, cameraButton), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = context.dp(10)
        })
        content.addView(quickActions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(16)
        })

        val conversationHeader = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        conversationHeader.addView(text("Conversation", 16f, PortalPalette.TEXT_PRIMARY, medium = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        conversationHeader.addView(text("AUTO-CLEARS IN 2 MIN", 10f, PortalPalette.TEXT_SECONDARY, medium = true).apply { letterSpacing = 0.06f })
        content.addView(conversationHeader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(8)
        })

        val conversationSurface = FrameLayout(context).apply {
            minimumHeight = context.dp(144)
            background = context.portalSurface(PortalPalette.SURFACE, 22, PortalPalette.BORDER)
        }
        transcript = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), context.dp(14), context.dp(12), context.dp(14))
        }
        transcriptScroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(transcript, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        conversationSurface.addView(transcriptScroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        emptyState = buildEmptyState()
        conversationSurface.addView(emptyState, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        content.addView(conversationSurface, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            bottomMargin = context.dp(12)
        })

        composer = LinearLayout(context).apply {
            // Portal is voice/touch first. Keep optional typing in Settings, off the home screen.
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(8), context.dp(6), context.dp(6), context.dp(6))
            background = context.portalSurface(PortalPalette.SURFACE_ELEVATED, 18, PortalPalette.BORDER)
        }
        messageInput = EditText(context).apply {
            hint = "Type a message to Muse"
            setHintTextColor(PortalPalette.TEXT_SECONDARY)
            setTextColor(PortalPalette.TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
            minLines = 1
            gravity = Gravity.CENTER_VERTICAL
            background = null
            setPadding(context.dp(10), context.dp(6), context.dp(6), context.dp(6))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        composer.addView(messageInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sendButton = PortalActionButton(context, "Send", PortalActionTone.PRIMARY).apply { isEnabled = false }
        composer.addView(sendButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(48)).apply {
            leftMargin = context.dp(6)
        })
        content.addView(composer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(12)
        })

        talkButton = PortalTalkButton(context).apply { isEnabled = false }
        content.addView(talkButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(88)))
        micState = text("Microphone off · Hold to speak, then release to send", 13f, PortalPalette.TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(9), 0, 0)
        }
        content.addView(micState)
    }

    fun setConnectionState(connected: Boolean) {
        statusChip.setStatus(if (connected) "CONNECTED" else "CONNECTING", if (connected) PortalPalette.ACCENT else PortalPalette.LILAC)
        talkButton.isEnabled = connected
        sendButton.isEnabled = connected
        if (!connected) talkButton.setListening(false)
    }

    fun setTypingControlsVisible(visible: Boolean) {
        composer.visibility = if (visible) View.VISIBLE else View.GONE
        // Optional typing uses the status illustration's space, not the Talk control's.
        deviceStatus.visibility = if (visible) View.GONE else View.VISIBLE
        if (!visible) {
            messageInput.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(messageInput.windowToken, 0)
        }
    }

    fun setAudioState(label: String, detail: String, color: Int = PortalPalette.LILAC) {
        audioTitle.text = label
        audioTitle.setTextColor(color)
        audioState.text = detail
    }

    fun setCameraState(label: String, detail: String, color: Int = PortalPalette.WARNING) {
        cameraTitle.text = label
        cameraTitle.setTextColor(color)
        cameraState.text = detail
    }

    fun appendMessage(who: String, body: String): TextView {
        val mine = who == "You"
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(13), context.dp(16), context.dp(15))
            background = context.portalSurface(if (mine) Color.rgb(25, 69, 76) else PortalPalette.SURFACE_ELEVATED, 18,
                if (mine) Color.rgb(49, 113, 113) else null)
        }
        val speaker = text(if (mine) "YOU" else "MUSE", 11f, if (mine) PortalPalette.ACCENT else PortalPalette.LILAC, medium = true).apply {
            letterSpacing = 0.1f
        }
        val message = text(body, 18f, PortalPalette.TEXT_PRIMARY).apply {
            setPadding(0, context.dp(6), 0, 0)
            setTextIsSelectable(true)
            setLineSpacing(context.dp(2).toFloat(), 1.08f)
        }
        card.addView(speaker)
        card.addView(message)
        addConversationCard(card, mine)
        return message
    }

    fun appendPhoto(who: String, bitmap: android.graphics.Bitmap?, caption: String? = null, maxHeight: Int = 220) {
        val mine = who == "You"
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            background = context.portalSurface(if (mine) Color.rgb(25, 69, 76) else PortalPalette.SURFACE_ELEVATED, 18,
                if (mine) Color.rgb(49, 113, 113) else null)
        }
        card.addView(text(caption ?: if (mine) "YOU · PORTAL PHOTO" else "MUSE · PORTAL PHOTO", 11f,
            if (mine) PortalPalette.ACCENT else PortalPalette.LILAC, medium = true).apply { letterSpacing = 0.08f })
        if (bitmap != null) {
            card.addView(ImageView(context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                this.maxHeight = context.dp(maxHeight)
                tag = "portal-image"
                contentDescription = caption ?: "Portal photo"
                background = context.portalSurface(PortalPalette.BACKGROUND, 12)
                clipToOutline = true
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(10)
            })
        } else {
            card.addView(text("Photo captured", 16f, PortalPalette.TEXT_PRIMARY).apply { setPadding(0, context.dp(6), 0, 0) })
        }
        addConversationCard(card, mine)
    }

    fun clearConversation() {
        transcript.removeAllViews()
        emptyState.visibility = View.VISIBLE
    }

    /** display.show_animation clears images but preserves message text and captions. */
    fun clearImages() {
        for (i in 0 until transcript.childCount) {
            val card = transcript.getChildAt(i) as? LinearLayout ?: continue
            for (j in card.childCount - 1 downTo 0) {
                if (card.getChildAt(j).tag == "portal-image") card.removeViewAt(j)
            }
        }
    }

    private fun addConversationCard(card: View, mine: Boolean) {
        emptyState.visibility = View.GONE
        transcript.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = context.dp(10)
            if (mine) leftMargin = context.dp(54) else rightMargin = context.dp(54)
        })
        while (transcript.childCount > 60) transcript.removeViewAt(0)
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun quickCard(title: TextView, detail: TextView, action: PortalActionButton): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(14), context.dp(12), context.dp(10), context.dp(10))
        background = context.portalSurface(PortalPalette.SURFACE, 18, PortalPalette.BORDER)
        addView(title)
        val lower = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        lower.addView(detail, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = context.dp(4)
        })
        lower.addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, context.dp(48)).apply {
            leftMargin = context.dp(4)
            topMargin = context.dp(4)
        })
        addView(lower)
    }

    private fun buildEmptyState(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(28), context.dp(24), context.dp(28), context.dp(24))
        addView(text("Your Portal session is ready", 18f, PortalPalette.TEXT_PRIMARY, medium = true).apply { gravity = Gravity.CENTER })
        addView(text("Hold to talk to Muse. Replies linked to this Portal appear here.", 14f, PortalPalette.TEXT_SECONDARY).apply {
            gravity = Gravity.CENTER
            setPadding(0, context.dp(8), 0, 0)
            maxLines = 3
        })
    }

    private fun text(value: String, sizeSp: Float, color: Int, medium: Boolean = false): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
}
