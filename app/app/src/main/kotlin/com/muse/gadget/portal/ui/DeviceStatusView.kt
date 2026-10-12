package com.muse.gadget.portal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Original Android rendering of the SDK's status-ring / level-meter pattern.
 * No avatar assets or LVGL implementation are copied. All state is observable
 * local device activity; shared agent busy events never imply our turn is active.
 */
class DeviceStatusView(context:Context):View(context) {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase="CONNECTING"
    private var caption="Opening the secure connection"
    private var level=0f
    private var progress=0f
    private val accent=Color.rgb(167,125,255)
    fun show(phase:String,caption:String,level:Float=0f,progress:Float=0f) {
        this.phase=phase;this.caption=caption
        this.level=level.coerceIn(0f,1f);this.progress=progress.coerceIn(0f,1f)
        contentDescription="$phase. $caption"
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val h=height.toFloat();val radius=h*.32f;val cx=h*.52f;val cy=h*.5f
        ink.style=Paint.Style.FILL;ink.color=Color.rgb(20,15,34)
        canvas.drawRoundRect(0f,0f,width.toFloat(),h,24f,24f,ink)
        ink.style=Paint.Style.STROKE;ink.strokeWidth=5f;ink.color=Color.rgb(58,51,88)
        canvas.drawCircle(cx,cy,radius,ink)
        ink.color=accent
        canvas.drawArc(RectF(cx-radius,cy-radius,cx+radius,cy+radius),-90f,
            if(phase=="LISTENING")360f*progress else 270f,false,ink)
        ink.style=Paint.Style.FILL
        for(i in 0..6) {
            val bar=if(phase=="LISTENING")8f+level*26f*(1f-kotlin.math.abs(i-3)/5f) else 8f
            val x=cx+(i-3)*9f
            canvas.drawRoundRect(x-2.5f,cy-bar/2,x+2.5f,cy+bar/2,2f,2f,ink)
        }
        val left=h+12f
        ink.color=Color.rgb(242,239,255);ink.typeface=android.graphics.Typeface.DEFAULT_BOLD
        ink.textSize=24f*resources.displayMetrics.scaledDensity
        canvas.drawText(phase,left,h*.45f,ink)
        ink.typeface=android.graphics.Typeface.DEFAULT
        ink.textSize=14f*resources.displayMetrics.scaledDensity;ink.color=Color.rgb(216,210,255)
        val count=ink.breakText(caption,true,(width-left-20f).coerceAtLeast(0f),null)
        canvas.drawText(caption.take(count),left,h*.72f,ink)
    }
}
