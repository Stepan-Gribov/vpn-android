package com.v2ray.ang.simple

import android.Manifest
import android.app.Dialog
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.v2ray.ang.R
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.ManagedDnsTunnelResolverDiscovery
import com.v2ray.ang.core.VkTurnManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.VkCaptchaActivity
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TnDtun's independent native Android screen. */
class SimpleActivity : AppCompatActivity() {
    private val ink = Color.rgb(20, 31, 56)
    private val muted = Color.rgb(112, 122, 140)
    private val blue = Color.rgb(0, 122, 255)
    private val panel = Color.rgb(245, 247, 251)
    private val green = Color.rgb(52, 199, 89)
    private lateinit var store: SimpleStore
    private lateinit var root: LinearLayout
    private lateinit var statusPanel: LinearLayout
    private var status = "Disconnected"
    private var connected = false
    private var active = false
    private var preparing = false
    private var captcha = false
    private var recoveryFailed = false
    private var recoveryAttempt = ""
    private var ping: Long? = null
    private var pingMeasuredAt = 0L
    private var pingChecking = false
    private var pingRequest: String? = null
    private var upload = 0.0
    private var download = 0.0
    private var total = 0.0
    private var registered = false
    private var stateSyncJob: Job? = null
    private var panelEventsJob: Job? = null
    private var profileScroll: ScrollView? = null
    private var screenScroll: ScrollView? = null
    private var pending: SimpleConfiguration? = null
    private var connectionJob: Job? = null
    private var draft: SimpleConfiguration? = null
    private var sheet: Dialog? = null
    private var sheetKind: String? = null
    private var fullTunnelDialog: AlertDialog? = null
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) pending?.let(::startConnection)
        else { pending = null; preparing = false; render(); showError("VPN permission was not granted.") }
    }
    private var notificationPermissionPending = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationPermissionPending = false
        maybeExplainBatteryOptimization()
    }
    private val settingsResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { store.reload() }
                SimpleRuntime.applyAutomation(store)
                syncConnectionState()
                render()
                if (result.data?.getBooleanExtra("routingChanged", false) == true &&
                    result.data?.getBooleanExtra("restored", false) != true && active) {
                    val id = MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID)
                    store.configurations.firstOrNull { it.id == id }?.let { value ->
                        preparing = true
                        startConnection(value)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { showError(e.message.orEmpty()) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val content = intent.getStringExtra("content").orEmpty()
            when (intent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_START_FAILURE, AppConfig.MSG_VK_TURN_FAILURE -> {
                    preparing = false
                    showError(content.ifBlank { "Could not connect." })
                }
                SimpleRuntime.MSG_REAL_DELAY -> {
                    val reply = content.split('|', limit = 2)
                    if (connected && reply.size == 2 && reply[0] == pingRequest) {
                        ping = reply[1].toLongOrNull()?.takeIf { it >= 0 }
                        pingMeasuredAt = android.os.SystemClock.elapsedRealtime()
                        pingChecking = false
                        pingRequest = null
                    }
                }
                SimpleRuntime.MSG_TRAFFIC -> {
                    val values = content.split('|')
                    // A queued sample must not bring back speed after notification Stop.
                    if (DaemonConnectionState.read().connected) {
                        upload = values.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                        download = values.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    }
                    total = values.getOrNull(2)?.toDoubleOrNull() ?: total
                }
            }
            // Broadcasts wake the screen; persisted daemon state is the source of truth.
            syncConnectionState()
            renderStatus()
        }
    }

    private fun syncConnectionState() {
        if (!::store.isInitialized) return
        val snapshot = DaemonConnectionState.read()
        val previous = listOf(status, connected, active, captcha, recoveryFailed, recoveryAttempt)
        val previousMetrics = listOf(upload, download, total)
        if (ping != null && android.os.SystemClock.elapsedRealtime() - pingMeasuredAt > 60_000L) {
            ping = null
            renderStatus()
        }
        connected = snapshot.connected
        active = snapshot.active
        captcha = snapshot.captchaPending
        if (!connected) fullTunnelDialog?.dismiss()
        recoveryFailed = snapshot.phase == "FAILED"
        status = when (snapshot.phase) {
            "CONNECTED" -> "Connected"
            "WAITING_FOR_NETWORK" -> "Waiting for network…"
            "TURN_CAPTCHA" -> "Verifying VK in background…"
            "TURN_CAPTCHA_MANUAL" -> if (VkTurnManager.isAutoCaptchaEnabled()) getString(R.string.vk_turn_captcha_manual_needed)
                else "VK verification required"
            "STARTING_TURN", "TURN_READY", "STARTING_CORE", "VERIFYING_CONNECTION", "DNS_RECONNECTING" -> "Connecting…"
            "STOPPING" -> "Disconnecting…"
            "FAILED" -> "Connection lost"
            "CONNECTION_UNAVAILABLE" -> "Connection lost"
            "DISCONNECTED" -> "Disconnected"
            else -> "Reconnecting…"
        }
        recoveryAttempt = if (snapshot.attempt > 0) "Attempt ${snapshot.attempt} of ${snapshot.maximumAttempts}" else ""
        if (!connected) {
            ping = null
            pingChecking = false
            pingRequest = null
            upload = 0.0
            download = 0.0
        }
        if (connected) {
            val traffic = DaemonConnectionState.readTraffic()
            upload = traffic?.upload ?: 0.0
            download = traffic?.download ?: 0.0
        }
        lifecycleScope.launch { SimpleStartup.connectOnce(applicationContext) }
        total = MmkvManager.decodeSettingsString(SimpleRuntime.TOTAL, "0")?.toDoubleOrNull() ?: total
        if (previous != listOf(status, connected, active, captcha, recoveryFailed, recoveryAttempt)) render()
        else if (previousMetrics != listOf(upload, download, total)) renderStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        try { store = SimpleStore(this) } catch (e: Exception) {
            showError("Could not read saved configurations: ${e.message}")
            return
        }
        if (!DaemonConnectionState.hasLiveService()) SimpleRuntime.applyAutomation(store)
        total = MmkvManager.decodeSettingsString(SimpleRuntime.TOTAL, "0")?.toDoubleOrNull() ?: 0.0
        render()
        savedInstanceState?.getString("draft")?.let { runCatching { Gson().fromJson(it, SimpleConfiguration::class.java) }.getOrNull()?.let { edit(it) } }
        if (savedInstanceState?.getString("sheetKind") == "automation") automation()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionPending = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    override fun onStart() {
        super.onStart()
        if (!::store.isInitialized) return
        ContextCompat.registerReceiver(this, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        syncConnectionState()
        renderStatus()
        stateSyncJob?.cancel()
        stateSyncJob = lifecycleScope.launch {
            val stateFile=java.io.File(noBackupFilesDir,"simple-configurations.enc")
            var modified=stateFile.lastModified()
            while (registered) {
                delay(1_000)
                val time=stateFile.lastModified()
                if(time!=modified) {
                    withContext(Dispatchers.IO) { store.reload(); PanelAccess.enforce(applicationContext,store) }
                    modified=time; render()
                }
                syncConnectionState()
            }
        }
        panelEventsJob?.cancel()
        panelEventsJob = lifecycleScope.launch {
            PanelManager.events.collect {
                withContext(Dispatchers.IO) { store.reload(); PanelAccess.enforce(applicationContext,store) }
                render()
            }
        }
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }
    override fun onResume() {
        super.onResume()
        syncConnectionState()
        if (::store.isInitialized) {
            lifecycleScope.launch { withContext(Dispatchers.IO) { store.reload(); PanelAccess.enforce(applicationContext,store) }; render() }
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) maybeExplainBatteryOptimization()
    }

    private fun maybeExplainBatteryOptimization() {
        if (!::store.isInitialized || isFinishing || isDestroyed || !window.decorView.hasWindowFocus() ||
            notificationPermissionPending || sheet?.isShowing == true || fullTunnelDialog?.isShowing == true || preparing || pending != null ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        BatteryOptimizationPrompt.showOnce(this)
    }

    override fun onStop() {
        fullTunnelDialog?.dismiss()
        panelEventsJob?.cancel()
        stateSyncJob?.cancel()
        stateSyncJob = null
        if (registered) { unregisterReceiver(receiver); registered = false }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        draft?.let { outState.putString("draft", Gson().toJson(it)) }
        outState.putString("sheetKind", sheetKind)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { sheet?.dismiss(); super.onDestroy() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun bg(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }
    private fun button(value: String, description: String = value, reserved: List<String>? = null, action: () -> Unit) =
        (if(reserved==null) text(value,16,blue,true) else stableStatusText(value,16,blue,true,reserved)).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(12), dp(8), dp(12), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(20, 0, 122, 255)), bg(Color.TRANSPARENT, 12), null)
        contentDescription = description; isFocusable = true; setOnClickListener { action() }
    }
    private fun icon(kind: SimpleIconView.Kind, color: Int = blue): SimpleIconView =
        SimpleIconView(this, kind, color).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun iconButton(kind: SimpleIconView.Kind, description: String, color: Int = blue, action: () -> Unit): View =
        icon(kind, color).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = description
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(20, 0, 122, 255)), bg(Color.TRANSPARENT, 12), null)
            setOnClickListener { action() }
        }

    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun render() {
        if (!::store.isInitialized) return
        val savedScrollY = profileScroll?.scrollY ?: 0
        val savedScreenY=screenScroll?.scrollY ?: 0
        val largeText=resources.configuration.fontScale>=1.5f
        profileScroll = null
        val outer = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        root = column().apply { setPadding(dp(24), dp(16), dp(24), dp(8)) }
        screenScroll=if(largeText) ScrollView(this).apply {
            isFillViewport=true;addView(root,FrameLayout.LayoutParams(-1,-2))
            post {scrollTo(0,savedScreenY)}
        } else null
        outer.addView(screenScroll ?: root, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        outer.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = (view.width - view.paddingLeft - view.paddingRight).coerceAtMost(dp(620))
            val body=screenScroll ?: root
            if (width > 0 && body.layoutParams.width != width) {
                body.layoutParams = (body.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(outer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(outer)
        val heading = row().apply { translationY = dp(8).toFloat() }
        heading.addView(text("TnDtun", 25, bold = true).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(48), 1f))
        heading.addView(iconButton(SimpleIconView.Kind.SETTINGS, "Settings") { automation() }.apply {
            isEnabled = !preparing; translationY=dp(4).toFloat()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(heading); gap(root, 8)
        val subscription = row()
        subscription.addView(button(if(store.subscriptions.isEmpty()) "Добавить подписку" else "Подписка") {
            startActivity(Intent(this,PanelActivationActivity::class.java))
        }.also { PanelButtons.style(it) },LinearLayout.LayoutParams(0,dp(48),1f))
        if(store.subscriptions.isNotEmpty()) subscription.addView(button(if(PanelManager.busy) "Обновляем…" else "Обновить") {
            PanelManager.refreshManually(applicationContext)
        }.also { PanelButtons.style(it) }.apply { isEnabled=!PanelManager.busy },LinearLayout.LayoutParams(0,dp(48),1f).apply { marginStart=dp(8) })
        root.addView(subscription)
        val subscriptionCaption = row()
        subscriptionCaption.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
        store.subscriptions.maxOfOrNull { it.lastSync }?.takeIf { it > 0 }?.let { lastSync ->
            val timestamp = lastSync * 1000
            val flags = DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_TIME
            val display = DateUtils.formatDateTime(this, timestamp, if (DateUtils.isToday(timestamp)) flags
                else flags or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
            subscriptionCaption.addView(text(display, 11, muted).apply {
                id = R.id.subscription_last_sync
                gravity = Gravity.CENTER
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                contentDescription = getString(R.string.subscription_last_sync_description,
                    DateUtils.formatDateTime(this@SimpleActivity, timestamp, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 11, 1, TypedValue.COMPLEX_UNIT_DIP)
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
        }
        root.addView(subscriptionCaption, LinearLayout.LayoutParams(-1, dp(16)))
        statusPanel = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)); background = bg(panel, 22) }
        root.addView(statusPanel); renderStatus(); gap(root, 24)
        val modes = row().apply { background = bg(Color.rgb(238, 239, 241), 9); setPadding(dp(2), dp(2), dp(2), dp(2)) }
        for ((mode, label) in listOf("amnezia" to "Amnezia", "turn" to "TURN", "dns" to "DNS")) {
            modes.addView(button(label) { persistChange({ store.mode = mode }) }.apply {
                isEnabled = !preparing
                textSize = 14f; minHeight = dp(44); setTextColor(ink)
                if (store.mode == mode) { background = bg(Color.WHITE, 7); elevation = dp(1).toFloat() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        root.addView(modes); gap(root, 20)
        val visible = store.configurations.filter { it.mode == store.mode }
        val label = row()
        label.addView(text("Configurations", 18, bold = true))
        if (visible.isNotEmpty()) label.addView(text(visible.size.toString(), 12, muted, true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4)); background = bg(panel, 12)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        label.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (visible.isNotEmpty()) label.addView(iconButton(SimpleIconView.Kind.PLUS, "Add configuration") { add() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(label); gap(root, 14)
        if (visible.isEmpty()) {
            val empty = column().apply { gravity = Gravity.CENTER }
            val plus = iconButton(SimpleIconView.Kind.PLUS, "Add configuration") { add() }.apply {
                setPadding(dp(22), dp(22), dp(22), dp(22))
                background = bg(Color.rgb(238, 246, 255), 22)
            }
            empty.addView(plus, LinearLayout.LayoutParams(dp(70), dp(70))); gap(empty, 18)
            empty.addView(button("Add configuration") { add() })
            root.addView(empty, LinearLayout.LayoutParams(-1,if(largeText) -2 else 0,if(largeText) 0f else 1f))
        } else {
            val list = column()
            val activeID = MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID)
            visible.forEach { value ->
                val isActive = active && activeID == value.id
                val card = row().apply { setPadding(dp(16), dp(16), dp(12), dp(16)); background = bg(Color.WHITE, 19, if (isActive) Color.rgb(172, 228, 182) else Color.rgb(234, 234, 235)) }
                val tap = row().apply { isEnabled = !preparing; isFocusable = true; contentDescription = "${if (isActive) "Disconnect" else "Connect"} ${value.title}"; setOnClickListener { if (!preparing) connect(value) } }
                tap.addView(icon(if (isActive) SimpleIconView.Kind.STOP else SimpleIconView.Kind.PLAY, if (isActive) green else blue).apply {
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = bg(if (isActive) Color.rgb(238, 249, 240) else Color.rgb(237, 246, 255), 14)
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                val titles = column()
                titles.addView(text(value.title, 16, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                gap(titles, 8)
                titles.addView(text(if (isActive) status else value.detail, 13, if (isActive) green else muted).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                tap.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
                card.addView(tap, LinearLayout.LayoutParams(0, -2, 1f))
                if(!BundledDnsTunnel.isBuiltIn(value)) card.addView(iconButton(SimpleIconView.Kind.MORE, "Settings ${value.title}", muted) {
                    if (!isActive && !preparing) PopupMenu(this, card.getChildAt(1)).apply {
                        if(value.managed==null) menu.add("Edit").setOnMenuItemClickListener { edit(value.copy()); true }
                        menu.add("Delete").setOnMenuItemClickListener { confirmDelete(value); true }
                        show()
                    }
                }.apply { isEnabled = !isActive && !preparing }, LinearLayout.LayoutParams(dp(48), dp(48)))
                list.addView(card); gap(list, 12)
            }
            profileScroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(list)
                post { scrollTo(0, savedScrollY) }
            }
            root.addView(profileScroll, LinearLayout.LayoutParams(-1,if(largeText) dp(260) else 0,if(largeText) 0f else 1f))
        }
        root.addView(text(if (store.mode == "amnezia") "Amnezia VPN" else if (store.mode == "turn") "VK Calls · TURN" else "DNS tunneling · DNSTT", 12, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(12))
        })
        if(PanelManager.busy && PanelManager.route==PanelRequestRoute.DNS) {
            // A transient overlay keeps the VPN status card and controls in place.
            outer.addView(text(PanelRequestRoute.DNS.message,14,ink).apply {
                setPadding(dp(12),dp(10),dp(12),dp(10))
                background=bg(Color.rgb(237,246,255),12); elevation=dp(4).toFloat()
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
                isClickable=false; isFocusable=false
            },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply {
                leftMargin=dp(24); rightMargin=dp(24); bottomMargin=dp(36)
            })
        }
    }
    private fun selectedStatusProfile(): SimpleConfiguration? {
        val activeID=MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID)
        if(active || preparing) pending?.let { return it }
        if(active) store.configurations.firstOrNull { it.id==activeID }?.let { return it }
        val selected=store.selectedProfileID ?: activeID
        return store.configurations.firstOrNull { it.id==selected && it.mode==store.mode }
            ?: store.configurations.firstOrNull { it.mode==store.mode }
    }
    private fun stableStatusText(value: String,size: Int,color: Int=ink,bold: Boolean=false,variants: List<String>) =
        StableStatusText(this,variants).apply {
            text=value;textSize=size.toFloat();setTextColor(color);includeFontPadding=false
            if(bold) typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setLineSpacing(0f,1.15f)
        }
    private fun renderStatus() {
        if (!::statusPanel.isInitialized) return
        statusPanel.removeAllViews()
        val selected=selectedStatusProfile()
        val turn=selected?.mode=="turn"
        val statuses=listOf("Preparing…","Disconnected","Connecting…","Connected","Disconnecting…","Connection lost")+
            if(turn) listOf("Reconnecting…","Waiting for network…","Verifying VK in background…","VK verification required",
                getString(R.string.vk_turn_captcha_manual_needed)) else emptyList()
        val top = row()
        top.addView(View(this).apply {
            background = bg(if (connected) green else if (active || preparing) Color.rgb(255, 149, 0) else muted, 4)
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
        top.addView(stableStatusText(if(preparing) "Preparing…" else status,16,bold=true,variants=statuses).apply { gravity=Gravity.CENTER_VERTICAL },LinearLayout.LayoutParams(0,-2,1f))
        val pingButton = row().apply {
            gravity = Gravity.CENTER
            background = bg(Color.WHITE, 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Check latency"
            isFocusable = true
            minimumHeight=dp(48)
            isEnabled = connected && !pingChecking
            setOnClickListener {
                if (connected && !pingChecking) {
                    val requestID = android.os.SystemClock.elapsedRealtimeNanos().toString()
                    pingRequest = requestID
                    ping = null
                    pingChecking = true
                    renderStatus()
                    MessageUtil.sendMsg2Service(this@SimpleActivity, SimpleRuntime.MSG_REAL_DELAY, requestID)
                    lifecycleScope.launch {
                        delay(ManualRealDelay.TIMEOUT_MS + 1_000)
                        if (pingRequest == requestID) {
                            pingChecking = false; pingRequest = null; ping = null; renderStatus()
                        }
                    }
                }
            }
        }
        pingButton.addView(icon(SimpleIconView.Kind.REFRESH, if (connected) blue else muted), LinearLayout.LayoutParams(dp(14), dp(14)))
        pingButton.addView(stableStatusText(if (pingChecking) "… ms" else "${ping?.toString() ?: "—"} ms", 12, if (connected) blue else muted,variants=listOf("— ms","… ms","99999 ms")).apply {
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -2,1f).apply { marginStart = dp(6) })
        top.addView(pingButton, LinearLayout.LayoutParams(dp(88), -2).apply { marginStart = dp(8) })
        statusPanel.addView(top)
        gap(statusPanel,6)
        statusPanel.addView(text(selected?.title ?: " ",12,muted))
        val note=if(recoveryAttempt.isNotEmpty()) recoveryAttempt else
            if(active && RoutingPolicyState.pending(store)) "Новые настройки — после переподключения" else " "
        statusPanel.addView(stableStatusText(note,12,muted,variants=listOf("Attempt 5 of 5","Новые настройки — после переподключения")))
        gap(statusPanel, 6)
        val metrics = row().apply { gravity = Gravity.TOP }
        for ((index, pair) in listOf("Download" to TrafficDisplay.speed(download), "Upload" to TrafficDisplay.speed(upload), "Total" to TrafficDisplay.bytes(total)).withIndex()) {
            val metric = column()
            val metricLabel = row()
            if (index < 2) metricLabel.addView(icon(if (index == 0) SimpleIconView.Kind.DOWN else SimpleIconView.Kind.UP, muted), LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(4) })
            metricLabel.addView(text(pair.first, 12, muted))
            metric.addView(metricLabel)
            gap(metric, 10)
            metric.addView(stableStatusText(pair.second,15,bold=true,
                variants=if(index<2) listOf("0 B/s","999.9 MB/s") else listOf("0 B","999.9 GB")))
            metrics.addView(metric, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index < 2) marginEnd = dp(12) })
        }
        statusPanel.addView(metrics)
        gap(statusPanel,12)
        val actions=row()
        val disconnecting=active || preparing || status=="Disconnecting…"
        val primary=button(if(disconnecting) "Disconnect" else "Connect",reserved=listOf("Connect","Disconnect")) {
            if(disconnecting) disconnect() else selectedStatusProfile()?.let(::connect)
        }.apply {
            tag="status-primary-action";gravity=Gravity.START or Gravity.CENTER_VERTICAL;setPadding(0,0,0,0)
            isEnabled=selected!=null || disconnecting
        }
        actions.addView(primary,LinearLayout.LayoutParams(-2,-2).apply { marginEnd=dp(8) })
        val fullTunnel=Switch(this).apply {
            tag="status-full-tunnel"; text=getString(R.string.simple_full_tunnel_title); textSize=13f
            minHeight=dp(48); gravity=Gravity.END or Gravity.CENTER_VERTICAL
            showText=false; switchPadding=dp(8)
            isChecked=connected && TemporaryFullTunnel.active(selected?.id)
            isEnabled=connected && !preparing
            visibility=if(TemporaryFullTunnelPolicy.supported(selected?.mode.orEmpty(),store.amneziaSplitEnabled)) View.VISIBLE else View.INVISIBLE
            contentDescription=getString(R.string.simple_full_tunnel_title)
            setOnCheckedChangeListener { _,value ->
                setOnCheckedChangeListener(null)
                isChecked=!value // Show the applied state until the service acknowledges by reconnecting.
                if (value) {
                    renderStatus()
                    selected?.let { confirmFullTunnel(it.id) }
                } else applyFullTunnel("off")
            }
        }
        actions.addView(fullTunnel,LinearLayout.LayoutParams(0,-2,1f))
        if (turn && (recoveryFailed || (captcha && !VkTurnManager.isAutoCaptchaEnabled()))) {
            val auxiliary = if (recoveryFailed) {
                button("Retry connection") { selectedStatusProfile()?.let(::connect) }
            } else {
                button("Verify VK") { openCaptcha() }
            }
            auxiliary.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            fullTunnel.visibility = View.GONE
            actions.addView(auxiliary, LinearLayout.LayoutParams(0, -2, 1f))
        }
        statusPanel.addView(actions)
    }

    private fun confirmFullTunnel(profileId: String) {
        if (fullTunnelDialog?.isShowing == true) return
        val choices = column().apply { setPadding(dp(24), dp(12), dp(24), dp(8)) }
        choices.addView(text(getString(R.string.simple_full_tunnel_warning), 15))
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.simple_full_tunnel_title)
            .setView(ScrollView(this).apply { addView(choices) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        for ((label, minutes) in listOf(
            R.string.simple_full_tunnel_15_minutes to TemporaryFullTunnelPolicy.DEFAULT_MINUTES,
            R.string.simple_full_tunnel_until_restart to TemporaryFullTunnelPolicy.UNTIL_RESTART,
        )) {
            gap(choices, 10)
            choices.addView(button(getString(label)) {
                dialog.dismiss()
                if (!preparing && DaemonConnectionState.read().connected &&
                    MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID) == profileId
                ) applyFullTunnel(TemporaryFullTunnelPolicy.activationCommand(profileId, minutes))
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                background = RippleDrawable(ColorStateList.valueOf(Color.argb(20, 0, 122, 255)), bg(panel, 12), null)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setOnDismissListener { if (fullTunnelDialog === dialog) fullTunnelDialog = null }
        fullTunnelDialog = dialog
        dialog.show()
    }

    private fun applyFullTunnel(command: String) {
        statusPanel.findViewWithTag<Switch>("status-full-tunnel")?.isEnabled = false
        MessageUtil.sendMsg2Service(this, TemporaryFullTunnel.MESSAGE, command)
        lifecycleScope.launch { delay(1_000); syncConnectionState(); renderStatus() }
    }

    private fun add() = edit(if(store.mode=="dns") BundledDnsTunnel.profile(this).copy(id=java.util.UUID.randomUUID().toString()) else SimpleConfiguration(mode = store.mode))
    private fun confirmDelete(value: SimpleConfiguration) {
        AlertDialog.Builder(this).setTitle("Delete configuration?").setMessage(value.title)
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                persistChange({ store.remove(value.id) })
            }.show()
    }
    private fun persistChange(write: () -> Unit, onSaved: () -> Unit = {}) {
        if (preparing) return
        val editingSheet = sheet
        fun enable(view: View, enabled: Boolean) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (index in 0 until view.childCount) enable(view.getChildAt(index), enabled)
        }
        editingSheet?.setCancelable(false)
        editingSheet?.window?.decorView?.let { enable(it, false) }
        preparing = true; render()
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { write() }; onSaved() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { showError(e.message.orEmpty()) }
            finally {
                if (editingSheet === sheet) {
                    editingSheet?.setCancelable(true)
                    editingSheet?.window?.decorView?.let { enable(it, true) }
                }
                preparing = false; render()
            }
        }
    }
    private fun connect(value: SimpleConfiguration) {
        SimpleStartup.cancel()
        if (active && MmkvManager.decodeSettingsString(SimpleRuntime.ACTIVE_ID) == value.id) { disconnect(); return }
        try {
            store.requireAccess(value)
            pending = value.validated(forConnection = true)
            val permission = VpnService.prepare(this)
            preparing = true; render()
            if (permission != null) vpnPermission.launch(permission) else startConnection(pending!!)
        } catch (e: Exception) { preparing = false; pending = null; showError(e.message.orEmpty()) }
    }
    private fun startConnection(value: SimpleConfiguration) {
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { store.reload(); store.requireAccess(value) }
                val current=store.configurations.firstOrNull { it.id==value.id } ?: if(value.managed==null) value else error("Подключение больше не назначено.")
                if (active || DaemonConnectionState.hasLiveService()) {
                    CoreServiceManager.stopVService(this@SimpleActivity)
                    repeat(100) { if (DaemonConnectionState.hasLiveService()) delay(100) }
                    check(!DaemonConnectionState.hasLiveService()) { "The previous connection is still stopping. Try again in a few seconds." }
                }
                withContext(Dispatchers.IO) { SimpleRuntime.stage(current, store) }
                connected = false; active = true; status = "Connecting…"; captcha = false; recoveryFailed = false; ping = null
                check(CoreServiceManager.startVService(this@SimpleActivity, SimpleRuntime.STAGED_GUID)) { "Could not start VPN." }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { active = false; connected = false; status = "Disconnected"; showError(e.message.orEmpty()) }
            finally { preparing = false; pending = null; render() }
        }
    }
    private fun disconnect() {
        SimpleStartup.cancel()
        connectionJob?.cancel(); pending = null; preparing = false; recoveryFailed = false
        CoreServiceManager.stopVService(this)
        syncConnectionState()
        render()
    }
    private fun openCaptcha() {
        if (!VkTurnManager.isAutoCaptchaEnabled() && DaemonConnectionState.read().captchaPending) {
            startActivity(Intent(this, VkCaptchaActivity::class.java))
        }
    }
    private fun showError(message: String) { if (!isFinishing) AlertDialog.Builder(this).setTitle("Could not complete action").setMessage(message).setPositiveButton("OK", null).show() }

    private fun newSheet(title: String, kind: String, body: LinearLayout, save: () -> Unit): Dialog {
        sheet?.dismiss()
        val dialog = Dialog(this, com.v2ray.ang.R.style.SimpleBLTTheme)
        val content = column().apply { background = bg(Color.rgb(242, 242, 247), 22); setPadding(dp(20), dp(10), dp(20), dp(12)) }
        content.addView(FrameLayout(this).apply {
            addView(View(this@SimpleActivity).apply { background = bg(Color.rgb(190, 190, 194), 2) }, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, dp(20)))
        gap(content, 8)
        val nav = row()
        nav.addView(button("Cancel") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(80), dp(48)))
        nav.addView(text(title, 17, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(48), 1f))
        nav.addView(View(this), LinearLayout.LayoutParams(dp(80), 1)); content.addView(nav); gap(content, 12)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); gap(content, 12)
        content.addView(button("Save") { save() }.apply { background = bg(blue, 16); setTextColor(Color.WHITE); minHeight = dp(54) })
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent); setGravity(Gravity.BOTTOM)
            setLayout((resources.displayMetrics.widthPixels).coerceAtMost(dp(620)), (resources.displayMetrics.heightPixels * 0.91).toInt())
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener { if (sheet === dialog) { draft = null; sheet = null; sheetKind = null } }
        sheet = dialog; sheetKind = kind; dialog.show()
        // Dialog.show may replace the initial layout parameters.
        dialog.window?.setLayout((resources.displayMetrics.widthPixels).coerceAtMost(dp(620)), (resources.displayMetrics.heightPixels * 0.91).toInt())
        return dialog
    }
    private fun group(parent: LinearLayout, title: String? = null): LinearLayout {
        title?.let { parent.addView(text(it, 13, muted, true).apply { setPadding(dp(4), dp(12), dp(4), dp(12)) }) }
        return column().apply { background = bg(Color.WHITE, 12); setPadding(dp(16), dp(12), dp(16), dp(12)); parent.addView(this); gap(parent, 16) }
    }
    private fun hint(parent: LinearLayout, value: String) { parent.addView(text(value, 13, muted).apply { setPadding(dp(4), 0, dp(4), dp(16)) }) }
    private fun field(parent: LinearLayout, label: String, placeholder: String, value: String, update: (String) -> Unit, secret: Boolean = false, multiline: Boolean = false): EditText {
        parent.addView(text(label, 13, muted).apply { setPadding(0, dp(12), 0, dp(4)) })
        val edit = EditText(this).apply {
            setText(value); hint = placeholder; textSize = 16f; setTextColor(ink); setHintTextColor(Color.rgb(177, 177, 185))
            background = null
            includeFontPadding = false
            setPadding(0, dp(10), 0, dp(10))
            minHeight = dp(48)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = !multiline; if (multiline) { minLines = 4; maxLines = 6; maxHeight = dp(180); gravity = Gravity.TOP }
            contentDescription = label
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s.toString()) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        parent.addView(edit); return edit
    }
    private fun choices(parent: LinearLayout, label: String, labels: List<String>, selected: Int, update: (Int) -> Unit) {
        parent.addView(text(label, 12, muted).apply { setPadding(0, dp(10), 0, dp(4)) })
        val options = row()
        labels.forEachIndexed { index, value ->
            options.addView(button(value) { update(index) }.apply {
                textSize = 14f; setTextColor(if (index == selected) blue else ink)
                background = bg(if (index == selected) Color.rgb(235, 244, 255) else Color.WHITE, 8)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        parent.addView(options)
    }
    private fun edit(value: SimpleConfiguration, captureDefaultDNS: Boolean = true) {
        draft = value
        val body = column()
        lateinit var dialog: Dialog
        val pasteGroup = group(body)
        val pasteRow = row(); pasteRow.addView(text(if (value.mode == "amnezia") "Amnezia key" else "JSON configuration", 16), LinearLayout.LayoutParams(0, -2, 1f))
        pasteRow.addView(button("Paste", "Paste JSON") {
            try {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val content = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                val imported = ConfigurationJSONImporter.apply(content, value)
                dialog.setOnDismissListener(null); dialog.dismiss(); edit(imported, captureDefaultDNS = false)
                Toast.makeText(this, "Configuration filled in. Tap Save.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { showError(e.message.orEmpty()) }
        }); pasteGroup.addView(pasteRow)
        hint(body, if (value.mode == "amnezia") "Copy the complete vpn:// connection key from Amnezia and tap Paste." else "Copy a connection JSON and tap Paste.")
        if (value.mode == "amnezia") {
            val g = group(body)
            field(g, "Connection key", "vpn://… or [Interface]…", value.amneziaKey, { value.amneziaKey = it }, multiline = true)
            hint(body, "All parameters are applied from the key. Configure split tunneling in Settings.")
        } else if (value.mode == "turn") {
            val g = group(body)
            field(g, "VK call link", "https://vk.com/call/join/…", value.callLink, { value.callLink = it })
            field(g, "Server address", "example.invalid:56010", value.server, { value.server = it })
            hint(body, "Use an active VK call link. The default server port is 56010.")
            val access = group(body)
            field(access, "UUID VLESS", "Your server UUID", value.vlessID, { value.vlessID = it })
            field(access, "Transport key", "64 characters or blank", value.wrapKey, { value.wrapKey = it })
            hint(body, "A VLESS UUID is required. The transport key must match your server; leave it blank if your server does not use wrapping.")
        } else {
            val g = group(body)
            field(g, "Tunnel domain", "dns.example.invalid", value.domain, { value.domain = it })
            field(g, "Public key", "64 hexadecimal characters", value.publicKey, { value.publicKey = it })
            choices(g, "Mode", listOf("SOCKS5", "SSH"), if (value.tunnelType == "ssh") 1 else 0) {
                value.tunnelType = if (it == 1) "ssh" else "socks5"
                dialog.setOnDismissListener(null); dialog.dismiss(); edit(value)
            }
            hint(body, "The mode must match your DNSTT server.")
            choices(group(body, "Performance"), "Parallel sessions", listOf("1", "2", "4", "8"), listOf(1, 2, 4, 8).indexOf(value.sessionCount)) {
                value.sessionCount = listOf(1, 2, 4, 8)[it]
                dialog.setOnDismissListener(null); dialog.dismiss(); edit(value)
            }
            hint(body, "The default is 4. Multiple sessions download data in parallel. Reduce the number if the connection is unstable.")
            val dns = group(body, "DNS servers")
            dns.addView(Switch(this).apply {
                text = "Auto-detect DNS before connecting"; textSize = 15f; isChecked = value.autoDetectResolver; minHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> value.autoDetectResolver = checked }
            })
            val resolverField = field(dns, "Selected DNS", "1.1.1.1:53", value.resolver, { value.resolver = it })
            val resolverList = column(); dns.addView(resolverList)
            fun renderResolvers() {
                resolverList.removeAllViews()
                store.savedResolvers.forEach { address ->
                    val entry = row()
                    val selection = row().apply {
                        minimumHeight = dp(48)
                        contentDescription = "Select DNS $address"
                        isFocusable = true
                        setOnClickListener { value.resolver = address; resolverField.setText(address); renderResolvers() }
                    }
                    selection.addView(icon(if (address == value.resolver) SimpleIconView.Kind.CHECK else SimpleIconView.Kind.CIRCLE), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
                    selection.addView(text(address, 15), LinearLayout.LayoutParams(0, -2, 1f))
                    entry.addView(selection, LinearLayout.LayoutParams(0, -2, 1f))
                    entry.addView(iconButton(SimpleIconView.Kind.REMOVE, "Delete DNS $address", Color.RED) {
                        persistChange({ store.savedResolvers = store.savedResolvers - address }) { renderResolvers() }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    resolverList.addView(entry)
                }
            }
            val capture = button("Detect current network DNS") {
                try {
                    val addresses = ManagedDnsTunnelResolverDiscovery.discover(this)
                    if (addresses.isNotEmpty()) persistChange({ addresses.forEach(store::rememberResolver) }) {
                        value.resolver = addresses.first(); resolverField.setText(value.resolver); renderResolvers()
                    }
                    else Toast.makeText(this, "The system returned no DNS servers. Add one manually.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) { showError(e.message.orEmpty()) }
            }.apply { textSize = 14f; gravity = Gravity.START; isEnabled = !active }
            dns.addView(capture)
            if (active) hint(dns, "Disconnect VPN to detect mobile or Wi-Fi DNS.")
            renderResolvers()
            val addRow = row()
            val newDNS = EditText(this).apply { hint = "Add IP or IP:port"; textSize = 14f; inputType = InputType.TYPE_CLASS_TEXT; isSingleLine = true; contentDescription = "New DNS" }
            addRow.addView(newDNS, LinearLayout.LayoutParams(0, dp(48), 1f))
            addRow.addView(button("Add") {
                try {
                    val address = SimpleConfiguration.normalizeEndpoint(newDNS.text.toString(), 53, ipOnly = true)
                    persistChange({ store.rememberResolver(address) }) {
                        value.resolver = address; resolverField.setText(address); newDNS.setText(""); renderResolvers()
                    }
                }
                catch (e: Exception) { showError(e.message.orEmpty()) }
            }.apply { textSize = 14f }); dns.addView(addRow)
            hint(body, "Saved DNS servers are available in all DNS profiles.")
            if (value.tunnelType == "ssh") {
                val ssh = group(body, "SSH access")
                field(ssh, "Username", "Username", value.sshUsername, { value.sshUsername = it })
                field(ssh, "Password", "Password", value.sshPassword, { value.sshPassword = it }, secret = true)
                field(ssh, "Server fingerprint", "SHA256:…", value.sshHostKey, { value.sshHostKey = it })
                val keyContainer = column().apply { visibility = if (value.sshPrivateKey.isEmpty()) View.GONE else View.VISIBLE }
                ssh.addView(button("Use a private key instead of a password") { keyContainer.visibility = if (keyContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE }.apply { textSize = 14f; gravity = Gravity.START })
                field(keyContainer, "OpenSSH private key", "-----BEGIN OPENSSH PRIVATE KEY-----", value.sshPrivateKey, { value.sshPrivateKey = it }, multiline = true); ssh.addView(keyContainer)
            }
            val wifi = group(body)
            val wifiField = column().apply { visibility = if (value.useWiFiResolver) View.VISIBLE else View.GONE }
            wifi.addView(Switch(this).apply {
                text = "Separate DNS for Wi-Fi"; textSize = 15f; isChecked = value.useWiFiResolver; minHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> value.useWiFiResolver = checked; wifiField.visibility = if (checked) View.VISIBLE else View.GONE }
            })
            field(wifiField, "Wi-Fi DNS", "8.8.8.8", value.wifiResolver, { value.wifiResolver = it }); wifi.addView(wifiField)
            hint(body, "Wi-Fi uses this server; mobile data uses the selected DNS above. Switching is automatic.")
            // Capture once for a new row; redraw/import does not overwrite a user's selected DNS.
            if (captureDefaultDNS && !active && store.configurations.none { it.id == value.id } && value.resolver == "1.1.1.1:53") {
                ManagedDnsTunnelResolverDiscovery.discover(this).firstOrNull()?.let {
                    persistChange({ store.rememberResolver(it) }) { value.resolver = it; resolverField.setText(it); renderResolvers() }
                }
            }
        }
        dialog = newSheet(if (value.mode == "amnezia") "Amnezia" else if (value.mode == "turn") "TURN proxy" else "DNS tunnel", "profile", body) {
            val snapshot = value.copy(savedResolvers = value.savedResolvers.toList())
            persistChange({ store.save(snapshot) }) { dialog.dismiss() }
        }
        draft = value
    }
    private fun automation() {
        if (preparing) return
        settingsResult.launch(Intent(this, SimpleSettingsActivity::class.java))
    }
}
