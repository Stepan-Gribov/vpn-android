package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.VkTurnManager
import com.v2ray.ang.databinding.ActivityVkCaptchaBinding

/** Displays the interactive provider challenge for the user to complete. */
class VkCaptchaActivity : BaseActivity() {
    private val binding by lazy { ActivityVkCaptchaBinding.inflate(layoutInflater) }
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_VK_TURN_CAPTCHA_COMPLETED -> {
                    binding.progress.visibility = View.VISIBLE
                    binding.tvStatus.text = getString(R.string.vk_turn_captcha_submitted)
                }

                AppConfig.MSG_VK_TURN_READY -> {
                    binding.progress.visibility = View.GONE
                    binding.tvStatus.text = getString(R.string.vk_turn_ready)
                    binding.webView.postDelayed({
                        setResult(RESULT_OK)
                        finish()
                    }, 1_200)
                }

                AppConfig.MSG_VK_TURN_FAILURE -> {
                    binding.progress.visibility = View.GONE
                    binding.tvStatus.text = intent.getStringExtra("content")
                        ?: getString(R.string.vk_turn_captcha_failed)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VkTurnManager.isAutoCaptchaEnabled()) {
            finish()
            return
        }
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.vk_turn_captcha_title),
        )

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progress.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.vk_turn_captcha_loading)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                binding.tvStatus.text = getString(R.string.vk_turn_captcha_help)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = request?.url?.let { !isAllowedCaptchaUri(it) } ?: true

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    binding.progress.visibility = View.GONE
                    binding.tvStatus.text = getString(R.string.vk_turn_captcha_failed)
                }
            }
        }

        binding.btnReload.setOnClickListener { loadCaptcha() }
        loadCaptcha()
    }

    private fun loadCaptcha() {
        val requested = intent.getStringExtra(EXTRA_CAPTCHA_URL).orEmpty()
        val captchaUrl = requested.takeIf {
            runCatching { isAllowedCaptchaUri(Uri.parse(it)) }.getOrDefault(false)
        } ?: VkTurnManager.CAPTCHA_URL
        binding.webView.loadUrl(captchaUrl)
    }

    private fun isAllowedCaptchaUri(uri: Uri): Boolean =
        uri.scheme.equals("http", ignoreCase = true) &&
            uri.port == 8765 &&
            uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1")

    override fun onResume() {
        super.onResume()
        if (VkTurnManager.isAutoCaptchaEnabled()) finish()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
            binding.webView.stopLoading()
            binding.webView.loadUrl("about:blank")
            binding.webView.removeAllViews()
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CAPTCHA_URL = "captcha_url"
    }
}
