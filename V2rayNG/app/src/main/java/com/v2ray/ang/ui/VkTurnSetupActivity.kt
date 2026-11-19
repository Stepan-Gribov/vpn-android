package com.v2ray.ang.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.MaxAuthClient
import com.v2ray.ang.core.MaxTokenStore
import com.v2ray.ang.core.VkTurnManager
import com.v2ray.ang.databinding.ActivityVkTurnSetupBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VkTurnSetupActivity : BaseActivity() {
    private val binding by lazy { ActivityVkTurnSetupBinding.inflate(layoutInflater) }
    private var maxVerificationToken: String? = null
    private var authBusy = false

    private enum class StatusTone { NEUTRAL, SUCCESS, ERROR }

    companion object {
        const val EXTRA_OPEN_SUBSCRIPTION_ID = "open_subscription_id"
        const val EXTRA_SELECTED_TURN_GUID = "selected_turn_guid"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.vk_turn_setup_title),
        )

        binding.etVkCallLink.setText(VkTurnManager.getCallLink())
        binding.etMaxPhone.setText(VkTurnManager.getMaxPhone())
        binding.etMaxPeerId.setText(VkTurnManager.getMaxPeerID())
        binding.etPeer.setText(VkTurnManager.getPeer())
        binding.etPeer.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                VkTurnManager.getRememberedPeers(),
            ),
        )
        binding.etPeer.threshold = 0
        binding.etPeer.setOnClickListener { binding.etPeer.showDropDown() }
        binding.switchAutoCaptcha.isChecked = VkTurnManager.isAutoCaptchaEnabled()
        when (VkTurnManager.getProvider()) {
            VkTurnManager.Provider.VK -> binding.rbVkTurn.isChecked = true
            VkTurnManager.Provider.MAX -> binding.rbMaxTurn.isChecked = true
        }
        when (VkTurnManager.getSessionCount()) {
            VkTurnManager.MAXIMUM_SESSION_COUNT -> binding.rbMaximumSessions.isChecked = true
            else -> binding.rbDefaultSessions.isChecked = true
        }
        updatePerformanceHelp()
        binding.tvHelperDetails.text = getString(
            R.string.vk_turn_helper_details,
            VkTurnManager.HELPER_VERSION,
            AppConfig.VK_TURN_LOCAL_ADDRESS,
            AppConfig.VK_TURN_LOCAL_PORT,
        )

        binding.btnSave.setOnClickListener { save() }
        binding.btnAddPeer.setOnClickListener { addPeerAddress() }
        binding.rgSessionCount.setOnCheckedChangeListener { _, _ -> updatePerformanceHelp() }
        binding.rgTurnProvider.setOnCheckedChangeListener { _, _ -> updateProviderUI() }
        binding.btnMaxRequestCode.setOnClickListener { requestMaxCode() }
        binding.btnMaxLogin.setOnClickListener { confirmMaxLogin() }
        binding.btnMaxLogout.setOnClickListener { logoutMax() }
        binding.tilVkCallLink.setEndIconOnClickListener { pasteInto(binding.etVkCallLink) }
        binding.tilPeer.setEndIconOnClickListener { binding.etPeer.showDropDown() }
        binding.tilMaxPeerId.setEndIconOnClickListener { pasteInto(binding.etMaxPeerId) }
        updateProviderUI()
        updateMaxAuthStatus()
    }

    private fun pasteInto(field: EditText) {
        val clipboard = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (clipboard.isEmpty()) {
            toast(R.string.toast_clipboard_empty)
            return
        }
        field.setText(clipboard)
        field.setSelection(field.text.length)
    }

    private fun save() {
        val provider = selectedProvider()
        val peer = VkTurnManager.normalizePeer(binding.etPeer.text.toString())
        if (peer == null) {
            binding.etPeer.error = getString(R.string.vk_turn_error_peer_invalid)
            return
        }
        VkTurnManager.rememberPeerAddress(peer)

        val callLink = if (provider == VkTurnManager.Provider.VK) {
            VkTurnManager.normalizeVkCallLink(binding.etVkCallLink.text.toString()).also {
                if (it == null) binding.etVkCallLink.error = getString(R.string.vk_turn_link_invalid)
            } ?: return
        } else {
            binding.etVkCallLink.text?.toString()?.trim().orEmpty()
        }
        val maxPeerID = MaxAuthClient.normalizePeerID(binding.etMaxPeerId.text.toString())
        if (provider == VkTurnManager.Provider.MAX) {
            if (MaxTokenStore.load().isNullOrBlank()) {
                showStatus(getString(R.string.max_turn_login_required), StatusTone.ERROR)
                return
            }
            if (maxPeerID == null) {
                binding.etMaxPeerId.error = getString(R.string.max_turn_peer_invalid)
                return
            }
            if (maxPeerID == VkTurnManager.getMaxExternalID().takeIf { it.isNotBlank() }) {
                binding.etMaxPeerId.error = getString(R.string.max_turn_peer_is_self)
                return
            }
        }
        VkTurnManager.saveSettings(
            callLink = callLink,
            peer = peer,
            sessionCount = selectedSessionCount(),
            provider = provider,
            maxPeerID = maxPeerID.orEmpty(),
            maxPhone = MaxAuthClient.normalizePhone(binding.etMaxPhone.text.toString())
                ?: VkTurnManager.getMaxPhone(),
            autoCaptcha = binding.switchAutoCaptcha.isChecked,
        )
        binding.etPeer.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                VkTurnManager.getRememberedPeers(),
            ),
        )
        SettingsChangeManager.makeRestartService()
        showStatus(getString(R.string.vk_turn_settings_saved), StatusTone.SUCCESS)
        setResult(RESULT_OK)
    }

    private fun addPeerAddress() {
        val peer = VkTurnManager.normalizePeer(binding.etPeer.text.toString())
        if (peer == null) {
            binding.etPeer.error = getString(R.string.vk_turn_error_peer_invalid)
            return
        }
        binding.etPeer.error = null
        VkTurnManager.rememberPeerAddress(peer)
        binding.etPeer.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                VkTurnManager.getRememberedPeers(),
            ),
        )
        showStatus(getString(R.string.vk_turn_peer_added), StatusTone.SUCCESS)
    }

    private fun showStatus(status: CharSequence, tone: StatusTone = StatusTone.NEUTRAL) {
        binding.tvStatus.text = status
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (tone) {
                    StatusTone.SUCCESS -> R.color.colorPing
                    StatusTone.ERROR -> R.color.colorPingRed
                    StatusTone.NEUTRAL -> R.color.md_theme_onSurface
                },
            ),
        )
        binding.tvStatus.visibility = View.VISIBLE
    }

    private fun selectedSessionCount(): Int =
        if (binding.rbMaximumSessions.isChecked) {
            VkTurnManager.MAXIMUM_SESSION_COUNT
        } else {
            VkTurnManager.DEFAULT_SESSION_COUNT
        }

    private fun updatePerformanceHelp() {
        binding.tvSessionWarning.setText(
            if (binding.rbMaximumSessions.isChecked) {
                R.string.vk_turn_performance_maximum_warning
            } else {
                R.string.vk_turn_performance_default_help
            },
        )
    }

    private fun selectedProvider(): VkTurnManager.Provider =
        if (binding.rbMaxTurn.isChecked) VkTurnManager.Provider.MAX else VkTurnManager.Provider.VK

    private fun updateProviderUI() {
        val isMax = selectedProvider() == VkTurnManager.Provider.MAX
        binding.tilVkCallLink.visibility = if (isMax) View.GONE else View.VISIBLE
        binding.tvVkCallLinkHelp.visibility = if (isMax) View.GONE else View.VISIBLE
        binding.switchAutoCaptcha.visibility = View.GONE
        binding.tvCaptchaModeHelp.visibility = if (isMax) View.GONE else View.VISIBLE
        binding.groupMax.visibility = if (isMax) View.VISIBLE else View.GONE
        binding.tvProviderHelp.setText(
            if (isMax) R.string.turn_provider_max_help else R.string.turn_provider_vk_help,
        )
    }

    private fun updateMaxAuthStatus(message: CharSequence? = null) {
        val phone = MaxAuthClient.normalizePhone(binding.etMaxPhone.text.toString())
            ?: VkTurnManager.getMaxPhone()
        val loggedIn = !MaxTokenStore.load().isNullOrBlank()
        binding.tvMaxAuthStatus.text = message ?: if (loggedIn) {
            getString(
                R.string.max_turn_logged_in,
                maskPhone(phone),
                VkTurnManager.getMaxExternalID().ifBlank { getString(R.string.max_turn_id_unknown) },
            )
        } else {
            getString(R.string.max_turn_logged_out)
        }
        binding.btnMaxLogout.isEnabled = loggedIn && !authBusy
    }

    private fun requestMaxCode() {
        val phone = MaxAuthClient.normalizePhone(binding.etMaxPhone.text.toString())
        if (phone == null) {
            binding.etMaxPhone.error = getString(R.string.max_turn_phone_invalid)
            return
        }
        binding.etMaxPhone.error = null
        setAuthBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { MaxAuthClient.requestCode(phone) }
            withContext(Dispatchers.Main) {
                setAuthBusy(false)
                result.onSuccess { codeRequest ->
                    maxVerificationToken = codeRequest.verificationToken
                    binding.groupMaxCode.visibility = View.VISIBLE
                    binding.etMaxCode.requestFocus()
                    updateMaxAuthStatus(
                        getString(R.string.max_turn_code_sent, codeRequest.codeLength),
                    )
                }.onFailure { error ->
                    updateMaxAuthStatus(
                        getString(
                            R.string.max_turn_login_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    private fun confirmMaxLogin() {
        val verificationToken = maxVerificationToken
        val code = binding.etMaxCode.text?.toString()?.trim().orEmpty()
        if (verificationToken.isNullOrBlank() || code.length !in 4..8 || !code.all(Char::isDigit)) {
            binding.etMaxCode.error = getString(R.string.max_turn_code_invalid)
            return
        }
        binding.etMaxCode.error = null
        val password = binding.etMaxPassword.text?.toString()?.takeIf { it.isNotBlank() }
        setAuthBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val authResult = MaxAuthClient.confirmCode(verificationToken, code, password)
                if (authResult is MaxAuthClient.AuthResult.LoggedIn) {
                    MaxTokenStore.save(authResult.token)
                    val externalID = runCatching {
                        MaxAuthClient.resolveExternalUserID(authResult.token)
                    }.getOrNull()
                    MmkvManager.encodeSettings(
                        AppConfig.PREF_MAX_AUTH_EXTERNAL_ID,
                        externalID.orEmpty(),
                    )
                    authResult.copy(externalUserID = externalID)
                } else {
                    authResult
                }
            }
            withContext(Dispatchers.Main) {
                setAuthBusy(false)
                result.onSuccess { authResult ->
                    when (authResult) {
                        is MaxAuthClient.AuthResult.LoggedIn -> {
                            maxVerificationToken = null
                            binding.etMaxCode.text?.clear()
                            binding.etMaxPassword.text?.clear()
                            binding.groupMaxCode.visibility = View.GONE
                            updateMaxAuthStatus()
                        }

                        MaxAuthClient.AuthResult.RequiresPassword -> {
                            binding.etMaxPassword.error =
                                getString(R.string.max_turn_password_required)
                            updateMaxAuthStatus(getString(R.string.max_turn_password_required))
                        }
                    }
                }.onFailure { error ->
                    updateMaxAuthStatus(
                        getString(
                            R.string.max_turn_login_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    private fun logoutMax() {
        MaxTokenStore.clear()
        MmkvManager.encodeSettings(AppConfig.PREF_MAX_AUTH_EXTERNAL_ID, "")
        maxVerificationToken = null
        binding.etMaxCode.text?.clear()
        binding.etMaxPassword.text?.clear()
        binding.groupMaxCode.visibility = View.GONE
        updateMaxAuthStatus()
    }

    private fun setAuthBusy(busy: Boolean) {
        authBusy = busy
        updateEnabledState()
    }

    private fun updateEnabledState() {
        val enabled = !authBusy
        binding.btnSave.isEnabled = enabled
        binding.rbDefaultSessions.isEnabled = enabled
        binding.rbMaximumSessions.isEnabled = enabled
        binding.rbVkTurn.isEnabled = enabled
        binding.rbMaxTurn.isEnabled = enabled
        binding.switchAutoCaptcha.isEnabled = enabled
        binding.btnMaxRequestCode.isEnabled = enabled
        binding.btnMaxLogin.isEnabled = enabled
        binding.btnMaxLogout.isEnabled = enabled && !MaxTokenStore.load().isNullOrBlank()
    }

    private fun maskPhone(phone: String): String = when {
        phone.length <= 6 -> phone.ifBlank { "MAX" }
        else -> "${phone.take(2)}•••${phone.takeLast(4)}"
    }
}
