package com.mohamedrejeb.richeditor.model

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.utils.getBoundingBoxes
import kotlin.random.Random

@ExperimentalRichTextApi
public interface RichSpanStyle {
    public val spanStyle: (RichTextConfig) -> SpanStyle

    /**
     * If true, the user can add new text in the edges of the span,
     * For example, if the span is "Hello" and the user adds "World" in the end, the span will be "Hello World"
     * If false, the user can't add new text in the edges of the span,
     * For example, if the span is a "Hello" link and the user adds "World" in the end, the "World" will be added in a separate a span,
     */
    public val acceptNewTextInTheEdges: Boolean

    /**
     * If true, the span is treated as a single atomic unit for editing:
     * backspace deletes the whole span, typing adjacent to it creates a sibling span
     * instead of appending into it, and selections that straddle the span snap to its edges.
     *
     * Defaults to false. Overridden to true by [Image] and atomic token spans (e.g. mentions).
     */
    public val isAtomic: Boolean get() = false

    public fun DrawScope.drawCustomStyle(
        layoutResult: TextLayoutResult,
        textRange: TextRange,
        richTextConfig: RichTextConfig,
        topPadding: Float = 0f,
        startPadding: Float = 0f,
    )

    public fun AnnotatedString.Builder.appendCustomContent(
        richTextState: RichTextState
    ): AnnotatedString.Builder = this

    public class Link(
        public val url: String,
    ) : RichSpanStyle {
        override val spanStyle: (RichTextConfig) -> SpanStyle = {
            SpanStyle(
                color = it.linkColor,
                textDecoration = it.linkTextDecoration,
            )
        }

        override fun DrawScope.drawCustomStyle(
            layoutResult: TextLayoutResult,
            textRange: TextRange,
            richTextConfig: RichTextConfig,
            topPadding: Float,
            startPadding: Float,
        ): Unit = Unit

        override val acceptNewTextInTheEdges: Boolean =
            false

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Link) return false

            if (url != other.url) return false

