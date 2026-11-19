package com.v2ray.ang.simple

import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import androidx.appcompat.widget.AppCompatTextView

/** Reserve text-dependent height at the current width/font, without clipping or fixed dp. */
internal class StableStatusText(context: Context,private val variants: List<String>):AppCompatTextView(context) {
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        val width=(measuredWidth-compoundPaddingLeft-compoundPaddingRight).coerceAtLeast(1)
        val textHeight=(variants+text.toString()).maxOf { value ->
            StaticLayout.Builder.obtain(value,0,value.length,paint,width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(includeFontPadding)
                .setLineSpacing(lineSpacingExtra,lineSpacingMultiplier).build().height
        }
        setMeasuredDimension(measuredWidth,resolveSize(maxOf(measuredHeight,textHeight+compoundPaddingTop+compoundPaddingBottom),heightMeasureSpec))
    }
}
