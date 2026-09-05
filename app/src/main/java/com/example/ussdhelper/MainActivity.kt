package com.example.ussdhelper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class QuickPreset(
    val id: String,
    var name: String,
    var code: String,
    var simSlot: Int, // 1 for SIM 1, 2 for SIM 2, 0 for default
    var pin: String = "",
    val isCustom: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var latestRelease: ReleaseInfo? = null
    private var downloadedApk: File? = null
    private val presetsList = mutableListOf<QuickPreset>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ussd_prefs", Context.MODE_PRIVATE)

        setupBottomNavigation()
        setupAccessibilityBridgeAndTestButtons()
        setupPresetsSystem()
        setupCustomDialer()
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
                    if (latestRelease == null) {
                        performUpdateCheck(silent = true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupAccessibilityBridgeAndTestButtons() {
        val toggleLaunchersBtn = findViewById<Button>(R.id.toggleLaunchersBtn)
        val testLaunchersContainer = findViewById<View>(R.id.testLaunchersContainer)
        val accessibilityCard = findViewById<View>(R.id.accessibilityCard)

        val toggleAction = {
            if (testLaunchersContainer.visibility == View.VISIBLE) {
                testLaunchersContainer.visibility = View.GONE
                toggleLaunchersBtn?.text = if (isAccessibilityServiceEnabled()) "Shortcuts ▾" else "Direct Jump ▾"
            } else {
                testLaunchersContainer.visibility = View.VISIBLE
                toggleLaunchersBtn?.text = "Hide ▲"
            }
        }

        toggleLaunchersBtn?.setOnClickListener { toggleAction() }
        accessibilityCard?.setOnClickListener {
            if (!isAccessibilityServiceEnabled() && testLaunchersContainer.visibility != View.VISIBLE) {
                toggleAction()
            }
        }

        // Test Button 1: Direct Service Page (target fragment key)
        findViewById<Button>(R.id.btnTestDirectService)?.setOnClickListener {
            val componentName = ComponentName(packageName, UssdAccessibilityService::class.java.name).flattenToString()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", componentName)
                val bundle = Bundle().apply {
                    putString(":settings:fragment_args_key", componentName)
                }
                putExtra(":settings:show_fragment_args", bundle)
            }
            tryLaunchIntent(intent, "Direct Service page")
        }

        // Test Button 2: Installed / Downloaded Services Subpage
        findViewById<Button>(R.id.btnTestInstalledServices)?.setOnClickListener {
            val candidates = listOf(
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AccessibilityInstalledServiceActivity")),
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    data = Uri.parse("package:$packageName")
                },
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.accessibility.InstalledServicesActivity")),
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.accessibility.InstalledServices")),
                Intent("com.transsion.settings.accessibility.INSTALLED_SERVICES"),
                Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.accessibility.AccessibilityInstalledServicesSettings"))
            )

            var launched = false
            for (candidate in candidates) {
                try {
                    if (candidate.resolveActivity(packageManager) != null) {
                        startActivity(candidate)
                        Toast.makeText(this, "Opening Installed Services subpage", Toast.LENGTH_SHORT).show()
                        launched = true
                        break
                    }
                } catch (_: Exception) {}
            }

            if (!launched) {
                tryLaunchIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "General Accessibility (Installed Services fallback)")
            }
        }

        // Test Button 3: App Details (Allow Restricted Settings on Android 13+)
        findViewById<Button>(R.id.btnTestAppDetails)?.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            tryLaunchIntent(intent, "App Details (Allow Restricted Settings)")
        }

        // Test Button 4: General Accessibility Settings
        findViewById<Button>(R.id.btnTestGeneralSettings)?.setOnClickListener {
            tryLaunchIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "General Accessibility Settings")
        }
    }

    private fun tryLaunchIntent(intent: Intent, label: String) {
        try {
            startActivity(intent)
            Toast.makeText(this, "Opening: $label", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Falling back to Accessibility Settings", Toast.LENGTH_SHORT).show()
            } catch (err: Exception) {
                Toast.makeText(this, "Could not open settings: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupPresetsSystem() {
        loadPresets()
        renderPresets()

        findViewById<Button>(R.id.btnAddPreset)?.setOnClickListener {
            showAddOrEditPresetDialog(existing = null)
        }
    }

    private fun loadPresets() {
        presetsList.clear()

        // 1. Built-in defaults (incorporating any user-saved PIN overrides)
        val pin999 = prefs.getString("pin_for_*999#", "") ?: ""
        val pin777 = prefs.getString("pin_for_*777#", "") ?: ""
        val pin804 = prefs.getString("pin_for_*804#", "") ?: ""
        val pin127 = prefs.getString("pin_for_*127#", "") ?: ""

        presetsList.add(QuickPreset("def_999", "Ethio Telecom Portal", "*999#", simSlot = 1, pin = pin999, isCustom = false))
        presetsList.add(QuickPreset("def_777", "Airtime & Bundles", "*777#", simSlot = 2, pin = pin777, isCustom = false))
        presetsList.add(QuickPreset("def_804", "Check Account Balance", "*804#", simSlot = 1, pin = pin804, isCustom = false))
        presetsList.add(QuickPreset("def_127", "Check Package Status", "*127#", simSlot = 1, pin = pin127, isCustom = false))

        // 2. User-added custom presets
        val jsonStr = prefs.getString("custom_presets_json", null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val code = obj.optString("code", "*999#")
                    val savedPin = obj.optString("pin", prefs.getString("pin_for_$code", "") ?: "")
                    presetsList.add(
                        QuickPreset(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            name = obj.optString("name", "Custom Preset"),
                            code = code,
                            simSlot = obj.optInt("simSlot", 1),
                            pin = savedPin,
                            isCustom = true
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveCustomPresets() {
        val customPresets = presetsList.filter { it.isCustom }
        val array = JSONArray()
        for (preset in customPresets) {
            val obj = JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("code", preset.code)
                put("simSlot", preset.simSlot)
                put("pin", preset.pin)
            }
            array.put(obj)
            if (preset.pin.isNotEmpty()) {
                prefs.edit().putString("pin_for_${preset.code}", preset.pin).apply()
            }
        }
        prefs.edit().putString("custom_presets_json", array.toString()).apply()
    }

    private fun renderPresets() {
        val container = findViewById<LinearLayout>(R.id.presetsContainer) ?: return
        container.removeAllViews()

        val inflater = LayoutInflater.from(this)

        for (preset in presetsList) {
            val itemView = inflater.inflate(R.layout.item_quick_preset, container, false)
            val simBadge = itemView.findViewById<TextView>(R.id.presetSimBadge)
            val nameText = itemView.findViewById<TextView>(R.id.presetNameText)
            val codeText = itemView.findViewById<TextView>(R.id.presetCodeText)
            val pinBadge = itemView.findViewById<TextView>(R.id.presetPinBadge)
            val dialBtn = itemView.findViewById<Button>(R.id.presetDialBtn)
            val editBtn = itemView.findViewById<ImageButton>(R.id.presetEditBtn)
            val deleteBtn = itemView.findViewById<ImageButton>(R.id.presetDeleteBtn)

            nameText.text = preset.name
            codeText.text = preset.code

            // Display SIM badge
            when (preset.simSlot) {
                1 -> {
                    simBadge.text = "SIM 1"
                    simBadge.setBackgroundResource(R.drawable.bg_sim1_badge)
                    simBadge.setTextColor(Color.parseColor("#38BDF8"))
                }
                2 -> {
                    simBadge.text = "SIM 2"
                    simBadge.setBackgroundResource(R.drawable.bg_sim2_badge)
                    simBadge.setTextColor(Color.parseColor("#C084FC"))
                }
                else -> {
                    simBadge.text = "AUTO"
                    simBadge.setBackgroundResource(R.drawable.bg_status_active)
                    simBadge.setTextColor(Color.parseColor("#34D399"))
                }
            }

            // PIN indicator
            if (preset.pin.isNotEmpty()) {
                pinBadge.visibility = View.VISIBLE
            } else {
                pinBadge.visibility = View.GONE
            }

            // Dial button: STRICTLY labeled "Dial" with vibrant emerald styling
            dialBtn.text = "Dial"
            dialBtn.setOnClickListener {
                UssdAccessibilityService.activeDialedCode = preset.code
                UssdAccessibilityService.activeDialedPin = preset.pin
                dialUssdWithPreference(preset.code, defaultSimSlot = if (preset.simSlot != 0) preset.simSlot else 1)
            }

            // Edit button: allows editing name, code, SIM slot, and PIN
            editBtn.setOnClickListener {
                showAddOrEditPresetDialog(existing = preset)
            }

            // Delete button: visible for custom presets
            if (preset.isCustom) {
                deleteBtn.visibility = View.VISIBLE
                deleteBtn.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Delete Preset")
                        .setMessage("Are you sure you want to delete '${preset.name}' (${preset.code})?")
                        .setPositiveButton("Delete") { _, _ ->
                            presetsList.removeAll { it.id == preset.id }
                            saveCustomPresets()
                            renderPresets()
                            Toast.makeText(this, "Preset deleted", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } else {
                deleteBtn.visibility = View.GONE
            }

            container.addView(itemView)
        }
    }

    private fun showAddOrEditPresetDialog(existing: QuickPreset? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_preset, null)
        val titleText = dialogView.findViewById<TextView>(R.id.dialogTitleText)
        val nameInput = dialogView.findViewById<EditText>(R.id.dialogPresetNameInput)
        val codeInput = dialogView.findViewById<EditText>(R.id.dialogPresetCodeInput)
        val pinInput = dialogView.findViewById<EditText>(R.id.dialogPresetPinInput)
        val radioSim1 = dialogView.findViewById<RadioButton>(R.id.dialogRadioSim1)
        val radioSim2 = dialogView.findViewById<RadioButton>(R.id.dialogRadioSim2)
        val radioDefault = dialogView.findViewById<RadioButton>(R.id.dialogRadioDefault)
        val cancelBtn = dialogView.findViewById<Button>(R.id.dialogCancelBtn)
        val saveBtn = dialogView.findViewById<Button>(R.id.dialogSaveBtn)

        if (existing != null) {
            titleText.text = "Edit Preset & PIN"
            nameInput.setText(existing.name)
            codeInput.setText(existing.code)
            pinInput.setText(existing.pin)
            when (existing.simSlot) {
                1 -> radioSim1.isChecked = true
                2 -> radioSim2.isChecked = true
                else -> radioDefault.isChecked = true
            }
        } else {
            titleText.text = "Add Quick Action Preset"
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        cancelBtn.setOnClickListener { dialog.dismiss() }

        saveBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            var code = codeInput.text.toString().trim()
            val pin = pinInput.text.toString().trim()

            if (name.isEmpty()) {
                nameInput.error = "Please enter a preset name"
                return@setOnClickListener
            }
            if (code.isEmpty()) {
                codeInput.error = "Please enter a USSD code"
                return@setOnClickListener
            }

            if (!code.startsWith("*")) {
                code = "*$code"
            }
            if (!code.endsWith("#")) {
                code = "$code#"
            }

            val selectedSlot = when {
                radioSim1.isChecked -> 1
                radioSim2.isChecked -> 2
                else -> 0
            }

            if (existing != null) {
                existing.name = name
                existing.code = code
                existing.simSlot = selectedSlot
                existing.pin = pin

                // Save PIN in persistent storage for this code
                prefs.edit().putString("pin_for_$code", pin).apply()

                if (existing.isCustom) {
                    saveCustomPresets()
                }
            } else {
                val newPreset = QuickPreset(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    code = code,
                    simSlot = selectedSlot,
                    pin = pin,
                    isCustom = true
                )
                presetsList.add(newPreset)
                prefs.edit().putString("pin_for_$code", pin).apply()
                saveCustomPresets()
            }

            renderPresets()
            val toastMsg = if (pin.isNotEmpty()) "Saved with PIN auto-fill!" else "Preset saved!"
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupCustomDialer() {
        val customInput = findViewById<EditText>(R.id.customUssdInput)
        val customRadioSim2 = findViewById<RadioButton>(R.id.customRadioSim2)
        val customDialBtn = findViewById<Button>(R.id.customDialBtn)

        customDialBtn?.setOnClickListener {
            val code = customInput?.text?.toString()?.trim()
            if (code.isNullOrEmpty()) {
                Toast.makeText(this, "Please enter a USSD code (e.g. *999#)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val targetSim = if (customRadioSim2?.isChecked == true) 2 else 1
            val savedPin = prefs.getString("pin_for_$code", "") ?: ""

            UssdAccessibilityService.activeDialedCode = code
            UssdAccessibilityService.activeDialedPin = savedPin

            dialUssd(code, simSlot = targetSim)
        }
    }

    private fun setupSettingsAndUpdater() {
        val currentVersionText = findViewById<TextView>(R.id.currentVersionText)
        val checkUpdateBtn = findViewById<Button>(R.id.checkUpdateBtn)
        val downloadInstallBtn = findViewById<Button>(R.id.downloadInstallBtn)
        val openGithubBtn = findViewById<Button>(R.id.openGithubRepoBtn)

        val savedInstalledTag = prefs.getString("last_installed_release_tag", null)
        val currentVersion = if (!savedInstalledTag.isNullOrEmpty() && BuildConfig.VERSION_CODE <= 1) {
            savedInstalledTag
        } else {
            "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        }
        currentVersionText?.text = currentVersion

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
        val savedPref = prefs.getInt("pref_sim_choice", 0)
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
        val downloadBtn = findViewById<Button>(R.id.downloadInstallBtn)
        val checkBtn = findViewById<Button>(R.id.checkUpdateBtn)

        statusBadge?.text = "● Checking..."
        statusBadge?.setTextColor(Color.parseColor("#38BDF8"))
        checkBtn?.isEnabled = false

        val savedInstalledTag = prefs.getString("last_installed_release_tag", null)
        lifecycleScope.launch {
            val result = AppUpdater.checkLatestRelease(
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE,
                lastInstalledTag = savedInstalledTag
            )
            checkBtn?.isEnabled = true

            result.onSuccess { release ->
                latestRelease = release

                if (release.isNewerVersion) {
                    statusBadge?.text = "● Update Available (${release.tagName})"
                    statusBadge?.setTextColor(Color.parseColor("#F59E0B"))
                    downloadBtn?.visibility = View.VISIBLE
                    downloadBtn?.text = "Install ${release.tagName}"
                    if (!silent) {
                        Toast.makeText(this@MainActivity, "Update ${release.tagName} is available!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    statusBadge?.text = "● Up to date"
                    statusBadge?.setTextColor(Color.parseColor("#34D399"))
                    downloadBtn?.visibility = View.GONE
                    if (!silent) {
                        Toast.makeText(this@MainActivity, "You are using the latest version.", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { error ->
                statusBadge?.text = "● Check failed"
                statusBadge?.setTextColor(Color.parseColor("#EF4444"))
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
                prefs.edit().putString("last_installed_release_tag", release.tagName).apply()
                findViewById<TextView>(R.id.currentVersionText)?.text = release.tagName
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
        val statusIcon = findViewById<ImageView>(R.id.accessibilityStatusIcon)
        val toggleBtn = findViewById<Button>(R.id.toggleLaunchersBtn)
        val isEnabled = isAccessibilityServiceEnabled()

        if (isEnabled) {
            statusText.text = "● Service Active & Ready"
            statusText.setTextColor(Color.parseColor("#34D399"))
            statusIcon?.setColorFilter(Color.parseColor("#34D399"))
            toggleBtn?.text = "Shortcuts ▾"
        } else {
            statusText.text = "● Setup Required — Tap to Enable"
            statusText.setTextColor(Color.parseColor("#FBBF24"))
            statusIcon?.setColorFilter(Color.parseColor("#FBBF24"))
            toggleBtn?.text = "Direct Jump ▾"
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

        if (targetSubInfo != null) {
            val targetSubId = targetSubInfo.subscriptionId
            intent.putExtra("android.telephony.extra.SUBSCRIPTION_INDEX", targetSubId)
            intent.putExtra("subscription", targetSubId)
            intent.putExtra("subId", targetSubId)
        }

        intent.putExtra("com.android.phone.extra.slot", targetSlotIndex)
        intent.putExtra("com.android.phone.force.slot", true)
        intent.putExtra("simSlot", targetSlotIndex)
        intent.putExtra("slotId", targetSlotIndex)
        intent.putExtra("simId", targetSlotIndex)

        startActivity(intent)
    }
}
