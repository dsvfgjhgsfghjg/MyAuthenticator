package top.leoblog.myauthenticator.ui.lock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import top.leoblog.myauthenticator.R
import top.leoblog.myauthenticator.databinding.ActivityUnlockBinding
import top.leoblog.myauthenticator.storage.SecureStorage
import top.leoblog.myauthenticator.ui.main.MainActivity
import java.util.concurrent.Executor

/**
 * 解锁 Activity
 *
 * 应用锁启用时作为 Launcher 启动，验证通过后才跳转到 MainActivity。
 * 如果 PIN 未设置或锁已关闭，直接跳过。
 */
class UnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockBinding
    private lateinit var secureStorage: SecureStorage
    private lateinit var lockManager: LockManager
    private var isVerified = false

    companion object {
        private const val TAG = "UnlockActivity"

        fun createIntent(context: Context): Intent {
            return Intent(context, UnlockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        secureStorage = SecureStorage(this)
        lockManager = LockManager(secureStorage)

        // If lock not enabled or no PIN set, skip
        if (!lockManager.isLockEnabled() || !lockManager.hasPin()) {
            lockManager.markUnlocked()
            startMainActivity()
            return
        }

        setupViews()
    }

    private fun setupViews() {
        binding.btnUnlock.setOnClickListener {
            attemptPinUnlock()
        }

        binding.etPin.setOnEditorActionListener { _, _, _ ->
            attemptPinUnlock()
            true
        }

        // Biometric (fingerprint)
        if (secureStorage.isBiometricEnabled()) {
            setupBiometric()
        } else {
            binding.cvFingerprint.visibility = android.view.View.GONE
        }
    }

    private fun attemptPinUnlock() {
        val pin = binding.etPin.text.toString().trim()

        if (pin.length < 4) {
            showError(getString(R.string.pin_error_too_short))
            return
        }

        if (lockManager.verifyPin(pin)) {
            onUnlockSuccess()
        } else {
            showError(getString(R.string.pin_error_invalid))
            binding.etPin.text?.clear()
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = android.view.View.VISIBLE
    }

    private fun onUnlockSuccess() {
        isVerified = true
        lockManager.markUnlocked()
        startMainActivity()
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setupBiometric() {
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                binding.cvFingerprint.visibility = android.view.View.VISIBLE
                binding.cvFingerprint.setOnClickListener { showBiometricPrompt() }
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                binding.cvFingerprint.visibility = android.view.View.GONE
                secureStorage.setBiometricEnabled(false)
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    showError(errString.toString())
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onUnlockSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    showError(getString(R.string.pin_error_invalid))
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_hint))
            .setSubtitle(getString(R.string.fingerprint_hint))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onBackPressed() {
        // No back action on unlock screen - finish app
        finishAffinity()
    }
}