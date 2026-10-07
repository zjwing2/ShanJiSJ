package me.mudkip.moememos.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MoeMemosGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MoeMemosGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateScheduler.scheduleWidgetUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdateScheduler.cancelWidgetUpdatesIfNoWidgets(context)
    }

    /**
     * Worker class to update the widget periodically
     */
    class WidgetUpdateWorker(
        private val appContext: Context,
        workerParams: WorkerParameters
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            WidgetUpdateScheduler.updateAllWidgets(appContext)
            return Result.success()
        }
    }
}
