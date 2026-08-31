package com.mohamedrejeb.richeditor.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.ImageClickHandler
import com.mohamedrejeb.richeditor.model.ImageData
import com.mohamedrejeb.richeditor.model.ImageLoader
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * 一张内联图片在**编辑态**的绘制计划。
 *
 * 编辑态（`BasicRichTextEditor`）走的是 BasicTextField，而 Compose Foundation 1.11 的
 * BasicTextField 根本没有 inlineContent 参数，`RichSpanStyle.Image` 的 InlineTextContent
 * 永远不会被组合 —— 既画不出图，也解不出尺寸（children 里的 LaunchedEffect 不执行）。
 *
 * 因此编辑态改为：
 * 1. 组合期用 [ImageLoader] 取到 painter，按容器宽度算出显示尺寸（[resolveInlineImagePlacements]）
 * 2. 尺寸写回 Image span，重建 annotatedString，让段落 lineHeight 预留出纵向空间
 * 3. 用 [Modifier.drawInlineImages] 在文字之上把图片画出来
 *
 * @property style 对应的 Image span，[width] / [height] 会被写回它
 * @property textRange 图片占位符在 annotatedString 中的范围（用于反查所在行）
 * @property data 图片加载结果（painter / contentScale 等）
 * @property width 按容器宽度钳制后的显示宽度（sp）
 * @property height 按容器宽度等比钳制后的显示高度（sp）
 */
internal class InlineImagePlacement(
    val style: RichSpanStyle.Image,
    val textRange: TextRange,
    val data: ImageData,
    val width: TextUnit,
    val height: TextUnit,
) {
    /**
     * 刻意不比较 [data]：Coil 之类的加载器每次重组都会 new 一个 ImageData，
     * 比进去会让下面 LaunchedEffect 的 key 每次都变，进而反复触发重建。
     * [style] 用引用相等（Image 故意不重写 equals）。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InlineImagePlacement) return false
        return style === other.style &&
            textRange == other.textRange &&
            width == other.width &&
            height == other.height
    }

    override fun hashCode(): Int {
        var result = style.hashCode()
        result = 31 * result + textRange.hashCode()
        result = 31 * result + width.hashCode()
        result = 31 * result + height.hashCode()
        return result
    }
}

/**
 * 在组合期为正文里的每张内联图片解析出 painter 与显示尺寸。
 *
 * 必须在 composable 作用域调用：[ImageLoader.load] 是 @Composable（内部要 remember painter）。
 *
 * 图片尚未解码完成时 `painter.intrinsicSize` 为 Unspecified，这一帧直接跳过；
 * 解码完成后会自然重组，再走一次本函数拿到真实尺寸。
 *
 * @param state 富文本状态
 * @param imageLoader 图片加载器（由 LocalImageLoader 提供）
 * @param maxImageWidth 容器可用宽度（sp），用于等比缩小超宽图片
 * @return 当前可绘制的图片计划列表，无图片或全部未解码时为空列表
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
internal fun resolveInlineImagePlacements(
    state: RichTextState,
    imageLoader: ImageLoader,
    maxImageWidth: TextUnit,
): List<InlineImagePlacement> {
    val density = LocalDensity.current
    val imageSpans = state.styledRichSpanList.filter { it.richSpanStyle is RichSpanStyle.Image }
    val placements = ArrayList<InlineImagePlacement>(imageSpans.size)

    for (richSpan in imageSpans) {
        val style = richSpan.richSpanStyle as RichSpanStyle.Image
        val data = imageLoader.load(style.model)
        if (data == null) continue

        val intrinsicSize = data.painter.intrinsicSize
        if (intrinsicSize.isUnspecified) continue

        /** intrinsicSize 单位是 px：Float.toSp() = toDp().toSp()，正好是 px → sp */
        val intrinsicWidth = with(density) { intrinsicSize.width.coerceAtLeast(0f).toSp() }
        val intrinsicHeight = with(density) { intrinsicSize.height.coerceAtLeast(0f).toSp() }
        val (width, height) = RichSpanStyle.Image.clampToMaxWidth(
            width = intrinsicWidth,
            height = intrinsicHeight,
            maxWidth = maxImageWidth,
        )

        placements.add(
            InlineImagePlacement(
                style = style,
                textRange = richSpan.textRange,
                data = data,
                width = width,
                height = height,
            )
        )
    }

    return placements
}

