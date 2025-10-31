package com.shayan.firewall

import android.graphics.Color
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
        // holder.itemView.isActivated = appInfo.isSelected
    }

    override fun getItemCount(): Int = appList.size
    
    fun getAppList(): List<AppInfo> {
        return appList
    }

    fun updateApps(newAppList: List<AppInfo>) {
        // Use a more efficient DiffUtil later if performance becomes an issue
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


        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(appList[position])
                }
            }
            itemView.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemLongClick(appList[position])
                }
                true // Consume the long click
            }
            wifiIcon.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onWifiClick(appList[position])
                }
            }
            dataIcon.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onDataClick(appList[position])
                }
            }
        }

        fun bind(appInfo: AppInfo) {
            appIcon.setImageDrawable(appInfo.appIcon)
            appName.text = appInfo.appName

            if (appInfo.isSelected) {
                // --- SELECTED STATE ---
                itemView.setBackgroundColor(colorSelectedGrey)
                appName.setTextColor(colorWhite)

                // Set icons to white for contrast
                wifiIcon.setImageResource(R.drawable.ic_wifi)
                wifiIcon.setColorFilter(colorWhite)
                
                dataIcon.setImageResource(R.drawable.ic_data)
                dataIcon.setColorFilter(colorWhite)
                
            } else {
                // --- DEFAULT STATE ---
                itemView.setBackgroundColor(colorDarkGrey)
                appName.setTextColor(colorLightGrey)

                // Set Wi-Fi icon state (Blue if allowed, Grey if blocked)
                wifiIcon.setImageResource(R.drawable.ic_wifi)
                if (appInfo.isWifiBlocked) {
                    wifiIcon.setColorFilter(colorGrey)
                } else {
                    wifiIcon.setColorFilter(colorBlue)
                }

                // Set Data icon state (Blue if allowed, Grey if blocked)
                dataIcon.setImageResource(R.drawable.ic_data)
                if (appInfo.isDataBlocked) {
                    dataIcon.setColorFilter(colorGrey)
                } else {
                    dataIcon.setColorFilter(colorBlue)
                }
            }
        }
    }
}

