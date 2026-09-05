package com.example.ussdhelper.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.ussdhelper.R
import com.example.ussdhelper.model.SimCardInfo
import com.example.ussdhelper.model.UssdAction
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class UssdActionAdapter(
    private val context: Context,
    private val onDialClick: (UssdAction) -> Unit,
    private val onEditClick: (UssdAction) -> Unit,
    private val onDeleteClick: (UssdAction) -> Unit
) : RecyclerView.Adapter<UssdActionAdapter.ViewHolder>() {

    private val allActions = mutableListOf<UssdAction>()
    private val displayedActions = mutableListOf<UssdAction>()
    private var detectedSims = listOf<SimCardInfo>()
    private var currentCategoryFilter = "All"
    private var currentSearchQuery = ""

    fun updateData(actions: List<UssdAction>, sims: List<SimCardInfo>) {
        allActions.clear()
        allActions.addAll(actions)
        detectedSims = sims
        filterAndApply()
    }

    fun setCategoryFilter(category: String) {
        currentCategoryFilter = category
        filterAndApply()
    }

    fun setSearchQuery(query: String) {
        currentSearchQuery = query.trim()
        filterAndApply()
    }

    private fun filterAndApply() {
        displayedActions.clear()
        val filtered = allActions.filter { action ->
            val matchesCategory = if (currentCategoryFilter == "All") true else action.category.equals(currentCategoryFilter, ignoreCase = true)
            val matchesSearch = if (currentSearchQuery.isEmpty()) true else {
                action.title.contains(currentSearchQuery, ignoreCase = true) ||
                        action.code.contains(currentSearchQuery, ignoreCase = true) ||
                        action.description.contains(currentSearchQuery, ignoreCase = true)
            }
            matchesCategory && matchesSearch
        }
        displayedActions.addAll(filtered)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ussd_action, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = displayedActions[position]
        holder.bind(action)
    }

    override fun getItemCount(): Int = displayedActions.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardAction: MaterialCardView = itemView.findViewById(R.id.cardAction)
        private val iconContainer: FrameLayout = itemView.findViewById(R.id.iconContainer)
        private val imgCategory: ImageView = itemView.findViewById(R.id.imgCategory)
        private val tvActionTitle: TextView = itemView.findViewById(R.id.tvActionTitle)
        private val tvActionDescription: TextView = itemView.findViewById(R.id.tvActionDescription)
        private val tvActionCode: TextView = itemView.findViewById(R.id.tvActionCode)
        private val tvSimBadge: TextView = itemView.findViewById(R.id.tvSimBadge)
        private val btnDialAction: MaterialButton = itemView.findViewById(R.id.btnDialAction)
        private val btnMoreOptions: ImageButton = itemView.findViewById(R.id.btnMoreOptions)

        fun bind(action: UssdAction) {
            tvActionTitle.text = action.title
            tvActionDescription.text = if (action.description.isNotBlank()) action.description else "USSD shortcut"
            tvActionCode.text = action.code

            // Category icon & tint
            val (iconRes, tintColor) = when (action.category.lowercase()) {
                "balance" -> Pair(R.drawable.ic_category_balance, R.color.accent_green)
                "data", "packages & data" -> Pair(R.drawable.ic_category_data, R.color.primary_blue_light)
                "money", "transfer / money" -> Pair(R.drawable.ic_category_money, R.color.accent_amber)
                else -> Pair(R.drawable.ic_category_utility, R.color.accent_purple)
            }

            imgCategory.setImageResource(iconRes)
            imgCategory.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tintColor))

            // SIM Badge info
            when (action.simSlot) {
                1 -> {
                    val sim1Carrier = detectedSims.firstOrNull { it.slotIndex == 0 }?.displayLabel ?: "SIM 1"
                    tvSimBadge.text = "SIM 1 • $sim1Carrier"
                    tvSimBadge.setBackgroundResource(R.drawable.bg_badge_sim1)
                    tvSimBadge.setTextColor(ContextCompat.getColor(context, R.color.primary_blue_light))
                    tvSimBadge.visibility = View.VISIBLE
                }
                2 -> {
                    val sim2Carrier = detectedSims.firstOrNull { it.slotIndex == 1 }?.displayLabel ?: "SIM 2"
                    tvSimBadge.text = "SIM 2 • $sim2Carrier"
                    tvSimBadge.setBackgroundResource(R.drawable.bg_badge_sim2)
                    tvSimBadge.setTextColor(ContextCompat.getColor(context, R.color.accent_amber))
                    tvSimBadge.visibility = View.VISIBLE
                }
                else -> {
                    tvSimBadge.text = "AUTO / ASK"
                    tvSimBadge.setBackgroundResource(R.drawable.bg_badge_auto)
                    tvSimBadge.setTextColor(ContextCompat.getColor(context, R.color.accent_purple))
                    tvSimBadge.visibility = View.VISIBLE
                }
            }

            btnDialAction.setOnClickListener { onDialClick(action) }
            cardAction.setOnClickListener { onDialClick(action) }

            btnMoreOptions.setOnClickListener {
                showActionPopupMenu(it, action)
            }
        }

        private fun showActionPopupMenu(anchor: View, action: UssdAction) {
            val popup = androidx.appcompat.widget.PopupMenu(context, anchor)
            popup.menu.add(0, 1, 0, "Edit Action")
            popup.menu.add(0, 2, 1, "Delete Action")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> onEditClick(action)
                    2 -> onDeleteClick(action)
                }
                true
            }
            popup.show()
        }
    }
}
