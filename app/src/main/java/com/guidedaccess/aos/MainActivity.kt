package com.guidedaccess.aos

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvService: TextView
    private lateinit var btnOpenAccessibility: Button
    private lateinit var restrictedHint: View
    private lateinit var btnAppInfo: Button
    private lateinit var tvPin: TextView
    private lateinit var btnSetPin: Button
    private lateinit var swStrong: MaterialSwitch
    private lateinit var tvOwner: TextView
    private lateinit var btnClearOwner: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = b.left, top = b.top, right = b.right, bottom = b.bottom)
            insets
        }

        tvService = findViewById(R.id.tv_service_status)
        btnOpenAccessibility = findViewById(R.id.btn_open_accessibility)
        restrictedHint = findViewById(R.id.tv_restricted_hint)
        btnAppInfo = findViewById(R.id.btn_app_info)
        tvPin = findViewById(R.id.tv_pin_status)
        btnSetPin = findViewById(R.id.btn_set_pin)
        swStrong = findViewById(R.id.sw_strong_lock)
        tvOwner = findViewById(R.id.tv_owner_status)
        btnClearOwner = findViewById(R.id.btn_clear_owner)

        btnOpenAccessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btnAppInfo.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        btnSetPin.setOnClickListener { changePin() }
        findViewById<Button>(R.id.btn_pick_app).setOnClickListener { pickApp() }

        findViewById<MaterialSwitch>(R.id.sw_triple_click).apply {
            isChecked = prefs.tripleClickEnabled
            setOnCheckedChangeListener { _, c -> prefs.tripleClickEnabled = c }
        }
        findViewById<MaterialSwitch>(R.id.sw_keep_after_reboot).apply {
            isChecked = prefs.keepAfterReboot
            setOnCheckedChangeListener { _, c -> prefs.keepAfterReboot = c }
        }
        swStrong.isChecked = prefs.strongLockEnabled
        swStrong.setOnCheckedChangeListener { _, c -> prefs.strongLockEnabled = c }
        btnClearOwner.setOnClickListener { confirmClearOwner() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        tvService.setText(if (enabled) R.string.service_on else R.string.service_off)
        btnOpenAccessibility.visibility = if (enabled) View.GONE else View.VISIBLE
        val showRestricted = !enabled && Build.VERSION.SDK_INT >= 33
        restrictedHint.visibility = if (showRestricted) View.VISIBLE else View.GONE
        btnAppInfo.visibility = restrictedHint.visibility

        tvPin.setText(if (prefs.hasPin()) R.string.pin_set else R.string.pin_not_set)
        btnSetPin.setText(if (prefs.hasPin()) R.string.btn_change_pin else R.string.btn_set_pin)

        val owner = isDeviceOwner()
        swStrong.isEnabled = owner && Build.VERSION.SDK_INT >= 28
        tvOwner.setText(if (owner) R.string.strong_lock_owner else R.string.strong_lock_not_owner)
        btnClearOwner.visibility = if (owner) View.VISIBLE else View.GONE
    }

    private fun isServiceEnabled(): Boolean {
        if (GuidedAccessService.instance != null) return true
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == GuidedAccessService::class.java.name
        }
    }

    private fun isDeviceOwner() =
        getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(packageName) == true

    // ------------------------------------------------------------------ 앱 선택

    private data class AppItem(val pkg: String, val label: String, val icon: Drawable)

    private fun pickApp() {
        if (GuidedAccessService.instance == null) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.dialog_service_needed)
                .setPositiveButton(R.string.btn_open_accessibility) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val loading = Toast.makeText(this, R.string.loading_apps, Toast.LENGTH_SHORT).also { it.show() }
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
                .mapNotNull { pkg ->
                    try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        AppItem(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                loading.cancel()
                if (!isFinishing) showAppPicker(apps)
            }
        }.start()
    }

    private fun showAppPicker(apps: List<AppItem>) {
        val d = resources.displayMetrics.density
        val adapter = object : ArrayAdapter<AppItem>(this, 0, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((24 * d).toInt(), (10 * d).toInt(), (24 * d).toInt(), (10 * d).toInt())
                    addView(ImageView(context), LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
                    addView(TextView(context).apply {
                        textSize = 16f
                        setPadding((16 * d).toInt(), 0, 0, 0)
                    })
                }
                val item = getItem(position)!!
                (row.getChildAt(0) as ImageView).setImageDrawable(item.icon)
                (row.getChildAt(1) as TextView).text = item.label
                return row
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_pick_app)
            .setAdapter(adapter) { _, which -> startWith(apps[which].pkg) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startWith(pkg: String) {
        val service = GuidedAccessService.instance ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        service.prepareSetup(pkg)
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ------------------------------------------------------------------ 암호

    private fun changePin() {
        if (prefs.hasPin()) {
            askPin(R.string.dialog_current_pin) { current ->
                if (prefs.checkPin(current)) askNewPin()
                else toast(R.string.toast_pin_wrong)
            }
        } else {
            askNewPin()
        }
    }

    private fun askNewPin() {
        askPin(R.string.dialog_new_pin) { first ->
            if (first.length !in 4..8) {
                toast(R.string.pin_too_short)
                return@askPin
            }
            askPin(R.string.dialog_confirm_pin) { second ->
                if (first == second) {
                    prefs.setPin(first)
                    toast(R.string.toast_pin_saved)
                    refresh()
                } else {
                    toast(R.string.toast_pin_mismatch)
                }
            }
        }
    }

    private fun askPin(title: Int, onEntered: (String) -> Unit) {
        val d = resources.displayMetrics.density
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
            gravity = Gravity.CENTER
            textSize = 22f
        }
        val container = FrameLayout(this).apply {
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ -> onEntered(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    // ------------------------------------------------------------------ 기기 소유자

    private fun confirmClearOwner() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.dialog_clear_owner)
            .setPositiveButton(R.string.ok) { _, _ ->
                try {
                    @Suppress("DEPRECATION")
                    getSystemService(DevicePolicyManager::class.java)?.clearDeviceOwnerApp(packageName)
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
