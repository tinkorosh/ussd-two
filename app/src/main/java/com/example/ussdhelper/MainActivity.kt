package com.example.ussdhelper

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ussdhelper.adapter.UssdActionAdapter
import com.example.ussdhelper.data.UssdActionRepository
import com.example.ussdhelper.databinding.ActivityMainBinding
import com.example.ussdhelper.databinding.DialogAddEditActionBinding
import com.example.ussdhelper.databinding.DialogSelectSimBinding
import com.example.ussdhelper.databinding.DialogUssdExecutionBinding
import com.example.ussdhelper.model.SimCardInfo
import com.example.ussdhelper.model.UssdAction
import com.example.ussdhelper.util.SimManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: UssdActionRepository
    private lateinit var adapter: UssdActionAdapter
    private var detectedSims = listOf<SimCardInfo>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val stateGranted = permissions[Manifest.permission.READ_PHONE_STATE] == true
        val callGranted = permissions[Manifest.permission.CALL_PHONE] == true
        if (stateGranted || callGranted) {
            refreshSimCards()
            loadActions()
        }
        updatePermissionBanner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = UssdActionRepository(this)
        setupRecyclerView()
        setupListeners()

        checkPermissions()
        refreshSimCards()
        loadActions()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        refreshSimCards()
    }

    private fun setupRecyclerView() {
        adapter = UssdActionAdapter(
            context = this,
            onDialClick = { action -> onActionDial(action) },
            onEditClick = { action -> showAddEditDialog(action) },
            onDeleteClick = { action -> confirmDelete(action) }
        )
        binding.rvActions.layoutManager = LinearLayoutManager(this)
        binding.rvActions.adapter = adapter
    }

    private fun setupListeners() {
        // Service status button
        binding.btnServiceStatus.setOnClickListener {
            showServiceInfoDialog()
        }

        // Permission banner
        binding.btnGrantPermissions.setOnClickListener {
            requestRequiredPermissions()
        }

        // Quick Dial
        binding.btnQuickDial.setOnClickListener {
            val code = binding.etQuickUssd.text.toString().trim()
            if (code.isEmpty()) {
                binding.etQuickUssd.error = "Enter a USSD code (e.g. *999#)"
                return@setOnClickListener
            }
            binding.etQuickUssd.error = null
            if (!SimManager.hasCallPermission(this)) {
                requestRequiredPermissions()
                return@setOnClickListener
            }
            promptSimAndExecute(code, preferredSlot = 0)
        }

        // Bookmark quick code as shortcut
        binding.btnBookmarkQuick.setOnClickListener {
            val code = binding.etQuickUssd.text.toString().trim()
            showAddEditDialog(initialCode = code)
        }

        // Add Action FAB
        binding.fabAddAction.setOnClickListener {
            showAddEditDialog(null)
        }

        // Carrier Presets
        binding.btnCarrierPresets.setOnClickListener {
            showPresetsDialog()
        }

        // Reset to Defaults if empty
        binding.btnResetDefaults.setOnClickListener {
            repository.resetToDefaults()
            loadActions()
            Toast.makeText(this, "Default shortcuts restored", Toast.LENGTH_SHORT).show()
        }

        // Category Filter Chips
        binding.chipGroupFilter.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) {
                binding.chipFilterAll.isChecked = true
                adapter.setCategoryFilter("All")
                return@setOnCheckedStateChangeListener
            }
            val checkedChip = group.findViewById<Chip>(checkedIds.first())
            val category = checkedChip?.text?.toString() ?: "All"
            adapter.setCategoryFilter(category)
        }

        // Tapping SIM card shows details
        binding.cardSim1.setOnClickListener {
            val sim1 = detectedSims.firstOrNull { it.slotIndex == 0 }
            Toast.makeText(this, "SIM 1: ${sim1?.displayLabel ?: "No SIM detected"}", Toast.LENGTH_SHORT).show()
        }
        binding.cardSim2.setOnClickListener {
            val sim2 = detectedSims.firstOrNull { it.slotIndex == 1 }
            Toast.makeText(this, "SIM 2: ${sim2?.displayLabel ?: "No SIM detected"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        val hasCall = SimManager.hasCallPermission(this)
        val hasState = SimManager.hasPhoneStatePermission(this)

        if (!hasCall || !hasState) {
            updatePermissionBanner()
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun updatePermissionBanner() {
        val hasState = SimManager.hasPhoneStatePermission(this)
        val hasCall = SimManager.hasCallPermission(this)
        binding.cardPermissionBanner.visibility = if (!hasState || !hasCall) View.VISIBLE else View.GONE
    }

    private fun refreshSimCards() {
        detectedSims = SimManager.detectSimCards(this)

        val sim1 = detectedSims.firstOrNull { it.slotIndex == 0 }
        if (sim1 != null && sim1.isAvailable) {
            binding.tvSim1Carrier.text = sim1.displayLabel
            binding.tvSim1Status.text = "Active • Slot 1"
        } else {
            binding.tvSim1Carrier.text = "SIM 1"
            binding.tvSim1Status.text = if (!SimManager.hasPhoneStatePermission(this)) "Permission needed" else "Not detected"
        }

        val sim2 = detectedSims.firstOrNull { it.slotIndex == 1 }
        if (sim2 != null && sim2.isAvailable) {
            binding.tvSim2Carrier.text = sim2.displayLabel
            binding.tvSim2Status.text = "Active • Slot 2"
        } else {
            binding.tvSim2Carrier.text = "Empty Slot 2"
            binding.tvSim2Status.text = if (!SimManager.hasPhoneStatePermission(this)) "Permission needed" else "No SIM detected"
        }

        adapter.updateData(repository.getActions(), detectedSims)
    }

    private fun updateServiceStatus() {
        val isServiceEnabled = isAccessibilityServiceEnabled()

        if (isServiceEnabled) {
            binding.btnServiceStatus.text = "● Interceptor Ready"
            binding.btnServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnServiceStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.accent_green_container)
            )
        } else {
            binding.btnServiceStatus.text = "● In-App Mode"
            binding.btnServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.primary_blue_light))
            binding.btnServiceStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary_blue_container)
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedServiceName = "$packageName/${UssdAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any {
            it.equals(expectedServiceName, ignoreCase = true) ||
                    it.contains(UssdAccessibilityService::class.java.simpleName, ignoreCase = true)
        }
    }

    private fun loadActions() {
        val actions = repository.getActions()
        adapter.updateData(actions, detectedSims)

        if (actions.isEmpty()) {
            binding.rvActions.visibility = View.GONE
            binding.layoutEmptyState.visibility = View.VISIBLE
        } else {
            binding.rvActions.visibility = View.VISIBLE
            binding.layoutEmptyState.visibility = View.GONE
        }
    }

    private fun onActionDial(action: UssdAction) {
        if (!SimManager.hasCallPermission(this)) {
            requestRequiredPermissions()
            return
        }

        promptSimAndExecute(action.code, action.simSlot)
    }

    private fun promptSimAndExecute(code: String, preferredSlot: Int) {
        if (preferredSlot in 1..2) {
            startInAppExecution(code, preferredSlot)
            return
        }

        // If Auto / Ask: Check how many SIMs are available
        val availableSims = detectedSims.filter { it.isAvailable }
        if (availableSims.size <= 1) {
            val targetSlot = if (availableSims.isNotEmpty()) availableSims[0].slotIndex + 1 else 0
            startInAppExecution(code, targetSlot)
        } else {
            showSimPickerDialog(code)
        }
    }

    private fun showSimPickerDialog(code: String) {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogSelectSimBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.tvSelectSimCodePrompt.text = "Executing $code"

        val sim1 = detectedSims.firstOrNull { it.slotIndex == 0 }
        val sim2 = detectedSims.firstOrNull { it.slotIndex == 1 }

        dialogBinding.tvPickSim1Title.text = "SIM 1 • ${sim1?.displayLabel ?: "Primary"}"
        dialogBinding.tvPickSim2Title.text = "SIM 2 • ${sim2?.displayLabel ?: "Secondary"}"

        dialogBinding.cardPickSim1.setOnClickListener {
            dialog.dismiss()
            startInAppExecution(code, 1)
        }

        dialogBinding.cardPickSim2.setOnClickListener {
            dialog.dismiss()
            startInAppExecution(code, 2)
        }

        dialogBinding.btnCancelPickSim.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startInAppExecution(code: String, simSlot: Int) {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogUssdExecutionBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.tvExecutionTitle.text = "Executing USSD"
        dialogBinding.tvExecutionSubtitle.text = "In-App Direct Cellular Query"
        dialogBinding.tvExecutionCodeBadge.text = code

        val slotInfo = if (simSlot in 1..2) {
            val sim = detectedSims.firstOrNull { it.slotIndex == simSlot - 1 }
            "SIM $simSlot • ${sim?.displayLabel ?: "Active SIM"}"
        } else {
            val defaultSim = detectedSims.firstOrNull { it.isAvailable }
            "Default SIM • ${defaultSim?.displayLabel ?: "Cellular"}"
        }
        dialogBinding.tvExecutionSimBadge.text = slotInfo

        dialogBinding.layoutExecutionLoading.visibility = View.VISIBLE
        dialogBinding.layoutExecutionSuccess.visibility = View.GONE
        dialogBinding.layoutExecutionError.visibility = View.GONE
        dialogBinding.tvLoadingStatus.text = "Querying mobile network in-app..."

        dialogBinding.btnCancelLoading.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDoneExecution.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDismissError.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnCopyExecutionResponse.setOnClickListener {
            val text = dialogBinding.tvExecutionResponseText.text.toString()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("USSD Response", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Response copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnDialViaPhoneApp.setOnClickListener {
            dialog.dismiss()
            // Fallback: User explicitly chose to dial via the phone dialer
            SimManager.dialUssd(this, code, simSlot)
        }

        dialog.show()

        // Execute natively in-app via TelephonyManager.sendUssdRequest
        SimManager.executeUssdInApp(
            context = this,
            ussdCode = code,
            simSlot = simSlot,
            onSuccess = { returnMessage ->
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    dialogBinding.layoutExecutionLoading.visibility = View.GONE
                    dialogBinding.layoutExecutionError.visibility = View.GONE
                    dialogBinding.layoutExecutionSuccess.visibility = View.VISIBLE
                    dialogBinding.tvExecutionResponseText.text = returnMessage
                }
            },
            onError = { failureCode, errorMsg ->
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    dialogBinding.layoutExecutionLoading.visibility = View.GONE
                    dialogBinding.layoutExecutionSuccess.visibility = View.GONE
                    dialogBinding.layoutExecutionError.visibility = View.VISIBLE
                    dialogBinding.tvExecutionErrorText.text = "$errorMsg\n\nYou can run it via the phone dialer using the button below."
                }
            }
        )
    }

    private fun showServiceInfoDialog() {
        val isServiceEnabled = isAccessibilityServiceEnabled()
        val title = if (isServiceEnabled) "Automated Interceptor Active" else "Direct In-App Cellular Mode"
        val message = if (isServiceEnabled) {
            "USSD Helper runs codes directly inside this app without launching the dialer. The optional background interceptor is also active to assist if fallback dialing is ever needed."
        } else {
            "USSD Helper runs your USSD codes directly in-app using modern Android Telephony services.\n\nEnabling the optional accessibility service is only necessary if your mobile carrier requires external dialer sessions."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (isServiceEnabled) "Settings" else "Enable Optional Service") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Open Accessibility Settings", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("OK", null)
            .show()
    }

    private fun showAddEditDialog(existingAction: UssdAction? = null, initialCode: String = "") {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogAddEditActionBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val isEdit = existingAction != null
        dialogBinding.tvDialogTitle.text = if (isEdit) "Edit USSD Action" else "Add USSD Action"

        // Update SIM labels in chips with carrier names
        val sim1Label = detectedSims.firstOrNull { it.slotIndex == 0 }?.displayLabel ?: "SIM 1"
        val sim2Label = detectedSims.firstOrNull { it.slotIndex == 1 }?.displayLabel ?: "SIM 2"
        dialogBinding.chipSim1.text = "SIM 1 ($sim1Label)"
        dialogBinding.chipSim2.text = "SIM 2 ($sim2Label)"

        if (isEdit) {
            dialogBinding.etActionTitle.setText(existingAction?.title)
            dialogBinding.etActionCode.setText(existingAction?.code)
            dialogBinding.etActionDesc.setText(existingAction?.description)

            when (existingAction?.simSlot) {
                1 -> dialogBinding.chipSim1.isChecked = true
                2 -> dialogBinding.chipSim2.isChecked = true
                else -> dialogBinding.chipSimAuto.isChecked = true
            }

            when (existingAction?.category?.lowercase()) {
                "balance" -> dialogBinding.chipCatBalance.isChecked = true
                "data" -> dialogBinding.chipCatData.isChecked = true
                "money" -> dialogBinding.chipCatMoney.isChecked = true
                else -> dialogBinding.chipCatUtilities.isChecked = true
            }
        } else if (initialCode.isNotBlank()) {
            dialogBinding.etActionCode.setText(initialCode)
        }

        dialogBinding.btnCancelDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveAction.setOnClickListener {
            val title = dialogBinding.etActionTitle.text.toString().trim()
            val code = dialogBinding.etActionCode.text.toString().trim()
            val desc = dialogBinding.etActionDesc.text.toString().trim()

            if (title.isEmpty()) {
                dialogBinding.etActionTitle.error = "Name is required"
                return@setOnClickListener
            }
            if (code.isEmpty()) {
                dialogBinding.etActionCode.error = "USSD code is required"
                return@setOnClickListener
            }

            val simSlot = when {
                dialogBinding.chipSim1.isChecked -> 1
                dialogBinding.chipSim2.isChecked -> 2
                else -> 0
            }

            val category = when {
                dialogBinding.chipCatData.isChecked -> "Data"
                dialogBinding.chipCatMoney.isChecked -> "Money"
                dialogBinding.chipCatUtilities.isChecked -> "Utilities"
                else -> "Balance"
            }

            if (isEdit && existingAction != null) {
                existingAction.title = title
                existingAction.code = code
                existingAction.simSlot = simSlot
                existingAction.category = category
                existingAction.description = desc
                repository.updateAction(existingAction)
                Toast.makeText(this, "Action updated", Toast.LENGTH_SHORT).show()
            } else {
                val newAction = UssdAction(
                    title = title,
                    code = code,
                    simSlot = simSlot,
                    category = category,
                    description = desc
                )
                repository.addAction(newAction)
                Toast.makeText(this, "Action added", Toast.LENGTH_SHORT).show()
            }

            dialog.dismiss()
            loadActions()
        }

        dialog.show()
    }

    private fun showPresetsDialog() {
        val carriers = arrayOf("Ethio Telecom", "Safaricom", "Universal / General")
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Operator Template")
            .setItems(carriers) { _, which ->
                val selected = carriers[which]
                repository.addPresetCarrier(selected)
                loadActions()
                Toast.makeText(this, "Added $selected templates", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(action: UssdAction) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Shortcut?")
            .setMessage("Are you sure you want to remove '${action.title}' (${action.code})?")
            .setPositiveButton("Delete") { _, _ ->
                repository.deleteAction(action.id)
                loadActions()
                Snackbar.make(binding.root, "Action deleted", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {
                        repository.addAction(action)
                        loadActions()
                    }.show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
