package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.AccountApi
import com.v2ray.ang.core.AccountSessionStore
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SubscriptionUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AccountActivity : BaseActivity() {
    private val sessions by lazy { AccountSessionStore(this) }
    private val api by lazy { AccountApi(sessions) }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var captchaLabel: TextView? = null
    private var account: JSONObject? = null
    private var registration = false
    private var captcha: JSONObject? = null
    private var busy = false
    private val ru get() = resources.configuration.locales[0].language == "ru"
    private fun label(en: String, russian: String) = if (ru) russian else en

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentViewWithToolbar(scroll, showHomeAsUp = true, title = getString(R.string.account_title))
        render()
        work {
            account = withContext(Dispatchers.IO) {
                if (sessions.load() != null) api.request("me").optJSONObject("client") else null
            }
            render()
        }
    }

    private fun render() {
        content.removeAllViews()
        captchaLabel = null
        fun text(value: String) = TextView(this).apply { text = value; content.addView(this) }
        status = text("")
        val current = account
        if (current != null) {
            text(label("Signed in as ", "Вы вошли как ") + current.optString("client_username").ifBlank { "KindlyVPN" })
            text(label("Refresh your account after a subscription changes. Imported profiles remain available after sign-out.",
                "Обновите аккаунт после изменения подписки. При выходе импортированные профили остаются в приложении."))
            button(label("Refresh account", "Обновить аккаунт")) {
                work { account = withContext(Dispatchers.IO) { api.request("me").optJSONObject("client") }; render() }
            }
            val subscriptions = current.optJSONArray("subscriptions")
            if (subscriptions != null) for (index in 0 until subscriptions.length()) {
                val sub = subscriptions.getJSONObject(index)
                val clientId = sub.getString("client_id")
                text("${index + 1}. ${sub.optString("plan").ifBlank { "VPN" }}")
                text(label("Device limit: ", "Лимит устройств: ") + sub.optInt("device_limit"))
                if (!sub.isNull("time_expires_at")) text(label("Time allowance until: ", "Пакет времени до: ") + sub.optString("time_expires_at"))
                text(label("Traffic package remaining: ", "Осталось в пакете трафика: ") +
                    String.format("%.2f GB", sub.optLong("volume_remaining_bytes").toDouble() / 1073741824.0))
                if (current.optString("selected_client_id") == clientId) text(label("Selected", "Выбрана"))
                else button(label("Use this subscription", "Использовать эту подписку")) {
                    work {
                        account = withContext(Dispatchers.IO) {
                            api.request("account/select", JSONObject().put("client_id", clientId)).getJSONObject("client")
                        }
                        render()
                    }
                }
            }
            val url = current.optString("subscription_url")
            if (url.isNotBlank()) button(label("Import my subscription", "Импортировать мою подписку")) {
                work {
                    val result = withContext(Dispatchers.IO) {
                        require(url.startsWith("https://")) { "Invalid subscription URL" }
                        val item = MmkvManager.decodeSubscription(AppConfig.KINDLYVPN_SUBSCRIPTION_ID) ?: SubscriptionItem()
                        item.remarks = "KindlyVPN"; item.url = url; item.enabled = true; item.autoUpdate = true
                        item.allowInsecureUrl = false
                        MmkvManager.encodeSubscription(AppConfig.KINDLYVPN_SUBSCRIPTION_ID, item)
                        SubscriptionUpdater.sync()
                        AngConfigManager.updateConfigViaSub(SubscriptionCache(AppConfig.KINDLYVPN_SUBSCRIPTION_ID, item))
                    }
                    SettingsChangeManager.makeSetupGroupTab()
                    status.text = if (result.successCount > 0) label("Subscription imported. Select a profile on the home screen.", "Подписка импортирована. Выберите профиль на главном экране.")
                        else label("Import failed. Your account is still signed in; try again on a working network.", "Импорт не удался. Вход сохранён; повторите в работающей сети.")
                }
            } else text(if (current.optBoolean("selection_required")) label("Choose a subscription above.", "Выберите подписку выше.")
                else label("No subscription is attached to this account yet.", "У этого аккаунта пока нет подписки."))
            button(label("Sign out", "Выйти")) {
                work { withContext(Dispatchers.IO) { api.request("logout", JSONObject()) }; account = null; render() }
            }
            return
        }
        text(label("Use the same username and password as the website. Registration does not require email. Keep your password safe.",
            "Используйте имя пользователя и пароль от сайта. Для регистрации email не нужен. Храните пароль надёжно."))
        text(label("For a Telegram account, add a username and password after signing into the website with Telegram. You can also import your subscription link without a password.",
            "Для аккаунта Telegram добавьте имя и пароль после входа с Telegram на сайте. Также можно импортировать ссылку подписки без пароля."))
        button(label("Import a subscription link", "Импортировать ссылку подписки")) {
            startActivity(Intent(this, SubSettingActivity::class.java))
        }
        val username = field(label("Username", "Имя пользователя"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val password = field(label("Password (8–256 characters)", "Пароль (8–256 символов)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val confirm = if (registration) field(label("Repeat password", "Повторите пароль"), password.inputType) else null
        val answer = if (registration) {
            captchaLabel = text(captcha?.optString("question") ?: label("Load a verification question", "Загрузите проверочный вопрос"))
            button(label("New verification question", "Новый проверочный вопрос")) { loadCaptcha() }
            field(label("Answer", "Ответ"), InputType.TYPE_CLASS_NUMBER)
        } else null
        button(if (registration) label("Register", "Зарегистрироваться") else label("Sign in", "Войти")) {
            val payload = JSONObject().put("username", username.text.toString().trim()).put("password", password.text.toString())
            if (registration) payload.put("password_confirm", confirm?.text.toString())
                .put("captcha_answer", answer?.text.toString()).put("captcha_token", captcha?.optString("token").orEmpty())
            work {
                account = withContext(Dispatchers.IO) { api.request(if (registration) "signup" else "login", payload).getJSONObject("client") }
                password.text.clear(); confirm?.text?.clear(); captcha = null
                render()
            }
        }
        button(if (registration) label("Already registered? Sign in", "Уже зарегистрированы? Войти") else label("Create an account", "Создать аккаунт")) {
            registration = !registration
            render()
            if (registration) loadCaptcha()
        }
    }

    private fun loadCaptcha() = work {
        captcha = withContext(Dispatchers.IO) { api.request("captcha") }
        captchaLabel?.text = captcha?.optString("question")
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type; isSingleLine = true; isSaveEnabled = false
        content.addView(this)
    }

    private fun button(title: String, action: () -> Unit) {
        content.addView(Button(this).apply { text = title; isEnabled = !busy; setOnClickListener { action() } })
    }

    private fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        for (i in 0 until content.childCount) content.getChildAt(i).isEnabled = false
        val waiting = label("Please wait…", "Подождите…")
        status.text = waiting
        lifecycleScope.launch {
            try { action(); if (status.text == waiting) status.text = "" }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = error.message ?: label("Account service is unavailable", "Сервис аккаунтов недоступен") }
            finally {
                busy = false
                for (i in 0 until content.childCount) content.getChildAt(i).isEnabled = true
            }
        }
    }
}
