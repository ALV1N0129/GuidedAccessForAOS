package com.guidedaccess.aos

import android.accessibilityservice.AccessibilityButtonController
import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.guidedaccess.aos.overlay.BlockedAreaView
import com.guidedaccess.aos.overlay.MessageOverlay
import com.guidedaccess.aos.overlay.OverlayHost
import com.guidedaccess.aos.overlay.PinOverlay
import com.guidedaccess.aos.overlay.SetupOverlay
import kotlin.math.roundToInt

/**
 * 사용법 유도의 핵심.
 *
 * - 현재 화면에 떠 있는 앱(패키지)을 감시하다가 대상 앱이 아닌 곳으로 나가면 즉시 되돌린다.
 * - 접근성 오버레이로 지정한 영역(또는 화면 전체)의 터치를 막는다.
 * - 볼륨 키 입력을 가로채서 차단하고, '볼륨 내리기 3번'을 iOS 의 '측면 버튼 3번'처럼 쓴다.
 * - 시간 제한이 끝나면 화면을 잠근다.
 * - 이 앱이 기기 소유자(Device Owner)이면 시스템 LockTask 로 홈/최근 앱까지 완전히 잠근다.
 */
class GuidedAccessService : AccessibilityService() {

    enum class State { IDLE, SETUP, ACTIVE }

    var state = State.IDLE
        private set
    var targetPackage: String? = null
        private set
    private var config = AppConfig()

    private lateinit var prefs: Prefs
    private lateinit var host: OverlayHost
    private lateinit var themed: Context
    private val handler = Handler(Looper.getMainLooper())

    /** 마지막으로 화면에 뜬 '일반 앱' (시스템 UI/키보드/이 앱의 오버레이 제외) */
    private var lastAppPackage: String? = null
    /** 마지막 창 상태 변경 이벤트의 패키지 */
    private var currentForeground: String? = null
    private var pendingSetupPackage: String? = null
    private var imePackages: Set<String> = emptySet()
    private var launcherPackages: Set<String> = emptySet()

    private var setupOverlay: SetupOverlay? = null
    private var pinOverlay: PinOverlay? = null
    private var pinView: View? = null
    private val blockers = mutableListOf<View>()
    private var shield: View? = null
    private var timeUpView: View? = null
    private var timeUp = false
    private var warnedOneMinute = false
    private var strongLockActive = false

    private val volumeDownPresses = ArrayDeque<Long>()

    private val timerTick = object : Runnable {
        override fun run() {
            if (state != State.ACTIVE || timeUp) return
            val end = prefs.sessionEndAt
            if (end <= 0) return
            val left = end - System.currentTimeMillis()
            when {
                left <= 0 -> { onTimeUp(); return }
                left <= 60_000 && !warnedOneMinute && config.timeLimitMinutes > 1 -> {
                    warnedOneMinute = true
                    toast(R.string.toast_one_minute_left)
                }
            }
            // 화면이 꺼진 동안 Handler 시간이 멈추므로 짧게 반복하며 실제 시각으로 비교한다.
            handler.postDelayed(this, minOf(left, 5_000L))
        }
    }
    private val recheckForeground = Runnable { ensureTargetInFront() }

    // ------------------------------------------------------------------ 생명주기

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        host = OverlayHost(this)
        themed = ContextThemeWrapper(this, R.style.Theme_GuidedAccess_Overlay)
        refreshPackageSets()
        registerAccessibilityButton()

