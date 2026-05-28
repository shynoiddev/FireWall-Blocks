package com.shayan.firewall

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class MonitoringAdapter(
    private var logList: List<MonitoringLog>,
    val onWifiClick: (MonitoringLog) -> Unit,
    val onDataClick: (MonitoringLog) -> Unit
) : RecyclerView.Adapter<MonitoringAdapter.MonitoringViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonitoringViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_monitoring_log, parent, false)
        return MonitoringViewHolder(view)
    }

    override fun onBindViewHolder(holder: MonitoringViewHolder, position: Int) {
        val logInfo = logList[position]
        holder.bind(logInfo)
    }

    override fun getItemCount(): Int = logList.size
    
    fun getLogList(): List<MonitoringLog> {
        return logList
    }

    fun updateLogs(newLogList: List<MonitoringLog>) {
        this.logList = newLogList
        notifyDataSetChanged()
    }

    inner class MonitoringViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: ImageView = itemView.findViewById(R.id.image_app_icon)
        private val appName: TextView = itemView.findViewById(R.id.text_app_name)
        private val textDomainIp: TextView = itemView.findViewById(R.id.text_domain_ip)
        private val textTimestamp: TextView = itemView.findViewById(R.id.text_timestamp)
        private val wifiIcon: ImageView = itemView.findViewById(R.id.icon_wifi)
        private val dataIcon: ImageView = itemView.findViewById(R.id.icon_data)
        
        private val colorBlue = ContextCompat.getColor(itemView.context, R.color.dark_blue)
        private val colorGrey = ContextCompat.getColor(itemView.context, R.color.icon_grey_disabled)
        private val colorLightGrey = ContextCompat.getColor(itemView.context, R.color.light_grey)
        private val colorRedSystem = ContextCompat.getColor(itemView.context, R.color.red_system)
        private val colorYellowDisabled = ContextCompat.getColor(itemView.context, R.color.yellow_disabled)

        init {
            wifiIcon.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onWifiClick(logList[position])
                }
            }
            dataIcon.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onDataClick(logList[position])
                }
            }
        }

        fun bind(log: MonitoringLog) {
            if (log.appIcon != null) {
                appIcon.setImageDrawable(log.appIcon)
            } else {
                appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            
            appName.text = log.appName
            textDomainIp.text = log.domainOrIp
            textTimestamp.text = log.timestamp
            
            // Clean up paint flags
            appName.paintFlags = appName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

            val semanticTextColor = when {
                log.isUninstalled -> colorGrey
                !log.isEnabled -> colorYellowDisabled
                log.isSystemApp -> colorRedSystem
                else -> colorLightGrey
            }

            appName.setTextColor(semanticTextColor)

            if (log.isUninstalled) {
                appName.paintFlags = appName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }

            wifiIcon.setImageResource(R.drawable.ic_wifi)
            if (log.isWifiBlocked) {
                wifiIcon.setColorFilter(colorGrey)
            } else {
                wifiIcon.setColorFilter(colorBlue)
            }

            dataIcon.setImageResource(R.drawable.ic_data)
            if (log.isDataBlocked) {
                dataIcon.setColorFilter(colorGrey)
            } else {
                dataIcon.setColorFilter(colorBlue)
            }
        }
    }
}