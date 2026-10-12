package com.muse.gadget.portal.ui

import android.app.Application
import android.app.Activity
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import com.muse.gadget.options.ReplyOptionsGate
import com.muse.gadget.portal.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** View-only fixtures; never starts PortalActivity, opens hardware, or sends any user message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h800dp-land-mdpi", application = Application::class)
class ReplyOptionsViewTest {
    private var clock = 1_000L
    private fun view() = ReplyOptionsView(ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_MusePortal)) { clock }
    private fun card(token: Long = 1, labels: List<String> = listOf("Yes", "No")) =
        ReplyOptionsGate.Card(token, "r1", labels, clock + 120_000)

    @Test fun boundedOptionsHaveLargeTouchTargetsAndExactText() {
        val view = view()
        val labels = listOf("Show me the details", "A".repeat(80), "Keep this", "Continue", "Back", "Cancel")
        view.render(card(labels = labels)) { _, _, _ -> fail("Rendering must not select") }
        view.measure(View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals(6, view.childCount)
        for (i in labels.indices) {
            val button = view.getChildAt(i) as PortalActionButton
            assertEquals(labels[i], button.text.toString())
            assertTrue(button.height >= 48)
            assertTrue(button.bottom <= view.height)
            assertNull(button.ellipsize)
        }
    }

    @Test fun aRealTapEmitsExactLabelOnceAndOldViewsCannotReplay() {
        val view = view(); val events = mutableListOf<String>()
        view.render(card(labels = listOf("Show me the menu 📋"))) { token, index, label ->
            events.add("$token:$index:$label")
        }
        val oldButton = view.getChildAt(0)
        oldButton.performClick(); oldButton.performClick()
        assertEquals(listOf("1:0:Show me the menu 📋"), events)
        assertEquals(View.GONE, view.visibility)
        assertEquals(0, view.childCount)
    }

    @Test fun clearReplaceAndExpiryRevokeOldButtons() {
        val view = view(); var taps = 0
        view.render(card()) { _, _, _ -> taps++ }
        val cleared = view.getChildAt(0); view.clear(); cleared.performClick()
        view.render(card(2)) { _, _, _ -> taps++ }
        val replaced = view.getChildAt(0)
        view.render(card(3)) { _, _, _ -> taps++ }; replaced.performClick()
        val expired = view.getChildAt(0); clock += 120_000; expired.performClick()
        assertEquals(0, taps)
        assertEquals(View.GONE, view.visibility)
    }

    @Test fun changedLabelsAndInvalidCardsCannotProduceATap() {
        val view = view(); var taps = 0
        view.render(card()) { _, _, _ -> taps++ }
        val button = view.getChildAt(0) as PortalActionButton
        button.text = "Different action"; button.performClick()
        assertEquals(0, taps)
        view.render(card(labels = listOf(" padded "))) { _, _, _ -> taps++ }
        assertEquals(View.GONE, view.visibility)
        view.render(card().copy(expiresAtMs = clock)) { _, _, _ -> taps++ }
        assertEquals(0, view.childCount)
    }

    @Test fun deadlineClearsTheVisibleLabelsWithoutATap() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = view(); activity.setContentView(view)
        view.render(card()) { _, _, _ -> fail("Expiry must not select") }
        clock += 120_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120_000))
        assertEquals(View.GONE, view.visibility)
        assertEquals(0, view.childCount)
    }

    @Test fun removingTheViewInvalidatesHeldButtons() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity); activity.setContentView(parent)
        val view = view(); parent.addView(view)
        view.render(card()) { _, _, _ -> fail("Detached button must not select") }
        val held = view.getChildAt(0)
        parent.removeView(view); held.performClick()
        assertEquals(View.GONE, view.visibility)
        assertEquals(0, view.childCount)
    }
}
