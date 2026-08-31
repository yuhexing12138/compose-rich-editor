package com.mohamedrejeb.richeditor.ui

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.clipboard.ClipboardEventEffect
import com.mohamedrejeb.richeditor.clipboard.createRichTextClipboardManager
import com.mohamedrejeb.richeditor.model.ImageClickHandler
import com.mohamedrejeb.richeditor.model.ImageLoader
import com.mohamedrejeb.richeditor.model.LocalImageClickHandler
import com.mohamedrejeb.richeditor.model.LocalImageLoader
import com.mohamedrejeb.richeditor.model.LocalRichTextMaxImageWidthProvider
import com.mohamedrejeb.richeditor.model.RichTextMaxImageWidthProvider
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlinx.coroutines.CoroutineScope

/**
 * Basic composable that enables users to edit rich text via hardware or software keyboard, but provides no decorations like hint or placeholder.
 * Whenever the user edits the texe.
 *
 * BasicRichTextEditor is a wrapper around [BasicTextField] and it accepts all the parameters that [BasicTextField] accepts.
 *
 * This composable provides basic rich text editing functionality, however does not include any
 * decorations such as borders, hints/placeholder. A design system based implementation such as
 * Material Design Filled text field is typically what is needed to cover most of the needs. This
 * composable is designed to be used when a custom implementation for different design system is
 * needed.
 *
 * @param state [RichTextState] that holds the state of the [BasicRichTextEditor].
 * @param modifier optional [Modifier] for this text field.
 * @param enabled controls the enabled state of the [BasicRichTextEditor]. When `false`, the text
 * field will be neither editable nor focusable, the input of the text field will not be selectable
 * @param readOnly controls the editable state of the [BasicRichTextEditor]. When `true`, the text
 * field can not be modified, however, a user can focus it and copy text from it. Read-only text
 * fields are usually used to display pre-filled forms that user can not edit
 * @param textStyle Style configuration that applies at character level such as color, font etc.
 * @param keyboardOptions software keyboard options that contains configuration such as
 * [KeyboardType] and [ImeAction].
 * @param keyboardActions when the input service emits an IME action, the corresponding callback
 * is called. Note that this IME action may be different from what you specified in
 * [KeyboardOptions.imeAction].
 * @param singleLine when set to true, this text field becomes a single horizontally scrolling
 * text field instead of wrapping onto multiple lines. The keyboard will be informed to not show
 * the return key as the [ImeAction]. [maxLines] and [minLines] are ignored as both are
 * automatically set to 1.
 * @param maxLines the maximum height in terms of maximum number of visible lines. It is required
 * that 1 <= [minLines] <= [maxLines]. This parameter is ignored when [singleLine] is true.
 * @param minLines the minimum height in terms of minimum number of visible lines. It is required
 * that 1 <= [minLines] <= [maxLines]. This parameter is ignored when [singleLine] is true.
 * @param maxLength the maximum length of the text field. If the text is longer than this value,
 * it will be ignored. The default value of this parameter is [Int.MAX_VALUE].
 * @param onTextLayout Callback that is executed when a new text layout is calculated. A
 * [TextLayoutResult] object that callback provides contains paragraph information, size of the
 * text, baselines and other details. The callback can be used to add additional decoration or
 * functionality to the text. For example, to draw a cursor or selection around the text.
 * @param interactionSource the [MutableInteractionSource] representing the stream of
 * [Interaction]s for this TextField. You can create and pass in your own remembered
 * [MutableInteractionSource] if you want to observe [Interaction]s and customize the
 * appearance / behavior of this TextField in different [Interaction]s.
 * @param cursorBrush [Brush] to paint cursor with. If [SolidColor] with [Color.Unspecified]
 * provided, there will be no cursor drawn
 * @param decorationBox Composable lambda that allows to add decorations around text field, such
 * as icon, placeholder, helper messages or similar, and automatically increase the hit target area
 * of the text field. To allow you to control the placement of the inner text field relative to your
 * decorations, the text field implementation will pass in a framework-controlled composable
 * parameter "innerTextField" to the decorationBox lambda you provide. You must call
 * innerTextField exactly once.
 *
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
public fun BasicRichTextEditor(
    state: RichTextState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    maxLength: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    cursorBrush: Brush = SolidColor(Color.Black),
    undoBehavior: UndoBehavior = UndoBehavior.Enabled,
    imageLoader: ImageLoader = LocalImageLoader.current,
    onImageClick: ImageClickHandler? = LocalImageClickHandler.current,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit =
        @Composable { innerTextField -> innerTextField() }
) {
    BasicRichTextEditor(
        state = state,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        maxLength = maxLength,
        onTextLayout = onTextLayout,
        interactionSource = interactionSource,
        cursorBrush = cursorBrush,
        undoBehavior = undoBehavior,
        imageLoader = imageLoader,
        onImageClick = onImageClick,
        decorationBox = decorationBox,
        contentPadding = PaddingValues()
    )
}

/**
 * Basic composable that enables users to edit rich text via hardware or software keyboard, but provides no decorations like hint or placeholder.
 * Whenever the user edits the texe.
 *
 * BasicRichTextEditor is a wrapper around [BasicTextField] and it accepts all the parameters that [BasicTextField] accepts.
 *
 * This composable provides basic rich text editing functionality, however does not include any
 * decorations such as borders, hints/placeholder. A design system based implementation such as
 * Material Design Filled text field is typically what is needed to cover most of the needs. This
 * composable is designed to be used when a custom implementation for different design system is
 * needed.
 *
 * @param state [RichTextState] that holds the state of the [BasicRichTextEditor].
 * @param modifier optional [Modifier] for this text field.
 * @param enabled controls the enabled state of the [BasicRichTextEditor]. When `false`, the text
 * field will be neither editable nor focusable, the input of the text field will not be selectable
 * @param readOnly controls the editable state of the [BasicRichTextEditor]. When `true`, the text
 * field can not be modified, however, a user can focus it and copy text from it. Read-only text
 * fields are usually used to display pre-filled forms that user can not edit
 * @param textStyle Style configuration that applies at character level such as color, font etc.
 * @param keyboardOptions software keyboard options that contains configuration such as
 * [KeyboardType] and [ImeAction].
 * @param keyboardActions when the input service emits an IME action, the corresponding callback
 * is called. Note that this IME action may be different from what you specified in
 * [KeyboardOptions.imeAction].
 * @param singleLine when set to true, this text field becomes a single horizontally scrolling
 * text field instead of wrapping onto multiple lines. The keyboard will be informed to not show
 * the return key as the [ImeAction]. [maxLines] and [minLines] are ignored as both are
 * automatically set to 1.
 * @param maxLines the maximum height in terms of maximum number of visible lines. It is required
 * that 1 <= [minLines] <= [maxLines]. This parameter is ignored when [singleLine] is true.
 * @param minLines the minimum height in terms of minimum number of visible lines. It is required
 * that 1 <= [minLines] <= [maxLines]. This parameter is ignored when [singleLine] is true.
 * @param maxLength the maximum length of the text field. If the text is longer than this value,
 * it will be ignored. The default value of this parameter is [Int.MAX_VALUE].
 * @param onTextLayout Callback that is executed when a new text layout is calculated. A
 * [TextLayoutResult] object that callback provides contains paragraph information, size of the
 * text, baselines and other details. The callback can be used to add additional decoration or
 * functionality to the text. For example, to draw a cursor or selection around the text.
 * @param interactionSource the [MutableInteractionSource] representing the stream of
 * [Interaction]s for this TextField. You can create and pass in your own remembered
 * [MutableInteractionSource] if you want to observe [Interaction]s and customize the
 * appearance / behavior of this TextField in different [Interaction]s.
 * @param cursorBrush [Brush] to paint cursor with. If [SolidColor] with [Color.Unspecified]
 * provided, there will be no cursor drawn
 * @param decorationBox Composable lambda that allows to add decorations around text field, such
 * as icon, placeholder, helper messages or similar, and automatically increase the hit target area
 * of the text field. To allow you to control the placement of the inner text field relative to your
 * decorations, the text field implementation will pass in a framework-controlled composable
 * parameter "innerTextField" to the decorationBox lambda you provide. You must call
 * innerTextField exactly once.
 *
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
public fun BasicRichTextEditor(
    state: RichTextState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    singleParagraph: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    maxLength: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    cursorBrush: Brush = SolidColor(Color.Black),
    undoBehavior: UndoBehavior = UndoBehavior.Enabled,
    imageLoader: ImageLoader = LocalImageLoader.current,
    onImageClick: ImageClickHandler? = LocalImageClickHandler.current,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit =
        @Composable { innerTextField -> innerTextField() },
    contentPadding: PaddingValues
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val clipboard = LocalClipboard.current

    /**
     * v2026-08-31：剥离 textStyle 的 lineHeight + 注入正文行高，修复图片段落行高被锁死。
     *
     * Compose 排版对每个 ParagraphStyle range 执行 `defaultParagraphStyle.merge(range.item)`
     * （AnnotatedString.normalizedParagraphStyles），**default（BasicTextField 的 textStyle）
     * 的已指定字段优先**。textStyle 自带的 lineHeight（Material3 typography 通常指定，
     * 如 bodyLarge 的 24.sp）会覆盖段落 range 里预置的图片行高 → 图片段落行高被锁死为
     * 一行正文 → 覆盖层按占位符行的 lineTop 画大图 → 相邻图片相互重叠。
     *
     * 处理：传给 BasicTextField 的 textStyle 剥掉 lineHeight（置 Unspecified）；
     * 原始值注入 state.editorLineHeight，由 withImageBlockLineHeight 还回每个段落
     * range——普通段落行高视觉不变，图片段落撑到图片高度。
     */
    SideEffect {
        val lineHeight = textStyle.lineHeight
        /**
         * 变化检测不能用裸 `!=`：TextUnit.Unspecified.value 是 NaN，NaN != NaN 恒为 true，
         * 会造成"每次组合都重建"的无限循环。目标值 Unspecified 时按"当前是否已 Unspecified"判断。
         */
        val changed =
            if (lineHeight.isSpecified)
                state.editorLineHeight != lineHeight
            else
                state.editorLineHeight.isSpecified
        if (changed) {
            state.editorLineHeight = lineHeight
            /** 行高进段落 range 依赖 annotatedString 重建，注入变化后立即重建一次 */
            state.updateAnnotatedString()
        }
    }
    val effectiveTextStyle = textStyle.copy(lineHeight = TextUnit.Unspecified)

    /**
     * v2026-08-01 Phase 4：编辑模式下 inline 图片渲染限制
     *
     * Compose Foundation 1.11.0（BOM 2026.04.01）的 BasicTextField 已移除 inlineContent 参数，
     * 因此编辑模式下 RichSpanStyle.Image 只能显示为占位符（U+FFFD）。
     * 只读版本 BasicRichText 使用 BasicText（仍支持 inlineContent）可完整渲染图片。
     *
     * 保留 maxImageWidthProvider + LocalImageLoader 注入，确保：
     * 1. 切换到只读模式时能立即渲染图片
     * 2. onSizeChanged 持续更新容器宽度，供 RichSpanStyle.Image 按 maxWidth 缩放
     */
    val maxImageWidthProvider = remember { RichTextMaxImageWidthProvider() }

    val richClipboardManager = remember(state, clipboard) {
        createRichTextClipboardManager(
            richTextState = state,
            clipboard = clipboard
        )
    }

    ClipboardEventEffect(richTextState = state)

    LaunchedEffect(singleParagraph) {
        state.singleParagraphMode = singleParagraph
    }

    DisposableEffect(state, undoBehavior) {
        state.suppressUndoShortcuts = (undoBehavior == UndoBehavior.Disabled)
        onDispose { state.suppressUndoShortcuts = false }
    }

    if (!singleParagraph) {
        // Workaround for Android to fix a bug in BasicTextField where it doesn't select the correct text
        // when the text contains multiple paragraphs.
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        state.onSelectionGestureStart()

                        val pressPosition = interaction.pressPosition
                        val topPadding = with(density) { contentPadding.calculateTopPadding().toPx() }
                        val startPadding = with(density) { contentPadding.calculateStartPadding(layoutDirection).toPx() }

                        adjustTextIndicatorOffset(
                            pressPosition = pressPosition,
                            state = state,
                            topPadding = topPadding,
                            startPadding = startPadding,
                        )
                    }

                    is PressInteraction.Release,
                    is PressInteraction.Cancel -> state.onSelectionGestureEnd()
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalClipboard provides richClipboardManager,
        LocalImageLoader provides imageLoader,
        LocalRichTextMaxImageWidthProvider provides maxImageWidthProvider,
    ) {
        // Capture position on the innerTextField (the actual text content composable),
        // not on the outer BasicTextField, so trigger-suggestion popups can anchor
        // precisely at the text content's origin - not at the top of the decorated
        // container (which for OutlinedRichTextEditor is ~16dp higher).
        val positionCapturingDecorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit =
            { innerTextField ->
                decorationBox {
                    Layout(
                        content = { innerTextField() },
                        modifier = Modifier.onPlaced { coords ->
                            state.textFieldWindowPosition = coords.positionInWindow()
                        }
                    ) { measurables, constraints ->
                        val placeable = measurables.first().measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
                }
            }

        /**
         * v2026-08-31：编辑态内联图片覆盖层
         *
         * BasicTextField 不支持 inlineContent（foundation 1.11 连该参数都没有），
         * 所以图片不能靠库自带的 InlineTextContent 渲染，只能在文字之上自己画。
         *
         * 三步走：
         * 1. 组合期取 painter + 按容器宽度算显示尺寸（resolveInlineImagePlacements）
         * 2. 尺寸写回 Image span 并重建 annotatedString，让段落 lineHeight 预留纵向空间
         *    （ApplyInlineImageSizes）
         * 3. drawInlineImages 按"行"定位，在文本之上画出位图
         *
         * 放在 CompositionLocalProvider 内部，保证 imageLoader 与容器宽度
         * 对 Coil 等加载器可见。
         */
        val inlineImagePlacements = resolveInlineImagePlacements(
            state = state,
            imageLoader = imageLoader,
            maxImageWidth = maxImageWidthProvider.maxWidth,
        )
        ApplyInlineImageSizes(
            state = state,
            placements = inlineImagePlacements,
        )

        BasicTextField(
            value = state.textFieldValue,
            onValueChange = {
                if (readOnly) return@BasicTextField
                if (it.text.length > maxLength) return@BasicTextField

                state.onTextFieldValueChange(it)
            },
            modifier = modifier
                .onFocusChanged { focusState ->
                    state.isFocused = focusState.isFocused
                }
                .onPreviewKeyEvent { event ->
                    if (readOnly)
                        return@onPreviewKeyEvent false

                    state.onPreviewKeyEvent(event)
                }
                .drawRichSpanStyle(
                    richTextState = state,
                    topPadding = with(density) { contentPadding.calculateTopPadding().toPx() },
                    startPadding = with(density) { contentPadding.calculateStartPadding(layoutDirection).toPx() },
                )
                .then(
                    if (!readOnly)
                        Modifier
                    else
                        Modifier.focusProperties { canFocus = false }
                )
                .then(
                    if (singleParagraph)
                        Modifier
                    else
                        Modifier
                            // Passive pointer observer feeding the geometric selection
                            // clamp; never consumes events.
                            .pointerInput(state) {
                                val topPadding = with(density) { contentPadding.calculateTopPadding().toPx() }
                                val startPadding =
                                    with(density) { contentPadding.calculateStartPadding(layoutDirection).toPx() }
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.pressed } ?: continue
                                        state.onSelectionGesturePointerMove(
                                            Offset(
                                                change.position.x - startPadding,
                                                change.position.y - topPadding,
                                            )
                                        )
                                    }
                                }
                            }
                            // Workaround for Desktop to fix a bug in BasicTextField where it doesn't select the correct text
                            // when the text contains multiple paragraphs.
                            .adjustTextIndicatorOffset(
                                state = state,
                                contentPadding = contentPadding,
                                density = density,
                                layoutDirection = layoutDirection,
                                scope = rememberCoroutineScope()
                            )
                )
                // v2026-08-01 Phase 4：更新容器宽度，供 RichSpanStyle.Image 按 maxWidth 缩放
                .onSizeChanged { size ->
                    val newWidth = with(density) { size.width.toSp() }
                    if (newWidth != maxImageWidthProvider.maxWidth) {
                        maxImageWidthProvider.maxWidth = newWidth
                    }
                }
                // v2026-08-31：必须挂在链尾，drawWithContent 会包住前面所有绘制
                // （含 drawRichSpanStyle 与文本本体），这样图片才画在最上层。
                .pointerInputInlineImages(
                    state = state,
                    placements = inlineImagePlacements,
                    density = density,
                    topPadding = with(density) { contentPadding.calculateTopPadding().toPx() },
                    startPadding = with(density) { contentPadding.calculateStartPadding(layoutDirection).toPx() },
                    onImageClick = onImageClick,
                )
                .drawInlineImages(
                    state = state,
                    placements = inlineImagePlacements,
                    density = density,
                    topPadding = with(density) { contentPadding.calculateTopPadding().toPx() },
                    startPadding = with(density) { contentPadding.calculateStartPadding(layoutDirection).toPx() },
                ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = effectiveTextStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation = if (enabled) {
                state.visualTransformation
            } else {
                DisabledTextVisualTransformation(
                    delegate = state.visualTransformation,
                    disabledAlpha = DisabledStateAlpha,
                )
            },
            onTextLayout = {
                state.onTextLayout(
                    textLayoutResult = it,
                    density = density,
                )
                onTextLayout(it)
            },
            interactionSource = interactionSource,
            cursorBrush = cursorBrush,
            decorationBox = positionCapturingDecorationBox,
            // v2026-08-01 Phase 4：Compose 1.11.0 的 BasicTextField 已移除 inlineContent 参数
            // 编辑模式下 RichSpanStyle.Image 显示为占位符，只读 BasicRichText 才完整渲染图片
            // 若需编辑时预览图片，需切换为 BasicText + 手动管理光标，或等待库官方支持
        )
    }
}

internal expect fun Modifier.adjustTextIndicatorOffset(
    state: RichTextState,
    contentPadding: PaddingValues,
    density: Density,
    layoutDirection: LayoutDirection,
    scope: CoroutineScope,
): Modifier

internal suspend fun adjustTextIndicatorOffset(
    pressPosition: Offset,
    state: RichTextState,
    topPadding: Float,
    startPadding: Float,
) {
    state.adjustSelectionAndRegisterPressPosition(
        pressPosition = Offset(
            x = pressPosition.x - startPadding,
            y = pressPosition.y - topPadding
        ),
    )
}

public typealias RichTextChangedListener = (RichTextState) -> Unit
