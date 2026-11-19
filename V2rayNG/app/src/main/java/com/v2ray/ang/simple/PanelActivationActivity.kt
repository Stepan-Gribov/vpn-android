package com.v2ray.ang.simple

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.app.Dialog
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PanelActivationModel: ViewModel() {
    var text=""
    var request: Deferred<Boolean>?=null
    var adding=false
    var feedback=""
    var pending: ActivationInput?=null
}
/** Subscription management and a separate, single-key addition dialog. */
class PanelActivationActivity: AppCompatActivity() {
    private lateinit var model: PanelActivationModel
    private var input: EditText?=null
    private var addDialog: Dialog?=null
    private var addProgress: TextView?=null
    private var addButton: Button?=null
    private lateinit var summary: TextView
    private lateinit var progress: TextView
    private lateinit var add: Button
    private lateinit var sync: Button
    private lateinit var clear: Button
    private var trustDialog: AlertDialog?=null
    private var observe: Job?=null
    private var activationWatch: Job?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowCompat.getInsetsController(window,window.decorView).apply { isAppearanceLightStatusBars=true; isAppearanceLightNavigationBars=true }
        model=ViewModelProvider(this)[PanelActivationModel::class.java]
        build()
        val incomingLink=intent.action==Intent.ACTION_VIEW && savedInstanceState==null
        lifecycleScope.launch {
            val empty=withContext(Dispatchers.IO) { SimpleStore(applicationContext).subscriptions.isEmpty() }
            if(incomingLink) incoming(intent)
            else {
                if(model.adding || empty) showAddSubscription()
                model.pending?.let { activateCandidate(it) }
                model.request?.let { observeRequest(it) }
            }
        }
    }
    private fun dp(value: Int)=(value*resources.displayMetrics.density+0.5f).toInt()
    private fun label(value: String,size: Float=15f)=TextView(this).apply {
        text=value; textSize=size; setTextColor(Color.rgb(20,31,56)); includeFontPadding=false; setPadding(0,dp(8),0,dp(8))
    }
    private fun build() {
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(12),dp(20),dp(24)); setBackgroundColor(Color.rgb(245,247,251)) }
        body.addView(label("Подписка",23f).apply { typeface=Typeface.DEFAULT_BOLD })
        summary=label(""); body.addView(summary)
        add=Button(this).apply { text="Добавить подписку"; setOnClickListener { showAddSubscription() }; PanelButtons.style(this) }
        body.addView(add,LinearLayout.LayoutParams(-1,dp(48)))
        sync=Button(this).apply { text="Обновить"; setOnClickListener { PanelManager.refreshManually(applicationContext) }; PanelButtons.style(this) }
        body.addView(sync,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(12) })
        progress=label("").apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }; body.addView(progress)
        clear=Button(this).apply { text="Удалить подписку"; PanelButtons.style(this,true); setOnClickListener { clearSubscription() } }
        body.addView(clear,LinearLayout.LayoutParams(-1,dp(48)))
        body.addView(Button(this).apply { text="К подключениям"; minHeight=dp(48); setOnClickListener { openMain() } })
        val scroll=ScrollView(this).apply { isFillViewport=true; addView(body) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        setContentView(scroll)
    }
    internal fun showAddSubscription() {
        if(addDialog?.isShowing==true) return
        model.adding=true
        val dialog=Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(20),dp(24),dp(20)) }
        body.addView(label("Добавить подписку",22f).apply { typeface=Typeface.DEFAULT_BOLD; ViewCompat.setAccessibilityHeading(this,true) })
        body.addView(label("Ключ подписки",14f),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        val field=EditText(this).apply {
            contentDescription="Ключ подписки"
            hint="4 английские буквы"; textSize=22f; gravity=Gravity.CENTER; setSingleLine(true)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeHintLocales=android.os.LocaleList(java.util.Locale.ENGLISH)
            imeOptions=EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minHeight=dp(56); setPadding(dp(12),dp(8),dp(12),dp(8))
            setTextColor(Color.rgb(20,31,56)); setHintTextColor(Color.rgb(112,122,140))
            background=GradientDrawable().apply { setColor(Color.rgb(245,247,251)); cornerRadius=dp(12).toFloat(); setStroke(dp(1),Color.rgb(215,225,238)) }
            if(android.os.Build.VERSION.SDK_INT>=26) importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled=false
            filters=arrayOf(InputFilter { source,start,end,dest,dstart,dend->
                val raw=source.subSequence(start,end).toString()
                val normalized=raw.filterNot { it.isWhitespace() || it=='-' }
                val result=dest.substring(0,dstart)+normalized+dest.substring(dend)
                if(result.length>4 || normalized.any { it !in 'A'..'Z' && it !in 'a'..'z' }) {
                    post { error="Введите 4 английские буквы." }
                    dest.subSequence(dstart,dend)
                } else normalized.uppercase(java.util.Locale.ROOT)
            })
            setText(model.text)
        }
        input=field; body.addView(field,LinearLayout.LayoutParams(-1,dp(56)))
        val action=Button(this).apply {
            text="Добавить"; PanelButtons.style(this); setOnClickListener { submit() }
            setTextColor(Color.WHITE)
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(35,255,255,255)),
                GradientDrawable().apply { setColor(Color.rgb(0,104,221)); cornerRadius=dp(12).toFloat() },null)
        }
        addButton=action
        body.addView(action,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(20) })
        addProgress=label("",14f).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }.also { body.addView(it) }
        field.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                model.text=s.toString(); model.feedback=""; field.error=null; renderAddState()
            }
            override fun afterTextChanged(e:android.text.Editable?){}
        })
        field.setOnEditorActionListener { _,actionID,_->
            if(actionID==EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dialog.setContentView(ScrollView(this).apply { addView(body) })
        dialog.setOnDismissListener {
            model.adding=false; addDialog=null; input=null; addButton=null; addProgress=null
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=dp(20).toFloat() })
                setLayout(minOf(resources.displayMetrics.widthPixels-dp(32),dp(360)),WindowManager.LayoutParams.WRAP_CONTENT)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    if(model.request==null) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            field.requestFocus()
            if(model.request==null) field.post { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT) }
        }
        addDialog=dialog; renderAddState(); dialog.show()
    }
    private fun renderAddState() {
        val busy=model.request!=null || PanelManager.busy || model.pending!=null
        input?.isEnabled=!busy
        addButton?.apply { isEnabled=!busy; text=if(busy) "Добавляем…" else "Добавить"; alpha=if(busy) 0.6f else 1f }
        addProgress?.apply {
            text=if(busy) PanelManager.route?.message ?: "Добавляем подписку…" else model.feedback
            visibility=if(text.isEmpty()) View.GONE else View.VISIBLE
        }
    }
    private fun clearSubscription() {
        lifecycleScope.launch {
            val store=withContext(Dispatchers.IO) { SimpleStore(applicationContext) }
            val subscriptions=store.subscriptions
            fun confirm(sub: PanelSubscription) {
                AlertDialog.Builder(this@PanelActivationActivity).setTitle("Удалить подписку?")
                    .setMessage("Подписка «${sub.clientName}» и её подключения будут удалены с этого телефона. Ручные профили останутся. Для возврата можно снова ввести код.")
                    .setNegativeButton("Отмена",null).setPositiveButton("Удалить") { _,_->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                store.removeSubscription(sub.id)
                                PanelAccess.enforce(applicationContext,store)
                            }
                            model.text=""; refresh(); progress.text="Подписка удалена"; progress.visibility=View.VISIBLE
                        }
                    }.show()
            }
            if(subscriptions.size==1) confirm(subscriptions.single())
            else if(subscriptions.isNotEmpty()) AlertDialog.Builder(this@PanelActivationActivity).setTitle("Выберите подписку")
                .setItems(subscriptions.map { it.clientName }.toTypedArray()) { _,which->confirm(subscriptions[which]) }.show()
        }
    }
    override fun onStart() {
        super.onStart()
        observe=lifecycleScope.launch { PanelManager.events.collect { refresh() } }
    }
    override fun onStop() { observe?.cancel(); observe=null; super.onStop() }
    override fun onDestroy() {
        trustDialog?.dismiss()
        addDialog?.setOnDismissListener(null); addDialog?.dismiss()
        super.onDestroy()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); incoming(intent) }
    private fun incoming(intent: Intent) {
        val raw=intent.dataString.orEmpty()
        intent.data=null; setIntent(intent)
        if(model.request!=null || model.pending!=null || PanelManager.busy) return
        try { activateCandidate(PanelContract.activation(raw,PanelManager.bootstrap(this))) }
        catch(_:Exception) { showAddSubscription(); model.feedback="Не удалось открыть подписку. Проверьте ключ."; renderAddState() }
    }
    private fun submit() {
        if(model.request!=null || model.pending!=null || PanelManager.busy) return
        val key=input?.text?.toString().orEmpty()
        if(key.length!=4) { model.feedback="Введите 4 английские буквы."; renderAddState(); input?.requestFocus(); return }
        try { activateCandidate(PanelContract.activation(key,PanelManager.bootstrap(this))) }
        catch(_:Exception) { model.feedback="Проверьте ключ подписки."; renderAddState() }
    }
    private fun activateCandidate(candidate: ActivationInput) {
        model.pending=candidate; renderAddState()
        lifecycleScope.launch {
            try {
                val known=withContext(Dispatchers.IO) { SimpleStore(applicationContext).subscriptions.map { it.trust } }
                if(candidate.trust.sameSource(PanelManager.bootstrap(this@PanelActivationActivity)) || known.any { it.sameSource(candidate.trust) }) {
                    model.pending=null; perform(candidate)
                } else confirm(candidate)
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(_:Exception) {
                model.pending=null; model.feedback="Не удалось добавить подписку. Повторите."; renderAddState()
            }
        }
    }
    private fun confirm(candidate: ActivationInput) {
        if(trustDialog?.isShowing==true) return
        trustDialog=AlertDialog.Builder(this).setTitle("Доверять этому сервису?")
            .setMessage("Источник: ${candidate.trust.api}\nКлюч: ${candidate.trust.publicKey}\nID ключа: ${candidate.trust.keyID}\n\nКод будет отправлен этому сервису. Подтвердите источник у администратора.")
            .setNegativeButton("Отмена") { _,_->model.pending=null; renderAddState() }
            .setPositiveButton("Доверять и активировать") { _,_->model.pending=null; perform(candidate) }
            .setOnCancelListener { model.pending=null; renderAddState() }.show()
    }
    private fun perform(candidate: ActivationInput) {
        if(model.request!=null) return
        val deviceName=android.os.Build.MODEL.filterNot { it.isISOControl() }.take(100).ifBlank { "Android" }
        val request=PanelManager.activate(applicationContext,candidate,deviceName)
        model.request=request; model.feedback=""; renderAddState()
        input?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        observeRequest(request)
    }
    private fun observeRequest(request: Deferred<Boolean>) {
        activationWatch?.cancel()
        activationWatch=lifecycleScope.launch {
            val success=request.await()
            if(model.request!==request) return@launch
            model.request=null
            if(success) {
                model.text=""; model.feedback="Подписка добавлена"
                Toast.makeText(this@PanelActivationActivity,"Подписка добавлена",Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK); openMain()
            } else {
                model.feedback=when {
                    PanelManager.message.startsWith("Код недействителен") -> "Ключ не найден или срок истёк."
                    PanelManager.message.startsWith("Достигнут лимит устройств") -> "Достигнут лимит устройств. Обратитесь к администратору."
                    else -> "Не удалось добавить подписку. Повторите."
                }
                if(addDialog?.isShowing!=true) showAddSubscription()
                renderAddState(); refresh()
            }
        }
    }
    private suspend fun refresh() {
        val store=withContext(Dispatchers.IO) { SimpleStore(applicationContext) }
        val now=System.currentTimeMillis()/1000
        summary.text=if(store.subscriptions.isEmpty()) "Подписка ещё не активирована" else store.subscriptions.joinToString("\n\n") { sub->
            val last=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(sub.lastSync*1000))
            val expiry=sub.expiresAt?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it*1000)) } ?: "без срока"
            val shared=store.configurations.any { it.managed?.subscriptionID==sub.id && it.managed.credentialScope=="shared" }
            "${sub.clientName}: ${sub.statusText(now)}"+
                (sub.activationCode?.takeIf { it.isNotBlank() }?.let { "\nКод подписки: $it" } ?: "")+
                "\nСрок: $expiry\nПоследняя синхронизация: $last"+
                if(shared) "\nVK TURN использует общие параметры. Индивидуальный отзыв на сервере TURN не поддерживается." else ""
        }
        add.isEnabled=!PanelManager.busy && model.request==null
        renderAddState()
        sync.isEnabled=!PanelManager.busy && store.subscriptions.isNotEmpty()
        clear.visibility=if(store.subscriptions.isEmpty()) View.GONE else View.VISIBLE
        clear.isEnabled=!PanelManager.busy
        progress.text=PanelManager.message
        progress.visibility=if(progress.text.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun openMain() {
        startActivity(Intent(this,SimpleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