            return true
        }

        override fun hashCode(): Int {
            return url.hashCode()
        }
    }

    public class Code(
        private val cornerRadius: TextUnit = 8.sp,
        private val strokeWidth: TextUnit = 1.sp,
        private val padding: TextPaddingValues = TextPaddingValues(horizontal = 2.sp, vertical = 2.sp)
    ) : RichSpanStyle {
        override val spanStyle: (RichTextConfig) -> SpanStyle = {
            SpanStyle(
                color = it.codeSpanColor,
            )
        }

        override fun DrawScope.drawCustomStyle(
            layoutResult: TextLayoutResult,
            textRange: TextRange,
            richTextConfig: RichTextConfig,
            topPadding: Float,
            startPadding: Float,
        ) {
            val path = Path()
            val backgroundColor = richTextConfig.codeSpanBackgroundColor
            val strokeColor = richTextConfig.codeSpanStrokeColor
            val cornerRadius = CornerRadius(cornerRadius.toPx())
            val boxes = layoutResult.getBoundingBoxes(
                startOffset = textRange.start,
                endOffset = textRange.end,
                flattenForFullParagraphs = true
            )

            boxes.fastForEachIndexed { index, box ->
                path.addRoundRect(
                    RoundRect(
                        rect = box.copy(
                            left = box.left - padding.horizontal.toPx() + startPadding,
                            right = box.right + padding.horizontal.toPx() + startPadding,
                            top = box.top - padding.vertical.toPx() + topPadding,
                            bottom = box.bottom + padding.vertical.toPx() + topPadding,
                        ),
                        topLeft = if (index == 0) cornerRadius else CornerRadius.Zero,
                        bottomLeft = if (index == 0) cornerRadius else CornerRadius.Zero,
                        topRight = if (index == boxes.lastIndex) cornerRadius else CornerRadius.Zero,
                        bottomRight = if (index == boxes.lastIndex) cornerRadius else CornerRadius.Zero
                    )
                )
                drawPath(
                    path = path,
                    color = backgroundColor,
                    style = Fill
                )
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(
                        width = strokeWidth.toPx(),
                    )
                )
            }
        }

        override val acceptNewTextInTheEdges: Boolean =
            true

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Code) return false

            if (cornerRadius != other.cornerRadius) return false
            if (strokeWidth != other.strokeWidth) return false
            if (padding != other.padding) return false

            return true
        }

        override fun hashCode(): Int {
            var result = cornerRadius.hashCode()
            result = 31 * result + strokeWidth.hashCode()
            result = 31 * result + padding.hashCode()
            return result
        }
    }

    public class Image(
        public val model: Any,
        width: TextUnit,
        height: TextUnit,
        public val contentDescription: String? = null,
    ) : RichSpanStyle {

        init {
            require(width.isSpecified || height.isSpecified) {
                "At least one of the width or height should be specified"
            }

            require(width.value >= 0 || height.value >= 0) {
                "The width and height should be greater than or equal to 0"
            }

            require(width.value.isFinite() || height.value.isFinite()) {
                "The width and height should be finite"
            }
        }

        /**
         * Initial `(width, height)` for this Image span.
         *
         * Consult [resolvedDimensionsCache] first: a previously-rendered
         * Image with the same [model] (same src) will have populated the
         * cache with its post-clamp size. Using that eliminates the
         * big-then-small flicker that otherwise happens when HTML attrs
         * overstate the display size (e.g. `<img width="600">` on a 2x
         * density screen would reserve a 600sp slot that later shrinks to
         * the 300sp intrinsic once the painter resolves).
         *
         * Falls back to the caller-supplied dimensions on the first-ever
         * render of a given model, where the cache is still empty.
         */
        private val initialDimensions: Pair<TextUnit, TextUnit> =
            resolvedDimensionsCache[model] ?: (width to height)

        public var width: TextUnit by mutableStateOf(initialDimensions.first)
            private set

        public var height: TextUnit by mutableStateOf(initialDimensions.second)
            private set

        /**
         * Stable, per-instance id used as the key into
         * [RichTextState.inlineContentMap]. Deliberately independent of
         * [width]/[height] so that updating dimensions (from intrinsic size,
         * from the container-width clamp, etc.) replaces the map entry in
         * place instead of routing through a different key, which would
         * briefly desync the annotated string's inline-content marker from
         * the map and cause the image to disappear for a frame.
         */
        private val id: String = "richtext-img-${Random.nextLong().toULong().toString(16)}"

        override val spanStyle: (RichTextConfig) -> SpanStyle =
            { SpanStyle() }

        override fun DrawScope.drawCustomStyle(
            layoutResult: TextLayoutResult,
            textRange: TextRange,
            richTextConfig: RichTextConfig,
            topPadding: Float,
            startPadding: Float,
        ): Unit = Unit

        override fun AnnotatedString.Builder.appendCustomContent(
            richTextState: RichTextState
        ): AnnotatedString.Builder {
            if (id !in richTextState.inlineContentMap.keys) {
                richTextState.inlineContentMap[id] = createInlineTextContent(richTextState = richTextState)
            }

            richTextState.usedInlineContentMapKeys.add(id)

            /**
             * v2026-08-31：把占位符染成透明。
             *
             * `appendInlineContent` 打的占位符是 U+FFFD。只有 BasicText（BasicRichText）支持
             * inlineContent；编辑态走的是 BasicTextField，而 Compose Foundation 1.11 连
             * inlineContent 参数都不存在，U+FFFD 会被当成普通字符画出来 —— 用户看到的就是"问号方块"。
             * 真正的位图改由 BasicRichTextEditor 的覆盖层绘制（见 ui/InlineImageOverlay.kt），
             * 这里只需让底下的占位符隐形。
             *
             * 只读态下 inlineContent 会顶替占位符的绘制区域，透明化不产生任何副作用。
             */
            withStyle(SpanStyle(color = Color.Transparent)) {
                appendInlineContent(id = id)
            }

            return this
        }

        /**
         * v2026-08-31：由编辑态覆盖层回填解码后的真实尺寸。
         *
         * 编辑态下 `createInlineTextContent` 里的 `LaunchedEffect` 永远不会执行 ——
         * BasicTextField 不组合 inlineContent，children 这个 composable 根本不会被调用，
         * 于是 width/height 恒为插入时的 0：既画不出图，段落也不会预留纵向空间（尺寸死锁）。
         *
         * 改由覆盖层在组合期解析出尺寸后调用本方法写回，并同步进 [resolvedDimensionsCache]。
         *
         * @param newWidth 钳制后的显示宽度（sp）
         * @param newHeight 钳制后的显示高度（sp）
         */
        internal fun setResolvedSize(
            newWidth: TextUnit,
            newHeight: TextUnit,
        ) {
            width = newWidth
            height = newHeight
            resolvedDimensionsCache[model] = newWidth to newHeight
        }

        private fun createInlineTextContent(
            richTextState: RichTextState
        ): InlineTextContent =
            InlineTextContent(
                placeholder = Placeholder(
                    width = width.value.coerceAtLeast(0f).sp,
                    height = height.value.coerceAtLeast(0f).sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom
                ),
                children = {
                    val density = LocalDensity.current
                    val imageLoader = LocalImageLoader.current
                    val maxImageWidth = LocalRichTextMaxImageWidthProvider.current.maxWidth
                    val data = imageLoader.load(model) ?: return@InlineTextContent
                    // Read intrinsicSize in composable scope so we observe its
                    // state. Async painters (Coil, etc.) start with
                    // [Size.Unspecified] and flip to a real size once the
                    // image decodes. Including it in the effect key ensures
                    // the effect re-runs when the size becomes available,
                    // instead of exiting early on first run and leaving the
                    // Placeholder at 0x0 forever.
                    val intrinsicSize = data.painter.intrinsicSize

                    // [id] is included in the key so that each fresh Image
                    // instance (new id) retriggers the clamp. BasicText's
                    // inline-content subcomposition is sometimes reused across
                    // `setHtml(...)` calls even when the Image instance and
                    // inlineContentMap key change - the remembered [data] and
                    // the remembered `imageData` state in ImageLoaders that
                    // cache painters (e.g. Coil3) end up identical to the
                    // previous scope, so without a fresh key the effect would
                    // see unchanged (data, intrinsicSize, maxImageWidth) and
                    // skip its body, leaving the new Image's dimensions
                    // un-clamped until the next container resize.
                    LaunchedEffect(id, data, intrinsicSize, maxImageWidth) {
                        if (intrinsicSize.isUnspecified)
                            return@LaunchedEffect

                        val intrinsicWidth = with(density) {
                            intrinsicSize.width.coerceAtLeast(0f).toSp()
                        }
                        val intrinsicHeight = with(density) {
                            intrinsicSize.height.coerceAtLeast(0f).toSp()
                        }

                        val (clampedWidth, clampedHeight) = clampToMaxWidth(
                            width = intrinsicWidth,
                            height = intrinsicHeight,
                            maxWidth = maxImageWidth,
                        )

                        val shouldSetWidth = width.isUnspecified ||
                            width.value <= 0 ||
                            width != clampedWidth
                        val shouldSetHeight = height.isUnspecified ||
                            height.value <= 0 ||
                            height != clampedHeight

                        if (!shouldSetWidth && !shouldSetHeight)
                            return@LaunchedEffect

                        if (shouldSetWidth) width = clampedWidth
                        if (shouldSetHeight) height = clampedHeight

                        // Remember the resolved dimensions for this model so
                        // the next Image span with the same src (created by
                        // a later setHtml, e.g. every keystroke in an HTML
                        // source-editor) starts with the right Placeholder
                        // size instead of blinking through 0x0.
                        resolvedDimensionsCache[model] = width to height

                        // Overwrite the InlineTextContent at the same stable [id]
                        // so BasicText observes the new Placeholder dimensions on
                        // the next frame. The annotated string's inline-content
                        // marker does not change, so no rebuild is needed.
                        richTextState.inlineContentMap[id] = createInlineTextContent(
                            richTextState = richTextState,
                        )
                    }

                    Image(
                        painter = data.painter,
                        contentDescription = data.contentDescription ?: contentDescription,
                        alignment = data.alignment,
                        contentScale = data.contentScale,
                        modifier = data.modifier
                            .fillMaxSize()
                    )
                }
            )

        override val acceptNewTextInTheEdges: Boolean =
            false

        override val isAtomic: Boolean = true

        internal companion object {
            /**
             * Process-wide cache of resolved `(width, height)` per image
             * [model]. Populated when the painter's intrinsic size has been
             * clamped and applied; consulted in [Image.init] so that a fresh
             * Image constructed from the same src (e.g. a later `setHtml`)
             * starts at the already-known Placeholder size.
             *
             * Not thread-safe; Compose edits run on the main thread. Grows
             * unboundedly in pathological cases; acceptable for realistic
             * document sizes.
             */
            internal val resolvedDimensionsCache: MutableMap<Any, Pair<TextUnit, TextUnit>> =
                mutableMapOf()

            /**
             * Scale [width]/[height] down proportionally so [width] is at most
             * [maxWidth]. Returns the input unchanged when [maxWidth] is
             * unspecified, non-positive, or already wider than [width].
             */
            internal fun clampToMaxWidth(
                width: TextUnit,
                height: TextUnit,
                maxWidth: TextUnit,
            ): Pair<TextUnit, TextUnit> {
                if (!maxWidth.isSpecified || maxWidth.value <= 0f) return width to height
                if (!width.isSpecified || width.value <= maxWidth.value) return width to height

                val scale = maxWidth.value / width.value
                val clampedHeight = if (height.isSpecified)
                    (height.value * scale).sp
                else
                    height
                return maxWidth to clampedHeight
            }
        }

        // Image intentionally does not override equals/hashCode. It relies
        // on identity: two `<img>` tags are two distinct visual slots in
        // the document and must never be collapsed by the consecutive-span
        // merging that runs on `richSpanStyle ==` (see
        // AnnotatedStringExt.appendRichSpanList). Content-based equality
        // would also have been a footgun once [width]/[height] resolve from
        // intrinsic size, since those are mutable state.
    }

    /**
     * Atomic token produced by committing a trigger query (see [com.mohamedrejeb.richeditor.model.trigger.Trigger]).
     *
     * Tokens are single indivisible units for editing purposes:
     * backspace removes the whole [label], typing adjacent to a token creates
     * a sibling span, and selections that straddle a token snap to its edges.
     *
     * @property triggerId Id of the [com.mohamedrejeb.richeditor.model.trigger.Trigger] that produced this token.
     * Used at render time to look up the trigger's style and at serialization
     * time to round-trip the token through HTML/Markdown.
     * @property id Stable identity for the referenced entity (e.g. user id, tag slug, command name).
     * Preserved across HTML/Markdown round-trips.
     * @property label Display text of the token, including the trigger character
     * (e.g. "@mohamed", "#release", "/help"). This text becomes the raw text
     * of the span.
     */
    public class Token(
        public val triggerId: String,
        public val id: String,
        public val label: String,
    ) : RichSpanStyle {
        override val spanStyle: (RichTextConfig) -> SpanStyle = {
            SpanStyle(color = it.linkColor)
        }

        override fun DrawScope.drawCustomStyle(
            layoutResult: TextLayoutResult,
            textRange: TextRange,
            richTextConfig: RichTextConfig,
            topPadding: Float,
            startPadding: Float,
        ): Unit = Unit

        override val acceptNewTextInTheEdges: Boolean = false

        override val isAtomic: Boolean = true

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Token) return false
            return triggerId == other.triggerId && id == other.id && label == other.label
        }

        override fun hashCode(): Int {
            var result = triggerId.hashCode()
            result = 31 * result + id.hashCode()
            result = 31 * result + label.hashCode()
            return result
        }

        override fun toString(): String =
            "Token(triggerId='$triggerId', id='$id', label='$label')"
    }

    /**
     * 段落级复选框（任务列表）标识的绘制样式（v2026-09-15 新增）。
     *
     * **用途**：作为 [com.mohamedrejeb.richeditor.paragraph.type.TaskList] 段落
     * marker（`startRichSpan`）的 `richSpanStyle`。marker 文本本身是**不可见占位
     * 字符**（NBSP，只用于占位宽与定位），真正的勾选框由 [drawCustomStyle] 按
     * marker 的**排版位置**绘制。
     *
     * **为什么走绘制而不是 inlineContent**：编辑态是 BasicTextField，Compose
     * Foundation 1.11 不支持 inlineContent（U+FFFD 占位符会被当普通字符画出来，
     * 见 [Image] 的覆盖层注释）；而 [DrawScope.drawCustomStyle] 由
     * `Modifier.drawRichSpanStyle` 在**编辑态与只读态都挂载**，因此同一套绘制
     * 代码即可保证两端视觉一致，且勾选框跟随段落排版（折行、缩放）自动定位。
     *
     * **绘制规格**（与 App 侧 `CheckboxBoxIcon` 对齐）：
     * - 未勾选 = 圆角方框描边（默认 1.5dp）；
     * - 已勾选 = 实心填充 + 白色圆头对勾；
     * - 方框边长/圆角/描边/配色全部可由调用方注入（App 传主题色）。
     *
     * ⚠️ [equals]/[hashCode] 必须包含 [checked]：库以样式对象相等性判断是否
     * 需要刷新，漏掉勾选态会导致点击后画面不更新。
     *
     * **几何约定**：尺寸用 [TextUnit]（sp），与
     * [com.mohamedrejeb.richeditor.paragraph.type.TaskList] 的 `TextIndent` 预留
     * 宽度（即 `startTextWidth`）保持同一单位体系——段落 marker 被 `TextIndent`
     * 推到「[boxSize] + [gap]」之后，勾选框就画在 marker 左侧这段预留区里。
     *
     * ⚠️ 边长参数命名为 `boxSize` 而**不是** `size`：`DrawScope` 自身有 `size: Size`
     * 成员，而 `DrawScope.drawCustomStyle` 函数体里**扩展接收者优先于派发接收者**，
     * 写 `size` 会被解析成 `DrawScope.size`（编译报 `Size.toPx()` 不存在）。
     *
     * @param checked 勾选态。
     * @param boxSize 方框边长（sp）。
     * @param gap 勾选框与正文之间的间距（sp）。
     * @param cornerRadius 方框圆角。
     * @param strokeWidth 未勾选态描边宽度。
     * @param checkmarkStrokeWidth 对勾线宽。
     * @param checkedColor 勾选态填充色。
     * @param uncheckedColor 未勾选态描边色。
     * @param checkmarkColor 对勾颜色。
     */
    public class CheckBox(
        public val checked: Boolean,
        private val boxSize: TextUnit = DefaultTaskListCheckBoxSize,
        private val gap: TextUnit = DefaultTaskListCheckBoxGap,
        private val cornerRadius: TextUnit = DefaultTaskListCheckBoxCornerRadius,
        private val strokeWidth: TextUnit = DefaultTaskListCheckBoxStrokeWidth,
        private val checkmarkStrokeWidth: TextUnit = DefaultTaskListCheckmarkStrokeWidth,
        private val checkedColor: Color = DefaultTaskListCheckedColor,
        private val uncheckedColor: Color = DefaultTaskListUncheckedColor,
        private val checkmarkColor: Color = DefaultTaskListCheckmarkColor,
    ) : RichSpanStyle {

        /** 不参与文字外观（marker 是占位字符，视觉完全由 [drawCustomStyle] 负责） */
        override val spanStyle: (RichTextConfig) -> SpanStyle =
            { SpanStyle() }

        /** marker 是段首固定标识：不允许在其边缘续写文本 */
        override val acceptNewTextInTheEdges: Boolean = false

        /** 原子单元：编辑操作不切入 marker 内部 */
        override val isAtomic: Boolean = true

        override fun DrawScope.drawCustomStyle(
            layoutResult: TextLayoutResult,
            textRange: TextRange,
            richTextConfig: RichTextConfig,
            topPadding: Float,
            startPadding: Float,
        ) {
            /** 折叠 range（无占位字符）无法定位，直接跳过 */
            if (textRange.collapsed) return

            val box = layoutResult.getBoundingBoxes(
                startOffset = textRange.start,
                endOffset = textRange.end,
                flattenForFullParagraphs = false,
            ).firstOrNull() ?: return

            val side = boxSize.toPx()
            /**
             * marker 已被 [com.mohamedrejeb.richeditor.paragraph.type.TaskList] 的
             * TextIndent 推到「[boxSize] + [gap]」之后，故勾选框画在 marker 左侧这段
             * 预留区里：左缘 = marker 左缘 - 预留宽度。
             */
            val reserved = side + gap.toPx()
            val left = box.left - reserved + startPadding
            /** 与 marker 所在行垂直居中（box 即该行的行盒） */
            val top = box.top + topPadding + (box.height - side) / 2f
            val radius = CornerRadius(cornerRadius.toPx())

            /** 圆角方框路径（勾选/未勾选共用同一条路径，仅填充与描边不同） */
            val framePath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left,
                        top = top,
                        right = left + side,
                        bottom = top + side,
                        topLeftCornerRadius = radius,
                        topRightCornerRadius = radius,
                        bottomRightCornerRadius = radius,
                        bottomLeftCornerRadius = radius,
                    )
                )
            }

            if (checked) {
                /** 勾选态：实心填充 + 白色圆头对勾 */
                drawPath(path = framePath, color = checkedColor, style = Fill)

                val checkPath = Path().apply {
                    moveTo(left + side * 0.26f, top + side * 0.52f)
                    lineTo(left + side * 0.44f, top + side * 0.70f)
                    lineTo(left + side * 0.74f, top + side * 0.32f)
                }
                drawPath(
                    path = checkPath,
                    color = checkmarkColor,
                    style = Stroke(
                        width = checkmarkStrokeWidth.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            } else {
                /** 未勾选态：只描边 */
                drawPath(
                    path = framePath,
                    color = uncheckedColor,
                    style = Stroke(width = strokeWidth.toPx()),
                )
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CheckBox) return false

            return checked == other.checked &&
                boxSize == other.boxSize &&
                gap == other.gap &&
                cornerRadius == other.cornerRadius &&
                strokeWidth == other.strokeWidth &&
                checkmarkStrokeWidth == other.checkmarkStrokeWidth &&
                checkedColor == other.checkedColor &&
                uncheckedColor == other.uncheckedColor &&
                checkmarkColor == other.checkmarkColor
        }

        override fun hashCode(): Int {
            var result = checked.hashCode()
            result = 31 * result + boxSize.hashCode()
            result = 31 * result + gap.hashCode()
            result = 31 * result + cornerRadius.hashCode()
            result = 31 * result + strokeWidth.hashCode()
            result = 31 * result + checkmarkStrokeWidth.hashCode()
            result = 31 * result + checkedColor.hashCode()
            result = 31 * result + uncheckedColor.hashCode()
            result = 31 * result + checkmarkColor.hashCode()
            return result
        }
    }

    public data object Default : RichSpanStyle {
        override val spanStyle: (RichTextConfig) -> SpanStyle =
            { SpanStyle() }

        override fun DrawScope.drawCustomStyle(
            layoutResult: TextLayoutResult,
            textRange: TextRange,
            richTextConfig: RichTextConfig,
            topPadding: Float,
            startPadding: Float,
        ): Unit = Unit

        override val acceptNewTextInTheEdges: Boolean =
            true
    }

    public companion object {
        internal val DefaultSpanStyle = SpanStyle()
    }
}

/**
 * 任务列表勾选框默认规格（v2026-09-15）。
 *
 * 与 App 侧 `CheckboxBoxIcon` 的视觉规格对齐：边长 18 / 圆角 5 的方框、
 * 未勾选 1.5 描边、对勾线宽 2、勾选框与正文间距 8（单位 sp，数值与 App 的
 * dp 规格一致）；配色为中性默认值，App 会按主题色注入覆盖。
 *
 * 可见性为 `internal`：同模块的 [com.mohamedrejeb.richeditor.paragraph.type.TaskList]
 * 需要复用同一套默认规格，避免两处各写一份常量。
 */
internal val DefaultTaskListCheckBoxSize = 18.sp
internal val DefaultTaskListCheckBoxGap = 8.sp
internal val DefaultTaskListCheckBoxCornerRadius = 5.sp
internal val DefaultTaskListCheckBoxStrokeWidth = 1.5.sp
internal val DefaultTaskListCheckmarkStrokeWidth = 2.sp
internal val DefaultTaskListCheckedColor = Color(0xFF4C9AFF)
internal val DefaultTaskListUncheckedColor = Color(0xFF8A8A8E)
internal val DefaultTaskListCheckmarkColor = Color.White