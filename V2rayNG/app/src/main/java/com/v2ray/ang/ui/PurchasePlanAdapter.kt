package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemSubscriptionPlanBinding
import com.v2ray.ang.dto.PurchasePlan

class PurchasePlanAdapter(
    private val useEnglishLabels: Boolean,
    private val onPlanSelected: (PurchasePlan) -> Unit,
) : RecyclerView.Adapter<PurchasePlanAdapter.PlanViewHolder>() {
    private val plans = mutableListOf<PurchasePlan>()
    private var busyPlanId: String? = null

    fun submitPlans(newPlans: List<PurchasePlan>) {
        plans.clear()
        plans.addAll(newPlans.filter { it.id.isNotBlank() && it.priceRub > 0 })
        notifyDataSetChanged()
    }

    fun setBusy(planId: String?) {
        busyPlanId = planId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = plans.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanViewHolder =
        PlanViewHolder(
            ItemSubscriptionPlanBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
        )

    override fun onBindViewHolder(holder: PlanViewHolder, position: Int) {
        holder.bind(plans[position])
    }

    inner class PlanViewHolder(
        private val binding: ItemSubscriptionPlanBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(plan: PurchasePlan) = with(binding) {
            val context = root.context
            tvPlanName.text = if (useEnglishLabels) {
                plan.labelEnglish?.takeIf(String::isNotBlank) ?: when (plan.durationDays) {
                    30 -> context.getString(R.string.purchase_duration_one_month)
                    90 -> context.getString(R.string.purchase_duration_three_months)
                    180 -> context.getString(R.string.purchase_duration_six_months)
                    365 -> context.getString(R.string.purchase_duration_twelve_months)
                    else -> plan.label
                }
            } else {
                plan.label
            }
            tvPlanPrice.text = context.getString(R.string.purchase_price_rub, plan.priceRub)
            tvPlanDetails.text = when {
                plan.type.equals("UNLIMITED", ignoreCase = true) -> context.getString(
                    R.string.purchase_plan_unlimited_details,
                    plan.durationDays,
                    plan.deviceLimit,
                )
                else -> context.getString(
                    R.string.purchase_plan_traffic_details,
                    plan.trafficGb,
                    plan.deviceLimit,
                )
            }

            val badgeLabel = when (plan.badge) {
                "popular" -> R.string.purchase_badge_popular
                "best_value" -> R.string.purchase_badge_best_value
                "starter" -> R.string.purchase_badge_starter
                else -> null
            }
            tvPlanBadge.isVisible = badgeLabel != null
            badgeLabel?.let(tvPlanBadge::setText)

            val isBusy = busyPlanId == plan.id
            val anyBusy = busyPlanId != null
            btnChoosePlan.isEnabled = !anyBusy
            btnChoosePlan.setText(
                if (isBusy) R.string.purchase_creating_checkout
                else R.string.purchase_choose_plan,
            )
            btnChoosePlan.setOnClickListener { onPlanSelected(plan) }
            root.setOnClickListener {
                if (!anyBusy) onPlanSelected(plan)
            }
        }
    }
}
