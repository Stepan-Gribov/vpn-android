package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.DnsTunnelProfiles
import com.v2ray.ang.core.DnsTunnelSettings
import com.v2ray.ang.core.DnsTunnelStore
import com.v2ray.ang.databinding.ActivityDnsTunnelSetupBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsTunnelSetupActivity : BaseActivity() {
    companion object { const val EXTRA_CONNECTION_GUID = "dns_connection_guid" }
    private lateinit var binding: ActivityDnsTunnelSetupBinding
    private var sources = emptyList<Pair<String, ProfileItem>>()
    private var publicSettings: DnsTunnelSettings? = null
    private var busy = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSettings {
            contentResolver.openInputStream(uri)?.use {
                DnsTunnelSettings.fromJson(DnsTunnelStore.readConfiguration(it))
            } ?: error("Missing file")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDnsTunnelSetupBinding.inflate(layoutInflater)
        setContentViewWithToolbar(binding.root, title = getString(R.string.dns_tunnel_setup_title))
        binding.etConnectionName.setText(getString(R.string.dns_tunnel_profile_name))
        binding.etDnsResolvers.setText("1.1.1.1:53\n8.8.8.8:53")
        val selected = MmkvManager.getSelectServer()
        sources = MmkvManager.decodeAllServerList().mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.takeIf(DnsTunnelProfiles::canSupplyIdentity)?.let { guid to it }
        }.sortedBy { if (it.first == selected) 0 else if (DnsTunnelProfiles.isDns(it.second)) 1 else 2 }
            .distinctBy { it.second.password }
        binding.spSubscriptionProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            sources.map { (_, profile) ->
                val subscription = MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks.orEmpty()
                listOf(subscription, profile.remarks).filter(String::isNotBlank).joinToString(" · ")
            })
        sources.firstOrNull()?.second?.takeIf(DnsTunnelProfiles::isDns)?.let {
            binding.etConnectionName.setText(it.remarks)
        }
        binding.btnSaveDns.isEnabled = sources.isNotEmpty()
        binding.tvDnsStatus.setText(if (sources.isEmpty()) R.string.dns_tunnel_import_subscription else R.string.dns_tunnel_enter_settings)
        if (savedInstanceState == null) DnsTunnelStore.settings(this)?.let {
            fill(it)
            if (DnsTunnelStore.automatic(this)) publicSettings = it
        }
        else publicSettings = savedInstanceState.getString("public_settings")?.let {
            runCatching { DnsTunnelSettings.fromJson(it) }.getOrNull()
        }
        binding.btnLoadDns.setOnClickListener { loadSettings(public = true) { DnsTunnelStore.download() } }
        binding.btnImportDns.setOnClickListener { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        binding.btnSaveDns.setOnClickListener { saveConnection() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        publicSettings?.let { outState.putString("public_settings", it.toJson()) }
        super.onSaveInstanceState(outState)
    }

    private fun fill(settings: DnsTunnelSettings) {
        binding.etDnsDomain.setText(settings.domain)
        binding.etDnsPublicKey.setText(settings.publicKey)
        binding.etDnsResolvers.setText(settings.resolvers.joinToString("\n"))
    }

    private fun controlsEnabled(enabled: Boolean) {
        busy = !enabled
        binding.btnLoadDns.isEnabled = enabled
        binding.btnImportDns.isEnabled = enabled
        binding.btnSaveDns.isEnabled = enabled && sources.isNotEmpty()
        binding.spSubscriptionProfile.isEnabled = enabled
        listOf(binding.etConnectionName, binding.etDnsDomain, binding.etDnsPublicKey, binding.etDnsResolvers)
            .forEach { it.isEnabled = enabled }
        if (enabled) hideLoading() else showLoading()
    }

    private fun loadSettings(public: Boolean = false, load: () -> DnsTunnelSettings) {
        if (busy) return
        controlsEnabled(false)
        binding.tvDnsStatus.setText(R.string.dns_tunnel_loading)
        lifecycleScope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { load() }
                publicSettings = settings.takeIf { public }
                fill(settings)
                binding.tvDnsStatus.setText(R.string.dns_tunnel_loaded)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                binding.tvDnsStatus.setText(if (error is DnsTunnelStore.Unavailable)
                    R.string.dns_tunnel_public_unavailable else R.string.dns_tunnel_invalid)
            } finally { controlsEnabled(true) }
        }
    }

    private fun saveConnection() {
        if (busy) return
        val source = sources.getOrNull(binding.spSubscriptionProfile.selectedItemPosition)?.second ?: return
        val name = binding.etConnectionName.text.toString().trim()
        val settings = runCatching {
            require(name.isNotBlank())
            DnsTunnelSettings.fromFields(binding.etDnsDomain.text.toString(),
                binding.etDnsPublicKey.text.toString(), binding.etDnsResolvers.text.toString())
        }.getOrElse {
            binding.tvDnsStatus.setText(R.string.dns_tunnel_invalid)
            return
        }
        controlsEnabled(false)
        binding.tvDnsStatus.setText(R.string.dns_tunnel_saving)
        lifecycleScope.launch {
            try {
                val guid = withContext(Dispatchers.IO) {
                    val automatic = settings == publicSettings
                    // Manual connections survive subscription refreshes in the Default group.
                    val group = if (automatic) source.subscriptionId else AppConfig.DEFAULT_SUBSCRIPTION_ID
                    val existing = MmkvManager.decodeServerList(group).firstOrNull {
                        val profile = MmkvManager.decodeServerConfig(it)
                        DnsTunnelProfiles.isDns(profile) && profile?.password == source.password
                    }.orEmpty()
                    DnsTunnelStore.save(this@DnsTunnelSetupActivity, settings, automatic)
                    MmkvManager.encodeServerConfig(existing, DnsTunnelProfiles.connection(source, name, group))
                }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_CONNECTION_GUID, guid))
                finish()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                binding.tvDnsStatus.setText(R.string.dns_tunnel_invalid)
            } finally { controlsEnabled(true) }
        }
    }
}
