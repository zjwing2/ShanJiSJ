package me.mudkip.moememos.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCheckBox
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import me.mudkip.moememos.R
import me.mudkip.moememos.ui.media.MediaViewerActivity
import me.mudkip.moememos.util.extractMarkdownImageLink
import me.mudkip.moememos.util.findCustomTagMatches
import me.mudkip.moememos.util.getCustomTagName
import me.mudkip.moememos.util.isCustomTagSupportedNode
import org.intellij.markdown.MarkdownTokenTypes
import com.mikepenz.markdown.m3.Markdown as M3Markdown

@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    imageBaseUrl: String? = null,
    checkboxChange: ((checked: Boolean, startOffset: Int, endOffset: Int) -> Unit)? = null,
    selectable: Boolean = false,
    onTagClick: ((tag: String) -> Unit)? = null,
) {
    fun withOptionalTextAlign(style: TextStyle): TextStyle {
        return if (textAlign == null) style else style.copy(textAlign = textAlign)
    }

    val bodyTextStyle = withOptionalTextAlign(MaterialTheme.typography.bodyLarge)
    val h1TextStyle = withOptionalTextAlign(MaterialTheme.typography.headlineLarge)
    val h2TextStyle = withOptionalTextAlign(MaterialTheme.typography.headlineMedium)
    val h3TextStyle = withOptionalTextAlign(MaterialTheme.typography.headlineSmall)
    val h4TextStyle = withOptionalTextAlign(MaterialTheme.typography.titleLarge)
    val h5TextStyle = withOptionalTextAlign(MaterialTheme.typography.titleMedium)
    val h6TextStyle = withOptionalTextAlign(MaterialTheme.typography.titleSmall)
    val uriHandler = LocalUriHandler.current
    val tagLinkStyle = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    )
    val tagLinkListener = remember(uriHandler, onTagClick) {
        LinkInteractionListener { link ->
            val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
            if (url.startsWith(TAG_LINK_PREFIX)) {
                onTagClick?.invoke(Uri.decode(url.removePrefix(TAG_LINK_PREFIX)))
                return@LinkInteractionListener
            }
            uriHandler.openUri(url)
        }
    }
    val imageTransformer = remember(imageBaseUrl) {
        object : ImageTransformer {
            @Composable
            override fun transform(link: String): ImageData {
                return Coil3ImageTransformerImpl.transform(resolveMarkdownImageLink(link, imageBaseUrl))
            }

            @Composable
            override fun intrinsicSize(painter: Painter): Size {
                return Coil3ImageTransformerImpl.intrinsicSize(painter)
            }
        }
    }
    val markdownState = rememberMarkdownState(
        content = text,
        retainState = true
    )

    val markdownContent: @Composable () -> Unit = {
        M3Markdown(
            markdownState = markdownState,
            modifier = modifier,
            imageTransformer = imageTransformer,
            typography = markdownTypography(
                h1 = h1TextStyle,
                h2 = h2TextStyle,
                h3 = h3TextStyle,
                h4 = h4TextStyle,
                h5 = h5TextStyle,
                h6 = h6TextStyle,
                text = bodyTextStyle,
                paragraph = bodyTextStyle,
                ordered = bodyTextStyle,
                bullet = bodyTextStyle,
                list = bodyTextStyle
            ),
            annotator = markdownAnnotator(
                config = markdownAnnotatorConfig(eolAsNewLine = true),
                annotate = { content, child ->
                    if (child.type != MarkdownTokenTypes.TEXT) {
                        return@markdownAnnotator false
                    }
                    if (!isCustomTagSupportedNode(child)) {
                        return@markdownAnnotator false
                    }
                    val source = child.getUnescapedTextInNode(content)
                    val tags = findCustomTagMatches(source).toList()
                    if (tags.isEmpty()) {
                        return@markdownAnnotator false
                    }

                    var cursor = 0
                    tags.forEach { match ->
                        val start = match.range.first
                        val endInclusive = match.range.last
                        if (start > cursor) {
                            append(source.substring(cursor, start))
                        }
                        val tagRaw = getCustomTagName(match)
                        withLink(
                            LinkAnnotation.Url(
                                url = TAG_LINK_PREFIX + Uri.encode(tagRaw),
                                styles = tagLinkStyle,
                                linkInteractionListener = tagLinkListener
                            )
                        ) {
                            append(match.value)
                        }
                        cursor = endInclusive + 1
                    }
                    if (cursor < source.length) {
                        append(source.substring(cursor))
                    }
                    true
                }
            ),
            components = markdownComponents(
                codeFence = highlightedCodeFence,
                codeBlock = highlightedCodeBlock,
                image = { model ->
                    LazyMarkdownImage(model = model, imageBaseUrl = imageBaseUrl)
                },
                checkbox = {
                    val node = it.node
                    MarkdownCheckBox(
                        content = it.content,
                        node = it.node,
                        style = it.typography.text,
                        checkedIndicator = { checked, modifier ->
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = if (checkboxChange != null) {
                                        { checkboxChange(!checked, node.startOffset, node.endOffset) }
                                    } else {
                                        null
                                    },
                                    modifier = modifier.semantics {
                                        role = Role.Checkbox
                                        stateDescription = if (checked) "Checked" else "Unchecked"
                                    },
                                )
                            }
                        }
                    )
                }
            )
        )
    }

    if (selectable) {
        SelectionContainer {
            markdownContent()
        }
    } else {
        markdownContent()
    }
}

