package com.guidedaccess.aos

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * 기기 소유자(Device Owner) 지정용. 지정하면 사용법 유도 중 시스템 LockTask 로
 * 홈·최근 앱·알림창을 완전히 막을 수 있다.
 *
 *   adb shell dpm set-device-owner com.guidedaccess.aos/.AdminReceiver
 */
class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    }
}
