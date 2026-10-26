package dz.association.app.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True on modest devices (low-RAM flag, < 3.5 GB RAM, or ≤ 4 CPU cores) or when the user disabled
 * system animations. Heavy decoration (animated backdrop, large blurred shadows, floating logo)
 * is then simplified so scrolling and transitions stay smooth.
 */
object DevicePerformance {
    fun isLowEnd(ctx: Context): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val animationsOff = runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        return am.isLowRamDevice || mem.totalMem < 3_500L * 1024 * 1024 || Runtime.getRuntime().availableProcessors() <= 4 || animationsOff
    }
}

val LocalLowEnd = staticCompositionLocalOf { false }
