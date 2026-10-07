package me.mudkip.moememos

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import me.mudkip.moememos.data.local.FolderMirrorPrefs
import me.mudkip.moememos.data.repository.FolderMirrorScheduler
import me.mudkip.moememos.ui.security.AppLockSession
import timber.log.Timber

@HiltAndroidApp
class MoeMemosApp: Application() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var CONTEXT: Context
    }

    override fun attachBaseContext(base: Context?) {
        CONTEXT = this
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Timber.plant(Timber.DebugTree())
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppLockSession.markAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                AppLockSession.markAppBackgrounded()
            }
        })
        // 已启用 Markdown 文件夹同步时确保周期对账在跑（KEEP 策略，重复调用不会叠加）
        if (FolderMirrorPrefs.isActive(this)) {
            FolderMirrorScheduler.start(this)
        }
    }
}
