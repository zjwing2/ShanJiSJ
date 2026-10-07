package me.mudkip.moememos.data.local

import androidx.core.net.toUri
import me.mudkip.moememos.data.model.ResourceRepresentable
import java.io.File

/**
 * 把附件资源还原成本机文件。
 *
 * 原本这段逻辑长在 UI 组件里，但「转写老音频」让 ViewModel 也需要回答
 * 同一个问题——文件在不在本机、在哪——于是下沉到 data 层，两边共用。
 *
 * 只有 `file://` 且真实存在才算数：远程账号的附件还在服务器上时返回 null，
 * 由调用方决定是下载、报错还是隐藏入口。
 */
fun localFileOf(resource: ResourceRepresentable): File? {
    val local = (resource.localUri ?: resource.uri).toUri()
    if (local.scheme != "file") {
        return null
    }
    val path = local.path ?: return null
    return File(path).takeIf { it.exists() }
}
