package com.v2ray.ang.simple

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.TextView

internal object PanelButtons {
    fun style(view: TextView,danger: Boolean=false) = view.apply {
        val density=resources.displayMetrics.density
        fun dp(n: Int)=(n*density+0.5f).toInt()
        val color=if(danger) Color.rgb(177,51,64) else Color.rgb(0,104,221)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Color.GRAY,color)))
        gravity=Gravity.CENTER; textSize=14f; includeFontPadding=false
        isAllCaps=false; minimumWidth=0; minWidth=0; minimumHeight=dp(48); minHeight=dp(48)
        setPadding(dp(10),dp(6),dp(10),dp(6))
        val shape=GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius=dp(12).toFloat(); setStroke(dp(1),Color.rgb(215,225,238))
        }
        backgroundTintList=null
        background=RippleDrawable(ColorStateList.valueOf(Color.argb(24,0,104,221)),shape,null)
    }
}
