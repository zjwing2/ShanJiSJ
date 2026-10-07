package me.mudkip.moememos.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 文件夹同步的持久化设置。
 *
 * 刻意不用 DataStore / Room，也不改 Settings 模型：
 * 这里只需要两个标量，SharedPreferences 足够，且能让“UI、仓库包装层、后台 Worker”
 * 三方共享同一份状态而互不依赖（任何一方新建的实例读到的都是同一份配置）。
 */
object FolderMirrorPrefs {

    private const val FILE = "moememos_folder_mirror"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 用户选择（并已持久化授权）的文件夹 SAF tree uri。 */
    fun treeUri(context: Context): String? =
        prefs(context).getString(KEY_TREE_URI, null)?.takeIf { it.isNotBlank() }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** 是否处于“已选目录 + 已启用”的生效状态。所有入口都以此为准。 */
    fun isActive(context: Context): Boolean = isEnabled(context) && treeUri(context) != null

    fun setTreeUri(context: Context, uri: String?) {
        prefs(context).edit().apply {
            if (uri.isNullOrBlank()) remove(KEY_TREE_URI) else putString(KEY_TREE_URI, uri)
        }.apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 仅关闭开关，保留已选目录，方便重新打开。 */
    fun disable(context: Context) = setEnabled(context, false)
}
