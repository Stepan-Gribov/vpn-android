package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.AccountApi
import com.v2ray.ang.core.AccountSessionStore
import com.v2ray.ang.core.PurchaseFlow
import com.v2ray.ang.databinding.ActivityPurchaseBinding
import com.v2ray.ang.dto.PurchaseCatalog
import com.v2ray.ang.dto.PurchasePlan
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Native plan catalogue with an in-app handoff to the existing SBP/crypto checkout. */
class PurchaseActivity : BaseActivity() {
    private val binding by lazy { ActivityPurchaseBinding.inflate(layoutInflater) }
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val planAdapter by lazy {
        PurchasePlanAdapter(
            useEnglishLabels = resources.configuration.locales[0].language != "ru",
            onPlanSelected = ::createCheckout,
        )
    }
    private var checkoutInFlight = false
    private var subscriptionImportInFlight = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.purchase_title),
        )

        binding.rvPlans.layoutManager = LinearLayoutManager(this)
        binding.rvPlans.adapter = planAdapter
        binding.btnRetry.setOnClickListener { loadCatalog() }
        binding.btnImportSubscription.setOnClickListener {
            startActivity(Intent(this, VkTurnSetupActivity::class.java))
        }
        configurePaymentWebView()
        configureBackNavigation()
        updateSubscriptionState()
        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        updateSubscriptionState()
    }

    private fun updateSubscriptionState() {
        val token = currentSubscriptionToken()
        if (AccountSessionStore(this).load() != null) {
            binding.tvSubscriptionState.setText(R.string.purchase_account_selected)
            binding.btnImportSubscription.visibility = View.GONE
            return
        }
        binding.tvSubscriptionState.setText(
            if (token == null) R.string.purchase_subscription_missing
            else R.string.purchase_subscription_ready,
        )
        binding.btnImportSubscription.visibility = if (token == null) View.VISIBLE else View.GONE
    }

    private fun loadCatalog() {
        binding.catalogProgress.visibility = View.VISIBLE
        binding.tvCatalogStatus.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(AppConfig.KINDLYVPN_PLAN_CATALOG_URL)
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache")
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            error("Plan catalogue HTTP ${response.code}")
                        }
                        val body = response.body?.string().orEmpty()
                        gson.fromJson(body, PurchaseCatalog::class.java)
                            ?: error("Empty plan catalogue")
                    }
                }
            }
            binding.catalogProgress.visibility = View.GONE
            result.onSuccess { catalog ->
                val plans = catalog.plans.filter { it.id.isNotBlank() && it.priceRub > 0 }
                if (plans.isEmpty()) {
                    showCatalogError(getString(R.string.purchase_catalog_empty))
                } else {
                    planAdapter.submitPlans(plans)
                    binding.rvPlans.visibility = View.VISIBLE
                }
            }.onFailure { error ->
                LogUtil.e(AppConfig.TAG, "Unable to load KindlyVPN plan catalogue", error)
                showCatalogError(getString(R.string.purchase_catalog_failed))
            }
        }
    }

    private fun showCatalogError(message: String) {
        binding.rvPlans.visibility = View.GONE
        binding.tvCatalogStatus.text = message
        binding.tvCatalogStatus.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.VISIBLE
    }

    private fun createCheckout(plan: PurchasePlan) {
        if (checkoutInFlight) return
        val subscriptionToken = currentSubscriptionToken()

        checkoutInFlight = true
        planAdapter.setBusy(plan.id)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val sessions = AccountSessionStore(this@PurchaseActivity)
                    val accountSession = sessions.load()
                    if (accountSession != null) {
                        check(AccountApi(sessions).request("me").optBoolean("authenticated")) {
                            getString(R.string.purchase_account_expired)
                        }
                    }
                    val payload = JSONObject().put("plan_id", plan.id)
                    if (accountSession == null) subscriptionToken?.let { payload.put("subscription_token", it) }
                    val request = Request.Builder()
                        .apply { accountSession?.let { header("Cookie", "kindlyvpn_session=$it") } }
                        .url(AppConfig.KINDLYVPN_CHECKOUT_CREATE_URL)
                        .header("Accept", "application/json")
                        .post(
                            payload.toString().toRequestBody(
                                "application/json; charset=utf-8".toMediaType(),
                            ),
                        )
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val apiMessage = runCatching {
                                JSONObject(responseBody)
                                    .optJSONObject("error")
                                    ?.optString("message")
                            }.getOrNull()
                            error(apiMessage?.takeIf(String::isNotBlank) ?: "HTTP ${response.code}")
                        }
                        JSONObject(responseBody).optString("reference_id")
                            .takeIf(String::isNotBlank)
                            ?: error("Checkout reference missing")
                    }
                }
            }
            checkoutInFlight = false
            planAdapter.setBusy(null)
            result.onSuccess(::showPayment)
                .onFailure { error ->
                    LogUtil.e(AppConfig.TAG, "Unable to create subscription checkout", error)
                    val message = if (error.message == "Invalid subscription") {
                        getString(R.string.purchase_subscription_invalid)
                    } else if (error.message == getString(R.string.purchase_account_expired)) {
                        getString(R.string.purchase_account_expired)
                    } else {
                        getString(R.string.purchase_checkout_failed)
                    }
                    Toast.makeText(this@PurchaseActivity, message, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showPayment(referenceId: String) {
        val lang = if (resources.configuration.locales[0].language == "ru") "ru" else "en"
        val url = "${AppConfig.KINDLYVPN_PUBLIC_ORIGIN}/pay/$referenceId?lang=$lang"
        binding.catalogContainer.visibility = View.GONE
        binding.paymentContainer.visibility = View.VISIBLE
        supportActionBar?.title = getString(R.string.purchase_payment_title)
        binding.webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurePaymentWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, false)
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString KindlyVPN-Android"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.paymentProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.paymentProgress.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return true
                if (PurchaseFlow.isSubscriptionUrl(url)) {
                    saveReturnedSubscription(url)
                    return true
                }
                if (PurchaseFlow.isCatalogUrl(url)) {
                    showCatalog()
                    return true
                }
                if (PurchaseFlow.isTrustedWebUrl(url)) return false
                openExternalUrl(url)
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    binding.paymentProgress.visibility = View.GONE
                    Toast.makeText(
                        this@PurchaseActivity,
                        R.string.purchase_payment_load_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun saveReturnedSubscription(url: String) {
        if (subscriptionImportInFlight || PurchaseFlow.extractSubscriptionToken(url) == null) return
        subscriptionImportInFlight = true
        val item = MmkvManager.decodeSubscription(AppConfig.KINDLYVPN_SUBSCRIPTION_ID)
            ?: SubscriptionItem()
        item.remarks = getString(R.string.app_name)
        item.url = url
        item.enabled = true
        item.autoUpdate = true
        item.allowInsecureUrl = false
        MmkvManager.encodeSubscription(AppConfig.KINDLYVPN_SUBSCRIPTION_ID, item)
        SettingsChangeManager.makeSetupGroupTab()
        SubscriptionUpdater.sync()
        binding.paymentProgress.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                AngConfigManager.updateConfigViaSub(
                    SubscriptionCache(AppConfig.KINDLYVPN_SUBSCRIPTION_ID, item),
                )
            }
            withContext(Dispatchers.Main) {
                subscriptionImportInFlight = false
                binding.paymentProgress.visibility = View.GONE
                Toast.makeText(
                    this@PurchaseActivity,
                    if ((result.getOrNull()?.successCount ?: 0) > 0) {
                        R.string.purchase_payment_received
                    } else {
                        R.string.purchase_payment_received_refresh_pending
                    },
                    Toast.LENGTH_LONG,
                ).show()
                setResult(
                    RESULT_OK,
                    Intent().putExtra(
                        VkTurnSetupActivity.EXTRA_OPEN_SUBSCRIPTION_ID,
                        AppConfig.KINDLYVPN_SUBSCRIPTION_ID,
                    ),
                )
                finish()
            }
        }
    }

    private fun openExternalUrl(url: String) {
        if (PurchaseFlow.isBlockedExternalScheme(url)) return
        try {
            val externalIntent = if (url.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            }
            startActivity(externalIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.purchase_external_app_missing, Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Unable to open external payment URL", error)
            Toast.makeText(this, R.string.purchase_external_app_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.paymentContainer.visibility == View.VISIBLE && binding.webView.canGoBack() ->
                        binding.webView.goBack()
                    binding.paymentContainer.visibility == View.VISIBLE -> showCatalog()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun showCatalog() {
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.paymentContainer.visibility = View.GONE
        binding.catalogContainer.visibility = View.VISIBLE
        supportActionBar?.title = getString(R.string.purchase_title)
        updateSubscriptionState()
    }

    private fun currentSubscriptionToken(): String? {
        val url = MmkvManager.decodeSubscription(AppConfig.KINDLYVPN_SUBSCRIPTION_ID)?.url
        return PurchaseFlow.extractSubscriptionToken(url)
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.removeAllViews()
        binding.webView.destroy()
        httpClient.dispatcher.cancelAll()
        super.onDestroy()
    }
}
