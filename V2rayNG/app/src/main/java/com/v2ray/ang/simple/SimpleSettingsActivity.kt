package com.v2ray.ang.simple

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.BuildConfig
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** Independent native Android settings. Heavy work belongs to the retained draft model. */
class SimpleSettingsActivity : AppCompatActivity() {
    private val ink = Color.rgb(20, 31, 56)
    private val muted = Color.rgb(112, 122, 140)
    private val blue = Color.rgb(0, 122, 255)
    private lateinit var model: SimpleSettingsModel
    private lateinit var form: LinearLayout
    private lateinit var panels: List<LinearLayout>
    private lateinit var tabs: List<TextView>
    private lateinit var cancel: TextView
    private lateinit var save: TextView
    private lateinit var progress: TextView
    private lateinit var captcha: Switch
    private lateinit var ipv6: Switch
    private lateinit var autoConnect: Switch
    private lateinit var amneziaSplit: Switch
    private lateinit var fullDuration: Spinner
    private lateinit var turnSessions: Spinner
    private lateinit var recovery: Switch
    private lateinit var policies: List<RadioButton>
    private lateinit var excludedApps: TextView
    private lateinit var pushApple: Switch
    private lateinit var pushGoogle: Switch
    private var appPicker: AlertDialog? = null
    private lateinit var modeDescription: TextView
    private lateinit var addressFields: LinearLayout
    private lateinit var newAddress: EditText
    private lateinit var search: EditText
    private lateinit var count: TextView
    private lateinit var addressList: RecyclerView
    private lateinit var addresses: AddressAdapter
    private lateinit var expand: TextView
    private lateinit var listMessage: TextView
    private lateinit var transferMessage: TextView
    private var preview: AlertDialog? = null
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (model.fileKind == "backup") model.readBackup { readFile(uri, ApplicationBackup.MAXIMUM_SIZE) }
            else model.importAddresses { readFile(uri, SplitTunnelImport.MAXIMUM_SIZE) }
        }
    }
    private val createFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) model.cancelExport() else model.writeBackup { data ->
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Could not open file for writing.")
            stream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
        }
        model = ViewModelProvider(this)[SimpleSettingsModel::class.java]
        buildScreen()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!model.busy) finish() }
        })
        model.changed.observe(this) { updateScreen() }
        model.load(applicationContext)
    }
    override fun onDestroy() { appPicker?.dismiss(); preview?.dismiss(); super.onDestroy() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun bg(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun text(value: String, size: Int = 15, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); includeFontPadding = false
        setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun button(value: String, action: () -> Unit) = text(value, color = blue, bold = true).apply {
        minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(20, 0, 122, 255)), bg(Color.TRANSPARENT, 10), null)
        isFocusable = true; setOnClickListener { action() }
    }
    private fun section(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(text(title, 13, muted, true).apply { setPadding(dp(4), dp(20), dp(4), dp(10)) })
        return column().apply {
            background = bg(Color.WHITE, 14); setPadding(dp(16), dp(10), dp(16), dp(10))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun hint(parent: LinearLayout, value: String) = text(value, 13, muted).apply {
        setPadding(dp(4), dp(10), dp(4), dp(12)); parent.addView(this)
    }
    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText; textSize = 16f; setTextColor(ink); setHintTextColor(muted)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = true; minHeight = dp(48); includeFontPadding = false
        setPadding(0, dp(10), 0, dp(10)); background = null
    }
    private fun buildScreen() {
        val outer = FrameLayout(this).apply { setBackgroundColor(Color.rgb(242, 242, 247)) }
        val content = column()
        outer.addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        ViewCompat.setOnApplyWindowInsetsListener(outer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        outer.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = (view.width - view.paddingLeft - view.paddingRight).coerceAtMost(dp(620))
            if (width > 0 && content.layoutParams.width != width) {
                content.layoutParams = (content.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
            }
        }
        content.addView(text("Settings", 20, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(dp(20), dp(16), dp(20), dp(16))
        })
        val tabRow = row().apply { setPadding(dp(20), 0, dp(20), dp(8)) }
        tabs = listOf("General", "Split tunneling", "Transfer").mapIndexed { index, title ->
            button(title) { model.tab = index; updateScreen() }.apply {
                gravity = Gravity.CENTER; textSize = 13f
                tabRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        content.addView(tabRow)
        form = column().apply { setPadding(dp(20), 0, dp(20), dp(20)) }
        panels = List(3) { column().also { form.addView(it) } }
        buildGeneral(panels[0]); buildSplit(panels[1]); buildTransfer(panels[2])
        content.addView(ScrollView(this).apply { isFillViewport = true; addView(form) }, LinearLayout.LayoutParams(-1, 0, 1f))
        progress = text("", 13, muted).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        content.addView(progress)
        val footer = row().apply { setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(Color.WHITE) }
        cancel = button("Cancel") { finish() }.apply { gravity = Gravity.CENTER; background = bg(Color.rgb(237, 241, 249), 14) }
        save = button("Save") { model.save() }.apply { gravity = Gravity.CENTER; background = bg(blue, 14); setTextColor(Color.WHITE) }
        footer.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        footer.addView(save, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(footer)
        setContentView(outer)
    }
    private fun buildGeneral(parent: LinearLayout) {
        autoConnect = Switch(this).apply {
            text = "Автоподключение при перезапуске"; textSize = 16f; minHeight = dp(52)
            setOnCheckedChangeListener { _, value -> if (!model.busy) model.autoConnectOnRestart = value }
        }
        section(parent, "Запуск приложения").addView(autoConnect)
        hint(parent, "Один раз подключает последний выбранный профиль при новом запуске приложения или после перезагрузки Android и разблокировки. Требуется ранее выданное разрешение VPN. Принудительная остановка и ограничения производителя могут блокировать запуск после перезагрузки.")
        ipv6=Switch(this).apply {
            text="IPv6"; textSize=16f; minHeight=dp(52)
            setOnCheckedChangeListener { _,value->if(!model.busy) model.ipv6Enabled=value }
        }
        section(parent,"Network").addView(ipv6)
        hint(parent,"IPv6 uses the same split rules. Leave disabled if the selected server does not support it.")
        captcha = Switch(this).apply {
            text = "Automatic VK verification"; textSize = 16f; minHeight = dp(52)
            setOnCheckedChangeListener { _, value -> if (!model.busy) model.autoCaptcha = value }
        }
        section(parent, "VK verification").addView(captcha)
        hint(parent, "Verification runs in the background. If VK needs a manual step, tap Verify VK on the main screen.")
        val channels = section(parent, "TURN · Параллельные каналы")
        turnSessions = Spinner(this).apply {
            contentDescription = "Число каналов TURN"
            val values = com.v2ray.ang.core.TurnSessionCount.choices
            adapter = ArrayAdapter(this@SimpleSettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                values.map { if (it == 10) "10 · по умолчанию" else it.toString() })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!model.busy && model.loaded) model.turnSessionCount = values[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        channels.addView(turnSessions, LinearLayout.LayoutParams(-1, dp(52)))
        hint(parent, "При нестабильной сети попробуйте 1, 2 или 4 канала. Скорость может снизиться. Только TURN; применяется при следующем подключении. Обновление подписки этот выбор не изменяет.")
        recovery = Switch(this).apply {
            text = "Auto-recovery · TURN only"; textSize = 16f; minHeight = dp(52)
            setOnCheckedChangeListener { _, value -> if (!model.busy) model.autoRecovery = value }
        }
        section(parent, "TURN proxy · Connection recovery").addView(recovery)
        hint(parent, "Только TURN proxy: фоновая проверка и повторное подключение. На Amnezia и DNS эта настройка не действует. Значение сохраняется для возврата к TURN.")
        val checks = section(parent, "TURN · background check")
        checks.addView(android.widget.Spinner(this).apply {
            val intervals = (15..300).toList()
            adapter = android.widget.ArrayAdapter(this@SimpleSettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                intervals.map { "$it seconds" })
            tag = "turn-interval"
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!model.busy && model.loaded) model.turnCheckInterval = intervals[position]
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        })
        hint(parent, "Applies to TURN when Auto-recovery is enabled. Checks real traffic in the background.")
        hint(parent, "TnDtun ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
    private fun buildSplit(parent: LinearLayout) {
        val temporary=section(parent,"Всё в туннель · временно")
        fullDuration=Spinner(this).apply {
            tag="full-tunnel-duration"
            adapter=ArrayAdapter(this@SimpleSettingsActivity,android.R.layout.simple_spinner_dropdown_item,
                TemporaryFullTunnelPolicy.durations.map { if(it==0) "До следующего подключения" else "$it минут" })
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                    if(!model.busy && model.loaded) model.fullTunnelMinutes=TemporaryFullTunnelPolicy.durations[position]
                }
                override fun onNothingSelected(parent:AdapterView<*>?) {}
            }
        }
        temporary.addView(fullDuration,LinearLayout.LayoutParams(-1,dp(52)))
        hint(parent,"Переключатель рядом с Disconnect временно отменяет адресные правила и исключения приложений. Требуется короткое переподключение. Списки сохраняются. Сплит возвращается по таймеру или при следующем подключении VPN, включая автоматическое. Во сне Android таймер применяется при ближайшем пробуждении службы.")
        amneziaSplit=Switch(this).apply {
            text="Сплит-туннель для Amnezia"; textSize=16f; minHeight=dp(52)
            setOnCheckedChangeListener { _,value->if(!model.busy) model.amneziaSplitEnabled=value }
        }
        section(parent,"Где применяется сплит-туннель").addView(amneziaSplit)
        hint(parent,"Адресные правила и исключения приложений по умолчанию работают в VK TURN proxy и Amnezia. Для Amnezia их можно отключить здесь. В DNS-режиме весь трафик идёт через туннель. Push-правила сохраняются отдельно.")
        val push=section(parent,"Push-уведомления через VPN")
        pushApple=Switch(this).apply {
            text="Apple"; textSize=16f; minHeight=dp(52)
            setOnCheckedChangeListener { _,value -> model.setPush(value,model.split.pushNotifications?.google ?: false) }
        }
        pushGoogle=Switch(this).apply {
            text="Google / Android"; textSize=16f; minHeight=dp(52)
            setOnCheckedChangeListener { _,value -> model.setPush(model.split.pushNotifications?.apple ?: false,value) }
        }
        push.addView(pushApple); push.addView(pushGoogle)
        hint(parent,"При белых списках направляет push-службы через выбранное подключение раньше адресных правил. Списки не изменяются. Набор 1. Приложения, исключённые из VPN ниже, остаются исключёнными. Применяется после Save и переподключения.")
        val apps=section(parent,"Приложения без VPN")
        excludedApps=button("Выбрать приложения") {
            appPicker=AppExclusionPicker.show(this,model.excludedApplications,model::setExcludedApplications)
        }
        apps.addView(excludedApps)
        hint(parent,"Выбранные приложения целиком идут напрямую, включая DNS. Для остальных действуют адресные правила ниже. После «Готово» нажмите Save; активный VPN переподключится.")
        val modes = section(parent, "Mode")
        policies = SplitTunnelSettings.MODES.map { mode ->
            RadioButton(this).apply {
                text = SplitTunnelSettings.title(mode); textSize = 15f; minHeight = dp(52)
                setPadding(0, dp(8), 0, dp(8)); setOnClickListener { model.setMode(mode) }; modes.addView(this)
            }
        }
        modeDescription = hint(parent, "")
        addressFields = column().also { parent.addView(it) }
        val add = section(addressFields, "Add address")
        newAddress = field("Domain, IP or CIDR").apply {
            addTextChangedListener { model.newAddress = it.toString() }
            setOnEditorActionListener { _, _, _ -> model.addManual(); true }
        }
        add.addView(newAddress)
        add.addView(button("Add") { model.addManual() })
        hint(addressFields, "Example: example.com, 203.0.113.7 or 203.0.113.0/24. Separate addresses with spaces or commas.")
        val imports = section(addressFields, "Import JSON")
        imports.addView(button("Choose JSON file") { model.fileKind = "addresses"; openFile.launch(arrayOf("application/json", "text/*")) })
        imports.addView(button("Paste JSON") { clipboard()?.let { text -> model.importAddresses { text } } })
        imports.addView(button("Download address list") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://iplist.opencck.org/ru/"))) })
        hint(addressFields, "Import adds to the current list. Each mode keeps its own addresses. Save to apply routing changes.")
        val list = section(addressFields, "Addresses")
        count = text("", 13, muted).also { list.addView(it) }
        search = field("Search all addresses").apply { addTextChangedListener { model.setSearch(it.toString()) } }
        list.addView(search)
        addresses = AddressAdapter({ showNetworks(it) }, { model.remove(it) })
        addressList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SimpleSettingsActivity); adapter = addresses
            isNestedScrollingEnabled = true; itemAnimator = null
        }
        list.addView(addressList, LinearLayout.LayoutParams(-1, dp(280)))
        expand = button("Show all") { model.toggleExpanded() }.also { list.addView(it) }
        listMessage = hint(list, "")
        list.addView(button("Clear list") {
            AlertDialog.Builder(this).setTitle("Delete all addresses in this mode?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> model.clearList() }.show()
        }.apply { setTextColor(Color.rgb(210, 40, 40)) })
    }
    private fun buildTransfer(parent: LinearLayout) {
        section(parent, "Move to another device").addView(button("Export everything") { model.prepareBackup() })
        hint(parent, "All profiles, keys, DNS servers, split lists and settings in one JSON. It includes passwords; share it with trusted people.")
        val imports = section(parent, "Restore")
        imports.addView(button("Import from file") { model.fileKind = "backup"; openFile.launch(arrayOf("application/json", "text/*")) })
        imports.addView(button("Paste backup") { clipboard()?.let { text -> model.readBackup { text } } })
        hint(parent, "Review the backup before importing. After confirmation it replaces all profiles and settings. VPN stays disconnected.")
        transferMessage = hint(parent, "")
    }
    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) setEnabled(view.getChildAt(index), enabled)
    }
    private fun updateScreen() {
        if (model.finished) {
            setResult(RESULT_OK, Intent().putExtra("routingChanged", model.routingChanged).putExtra("restored", model.restored))
            finish(); return
        }
        panels.forEachIndexed { index, panel -> panel.visibility = if (index == model.tab) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { index, tab ->
            tab.setTextColor(if (index == model.tab) blue else muted)
            tab.background = bg(if (index == model.tab) Color.WHITE else Color.TRANSPARENT, 10)
            tab.isEnabled = !model.busy
        }
        setEnabled(form, !model.busy)
        cancel.isEnabled = !model.busy; save.isEnabled = !model.busy && model.loaded
        save.alpha = if (save.isEnabled) 1f else 0.5f
        progress.text = if (model.busy) "Processing…" else ""
        progress.visibility = if (model.busy) View.VISIBLE else View.GONE
        form.findViewWithTag<android.widget.Spinner>("turn-interval")?.let { spinner ->
            val selected = (model.turnCheckInterval-15).coerceIn(0,285)
            if (spinner.selectedItemPosition != selected) spinner.setSelection(selected)
        }
        recovery.isEnabled=!model.busy && model.recoveryApplies
        recovery.alpha=if(model.recoveryApplies) 1f else 0.5f
        amneziaSplit.isChecked=model.amneziaSplitEnabled
        val durationIndex=TemporaryFullTunnelPolicy.durations.indexOf(model.fullTunnelMinutes).coerceAtLeast(0)
        if(fullDuration.selectedItemPosition!=durationIndex) fullDuration.setSelection(durationIndex)
        val sessionIndex = com.v2ray.ang.core.TurnSessionCount.choices.indexOf(model.turnSessionCount).coerceAtLeast(0)
        if (turnSessions.selectedItemPosition != sessionIndex) turnSessions.setSelection(sessionIndex)
        autoConnect.isChecked = model.autoConnectOnRestart
        captcha.isChecked = model.autoCaptcha; recovery.isChecked = model.autoRecovery; ipv6.isChecked=model.ipv6Enabled
        excludedApps.text="Выбрать приложения · ${model.excludedApplications.size}"
        pushApple.isChecked=model.split.pushNotifications?.apple ?: false
        pushGoogle.isChecked=model.split.pushNotifications?.google ?: false
        policies.forEachIndexed { index, radio -> radio.isChecked = SplitTunnelSettings.MODES[index] == model.split.mode }
        modeDescription.text = SplitTunnelSettings.detail(model.split.mode)
        addressFields.visibility = if (model.split.mode == SplitTunnelSettings.DISABLED) View.GONE else View.VISIBLE
        if (newAddress.text.toString() != model.newAddress) newAddress.setText(model.newAddress)
        if (search.text.toString() != model.search) search.setText(model.search)
        count.text = "In list: ${model.split.activeEntries.size} · matches: ${model.matches.size}"
        addresses.submitList(model.displayedEntries)
        addressList.layoutParams = (addressList.layoutParams as LinearLayout.LayoutParams).apply {
            height = dp(if (model.expanded) 350 else (model.displayedEntries.size * 60).coerceAtMost(300))
        }
        expand.text = if (model.expanded) "Show first 5 addresses" else "Show all ${model.matches.size} addresses"
        expand.visibility = if (model.matches.size > 5) View.VISIBLE else View.GONE
        listMessage.text = model.message.ifBlank { if (model.matches.isEmpty()) "No addresses" else "" }
        transferMessage.text = model.message
        model.error?.let { message ->
            model.error = null
            AlertDialog.Builder(this).setTitle("Could not complete action").setMessage(message).setPositiveButton("OK", null).show()
        }
        if (model.requestExport) {
            model.requestExport = false
            createFile.launch("TnDtun-backup-${LocalDate.now()}.json")
        }
        if (model.pendingBackup != null && preview == null) showBackupPreview()
    }
    private fun showBackupPreview() {
        val backup = model.pendingBackup ?: return
        val summary = "Amnezia: ${backup.profiles.count { it.mode == "amnezia" }}\nTURN: ${backup.profiles.count { it.mode == "turn" }}\nDNS: ${backup.profiles.count { it.mode == "dns" }}\n" +
            "Bypass: ${backup.splitTunneling.bypassEntries.size}\nTunnel: ${backup.splitTunneling.tunnelEntries.size}\n\n" +
            "Current profiles and settings will be replaced. VPN will disconnect."
        preview = AlertDialog.Builder(this).setTitle("Import settings?").setMessage(summary)
            .setNegativeButton("Cancel") { _, _ -> model.pendingBackup = null }
            .setPositiveButton("Import") { _, _ -> model.restoreBackup(applicationContext) }
            .create().apply {
                setOnCancelListener { model.pendingBackup = null }
                setOnDismissListener { preview = null }
                show()
            }
    }
    private fun clipboard(): String? {
        val value = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (value.isNullOrBlank()) AlertDialog.Builder(this).setMessage("Clipboard is empty.").setPositiveButton("OK", null).show()
        return value?.takeIf { it.isNotBlank() }
    }
    private fun readFile(uri: Uri, limit: Int): String {
        val stream = contentResolver.openInputStream(uri) ?: error("Could not open file.")
        return stream.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "File is too large. Maximum: ${limit / 1024 / 1024} MB." }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }
    private fun showNetworks(entry: SplitTunnelEntry) {
        if (entry.networks.isEmpty()) return
        val content = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entry.networks.take(5))
        content.addView(ListView(this).apply { this.adapter = adapter }, LinearLayout.LayoutParams(-1, dp(300)))
        if (entry.networks.size > 5) {
            var expanded = false
            lateinit var toggle: TextView
            toggle = button("Show all ${entry.networks.size} networks") {
                expanded = !expanded; adapter.clear(); adapter.addAll(if (expanded) entry.networks else entry.networks.take(5))
                toggle.text = if (expanded) "Show first 5 networks" else "Show all ${entry.networks.size} networks"
            }
            content.addView(toggle)
        }
        AlertDialog.Builder(this).setTitle(entry.address).setView(content).setPositiveButton("Done", null).show()
    }
    private inner class AddressAdapter(val open: (SplitTunnelEntry) -> Unit, val remove: (SplitTunnelEntry) -> Unit) :
        ListAdapter<SplitTunnelEntry, AddressHolder>(object : DiffUtil.ItemCallback<SplitTunnelEntry>() {
            override fun areItemsTheSame(old: SplitTunnelEntry, new: SplitTunnelEntry) = old.id == new.id
            override fun areContentsTheSame(old: SplitTunnelEntry, new: SplitTunnelEntry) = old == new
        }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddressHolder {
            val row = row().apply { layoutParams = RecyclerView.LayoutParams(-1, -2); minimumHeight = dp(60) }
            val title = text("", 15).apply { maxLines = 2; setPadding(0, dp(10), dp(8), dp(10)) }
            row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            val delete = SimpleIconView(this@SimpleSettingsActivity, SimpleIconView.Kind.REMOVE, Color.rgb(210, 40, 40)).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12)); isFocusable = true
            }
            row.addView(delete, LinearLayout.LayoutParams(dp(48), dp(48)))
            return AddressHolder(row, title, delete)
        }
        override fun onBindViewHolder(holder: AddressHolder, position: Int) {
            val entry = getItem(position)
            holder.title.text = entry.address + if (entry.networks.isEmpty()) "" else "\nNetworks: ${entry.networks.size}"
            holder.title.setOnClickListener { open(entry) }
            holder.delete.contentDescription = "Delete ${entry.address}"
            holder.delete.isEnabled = !model.busy
            holder.delete.setOnClickListener { remove(entry) }
        }
    }
    private class AddressHolder(view: View, val title: TextView, val delete: View) : RecyclerView.ViewHolder(view)
}