/**
 * 详情页里的远程图片：滚到眼前才加载，没加载前先占一块固定高度的空位。
 *
 * 一篇抓下来的文章可能有五十多张图，全量渲染时每张图加载完高度一变，
 * 整列就要重新布局一次，实测进页面掉一百多帧。这里只做一件事——
 * **没进入视口就不发请求**，把几十张图同时抢主线程变成几张。
 *
 * 占位高度写死是刻意的：高度稳定，图还没加载时整列不用反复重排。
 * 加载完成后换成按原图宽高比撑开（见 success 分支），观感和以前一致。
 */
private val IMAGE_PLACEHOLDER_HEIGHT = 220.dp

@Composable
private fun LazyMarkdownImage(
    model: MarkdownComponentModel,
    imageBaseUrl: String?,
) {
    val context = LocalContext.current
    val link = remember(model.content) { extractMarkdownImageLink(model.content) }
    var requested by remember(model.content) { mutableStateOf(false) }
    // 用 View 的实际像素高，省得再换算 dp
    val viewportHeightPx = LocalView.current.height.toFloat()

    if (requested && link != null) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(resolveMarkdownImageLink(link, imageBaseUrl))
                .build(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    context.startActivity(
                        Intent(context, MediaViewerActivity::class.java).apply {
                            putExtra(
                                MediaViewerActivity.EXTRA_IMAGE_URLS,
                                arrayOf(resolveMarkdownImageLink(link, imageBaseUrl))
                            )
                            putExtra(MediaViewerActivity.EXTRA_INITIAL_INDEX, 0)
                        }
                    )
                },
            loading = { MarkdownImagePlaceholder() },
            error = { MarkdownImagePlaceholder() },
            success = { state ->
                Image(
                    painter = state.painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                )
            }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IMAGE_PLACEHOLDER_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onGloballyPositioned { coordinates ->
                // 视口下方一屏内就开始加载，滚到眼前时图通常已经在了
                if (!requested && coordinates.positionInRoot().y < viewportHeightPx * 2) {
                    requested = true
                }
            }
            .clickable(enabled = link != null) { requested = true },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.image_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MarkdownImagePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IMAGE_PLACEHOLDER_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.image_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun resolveMarkdownImageLink(link: String, imageBaseUrl: String?): String {
    val uri = link.toUri()
    if (uri.scheme != null || imageBaseUrl.isNullOrBlank()) {
        return link
    }
    return imageBaseUrl.toUri().buildUpon().path(link).build().toString()
}

private const val TAG_LINK_PREFIX = "moememos://tag/"
