package com.remotemonitor.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.remotemonitor.app.BuildConfig
import com.remotemonitor.app.RemoteMonitorApp
import com.remotemonitor.app.data.LoginRequest
import com.remotemonitor.app.data.RegisterDeviceRequest
import com.remotemonitor.app.data.RetrofitClient
import com.remotemonitor.app.databinding.ActivityLoginBinding
import com.remotemonitor.app.services.MonitorService
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val session by lazy { RemoteMonitorApp.instance.sessionManager }

    // ── All permissions the app needs (requested together on login) ────────
    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "All permissions are required for monitoring to work.", Toast.LENGTH_LONG).show()
        }
    }

    // Background location is a separate, sequential request (Android 10+)
    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Background location denied — GPS will only update while app is open.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // If already logged in, skip directly to main
        if (session.isLoggedIn) {
            startMainActivity()
            return
        }

        binding.btnLogin.setOnClickListener { handleLogin() }
    }

    private fun handleLogin() {
        val email    = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val server   = binding.etServerUrl.text.toString().trim().ifBlank { BuildConfig.SERVER_URL }

        if (email.isBlank() || password.isBlank()) {
            binding.etEmail.error    = if (email.isBlank()) "Required" else null
            binding.etPassword.error = if (password.isBlank()) "Required" else null
            return
        }

        binding.btnLogin.isEnabled = false
        binding.progressBar.show()

        lifecycleScope.launch {
            try {
                session.setServerUrl(server)
                RetrofitClient.reset()
                val api = RetrofitClient.getInstance(server)

                // 1. Authenticate
                val loginResp = api.login(LoginRequest(email, password))
                if (!loginResp.isSuccessful || loginResp.body()?.success != true) {
                    showError(loginResp.body()?.let { "Login failed" } ?: "Network error")
                    return@launch
                }
                val loginData = loginResp.body()!!.data!!

                // 2. Register device (or retrieve existing token)
                val model    = android.os.Build.MODEL
                val osVer    = android.os.Build.VERSION.RELEASE
                val appVer   = BuildConfig.VERSION_NAME
                val devName  = android.os.Build.DEVICE

                val regResp = api.registerDevice(
                    "Bearer ${loginData.accessToken}",
                    RegisterDeviceRequest(devName, model, osVer, appVer)
                )
                if (!regResp.isSuccessful || regResp.body()?.success != true) {
                    showError("Device registration failed")
                    return@launch
                }
                val deviceToken = regResp.body()!!.data!!.deviceToken

                // 3. Persist session
                session.saveSession(
                    accessToken  = loginData.accessToken,
                    refreshToken = loginData.refreshToken,
                    deviceToken  = deviceToken,
                    email        = loginData.user.email,
                    userId       = loginData.user.id.toString()
                )

                // 4. Request permissions (must be done before starting service)
                requestAllPermissions()

                // 5. Launch
                startMainActivity()

            } catch (e: Exception) {
                showError("Connection error: ${e.message}")
            } finally {
                binding.btnLogin.isEnabled = true
                binding.progressBar.hide()
            }
        }
    }

    private fun requestAllPermissions() {
        val denied = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) permissionLauncher.launch(denied.toTypedArray())

        // Background location requires prior fine location grant
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun View.show() {
        this.visibility = View.VISIBLE
    }

    private fun View.hide() {
        this.visibility = View.GONE
    }
}
