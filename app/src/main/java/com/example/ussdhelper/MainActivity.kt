package com.example.ussdhelper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var latestRelease: ReleaseInfo? = null
    private var downloadedApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ussd_prefs", Context.MODE_PRIVATE)

        setupBottomNavigation()
        setupDashboardActions()
        setupSettingsAndUpdater()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
        val dashboardView = findViewById<View>(R.id.dashboardView)
        val settingsView = findViewById<View>(R.id.settingsView)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    dashboardView.visibility = View.VISIBLE
                    settingsView.visibility = View.GONE
                    true
                }
                R.id.nav_settings -> {
                    dashboardView.visibility = View.GONE
                    settingsView.visibility = View.VISIBLE
                    // Check for updates automatically when user opens Settings tab if not checked yet
                    if (latestRelease == null) {
                        performUpdateCheck(silent = true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupDashboardActions() {
        findViewById<Button>(R.id.enableAccessibilityBtn)?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Dial *999# targeting SIM 1
        findViewById<Button>(R.id.dial999Btn)?.setOnClickListener {
            dialUssdWithPreference("*999#", defaultSimSlot = 1)
        }

        // Dial *777# targeting SIM 2
        findViewById<Button>(R.id.dial777Btn)?.setOnClickListener {
            dialUssdWithPreference("*777#", defaultSimSlot = 2)
        }

        // Custom USSD dialer buttons
        val customInput = findViewById<EditText>(R.id.customUssdInput)
        findViewById<Button>(R.id.customDialSim1Btn)?.setOnClickListener {
            val code = customInput?.text?.toString()?.trim()
            if (code.isNullOrEmpty()) {
                Toast.makeText(this, "Please enter a USSD code (e.g. *999#)", Toast.LENGTH_SHORT).show()
            } else {
                dialUssd(code, simSlot = 1)
            }
        }

        findViewById<Button>(R.id.customDialSim2Btn)?.setOnClickListener {
            val code = customInput?.text?.toString()?.trim()
            if (code.isNullOrEmpty()) {
                Toast.makeText(this, "Please enter a USSD code (e.g. *777#)", Toast.LENGTH_SHORT).show()
            } else {
                dialUssd(code, simSlot = 2)
            }
        }
    }

    private fun setupSettingsAndUpdater() {
        val currentVersionText = findViewById<TextView>(R.id.currentVersionText)
        val checkUpdateBtn = findViewById<Button>(R.id.checkUpdateBtn)
        val downloadInstallBtn = findViewById<Button>(R.id.downloadInstallBtn)
        val openGithubBtn = findViewById<Button>(R.id.openGithubRepoBtn)

        val currentVersion = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        currentVersionText?.text = "Installed: $currentVersion"

        checkUpdateBtn?.setOnClickListener {
            performUpdateCheck(silent = false)
        }

        downloadInstallBtn?.setOnClickListener {
            val release = latestRelease
            if (release != null) {
                performApkDownloadAndInstall(release)
            }
        }

        openGithubBtn?.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tinkorosh/ussd-two"))
            startActivity(browserIntent)
        }

        // Dialing SIM preference radios
        val radioGroup = findViewById<RadioGroup>(R.id.simPreferenceRadioGroup)
        val savedPref = prefs.getInt("pref_sim_choice", 0) // 0: ask, 1: sim1, 2: sim2
        when (savedPref) {
            1 -> findViewById<RadioButton>(R.id.radioSim1)?.isChecked = true
            2 -> findViewById<RadioButton>(R.id.radioSim2)?.isChecked = true
            else -> findViewById<RadioButton>(R.id.radioSimAsk)?.isChecked = true
        }

        radioGroup?.setOnCheckedChangeListener { _, checkedId ->
            val choice = when (checkedId) {
                R.id.radioSim1 -> 1
                R.id.radioSim2 -> 2
                else -> 0
            }
            prefs.edit().putInt("pref_sim_choice", choice).apply()
        }
    }

    private fun performUpdateCheck(silent: Boolean) {
        val statusBadge = findViewById<TextView>(R.id.updateStatusBadge)
        val releaseBox = findViewById<View>(R.id.releaseDetailsBox)
        val releaseTag = findViewById<TextView>(R.id.releaseTagText)
        val releaseSize = findViewById<TextView>(R.id.releaseSizeText)
        val releaseNotes = findViewById<TextView>(R.id.releaseNotesText)
        val downloadBtn = findViewById<Button>(R.id.downloadInstallBtn)
        val checkBtn = findViewById<Button>(R.id.checkUpdateBtn)

        statusBadge?.text = "Checking..."
        statusBadge?.setTextColor(android.graphics.Color.parseColor("#38BDF8"))
        checkBtn?.isEnabled = false

        lifecycleScope.launch {
            val result = AppUpdater.checkLatestRelease(BuildConfig.VERSION_NAME)
            checkBtn?.isEnabled = true

            result.onSuccess { release ->
                latestRelease = release

                val sizeInMb = if (release.apkSize > 0) {
                    String.format("%.1f MB", release.apkSize / (1024.0 * 1024.0))
                } else {
                    "APK Ready"
                }

                releaseTag?.text = "Latest: ${release.tagName} (${release.title})"
                releaseSize?.text = sizeInMb
                releaseNotes?.text = release.releaseNotes.ifBlank { "Release ${release.tagName} is available." }
                releaseBox?.visibility = View.VISIBLE

                if (release.isNewerVersion) {
                    statusBadge?.text = "Update Available"
                    statusBadge?.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
                    statusBadge?.setBackgroundResource(R.drawable.bg_status_inactive)
                    downloadBtn?.visibility = View.VISIBLE
                    downloadBtn?.text = "Download & Install ${release.tagName}"
                    if (!silent) {
                        Toast.makeText(this@MainActivity, "Update ${release.tagName} is available!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    statusBadge?.text = "Up to date"
                    statusBadge?.setTextColor(android.graphics.Color.parseColor("#34D399"))
                    statusBadge?.setBackgroundResource(R.drawable.bg_status_active)
                    // Still allow re-installing if the user wants
                    downloadBtn?.visibility = View.VISIBLE
                    downloadBtn?.text = "Re-install Latest APK (${release.tagName})"
                    if (!silent) {
                        Toast.makeText(this@MainActivity, "You are using the latest version.", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { error ->
                statusBadge?.text = "Check failed"
                statusBadge?.setTextColor(android.graphics.Color.parseColor("#EF4444"))
                if (!silent) {
                    Toast.makeText(
                        this@MainActivity,
                        "Update check: ${error.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun performApkDownloadAndInstall(release: ReleaseInfo) {
        val progressBox = findViewById<View>(R.id.downloadProgressBox)
        val progressBar = findViewById<ProgressBar>(R.id.downloadProgressBar)
        val progressText = findViewById<TextView>(R.id.downloadProgressText)
        val downloadBtn = findViewById<Button>(R.id.downloadInstallBtn)
        val checkBtn = findViewById<Button>(R.id.checkUpdateBtn)

        progressBox?.visibility = View.VISIBLE
        downloadBtn?.isEnabled = false
        checkBtn?.isEnabled = false

        lifecycleScope.launch {
            val result = AppUpdater.downloadApk(this@MainActivity, release.apkDownloadUrl) { percent, downloaded, total ->
                if (percent >= 0) {
                    progressBar?.isIndeterminate = false
                    progressBar?.progress = percent
                    val dlMb = String.format("%.1f", downloaded / (1024.0 * 1024.0))
                    val totalMb = String.format("%.1f", total / (1024.0 * 1024.0))
                    progressText?.text = "Downloading: $dlMb MB / $totalMb MB ($percent%)"
                } else {
                    progressBar?.isIndeterminate = true
                    val dlMb = String.format("%.1f", downloaded / (1024.0 * 1024.0))
                    progressText?.text = "Downloading: $dlMb MB..."
                }
            }

            downloadBtn?.isEnabled = true
            checkBtn?.isEnabled = true

            result.onSuccess { apkFile ->
                downloadedApk = apkFile
                progressText?.text = "Download complete! Opening installer..."
                Toast.makeText(this@MainActivity, "Download complete! Starting installer...", Toast.LENGTH_SHORT).show()
                AppUpdater.installApk(this@MainActivity, apkFile)
            }.onFailure { error ->
                progressText?.text = "Download failed: ${error.localizedMessage}"
                Toast.makeText(this@MainActivity, "Error downloading APK: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dialUssdWithPreference(ussdCode: String, defaultSimSlot: Int) {
        val pref = prefs.getInt("pref_sim_choice", 0)
        val targetSlot = when (pref) {
            1 -> 1
            2 -> 2
            else -> defaultSimSlot
        }
        dialUssd(ussdCode, simSlot = targetSlot)
    }

    private fun updateAccessibilityStatus() {
        val statusText = findViewById<TextView>(R.id.accessibilityStatusText) ?: return
        val enableBtn = findViewById<Button>(R.id.enableAccessibilityBtn)
        val isEnabled = isAccessibilityServiceEnabled()

        if (isEnabled) {
            statusText.text = "● Service Active & Ready"
            statusText.setTextColor(android.graphics.Color.parseColor("#34D399"))
            enableBtn?.text = "Accessibility Active (Tap to Manage)"
        } else {
            statusText.text = "● Setup Required — Tap to Enable"
            statusText.setTextColor(android.graphics.Color.parseColor("#FBBF24"))
            enableBtn?.text = "Enable Accessibility Service"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "${packageName}/${UssdAccessibilityService::class.java.canonicalName}"
        val expectedShortName = "${packageName}/${UssdAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(expectedComponentName, ignoreCase = true) ||
                component.equals(expectedShortName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun dialUssd(ussdCode: String, simSlot: Int) {
        val permissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1)
            return
        }

        // 1. Tell our Accessibility Service that this USSD call is initiated by our app
        UssdAccessibilityService.isAppInitiatedCall = true

        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(ussdCode)))

        val targetSlotIndex = simSlot - 1
        val activeSubscriptions = try {
            subscriptionManager.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            null
        }

        val targetSubInfo = activeSubscriptions?.find { it.simSlotIndex == targetSlotIndex }
        val phoneAccounts = try {
            telecomManager.callCapablePhoneAccounts
        } catch (e: SecurityException) {
            null
        }

        var matchedHandle: android.telecom.PhoneAccountHandle? = null

        if (phoneAccounts != null && phoneAccounts.isNotEmpty()) {
            if (targetSubInfo != null) {
                val targetSubIdStr = targetSubInfo.subscriptionId.toString()
                val targetIccId = targetSubInfo.iccId ?: ""
                val targetSlotStr = targetSlotIndex.toString()

                // Advanced dual-SIM identifier matcher (supports subIds, physical slots, and ICCIDs)
                matchedHandle = phoneAccounts.find { handle ->
                    val handleId = (handle.id ?: "").lowercase()
                    handleId == targetSubIdStr ||
                            handleId == targetSlotStr ||
                            handleId == "slot$targetSlotStr" ||
                            handleId == "sim$targetSlotStr" ||
                            (targetIccId.isNotEmpty() && handleId.contains(targetIccId.lowercase()))
                }
            }

            if (matchedHandle != null) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, matchedHandle)
            }
        }

        // Pass subscription ID directly using the active subId (4 for SIM 1, 3 for SIM 2)
        if (targetSubInfo != null) {
            val targetSubId = targetSubInfo.subscriptionId
            intent.putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", targetSubId)
            intent.putExtra("subscription", targetSubId)
            intent.putExtra("subId", targetSubId)
        }

        // OEM fallbacks for physical slot mapping (Slot 0 for SIM 1, Slot 1 for SIM 2)
        intent.putExtra("com.android.phone.extra.slot", targetSlotIndex)
        intent.putExtra("com.android.phone.force.slot", true)
        intent.putExtra("simSlot", targetSlotIndex)
        intent.putExtra("slotId", targetSlotIndex)
        intent.putExtra("simId", targetSlotIndex)

        startActivity(intent)
    }
}
