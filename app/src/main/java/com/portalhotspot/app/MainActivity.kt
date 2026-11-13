package com.portalhotspot.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.portalhotspot.app.data.AccountManager
import com.portalhotspot.app.databinding.ActivityMainBinding
import com.portalhotspot.app.models.Account
import com.portalhotspot.app.service.PortalService
import com.portalhotspot.app.ui.AccountAdapter
import com.portalhotspot.app.ui.ClientAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var accountManager: AccountManager
    private lateinit var accountAdapter: AccountAdapter
    private lateinit var clientAdapter: ClientAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLists()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        accountManager = AccountManager.getInstance(this)

        setupRecyclerViews()
        setupListeners()
        updateServiceStateUI()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
        updateServiceStateUI()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun setupRecyclerViews() {
        accountAdapter = AccountAdapter(accountManager.getAllAccounts()) { account ->
            AlertDialog.Builder(this)
                .setTitle("Xóa tài khoản")
                .setMessage("Bạn có chắc muốn xóa tài khoản '${account.username}' không?")
                .setPositiveButton("Xóa") { _, _ ->
                    accountManager.deleteAccount(account.username)
                    refreshLists()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
        binding.rvAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvAccounts.adapter = accountAdapter

        clientAdapter = ClientAdapter(accountManager.getAllClients()) { client ->
            accountManager.kickClient(client.ip)
            Toast.makeText(this, "Đã ngắt kết nối IP ${client.ip}", Toast.LENGTH_SHORT).show()
            refreshLists()
        }
        binding.rvClients.layoutManager = LinearLayoutManager(this)
        binding.rvClients.adapter = clientAdapter
    }

    private fun setupListeners() {
        binding.btnToggleService.setOnClickListener {
            val intent = Intent(this, PortalService::class.java)
            if (PortalService.isRunning) {
                intent.action = PortalService.ACTION_STOP
                startService(intent)
                PortalService.isRunning = false
            } else {
                intent.action = PortalService.ACTION_START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                PortalService.isRunning = true
            }
            updateServiceStateUI()
        }

        binding.btnCreateAccount.setOnClickListener {
            showAddAccountDialog()
        }
    }

    private fun showAddAccountDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_account, null)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val rbSingle = dialogView.findViewById<RadioButton>(R.id.rbSingleDevice)
        val etDataLimitMb = dialogView.findViewById<EditText>(R.id.etDataLimitMb)
        val cbUnlimited = dialogView.findViewById<CheckBox>(R.id.cbUnlimited)

        cbUnlimited.setOnCheckedChangeListener { _, isChecked ->
            etDataLimitMb.isEnabled = !isChecked
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Tạo") { _, _ ->
                val username = etUsername.text.toString().trim()
                val password = etPassword.text.toString().trim()

                if (username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(this, "Vui lòng nhập đầy đủ tài khoản & mật khẩu!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val isSingleDevice = rbSingle.isChecked
                val limitBytes = if (cbUnlimited.isChecked) {
                    0L
                } else {
                    val mb = etDataLimitMb.text.toString().toLongOrNull() ?: 500L
                    mb * 1024L * 1024L
                }

                val newAccount = Account(
                    username = username,
                    password = password,
                    isSingleDevice = isSingleDevice,
                    dataLimitBytes = limitBytes
                )

                accountManager.addOrUpdateAccount(newAccount)
                Toast.makeText(this, "Đã tạo tài khoản $username thành công!", Toast.LENGTH_SHORT).show()
                refreshLists()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun updateServiceStateUI() {
        if (PortalService.isRunning) {
            binding.tvStatus.text = "● Captive Portal ĐANG HOẠT ĐỘNG"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_color))
            binding.btnToggleService.text = "TẮT DỊCH VỤ CAPTIVE PORTAL"
            binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.danger)
        } else {
            binding.tvStatus.text = "○ Dịch vụ đang tắt"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
            binding.btnToggleService.text = "BẬT CỔNG CAPTIVE PORTAL"
            binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_color)
        }
    }

    private fun refreshLists() {
        accountAdapter.updateData(accountManager.getAllAccounts())
        clientAdapter.updateData(accountManager.getAllClients())
    }
}
