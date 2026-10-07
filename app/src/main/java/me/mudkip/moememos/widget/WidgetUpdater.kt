package me.mudkip.moememos.widget

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Utility class to update all widget instances when memos are changed
 */
object WidgetUpdater {
    /**
     * Update all widget instances
     */
    fun updateWidgets(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            WidgetUpdateScheduler.updateAllWidgets(context)
        }
    }
}
