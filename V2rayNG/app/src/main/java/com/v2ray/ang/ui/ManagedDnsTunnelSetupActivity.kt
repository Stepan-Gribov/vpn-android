package com.v2ray.ang.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.ManagedDnsTunnelManager
import com.v2ray.ang.core.ManagedDnsTunnelSettings
import com.v2ray.ang.core.ManagedDnsTunnelTransport
import com.v2ray.ang.core.ManagedDnsTunnelType
import com.v2ray.ang.databinding.ActivityManagedDnsTunnelSetupBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager

class ManagedDnsTunnelSetupActivity : BaseActivity() {
    private val binding by lazy { ActivityManagedDnsTunnelSetupBinding.inflate(layoutInflater) }
    private lateinit var resolverAdapter: ArrayAdapter<String>

    companion object {
        const val EXTRA_OPEN_SUBSCRIPTION_ID = "dns_tunnel_open_subscription_id"
        const val EXTRA_SELECTED_DNS_GUID = "selected_dns_tunnel_guid"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.managed_dns_tunnel_setup_title),
        )

        val settings = ManagedDnsTunnelManager.getSettings()
        fun selectSessions() {
            val counts = ManagedDnsTunnelManager.SESSION_COUNTS
            val selected = counts.indexOf(binding.etSessions.text.toString().toIntOrNull())
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.managed_dns_tunnel_sessions)
                .setSingleChoiceItems(counts.map(Int::toString).toTypedArray(), selected) { dialog, which ->
                    binding.etSessions.setText(counts[which].toString(), false)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.etSessions.setOnClickListener { selectSessions() }
        binding.tilSessions.setEndIconOnClickListener { selectSessions() }
        binding.etSessions.setText(settings.sessionCount.toString(), false)
        binding.etName.setText(settings.name)
        binding.etDomain.setText(settings.domain)
        refreshResolverList(settings.resolver)
        binding.etPublicKey.setText(settings.publicKey)
        binding.etKeepalive.setText(getString(R.string.managed_dns_tunnel_keepalive_value, settings.keepAliveInterval))
        binding.switchGso.isChecked = settings.gsoEnabled
        binding.switchAutoCaptureResolver.isChecked = settings.automaticResolverCapture
        binding.etSshUsername.setText(settings.sshUsername)
        binding.etSshPassword.setText(settings.sshPassword)
        binding.etSshPrivateKey.setText(settings.sshPrivateKey)
        binding.toggleTransport.check(
            if (settings.transport == ManagedDnsTunnelTransport.SLIPSTREAM) {
                R.id.btn_transport_slipstream
            } else R.id.btn_transport_dnstt,
        )
        binding.toggleTunnelType.check(
            if (settings.tunnelType == ManagedDnsTunnelType.SSH) R.id.btn_type_ssh else R.id.btn_type_socks5,
        )
        binding.toggleCongestion.check(
            if (settings.congestionControl == "bbr") R.id.btn_congestion_bbr else R.id.btn_congestion_dcubic,
        )
        binding.tvHelperDetails.text = getString(
            R.string.managed_dns_tunnel_helper_details,
            ManagedDnsTunnelManager.HELPER_VERSION,
            AppConfig.DNS_TUNNEL_LOCAL_ADDRESS,
            AppConfig.DNS_TUNNEL_LOCAL_PORT,
        )
        binding.tilDomain.setEndIconOnClickListener { pasteInto(binding.etDomain) }
        binding.tilPublicKey.setEndIconOnClickListener { pasteInto(binding.etPublicKey) }
        binding.tilSshPrivateKey.setEndIconOnClickListener { pasteInto(binding.etSshPrivateKey) }
        binding.toggleTransport.addOnButtonCheckedListener { _, _, checked ->
            if (checked) updateConditionalFields()
        }
        binding.toggleTunnelType.addOnButtonCheckedListener { _, _, checked ->
            if (checked) updateConditionalFields()
        }
        binding.btnCaptureResolver.setOnClickListener { captureCurrentResolver() }
        binding.btnAddResolver.setOnClickListener { addTypedResolver() }
        binding.btnSaveSelect.setOnClickListener { saveAndSelect() }
        binding.btnClear.setOnClickListener {
            binding.etSessions.setText(ManagedDnsTunnelManager.DEFAULT_SESSION_COUNT.toString(), false)
            binding.etName.setText(R.string.managed_dns_tunnel_default_name)
            binding.etDomain.text?.clear()
            refreshResolverList(ManagedDnsTunnelManager.DEFAULT_RESOLVER)
            binding.etPublicKey.text?.clear()
            binding.etSshUsername.text?.clear()
            binding.etSshPassword.text?.clear()
            binding.etSshPrivateKey.text?.clear()
            binding.etKeepalive.setText(
                getString(
                    R.string.managed_dns_tunnel_keepalive_value,
                    ManagedDnsTunnelManager.DEFAULT_KEEPALIVE_MILLIS,
                ),
            )
            binding.switchGso.isChecked = false
            binding.switchAutoCaptureResolver.isChecked = false
            binding.tvResolverCaptureStatus.text = ""
            binding.toggleTransport.check(R.id.btn_transport_dnstt)
            binding.toggleTunnelType.check(R.id.btn_type_socks5)
            binding.toggleCongestion.check(R.id.btn_congestion_dcubic)
        }
        updateConditionalFields()
    }

    private fun saveAndSelect() {
        clearErrors()
        val transport = if (binding.toggleTransport.checkedButtonId == R.id.btn_transport_slipstream) {
            ManagedDnsTunnelTransport.SLIPSTREAM
        } else ManagedDnsTunnelTransport.DNSTT
        val tunnelType = if (binding.toggleTunnelType.checkedButtonId == R.id.btn_type_ssh) {
            ManagedDnsTunnelType.SSH
        } else ManagedDnsTunnelType.SOCKS5
        val domain = ManagedDnsTunnelManager.normalizeDomain(binding.etDomain.text?.toString().orEmpty())
        if (domain == null) {
            binding.etDomain.error = getString(R.string.managed_dns_tunnel_invalid_domain)
            return
        }
        val resolver = ManagedDnsTunnelManager.normalizeResolver(binding.etResolver.text?.toString().orEmpty(), transport)
        if (resolver == null) {
            binding.etResolver.error = getString(R.string.managed_dns_tunnel_invalid_resolver)
            return
        }
        val publicKey = binding.etPublicKey.text?.toString().orEmpty().trim()
        if (transport == ManagedDnsTunnelTransport.DNSTT &&
            ManagedDnsTunnelManager.normalizePublicKey(publicKey) == null
        ) {
            binding.etPublicKey.error = getString(R.string.managed_dns_tunnel_invalid_public_key)
            return
        }
        val keepAlive = binding.etKeepalive.text?.toString()?.toIntOrNull()
            ?: ManagedDnsTunnelManager.DEFAULT_KEEPALIVE_MILLIS
        if (transport == ManagedDnsTunnelTransport.SLIPSTREAM && keepAlive !in 50..60_000) {
            binding.etKeepalive.error = getString(R.string.managed_dns_tunnel_invalid_keepalive)
            return
        }
        val sshUsername = binding.etSshUsername.text?.toString().orEmpty().trim()
        val sshPassword = binding.etSshPassword.text?.toString().orEmpty()
        val sshPrivateKey = binding.etSshPrivateKey.text?.toString().orEmpty().trim()
        if (tunnelType == ManagedDnsTunnelType.SSH && sshUsername.isBlank()) {
            binding.etSshUsername.error = getString(R.string.managed_dns_tunnel_invalid_ssh_username)
            return
        }
        if (tunnelType == ManagedDnsTunnelType.SSH && sshPassword.isBlank() && sshPrivateKey.isBlank()) {
            binding.etSshPassword.error = getString(R.string.managed_dns_tunnel_invalid_ssh_credential)
            return
        }

        ManagedDnsTunnelManager.saveSettings(
            ManagedDnsTunnelSettings(
                name = binding.etName.text?.toString().orEmpty().trim().ifBlank {
                    getString(R.string.managed_dns_tunnel_default_name)
                },
                transport = transport,
                tunnelType = tunnelType,
                domain = domain,
                resolver = resolver,
                publicKey = publicKey,
                congestionControl = if (
                    binding.toggleCongestion.checkedButtonId == R.id.btn_congestion_bbr
                ) "bbr" else "dcubic",
                keepAliveInterval = keepAlive.coerceIn(50, 60_000),
                gsoEnabled = binding.switchGso.isChecked,
                automaticResolverCapture = binding.switchAutoCaptureResolver.isChecked,
                sshUsername = sshUsername,
                sshPassword = sshPassword,
                sshPrivateKey = sshPrivateKey,
                sessionCount = ManagedDnsTunnelManager.normalizeSessionCount(
                    binding.etSessions.text.toString().toIntOrNull() ?: ManagedDnsTunnelManager.DEFAULT_SESSION_COUNT,
                ),
            ),
        )
        val preferredSubscriptionId = MmkvManager.decodeSettingsString(
            AppConfig.CACHE_SUBSCRIPTION_ID,
            "",
        )
        val (guid, subscriptionId) = ManagedDnsTunnelManager.upsertManagedProfile(preferredSubscriptionId)
        MmkvManager.setSelectServer(guid)
        SettingsChangeManager.makeRestartService()
        SettingsChangeManager.makeSetupGroupTab()
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_SELECTED_DNS_GUID, guid)
                .putExtra(EXTRA_OPEN_SUBSCRIPTION_ID, subscriptionId),
        )
        toast(R.string.managed_dns_tunnel_profile_added_selected)
        finish()
    }

    private fun captureCurrentResolver() {
        val captured = ManagedDnsTunnelManager.captureAndApplyCurrentResolver(this)
        if (captured == null) {
            binding.tvResolverCaptureStatus.setText(R.string.managed_dns_tunnel_capture_unavailable)
            toast(R.string.managed_dns_tunnel_capture_unavailable)
            return
        }
        refreshResolverList(captured.activeResolver)
        binding.tvResolverCaptureStatus.text = getString(
            R.string.managed_dns_tunnel_capture_success,
            captured.activeResolver,
            captured.discoveredResolvers.size,
        )
    }

    private fun addTypedResolver() {
        val resolver = ManagedDnsTunnelManager.addResolver(binding.etResolver.text?.toString().orEmpty())
        if (resolver == null) {
            binding.etResolver.error = getString(R.string.managed_dns_tunnel_invalid_resolver)
            return
        }
        binding.etResolver.error = null
        refreshResolverList(resolver)
        binding.tvResolverCaptureStatus.setText(R.string.managed_dns_tunnel_resolver_saved)
    }

    private fun refreshResolverList(selectedResolver: String) {
        resolverAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            ManagedDnsTunnelManager.getResolverList(),
        )
        binding.etResolver.setAdapter(resolverAdapter)
        binding.etResolver.setText(selectedResolver, false)
    }

    private fun updateConditionalFields() {
        val slipstream = binding.toggleTransport.checkedButtonId == R.id.btn_transport_slipstream
        val ssh = binding.toggleTunnelType.checkedButtonId == R.id.btn_type_ssh
        binding.tilSessions.visibility = if (slipstream) View.GONE else View.VISIBLE
        binding.tilPublicKey.visibility = if (slipstream) View.GONE else View.VISIBLE
        binding.layoutSlipstreamOptions.visibility = if (slipstream) View.VISIBLE else View.GONE
        binding.layoutSshOptions.visibility = if (ssh) View.VISIBLE else View.GONE
    }

    private fun clearErrors() {
        binding.etDomain.error = null
        binding.etResolver.error = null
        binding.etPublicKey.error = null
        binding.etKeepalive.error = null
        binding.etSshUsername.error = null
        binding.etSshPassword.error = null
    }

    private fun pasteInto(field: EditText) {
        val value = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (value.isEmpty()) {
            toast(R.string.toast_clipboard_empty)
            return
        }
        field.setText(value)
        field.setSelection(field.text.length)
    }
}