/**
 * 把覆盖层解析出的尺寸写回 Image span，并重建 annotatedString。
 *
 * 重建是必需的：段落的 lineHeight 预留发生在 [RichTextState.updateAnnotatedString] 里，
 * 不重建的话图片高度不会进入排版，画出来的图会压在后面的文字上。
 *
 * 只在尺寸真的变化时才写回 + 重建，避免"写回 → 重建 → 重组 → 再写回"的空转。
 *
 * @param state 富文本状态
 * @param placements [resolveInlineImagePlacements] 的结果
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
internal fun ApplyInlineImageSizes(
    state: RichTextState,
    placements: List<InlineImagePlacement>,
) {
    LaunchedEffect(placements) {
        var needsRebuild = false

        placements.forEach { placement ->
            if (placement.style.width != placement.width ||
                placement.style.height != placement.height
            ) {
                placement.style.setResolvedSize(
                    newWidth = placement.width,
                    newHeight = placement.height,
                )
                needsRebuild = true
            }
        }

        if (needsRebuild) state.updateAnnotatedString()
    }
}

/**
 * 在文本字段内容之上绘制内联图片。
 *
 * 定位用的是「行」而不是占位符字符的 boundingBox：段落被补了
 * `lineHeight = 图片高度` 之后，整行的高度就是图片高度，而占位符字符
 * 是按基线摆在这一行中间的，拿它的 box.top 当图片顶部会偏。
 *
 * @param state 富文本状态，提供 textLayoutResult
 * @param placements 绘制计划
 * @param density 用于 TextUnit → px
 * @param topPadding 文本字段的纵向 contentPadding，行坐标是相对文本内容的
 * @param startPadding 文本字段的横向 contentPadding
 */
internal fun Modifier.drawInlineImages(
    state: RichTextState,
    placements: List<InlineImagePlacement>,
    density: Density,
    topPadding: Float,
    startPadding: Float,
): Modifier = this.drawWithContent {
    drawContent()

    if (placements.isEmpty()) return@drawWithContent

    val layoutResult = state.textLayoutResult ?: return@drawWithContent
    val textLength = layoutResult.layoutInput.text.length
    if (textLength == 0) return@drawWithContent

    /**
     * 按文档顺序（textRange.start 升序）绘制。
     *
     * **防重叠兜底（v2026-08-31）**：正常情况下图片各自独占段落
     * （paragraph boundary），layout 会给不同段落分配不同 line，
     * getLineTop 天然错开。但若因历史数据 / 内联插入（图片与文字同段）
     * 导致两张图落在同一 line，getLineForOffset 返回相同 line、lineTop
     * 相同 → 会叠在同一位置。这里对"同一 line 上的多张图"做垂直错开：
     * 从上一张图底部继续往下排，保证视觉上永远不会重叠。
     */
    val sortedPlacements = placements.sortedBy { it.textRange.start }
    var lastLine = -1
    var cursorY = 0f

    sortedPlacements.forEach { placement ->
        val widthPx = with(density) { placement.width.toPx() }
        val heightPx = with(density) { placement.height.toPx() }
        if (widthPx <= 0f || heightPx <= 0f) return@forEach

        val offset = placement.textRange.start.coerceIn(0, textLength - 1)
        val line = layoutResult.getLineForOffset(offset)
        val lineTop = layoutResult.getLineTop(line)

        // 同一 line 内多图错开；不同 line 用各自的 lineTop
        val y = if (line == lastLine) cursorY else lineTop
        cursorY = y + heightPx
        lastLine = line

        translate(
            left = layoutResult.getLineLeft(line) + startPadding,
            top = y + topPadding,
        ) {
            with(placement.data.painter) {
                draw(size = Size(widthPx, heightPx))
            }
        }
    }
}

