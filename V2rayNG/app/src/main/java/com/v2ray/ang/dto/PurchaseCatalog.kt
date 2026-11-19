package com.v2ray.ang.dto

import com.google.gson.annotations.SerializedName

data class PurchaseCatalog(
    val plans: List<PurchasePlan> = emptyList(),
)

data class PurchasePlan(
    val id: String = "",
    val label: String = "",
    @SerializedName("label_en") val labelEnglish: String? = null,
    val type: String = "",
    @SerializedName("traffic_gb") val trafficGb: Int = 0,
    @SerializedName("time_duration") val durationDays: Int = 0,
    @SerializedName("device_limit") val deviceLimit: Int = 0,
    @SerializedName("price_rub") val priceRub: Int = 0,
    val badge: String? = null,
)
