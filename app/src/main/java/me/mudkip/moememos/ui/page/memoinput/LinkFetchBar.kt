package me.mudkip.moememos.ui.page.memoinput

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.mudkip.moememos.R
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.viewmodel.LinkFetchState

/**
 * 网页读取状态条，贴在输入页底部工具栏上方，和转写状态条同一位置。
 *
 * 四种形态合成一个组件，是因为它们互斥地描述同一件事：
 *
 * - 正文里出现链接 → 提示「可以抓取」（这是**建议**，不打扰：用户不理会它也能照常写）
 * - 正在抓取 → 转圈 + 说明
 * - 失败 → 错误信息 + 重试（不用弹窗：抓取失败不影响已经写好的正文）
 * - 其余情况 → 完全不占空间
 */
@Composable
internal fun LinkFetchBar(
    state: LinkFetchState,
    suggestedUrl: String?,
    onFetch: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is LinkFetchState.Busy -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Text(
                R.string.web_link_fetching.string,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        is LinkFetchState.Failed -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp)
        ) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRetry) {
                Text(R.string.web_link_retry.string)
            }
        }

        is LinkFetchState.Idle -> if (suggestedUrl != null) Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp)
        ) {
            Icon(
                Icons.Outlined.Link,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = suggestedUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
            TextButton(onClick = { onFetch(suggestedUrl) }) {
                Text(R.string.web_link_fetch.string)
            }
        }
    }
}
