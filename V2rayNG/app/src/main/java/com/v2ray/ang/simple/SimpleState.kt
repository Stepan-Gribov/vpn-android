package com.v2ray.ang.simple

/** All accepted data, identity and revision are one transaction in noBackup storage. */
internal data class SimpleState(
    val configurations: List<SimpleConfiguration> = emptyList(), val mode: String = "turn",
    val autoCaptcha: Boolean = true, val autoRecovery: Boolean = true, val ipv6Enabled: Boolean = false,
    val savedResolvers: List<String> = emptyList(), val splitTunneling: SplitTunnelSettings = SplitTunnelSettings(),
    val selectedProfileID: String? = null, val turnCheckInterval: Int = 30,
    val installationID: String = "", val subscriptions: List<PanelSubscription> = emptyList(),
    val activeSubscriptionID: String? = null, val dnsTunnel: PanelDns? = null,
    val dnsServers: List<String> = emptyList(),
    val excludedApplications: List<String> = emptyList(),
    val removedSubscriptions: List<String> = emptyList(),
    val autoConnectOnRestart: Boolean = false,
    val amneziaSplitEnabled: Boolean = TemporaryFullTunnelPolicy.DEFAULT_AMNEZIA_SPLIT_ENABLED,
    val fullTunnelMinutes: Int = TemporaryFullTunnelPolicy.DEFAULT_MINUTES,
    val turnSessionCount: Int = com.v2ray.ang.core.TurnSessionCount.DEFAULT,
)
internal object PanelStateReducer {
    fun removeSubscription(state: SimpleState,id: String): SimpleState {
        val profiles=state.configurations.filterNot { it.managed?.subscriptionID==id }
        val subscriptions=state.subscriptions.filterNot { it.id==id }
        val next=subscriptions.lastOrNull()
        val removingActive=state.activeSubscriptionID==id
        val settings=if(removingActive) next?.settings else null
        return state.copy(configurations=profiles,subscriptions=subscriptions,
            removedSubscriptions=(state.removedSubscriptions.orEmpty()+id).distinct(),
            activeSubscriptionID=if(removingActive) next?.id else state.activeSubscriptionID,
            selectedProfileID=state.selectedProfileID?.takeIf { selected->profiles.any { it.id==selected } } ?: profiles.firstOrNull()?.id,
            splitTunneling=if(removingActive) settings?.splitTunneling ?: SplitTunnelSettings() else state.splitTunneling,
            dnsServers=if(removingActive) settings?.dnsServers.orEmpty() else state.dnsServers,
            dnsTunnel=if(removingActive) settings?.dnsTunnel else state.dnsTunnel)
    }
    /** A portable copy is manual; do not let it occupy IDs reserved by this device's subscription. */
    fun restoreBackup(state: SimpleState,backup: ApplicationBackup): SimpleState {
        val reserved=state.configurations.filter { it.managed!=null }.map { it.id }.toSet()
        val imported=backup.profiles.map { profile->
            profile.copy(id=if(profile.id in reserved) java.util.UUID.randomUUID().toString() else profile.id,managed=null)
        }
        val selected=backup.profiles.indexOfFirst { it.id==backup.selectedProfileID }.takeIf { it>=0 }?.let { imported[it].id }
        return state.copy(configurations=imported,mode=backup.mode,autoCaptcha=backup.autoCaptcha,
            autoRecovery=backup.autoRecovery,autoConnectOnRestart=backup.autoConnectOnRestart,turnSessionCount=backup.turnSessionCount,
            amneziaSplitEnabled=backup.amneziaSplitEnabled,fullTunnelMinutes=backup.fullTunnelMinutes,ipv6Enabled=backup.ipv6Enabled,excludedApplications=backup.excludedApplications,savedResolvers=backup.savedDNSServers,splitTunneling=backup.splitTunneling,
            selectedProfileID=selected,turnCheckInterval=backup.turnCheckInterval,dnsServers=backup.dnsServers,dnsTunnel=backup.dnsTunnel)
    }
    fun apply(state: SimpleState, packet: PanelPackage, trust: PanelTrust, token: String,
              now: Long, selected: String?, activation: Boolean=false, activationCode: String?=null): SimpleState {
        val id = PanelContract.subscriptionID(trust.api,packet.deviceID)
        val prior = state.subscriptions.firstOrNull { it.id == id }
        require(activation || id !in state.removedSubscriptions.orEmpty()) { "Подписка удалена с этого телефона." }
        require(prior == null || (prior.clientID == packet.clientID && (activation || prior.trust.sameSource(trust)) && packet.revision >= prior.revision)) {
            "Устаревший пакет или изменившийся источник подписки."
        }
        require(token.isNotBlank() && token.length <= 4096 && token.all { it.code in 33..126 }) { "Неверный токен устройства." }
        val manual = state.configurations.filter { it.managed?.subscriptionID != id }
        require(packet.profiles.none { p -> manual.any { it.id == p.id } }) { "Идентификатор профиля занят другим источником." }
        require(packet.profiles.all { it.managed?.subscriptionID == id })
        val profiles = manual + packet.profiles
        val sub = PanelSubscription(id,trust,packet.deviceID,token,packet.clientID,packet.clientName,packet.revision,
            packet.status,packet.expiresAt,packet.syncAfterSeconds,now,packet.settings ?: prior?.settings,
            activationCode=activationCode ?: prior?.activationCode,
            nextSyncAt=packet.settings?.subscriptionSync?.takeIf { it.enabled && packet.status == "active" }
                ?.nextAfter(now) ?: 0)
        val selectSource = activation || prior==null || state.activeSubscriptionID==null || state.activeSubscriptionID==id
        val settings = if(selectSource) packet.settings else null
        return state.copy(configurations=profiles,subscriptions=state.subscriptions.filterNot { it.id==id }+sub,
            removedSubscriptions=state.removedSubscriptions.orEmpty().filterNot { it==id },
            activeSubscriptionID=if(selectSource) id else state.activeSubscriptionID,
            selectedProfileID=(selected ?: state.selectedProfileID)?.takeIf { choice->profiles.any { it.id==choice } } ?: profiles.firstOrNull()?.id,
            mode=if(state.configurations.isEmpty()) profiles.firstOrNull()?.mode ?: state.mode else state.mode,
            autoCaptcha=settings?.autoCaptcha ?: state.autoCaptcha,autoRecovery=settings?.autoRecovery ?: state.autoRecovery,
            turnCheckInterval=settings?.turnCheckInterval ?: state.turnCheckInterval,
            dnsServers=settings?.dnsServers ?: state.dnsServers,splitTunneling=settings?.splitTunneling ?: state.splitTunneling,
            dnsTunnel=if(settings!=null) settings.dnsTunnel else state.dnsTunnel)
    }
}
