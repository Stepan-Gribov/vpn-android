package com.v2ray.ang.contracts

import com.v2ray.ang.dto.entities.ProfileItem

interface MainAdapterListener : BaseAdapterListener {

    fun onEdit(guid: String, position: Int, profile: ProfileItem)

    fun onSelectServer(guid: String)

    fun onConnect(guid: String)

    fun onPing(guid: String)

    fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean)

}
