package com.sam.btagent

import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(private val onItemClick: (BluetoothDevice) -> Unit) :
    ListAdapter<BluetoothDevice, DeviceAdapter.ViewHolder>(DeviceDiffCallback()) {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameView: TextView = view.findViewById(R.id.deviceName)
        val addressView: TextView = view.findViewById(R.id.deviceAddress)
        val iconView: android.widget.ImageView = view.findViewById(R.id.deviceIcon)

        init {
            view.setOnClickListener {
                getItem(adapterPosition)?.let { onItemClick(it) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.device_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = getItem(position)
        val name = device.name
        holder.nameView.text = name ?: "Unknown Device"
        holder.addressView.text = device.address

        // Set Icon and Color based on type and bond state
        when (device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> {
                holder.iconView.setImageResource(R.drawable.ic_bluetooth) // Use a badge or color for BLE
                holder.iconView.setColorFilter(android.graphics.Color.parseColor("#00B0FF")) // Light Blue for BLE
            }
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> {
                holder.iconView.setImageResource(R.drawable.ic_bluetooth)
                holder.iconView.setColorFilter(android.graphics.Color.parseColor("#2962FF")) // Darker Blue for Classic
            }
            BluetoothDevice.DEVICE_TYPE_DUAL -> {
                holder.iconView.setImageResource(R.drawable.ic_bluetooth)
                holder.iconView.setColorFilter(android.graphics.Color.parseColor("#AA00FF")) // Purple for Dual
            }
            else -> {
                holder.iconView.setImageResource(R.drawable.ic_bluetooth)
                holder.iconView.setColorFilter(android.graphics.Color.GRAY)
            }
        }

        // Highlight Bonded devices
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            holder.nameView.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            holder.nameView.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    class DeviceDiffCallback : DiffUtil.ItemCallback<BluetoothDevice>() {
        override fun areItemsTheSame(oldItem: BluetoothDevice, newItem: BluetoothDevice): Boolean {
            return oldItem.address == newItem.address
        }

        override fun areContentsTheSame(oldItem: BluetoothDevice, newItem: BluetoothDevice): Boolean {
            return oldItem.name == newItem.name && oldItem.bondState == newItem.bondState
        }
    }
}
