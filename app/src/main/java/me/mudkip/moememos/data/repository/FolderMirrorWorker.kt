package me.mudkip.moememos.data.repository

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.mudkip.moememos.data.local.FolderMirrorPrefs
import me.mudkip.moememos.data.local.MarkdownFolderStore
import me.mudkip.moememos.data.local.MoeMemosDatabase
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 周期性对账。存在的意义只有一个：把外部编辑器（Obsidian / VS Code / 桌面端）
 * 对文件的修改收回 App。
 *
 * 不做成前台 Service，是因为它本来就不是实时需求——15 分钟的延迟对“闪念笔记”
 * 完全够用，而常驻服务会带来耗电和保活的麻烦。
 *
 * 刻意不依赖 Hilt：这里只需要 DAO 和 SAF，直接从 applicationContext 构造即可，
 * 省掉 hilt-work 的接入成本。
 */
class FolderMirrorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!FolderMirrorPrefs.isActive(applicationContext)) return Result.success()
        return try {
            val dao = MoeMemosDatabase.getDatabase(applicationContext).memoDao()
            val store = MarkdownFolderStore(applicationContext)
            val engine = FolderMirrorEngine(dao, store)
            val changes = engine.reconcile()
            Timber.d("FolderMirror: reconcile finished, %d change(s)", changes)
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "FolderMirror: reconcile failed")
            Result.retry()
        }
    }
}

/** WorkManager 排程入口，UI 与 App 启动都用这里。 */
object FolderMirrorScheduler {

    private const val PERIODIC_WORK = "moememos-folder-mirror-periodic"
    private const val IMMEDIATE_WORK = "moememos-folder-mirror-now"

    /** 最小周期就是 15 分钟；KEEP 保证重复调用不会叠加。 */
    fun start(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FolderMirrorWorker>(15L, TimeUnit.MINUTES).build(),
            )
        }.onFailure { Timber.w(it, "FolderMirror: cannot schedule periodic work") }
    }

    fun stop(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK) }
    }

    /** 立即跑一次：刚绑定文件夹时的全量导出、以及用户手点“立即同步”都走这里。 */
    fun syncNow(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FolderMirrorWorker>().build(),
            )
        }.onFailure { Timber.w(it, "FolderMirror: cannot enqueue immediate work") }
    }
}
