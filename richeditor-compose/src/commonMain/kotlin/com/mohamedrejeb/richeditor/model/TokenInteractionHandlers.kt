package com.mohamedrejeb.richeditor.model

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi

/**
 * Handles a tap on a [RichSpanStyle.Token] span in a read-only rich-text surface
 * ([com.mohamedrejeb.richeditor.ui.BasicRichText] and its Material wrappers).
 *
 * Invoked with the [token] that was hit and the [tapOffset] in local coordinates
 * of the rich-text composable - useful for anchoring a popover at the tap point.
 *
 * Typical uses:
 * - `@mention` -> open a user card or navigate to profile.
 * - `#hashtag` or `#issueRef` -> navigate to a tag feed or open an issue.
 * - `/command` -> run a command.
 */
@ExperimentalRichTextApi
public fun interface TokenClickHandler {
    public operator fun invoke(token: RichSpanStyle.Token, tapOffset: Offset)
}

/**
 * Notifies when the [RichSpanStyle.Token] under the pointer changes in a read-only
 * rich-text surface.
 *
 * Fires on **enter** (token becomes non-null), **exit** (token becomes null), and
 * **change** (pointer moves directly from one token to an adjacent token). Does NOT
 * fire on every pointer-move event while staying over the same token - the callback
 * is cheap and the caller owns any delay / positioning policy.
 *
 * @param token the token currently under the pointer, or `null` when the pointer
 *   has left all tokens.
 * @param pointerOffset current pointer position in local coordinates - use this to
 *   anchor a preview card.
 *
 * Typical use: drive a GitHub-style preview popup for `@user` or `#issue` tokens.
 */
@ExperimentalRichTextApi
public fun interface TokenHoverHandler {
    public operator fun invoke(token: RichSpanStyle.Token?, pointerOffset: Offset)
}

/**
 * Handles a tap on an inline [RichSpanStyle.Image] in the **editor** surface
 * ([com.mohamedrejeb.richeditor.ui.BasicRichTextEditor] and its Material wrappers).
 *
 * 编辑态下图片由覆盖层直接绘制（见 ui/InlineImageOverlay.kt），不是 Token，
 * 无法走 [TokenClickHandler]，因此提供独立的回调：命中图片矩形后携带该图片
 * 的 [RichSpanStyle.Image] 样式对象与点击坐标回调。
 *
 * Typical uses:
 * - 打开图片全屏预览。
 */
@ExperimentalRichTextApi
public fun interface ImageClickHandler {
    public operator fun invoke(image: RichSpanStyle.Image, tapOffset: Offset)
}

/**
 * Screen-wide default for [RichSpanStyle.Token] taps. Prefer the composable
 * `onTokenClick` parameter for a specific surface; use this CompositionLocal
 * when multiple `RichText`s on one screen should share one handler.
 */
@ExperimentalRichTextApi
public val LocalTokenClickHandler: ProvidableCompositionLocal<TokenClickHandler?> =
    staticCompositionLocalOf { null }

/**
 * Screen-wide default for inline [RichSpanStyle.Image] taps in the editor surface.
 * Prefer the composable `onImageClick` parameter for a specific surface; use this
 * CompositionLocal when the handler should be provided above the editor subtree
 * （与 [LocalTokenClickHandler] / [LocalImageLoader] 同一注入方式）。
 */
@ExperimentalRichTextApi
public val LocalImageClickHandler: ProvidableCompositionLocal<ImageClickHandler?> =
    staticCompositionLocalOf { null }

/**
 * Screen-wide default for [RichSpanStyle.Token] hover transitions. Prefer the
 * composable `onTokenHover` parameter for a specific surface; use this
 * CompositionLocal when multiple `RichText`s on one screen should share one handler.
 */
@ExperimentalRichTextApi
public val LocalTokenHoverHandler: ProvidableCompositionLocal<TokenHoverHandler?> =
    staticCompositionLocalOf { null }