/**
 * 命中检测：返回覆盖在 [position]（相对文本字段内容的本地坐标）上的图片绘制计划。
 *
 * 几何口径必须与 [drawInlineImages] 完全一致（"行"级矩形 + contentPadding 偏移），
 * 否则会出现"点不到图"或"点到图外空白也触发"的错位。
 *
 * @param state 富文本状态，提供 textLayoutResult
 * @param position 触点在文本字段内容本地坐标系中的位置
 * @param placements 绘制计划（与绘制时同一份）
 * @param density 用于 TextUnit → px
 * @param topPadding / startPadding 文本字段的 contentPadding，与绘制侧相同
 * @return 命中的 [InlineImagePlacement]，未命中任何图片时返回 null
 */
@OptIn(ExperimentalRichTextApi::class)
internal fun findInlineImagePlacementAt(
    state: RichTextState,
    position: Offset,
    placements: List<InlineImagePlacement>,
    density: Density,
    topPadding: Float,
    startPadding: Float,
): InlineImagePlacement? {
    if (placements.isEmpty()) return null

    val layoutResult = state.textLayoutResult ?: return null
    val textLength = layoutResult.layoutInput.text.length
    if (textLength == 0) return null

    /**
     * 与 [drawInlineImages] 保持完全一致的"同 line 垂直错开"计算：
     * 绘制时对同一 line 上的多张图会从上一张图底部继续往下排，
     * 命中检测必须用同一套 y 计算，否则点击会错位。
     */
    val sortedPlacements = placements.sortedBy { it.textRange.start }
    var lastLine = -1
    var cursorY = 0f

    for (placement in sortedPlacements) {
        val widthPx = with(density) { placement.width.toPx() }
        val heightPx = with(density) { placement.height.toPx() }
        if (widthPx <= 0f || heightPx <= 0f) continue

        val offset = placement.textRange.start.coerceIn(0, textLength - 1)
        val line = layoutResult.getLineForOffset(offset)
        val lineTop = layoutResult.getLineTop(line)

        val y = if (line == lastLine) cursorY else lineTop
        cursorY = y + heightPx
        lastLine = line

        val left = layoutResult.getLineLeft(line) + startPadding
        val top = y + topPadding

        if (position.x >= left && position.x <= left + widthPx &&
            position.y >= top && position.y <= top + heightPx
        ) {
            return placement
        }
    }

    return null
}

/**
 * 编辑态内联图片的点击拦截。
 *
 * 为什么要用 [PointerEventPass.Initial] 抢占：
 * 文本框内部的手势（光标定位/选择）在 Main pass 处理，而外层 modifier 在 Initial pass
 * 先于内层看到事件——命中图片时立刻消费 down 及后续事件，才能阻止文本框把光标
 * 移到图片占位符上、弹出文字选择句柄。
 *
 * 未命中图片时立即放行（不消费），文本框行为与没有本 modifier 时完全一致。
 *
 * @param state 富文本状态，用于命中检测时读取 textLayoutResult
 * @param placements 绘制计划（与 [drawInlineImages] 同一份）
 * @param density 用于 TextUnit → px
 * @param topPadding / startPadding 文本字段的 contentPadding，与绘制侧相同
 * @param onImageClick 点击图片回调；null 时本 modifier 不做任何事
 */
@OptIn(ExperimentalRichTextApi::class)
internal fun Modifier.pointerInputInlineImages(
    state: RichTextState,
    placements: List<InlineImagePlacement>,
    density: Density,
    topPadding: Float,
    startPadding: Float,
    onImageClick: ImageClickHandler?,
): Modifier {
    if (onImageClick == null) return this

    return this.pointerInput(state, placements, onImageClick) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)

            val hitPlacement = findInlineImagePlacementAt(
                state = state,
                position = down.position,
                placements = placements,
                density = density,
                topPadding = topPadding,
                startPadding = startPadding,
            ) ?: return@awaitEachGesture

            /** 命中图片：消费整个手势，阻断文本框的光标定位与选择 */
            down.consume()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val allReleased = event.changes.none { it.pressed }
                event.changes.forEach { it.consume() }
                if (allReleased) break
            }

            onImageClick(hitPlacement.style, down.position)
        }
    }
}
