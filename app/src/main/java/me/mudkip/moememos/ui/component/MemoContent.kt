package me.mudkip.moememos.ui.component

import android.content.Intent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.entity.ResourceEntity
import me.mudkip.moememos.data.model.MemoRepresentable
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ui.page.common.LocalRootNavController
import me.mudkip.moememos.ui.page.common.RouteName
import me.mudkip.moememos.ui.media.MediaViewerActivity
import me.mudkip.moememos.viewmodel.LocalUserState
import me.mudkip.moememos.util.DETAIL_HEADING_LEVEL
import me.mudkip.moememos.util.extractPreviewContent
import me.mudkip.moememos.util.normalizeHeadingLevel
import me.mudkip.moememos.util.PROGRESSIVE_CHUNK_LINES
import me.mudkip.moememos.util.PROGRESSIVE_FIRST_LINES
import me.mudkip.moememos.util.splitProgressiveContent
import java.net.URLEncoder
import kotlin.math.ceil

@Composable
fun MemoContent(
    memo: MemoRepresentable,
    previewMode: Boolean = false,
    progressive: Boolean = false,
    checkboxChange: (checked: Boolean, startOffset: Int, endOffset: Int) -> Unit = { _, _, _ -> },
    onViewMore: (() -> Unit)? = null,
    selectable: Boolean = false,
    onTagClick: ((String) -> Unit)? = null,
    audioTranscribeMemoIdentifier: String? = null
) {
    val rootNavController = LocalRootNavController.current
    // progressive 只在详情页开：列表卡片本来就只露 3 行，不需要再切一刀
    var visibleLines by remember(memo.content) { mutableIntStateOf(PROGRESSIVE_FIRST_LINES) }
    val collapsed = remember(memo.content, previewMode, progressive, visibleLines) {
        when {
            previewMode -> extractPreviewContent(markdownText = memo.content)
            // 内页（详情页）：标题一律按四级显示。笔记里的层级是历史遗留的，
            // 有 `##` 也有 `####`，不统一的话打开不同笔记标题大小不一样。
            progressive -> {
                val (head, more) = splitProgressiveContent(
                    markdownText = memo.content,
                    firstLines = visibleLines,
                )
                Pair(normalizeHeadingLevel(head, DETAIL_HEADING_LEVEL), more)
            }
            else -> Pair(memo.content, false)
        }
    }
    val (collapsedText, hasMore) = collapsed
    val text = collapsedText
    val handleTagClick = remember(rootNavController, onTagClick) {
        onTagClick ?: { tag ->
            rootNavController.navigate("${RouteName.TAG}/${URLEncoder.encode(tag, "UTF-8")}") {
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Column(
        modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 10.dp)
    ) {
        Markdown(
            text,
            imageBaseUrl = LocalUserState.current.host,
            checkboxChange = checkboxChange,
            selectable = selectable,
            onTagClick = handleTagClick
        )

        MemoResourceContent(memo, audioTranscribeMemoIdentifier)

        if (previewMode && hasMore && onViewMore != null) {
            Row {
                Text(
                    text = R.string.view_more.string,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable(onClick = onViewMore)
                )
            }
        }

        // 长文一次铺完会掉帧，所以一段一段加：每次点「继续展开」多渲染一批
        if (hasMore && progressive && !previewMode) {
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    text = R.string.expand_more.string,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable { visibleLines += PROGRESSIVE_CHUNK_LINES }
                )
            }
        }
    }
}

@Composable
fun MemoResourceContent(memo: MemoRepresentable, transcribeMemoIdentifier: String? = null) {
    val cols = 3
    val context = LocalContext.current
    val imageList = memo.resources.filter { it.mimeType?.startsWith("image/") == true }
    val imageUrls = remember(imageList) {
        imageList.map { resource -> resource.localUri ?: resource.uri }
    }
    if (imageList.isNotEmpty()) {
        val rows = ceil(imageList.size.toFloat() / cols).toInt()
        for (rowIndex in 0 until rows) {
            Row {
                for (colIndex in 0 until cols) {
                    val index = rowIndex * cols + colIndex
                    if (index < imageList.size) {
                        Box(modifier = Modifier.fillMaxWidth(1f / (cols - colIndex))) {
                            MemoImage(
                                url = imageList[index].localUri ?: imageList[index].uri,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                resourceIdentifier = (imageList[index] as? ResourceEntity)?.identifier,
                                onClick = {
                                    context.startActivity(
                                        Intent(context, MediaViewerActivity::class.java).apply {
                                            putExtra(MediaViewerActivity.EXTRA_IMAGE_URLS, imageUrls.toTypedArray())
                                            putExtra(MediaViewerActivity.EXTRA_INITIAL_INDEX, index)
                                            putExtra(MediaViewerActivity.EXTRA_CAPTION, memo.content)
                                        }
                                    )
                                }
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.fillMaxWidth(1f / cols))
                    }
                }
            }
        }
    }
    memo.resources.filterNot { it.mimeType?.startsWith("image/") == true }.forEach { resource ->
        Attachment(resource, transcribeMemoIdentifier = transcribeMemoIdentifier)
    }
}
