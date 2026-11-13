package com.portalhotspot.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.portalhotspot.app.databinding.ItemClientBinding
import com.portalhotspot.app.models.ConnectedClient

class ClientAdapter(
    private var clients: List<ConnectedClient>,
    private val onKickClick: (ConnectedClient) -> Unit
) : RecyclerView.Adapter<ClientAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemClientBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClientBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val client = clients[position]
        holder.binding.tvClientIp.text = "IP: ${client.ip}"

        val authStr = if (client.isAuthenticated) {
            "User: ${client.username ?: "Chưa rõ"} | Đã dùng: ${String.format("%.2f", client.getSessionMb())} MB"
        } else {
            "Chưa đăng nhập (Chờ xác thực)"
        }

        holder.binding.tvClientStatus.text = authStr
        holder.binding.btnKickClient.setOnClickListener {
            onKickClick(client)
        }
    }

    override fun getItemCount(): Int = clients.size

    fun updateData(newClients: List<ConnectedClient>) {
        clients = newClients
        notifyDataSetChanged()
    }
}
