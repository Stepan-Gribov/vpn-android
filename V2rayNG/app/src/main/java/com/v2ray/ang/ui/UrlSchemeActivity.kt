package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityLogcatBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseActivity() {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        try {
            when {
                intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                    parseUri(intent.getStringExtra(Intent.EXTRA_TEXT), null)

                intent.action == Intent.ACTION_VIEW &&
                    intent.data?.host in setOf("install-config", "install-sub") -> {
                    val uri: Uri? = intent.data
                    parseUri(uri?.getQueryParameter("url"), uri?.fragment)
                }

                else -> {
                    toastError(R.string.toast_failure)
                    openMain()
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
            toastError(R.string.toast_failure)
            openMain()
        }
    }

    private fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            openMain()
            return
        }
        var decodedUrl = URLDecoder.decode(uriString, "UTF-8")
        val uri = Uri.parse(decodedUrl)
        if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
            decodedUrl += "#${fragment}"
        }
        lifecycleScope.launch {
            try {
                val (count, countSub) = withContext(Dispatchers.IO) {
                    AngConfigManager.importBatchConfig(decodedUrl, "", true)
                }
                if (count + countSub > 0) {
                    toast(R.string.import_subscription_success)
                } else {
                    toast(R.string.import_subscription_failure)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to import shared configuration", e)
                toastError(R.string.toast_failure)
            } finally {
                openMain()
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