        val saved = prefs.activeSessionPackage
        if (saved != null) {
            if (prefs.keepAfterReboot || prefs.sessionFromThisBoot()) restoreSession(saved)
            else prefs.saveSession(null) // iOS 처럼 재시작하면 해제
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardownOverlays()
        // 서비스가 사라지면 암호 화면을 띄울 수 없으므로 시스템 잠금은 반드시 푼다.
        releaseStrongLock()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) {
            teardownOverlays()
            releaseStrongLock()
            instance = null
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            setupOverlay?.onScreenChanged()
            host.onScreenChanged()
            if (state == State.ACTIVE && setupOverlay == null) applyBlockers()
        }
    }

    // ------------------------------------------------------------------ 이벤트

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // 이 앱의 오버레이 창은 무시 (메인 화면 Activity 는 '다른 앱'으로 취급)
        if (pkg == packageName && event.className?.toString() != MainActivity::class.java.name) return

        currentForeground = pkg
        if (pkg != SYSTEM_UI && pkg !in imePackages) lastAppPackage = pkg

        if (pkg == pendingSetupPackage) {
            pendingSetupPackage = null
            handler.postDelayed({ if (state == State.IDLE) startSetup(pkg) }, 400)
        }
        if (state == State.ACTIVE) handleForegroundChange(pkg)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP) return false

        // DOWN 과 UP 을 항상 같은 기준으로 소비해야 시스템의 볼륨 키 상태가 꼬이지 않는다.
        val consume = state == State.ACTIVE && !config.volumeEnabled

        // 사용 중에는 설정과 상관없이 항상 3번 눌러 종료할 수 있어야 한다.
        val shortcutAllowed = state == State.ACTIVE || prefs.tripleClickEnabled
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_UP && shortcutAllowed) {
            if (registerVolumeDownPress(event.eventTime)) handler.post { onShortcut() }
        }
        return consume
    }

    /** 0.6초 이내 간격으로 3번 눌렸는지 */
    private fun registerVolumeDownPress(time: Long): Boolean {
        val last = volumeDownPresses.lastOrNull()
        if (last != null && time - last > TRIPLE_PRESS_GAP_MS) volumeDownPresses.clear()
        volumeDownPresses.addLast(time)
        if (volumeDownPresses.size >= 3) {
            volumeDownPresses.clear()
            return true
        }
        return false
    }

    private fun registerAccessibilityButton() {
        // 내비게이션 바의 접근성 버튼 / 접근성 단축키(볼륨 키 길게)로도 시작/종료
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) = onShortcut()
                },
                handler,
            )
        } catch (e: Exception) {
            Log.w(TAG, "accessibility button unavailable", e)
        }
    }

    /** iOS 의 '측면 버튼 세 번 클릭'에 해당 */
    fun onShortcut() {
        when (state) {
            State.IDLE -> {
                val pkg = lastAppPackage
                if (pkg == null || pkg == packageName || pkg in launcherPackages ||
                    packageManager.getLaunchIntentForPackage(pkg) == null
                ) {
                    toast(R.string.toast_open_app_first)
                } else {
                    startSetup(pkg)
                }
            }
            State.SETUP -> Unit
            State.ACTIVE -> if (pinOverlay == null && setupOverlay == null) requestPinVerify()
        }
    }

    // ------------------------------------------------------------------ 외부(메인 화면)에서 호출

    /** 메인 화면에서 앱을 고른 경우: 앱이 화면에 뜨면 설정 화면을 연다. */
    fun prepareSetup(pkg: String) {
        if (state != State.IDLE) return
        pendingSetupPackage = pkg
        handler.postDelayed({
            if (pendingSetupPackage == pkg) {
                pendingSetupPackage = null
                if (state == State.IDLE) startSetup(pkg)
            }
        }, 2_500)
    }

    // ------------------------------------------------------------------ 설정 화면

    private fun startSetup(pkg: String) {
        if (state != State.IDLE) return
        refreshPackageSets()
        targetPackage = pkg
        config = prefs.loadConfig(pkg)
        state = State.SETUP
        showSetupOverlay(SetupOverlay.Mode.NEW)
    }

    private fun showSetupOverlay(mode: SetupOverlay.Mode) {
        removeSetupOverlay()
        val pkg = targetPackage ?: return
        val overlay = SetupOverlay(themed, host, appLabel(pkg), config.copyDeep(), mode, object : SetupOverlay.Listener {
            override fun onCancel() {
                removeSetupOverlay()
                state = State.IDLE
                targetPackage = null
            }

            override fun onStart(cfg: AppConfig) {
                config = cfg
                prefs.saveConfig(pkg, cfg)
                removeSetupOverlay()
                if (prefs.hasPin()) beginSession() else requestPinCreation()
            }

            override fun onEnd(cfg: AppConfig) {
                prefs.saveConfig(pkg, cfg)
                endSession()
            }

            override fun onResume(cfg: AppConfig) {
                val timeChanged = cfg.timeLimitMinutes != config.timeLimitMinutes
                config = cfg
                prefs.saveConfig(pkg, cfg)
                removeSetupOverlay()
                applyBlockers()
                if (timeUp || timeChanged) startTimer(cfg.timeLimitMinutes)
                ensureTargetInFront()
            }
        })
        setupOverlay = overlay
        overlay.show()
    }

    private fun removeSetupOverlay() {
        setupOverlay?.dismiss()
        setupOverlay = null
    }

    // ------------------------------------------------------------------ 암호

    private fun requestPinCreation() {
        showPin(PinOverlay.Mode.CREATE,
            onSuccess = { removePin(); beginSession() },
            onCancel = { removePin(); showSetupOverlay(SetupOverlay.Mode.NEW) })
    }

    private fun requestPinVerify() {
        showPin(PinOverlay.Mode.VERIFY,
            onSuccess = {
                removePin()
                removeBlockers()
                removeShield()
                removeTimeUp()
                showSetupOverlay(SetupOverlay.Mode.ACTIVE)
            },
            onCancel = { removePin() })
    }

    private fun showPin(mode: PinOverlay.Mode, onSuccess: () -> Unit, onCancel: () -> Unit) {
        removePin()
        val overlay = PinOverlay(themed, prefs, mode, onSuccess, onCancel)
        pinOverlay = overlay
        pinView = overlay.view
        host.addFullScreen(overlay.view)
    }

    private fun removePin() {
        pinOverlay?.release()
        host.remove(pinView)
        pinOverlay = null
        pinView = null
    }

    // ------------------------------------------------------------------ 세션

    private fun beginSession() {
        val pkg = targetPackage ?: return
        state = State.ACTIVE
        timeUp = false
        prefs.saveSession(pkg)
        applyBlockers()
        startTimer(config.timeLimitMinutes)
        if (!applyStrongLock()) ensureTargetInFront()
        toast(R.string.toast_started)
    }

    private fun restoreSession(pkg: String) {
        if (packageManager.getLaunchIntentForPackage(pkg) == null) {
            prefs.saveSession(null)
            return
        }
        targetPackage = pkg
        config = prefs.loadConfig(pkg)
        state = State.ACTIVE
        applyBlockers()
        val end = prefs.sessionEndAt
        if (end > 0) startTimer(config.timeLimitMinutes, end)
        if (!applyStrongLock()) bringTargetToFront()
    }

    private fun endSession() {
        handler.removeCallbacks(timerTick)
        handler.removeCallbacks(recheckForeground)
        teardownOverlays()
        releaseStrongLock()
        state = State.IDLE
        targetPackage = null
        timeUp = false
        prefs.saveSession(null)
        toast(R.string.toast_ended)
    }

    private fun teardownOverlays() {
        if (!::host.isInitialized) return
        removeSetupOverlay()
        removePin()
        removeBlockers()
        removeShield()
        removeTimeUp()
    }

    // ------------------------------------------------------------------ 터치 차단

    private fun applyBlockers() {
        removeBlockers()
        if (!config.touchEnabled) {
            // 터치 끔: 투명한 창으로 화면 전체를 막는다.
            val v = View(this).apply { isClickable = true }
            host.addFullScreen(v)
            blockers += v
            return
        }
        val s = host.screenSize()
        config.areas.forEach { r ->
            val x = (r.left * s.x).roundToInt()
            val y = (r.top * s.y).roundToInt()
            val w = ((r.right - r.left) * s.x).roundToInt()
            val h = ((r.bottom - r.top) * s.y).roundToInt()
            if (w <= 0 || h <= 0) return@forEach
            val v = BlockedAreaView(this)
            host.addAt(v, x, y, w, h)
            blockers += v
        }
    }

    private fun removeBlockers() {
        blockers.forEach { host.remove(it) }
        blockers.clear()
    }

    // ------------------------------------------------------------------ 앱 이탈 방지

    private fun handleForegroundChange(pkg: String) {
        when {
            pkg == targetPackage -> {
                handler.removeCallbacks(recheckForeground)
                removeShield()
            }
            pkg == SYSTEM_UI -> collapseSystemUi()
            pkg in imePackages || pkg in TRANSIENT_ALLOWED -> Unit
            else -> {
                // 홈 화면, 최근 앱, 다른 앱 → 즉시 가리고 대상 앱으로 되돌린다.
                showShield()
                bringTargetToFront()
            }
        }
    }

    private fun ensureTargetInFront() {
        if (state != State.ACTIVE) return
        val fg = currentForeground
        if (fg == null || fg == targetPackage || fg == SYSTEM_UI || fg in imePackages || fg in TRANSIENT_ALLOWED) {
            removeShield()
            return
        }
        showShield()
        bringTargetToFront()
    }

    private fun bringTargetToFront(clearTask: Boolean = false) {
        val pkg = targetPackage ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (clearTask) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(intent, lockTaskOptions())
        } catch (e: Exception) {
            Log.w(TAG, "failed to relaunch $pkg", e)
        }
        handler.removeCallbacks(recheckForeground)
        handler.postDelayed(recheckForeground, 1_500)
    }

    // ACTION_CLOSE_SYSTEM_DIALOGS 는 Android 11 이하에서만 보내며, 그 버전에서는 권한이 필요 없다.
    @SuppressLint("MissingPermission")
    private fun collapseSystemUi() {
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
        if (Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
    }

    private fun showShield() {
        if (shield != null) return
        val v = MessageOverlay.create(
            themed,
            getString(R.string.shield_title),
            getString(R.string.shield_message),
            footer = getString(R.string.exit_hint),
        )
        shield = v
        host.addFullScreen(v)
    }

    private fun removeShield() {
        host.remove(shield)
        shield = null
    }

    // ------------------------------------------------------------------ 시간 제한

    private fun startTimer(minutes: Int, endAt: Long? = null) {
        handler.removeCallbacks(timerTick)
        removeTimeUp()
        timeUp = false
        warnedOneMinute = false
        if (minutes <= 0 && endAt == null) {
            prefs.sessionEndAt = 0
            return
        }
        prefs.sessionEndAt = endAt ?: (System.currentTimeMillis() + minutes * 60_000L)
        timerTick.run()
    }

    private fun onTimeUp() {
        timeUp = true
        handler.removeCallbacks(timerTick)
        if (timeUpView != null) return
        val v = MessageOverlay.create(
            themed,
            getString(R.string.time_up_title),
            getString(R.string.time_up_message),
            footer = getString(R.string.exit_hint),
            button = getString(R.string.time_up_button),
        ) { if (pinOverlay == null) requestPinVerify() }
        timeUpView = v
        host.addFullScreen(v)
    }

    private fun removeTimeUp() {
        host.remove(timeUpView)
        timeUpView = null
    }

    // ------------------------------------------------------------------ 강력 잠금 (Device Owner)

    /** 기기 소유자라면 시스템 LockTask 로 잠그고 대상 앱을 실행한다. 적용했으면 true. */
    private fun applyStrongLock(): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !prefs.strongLockEnabled) return false
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!dpm.isDeviceOwnerApp(packageName)) return false
        val pkg = targetPackage ?: return false
        try {
            val admin = AdminReceiver.component(this)
            dpm.setLockTaskPackages(admin, arrayOf(pkg, packageName))
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            strongLockActive = true
        } catch (e: Exception) {
            Log.w(TAG, "lock task setup failed", e)
            return false
        }
        bringTargetToFront()
        // 이미 실행 중인 작업을 앞으로 가져오기만 하면 LockTask 에 들어가지 않을 수 있어 확인 후 재실행
        handler.postDelayed({
            val am = getSystemService(ActivityManager::class.java)
            if (strongLockActive && state == State.ACTIVE &&
                am?.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
            ) {
                bringTargetToFront(clearTask = true)
            }
        }, 1_200)
        return true
    }

    private fun releaseStrongLock() {
        if (!strongLockActive) return
        strongLockActive = false
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            // 허용 목록에서 빠지면 시스템이 LockTask 를 즉시 종료한다.
            dpm?.setLockTaskPackages(AdminReceiver.component(this), emptyArray())
        } catch (e: Exception) {
            Log.w(TAG, "lock task release failed", e)
        }
    }

    private fun lockTaskOptions(): Bundle? =
        if (strongLockActive && Build.VERSION.SDK_INT >= 28) {
            ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle()
        } else {
            null
        }

    // ------------------------------------------------------------------ 기타

    private fun refreshPackageSets() {
        imePackages = try {
            getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
                ?.map { it.packageName }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        launcherPackages = packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    private fun appLabel(pkg: String): CharSequence = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
    } catch (e: Exception) {
        pkg
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "GuidedAccess"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val TRIPLE_PRESS_GAP_MS = 600L

        /** 대상 앱이 잠깐 띄울 수 있는 시스템 화면 (권한 요청, 공유 시트, 구글 로그인 등) */
        private val TRANSIENT_ALLOWED = setOf(
            "android",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.gms",
        )

        @Volatile
        var instance: GuidedAccessService? = null
            private set
    }
}
