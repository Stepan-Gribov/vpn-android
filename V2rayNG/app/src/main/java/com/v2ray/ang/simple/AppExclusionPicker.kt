package com.v2ray.ang.simple

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/** Launcher query avoids QUERY_ALL_PACKAGES; saved missing packages remain removable. */
internal object AppExclusionPicker {
    private data class App(val packageName: String, val label: String, val icon: Drawable, val uid: Int?)
    @Suppress("DEPRECATION")
    private fun installed(activity: AppCompatActivity, selected: Set<String>): List<App> {
        val pm=activity.packageManager
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found=pm.queryIntentActivities(intent,0).map { it.activityInfo.applicationInfo }.associateBy { it.packageName }.toMutableMap()
        selected.forEach { name -> runCatching { pm.getApplicationInfo(name,0) }.getOrNull()?.let { found[name]=it } }
        val apps=found.values.filter { it.packageName!=activity.packageName }.map { info ->
            App(info.packageName,pm.getApplicationLabel(info).toString(),runCatching { pm.getApplicationIcon(info) }.getOrDefault(pm.defaultActivityIcon),info.uid)
        } + selected.filter { it !in found && it!=activity.packageName }.map { App(it,it,pm.defaultActivityIcon,null) }
        val collator=Collator.getInstance()
        return apps.sortedWith { a,b ->
            val bySelection=(b.packageName in selected).compareTo(a.packageName in selected)
            if(bySelection!=0) bySelection else collator.compare(a.label,b.label)
        }
    }
    fun show(activity: AppCompatActivity, original: List<String>, accept: (List<String>)->Unit): AlertDialog {
        fun dp(n: Int)=(n*activity.resources.displayMetrics.density+0.5f).toInt()
        val selected=original.toMutableSet()
        var all=emptyList<App>()
        val root=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),0) }
        val search=EditText(activity).apply { hint="Поиск по названию или пакету";textSize=15f;isSingleLine=true }
        root.addView(search)
        val status=TextView(activity).apply { text="Загрузка приложений…";setPadding(0,dp(8),0,dp(8)) }
        root.addView(status)
        val list=RecyclerView(activity).apply { layoutManager=LinearLayoutManager(activity);itemAnimator=null }
        root.addView(list,LinearLayout.LayoutParams(-1,dp(330)))
        val adapter=object: RecyclerView.Adapter<Holder>() {
            var rows=emptyList<App>()
            override fun getItemCount()=rows.size
            override fun onCreateViewHolder(parent: ViewGroup,type: Int): Holder {
                val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(68);setPadding(0,dp(6),0,dp(6)) }
                val icon=ImageView(activity);row.addView(icon,LinearLayout.LayoutParams(dp(40),dp(40)).apply { marginEnd=dp(12) })
                val labels=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
                val title=TextView(activity).apply { textSize=16f;setTextColor(Color.rgb(20,31,56));maxLines=2;setTypeface(null,Typeface.BOLD) }
                val details=TextView(activity).apply { textSize=11f;setTextColor(Color.GRAY);maxLines=3 }
                labels.addView(title);labels.addView(details);row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
                val check=CheckBox(activity);row.addView(check)
                return Holder(row,icon,title,details,check)
            }
            override fun onBindViewHolder(holder: Holder,position: Int) {
                val app=rows[position]
                holder.icon.setImageDrawable(app.icon);holder.title.text=app.label
                val siblings=if(app.uid!=null) all.filter { it.uid==app.uid && it.packageName!=app.packageName }.map { it.label } else emptyList()
                holder.details.text=app.packageName + when {
                    app.uid==null -> "\nНедоступно или не установлено. Выбор сохранён."
                    siblings.isNotEmpty() -> "\nТакже напрямую: "+siblings.joinToString(", ")
                    else -> ""
                }
                holder.check.setOnCheckedChangeListener(null)
                holder.check.isChecked=app.packageName in selected
                holder.check.contentDescription="Без VPN: ${app.label}"
                holder.check.setOnCheckedChangeListener { _,checked ->
                    if(checked) selected+=app.packageName else selected-=app.packageName
                    status.text="Выбрано: ${selected.size}"
                }
                holder.itemView.setOnClickListener { holder.check.isChecked=!holder.check.isChecked }
            }
            fun filter(query: String) {
                rows=all.filter { it.label.contains(query,true) || it.packageName.contains(query,true) }
                notifyDataSetChanged()
            }
        }
        list.adapter=adapter
        search.addTextChangedListener { adapter.filter(it.toString().trim()) }
        val dialog=AlertDialog.Builder(activity).setTitle("Приложения без VPN").setView(root)
            .setNegativeButton("Отмена",null).setPositiveButton("Готово") { _,_ -> accept(selected.toList()) }.create()
        val job=activity.lifecycleScope.launch {
            all=withContext(Dispatchers.IO) { installed(activity,selected) }
            status.text="Выбрано: ${selected.size}"
            adapter.filter(search.text.toString().trim())
        }
        dialog.setOnDismissListener { job.cancel() }
        dialog.show()
        return dialog
    }
    private class Holder(view: View,val icon: ImageView,val title: TextView,val details: TextView,val check: CheckBox): RecyclerView.ViewHolder(view)
}
