package com.portalhotspot.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.portalhotspot.app.databinding.ItemAccountBinding
import com.portalhotspot.app.models.Account

class AccountAdapter(
    private var accounts: List<Account>,
    private val onDeleteClick: (Account) -> Unit
) : RecyclerView.Adapter<AccountAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemAccountBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val account = accounts[position]
        holder.binding.tvUsername.text = "Tài khoản: ${account.username} (MK: ${account.password})"

        val modeStr = if (account.isSingleDevice) "Chỉ 1 máy" else "Dùng chung"
        val limitStr = if (account.dataLimitBytes > 0) {
            String.format("%.1f / %.1f MB", account.getUsedMb(), account.getLimitMb())
        } else {
            String.format("%.1f MB (Không giới hạn)", account.getUsedMb())
        }

        holder.binding.tvAccountType.text = "Chế độ: $modeStr | Đã dùng: $limitStr"
        holder.binding.btnDeleteAccount.setOnClickListener {
            onDeleteClick(account)
        }
    }

    override fun getItemCount(): Int = accounts.size

    fun updateData(newAccounts: List<Account>) {
        accounts = newAccounts
        notifyDataSetChanged()
    }
}
