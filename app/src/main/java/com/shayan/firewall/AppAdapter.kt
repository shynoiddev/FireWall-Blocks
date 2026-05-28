package com.shayan.firewall

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private var appList: List<AppInfo>,
    val onItemClick: (AppInfo) -> Unit,
    val onItemLongClick: (AppInfo) -> Unit,
    val onWifiClick: (AppInfo) -> Unit,
    val onDataClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = appList[position]
        holder.bind(appInfo)
    }

    override fun getItemCount(): Int = appList.size
    
    fun getAppList(): List<AppInfo> {
        return appList
    }

    fun updateApps(newAppList: List<AppInfo>) {
        this.appList = newAppList
        notifyDataSetChanged()
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: ImageView = itemView.findViewById(R.id.image_app_icon)
        private val appName: TextView = itemView.findViewById(R.id.text_app_name)
        private val wifiIcon: ImageView = itemView.findViewById(R.id.icon_wifi)
        private val dataIcon: ImageView = itemView.findViewById(R.id.icon_data)
        
        // Color cache
        private val colorBlue = ContextCompat.getColor(itemView.context, R.color.dark_blue)
        private val colorGrey = ContextCompat.getColor(itemView.context, R.color.icon_grey_disabled)
        private val colorWhite = Color.WHITE
        private val colorLightGrey = ContextCompat.getColor(itemView.context, R.color.light_grey)
        private val colorDarkGrey = ContextCompat.getColor(itemView.context, R.color.dark_grey)
        private val colorSelectedGrey = ContextCompat.getColor(itemView.context, R.color.selected_grey)
         
        // Semantic Colors
        private val colorRedSystem = ContextCompat.getColor(itemView.context, R.color.red_system)
        private val colorYellowDisabled = ContextCompat.getColor(itemView.context, R.color.yellow_disabled)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(appList[position])
                }
            }
            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(appList[position])
                }
                true 
            }
            wifiIcon.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onWifiClick(appList[position])
                }
            }
            dataIcon.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onDataClick(appList[position])
                }
            }
        }

        fun bind(appInfo: AppInfo) {
            appIcon.setImageDrawable(appInfo.appIcon)
            appName.text = appInfo.appName
            
            // Clean up paint flags due to View recycling
            appName.paintFlags = appName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

            // Resolve base text color (priority: uninstalled > disabled > system > regular)
            val semanticTextColor = when {
                appInfo.isUninstalled -> colorGrey
                !appInfo.isEnabled -> colorYellowDisabled
                appInfo.isSystemApp -> colorRedSystem
                else -> if (appInfo.isSelected) colorWhite else colorLightGrey
            }

            appName.setTextColor(semanticTextColor)

            if (appInfo.isUninstalled) {
                appName.paintFlags = appName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }

            if (appInfo.isSelected) {
                itemView.setBackgroundColor(colorSelectedGrey)
            } else {
                itemView.setBackgroundColor(colorDarkGrey)
            }

            // Always maintain the actual blocked/allowed color state for the icons
            wifiIcon.setImageResource(R.drawable.ic_wifi)
            if (appInfo.isWifiBlocked) {
                wifiIcon.setColorFilter(colorGrey)
            } else {
                wifiIcon.setColorFilter(colorBlue)
            }

            dataIcon.setImageResource(R.drawable.ic_data)
            if (appInfo.isDataBlocked) {
                dataIcon.setColorFilter(colorGrey)
            } else {
                dataIcon.setColorFilter(colorBlue)
            }
        }
    }
}