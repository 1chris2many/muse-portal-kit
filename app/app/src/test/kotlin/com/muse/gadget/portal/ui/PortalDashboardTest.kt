package com.muse.gadget.portal.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.muse.gadget.portal.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Offline Views only: never starts PortalActivity, connects to Muse, or opens hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1280dp-h800dp-land-mdpi", application = Application::class)
class PortalDashboardTest {
    private fun dashboard() = PortalDashboard(
        ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_MusePortal),
    )

    private fun measure(ui: PortalDashboard, width: Int = 1280, height: Int = 740) {
        ui.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        ui.layout(0, 0, width, height)
    }

    private fun assertInside(ui: PortalDashboard, view: View, minimumHeight: Int = 1) {
        val bounds = Rect(0, 0, view.width, view.height)
        ui.offsetDescendantRectToMyCoords(view, bounds)
        assertTrue("Clipped ${view.javaClass.simpleName}: $bounds in ${ui.width}x${ui.height}",
            bounds.left >= 0 && bounds.top >= 0 && bounds.right <= ui.width && bounds.bottom <= ui.height
                && bounds.width() > 0 && bounds.height() >= minimumHeight)
    }

    @Test fun voiceControlsFitActualPortalContentArea() {
        val ui = dashboard()
        // adb reports 1280x800 panel, content [0,60][1280,800] at mdpi.
        measure(ui)
        assertFalse(ui.typingControlsVisible)
        assertFalse(ui.messageInput.isShown)
        listOf(ui.talkButton, ui.wakeButton, ui.settingsButton, ui.clearButton, ui.stopAudioButton, ui.cameraButton)
            .forEach { assertInside(ui, it, 48) }
        assertInside(ui, ui.micState)
    }

    @Test fun voiceControlsAlsoFitFullPanelAndConservativeInsets() {
        for (height in listOf(800, 728)) {
            val ui = dashboard()
            measure(ui, height = height)
            assertInside(ui, ui.talkButton, 88)
            assertInside(ui, ui.micState)
        }
    }

    @Test fun longStatusMessagesDoNotPushTalkOffScreen() {
        val ui = dashboard()
        ui.wakeButton.text = "Wake starting"
        ui.connectionDetail.text = "A connection status line that wraps. ".repeat(12)
        ui.audioState.text = "An audio capability status that wraps. ".repeat(8)
        ui.cameraState.text = "A camera status that wraps. ".repeat(8)
        measure(ui)
        assertInside(ui, ui.talkButton, 88)
        assertInside(ui, ui.wakeButton, 48)
        assertInside(ui, ui.micState)
    }

    @Test fun optionalTypingCanBeEnabledWithoutLosingTalkAtNormalHeight() {
        val ui = dashboard()
        ui.setTypingControlsVisible(true)
        measure(ui)
        assertTrue(ui.typingControlsVisible)
        assertInside(ui, ui.messageInput)
        assertInside(ui, ui.sendButton, 48)
        assertInside(ui, ui.talkButton, 88)
        assertInside(ui, ui.micState)
        ui.setTypingControlsVisible(false)
        assertFalse(ui.typingControlsVisible)
    }

    private fun descendants(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()

    @Test fun richImageClearingPreservesTextAndFullClearingRestoresEmptyState() {
        val ui = dashboard()
        ui.appendMessage("Muse", "Keep this reply")
        ui.appendPhoto("Muse", Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), "MUSE · IMAGE", 520)
        assertEquals(1, descendants(ui).filterIsInstance<ImageView>().size)
        ui.clearImages()
        assertTrue(descendants(ui).filterIsInstance<ImageView>().isEmpty())
        assertTrue(descendants(ui).filterIsInstance<TextView>().any { it.text.toString() == "Keep this reply" })
        ui.clearConversation()
        assertFalse(descendants(ui).filterIsInstance<TextView>().any { it.text.toString() == "Keep this reply" })
        assertTrue(descendants(ui).filterIsInstance<TextView>().any { it.text.toString() == "Your Portal session is ready" && it.visibility == View.VISIBLE })
    }
}
