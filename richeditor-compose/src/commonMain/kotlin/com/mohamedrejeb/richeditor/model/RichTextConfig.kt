package com.mohamedrejeb.richeditor.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.paragraph.type.ListMarkerStyleBehavior
import com.mohamedrejeb.richeditor.paragraph.type.ListPrefixAlignment
import com.mohamedrejeb.richeditor.paragraph.type.OrderedListStyleType
import com.mohamedrejeb.richeditor.paragraph.type.UnorderedListStyleType

/**
 * Editor configuration.
 *
 * v2026-09-09 全选塌缩修复：**所有带副作用的 setter 都加了值守卫**
 * （`if (field == value) return`）。原因：setter 内的 `updateText()` 会触发
 * [com.mohamedrejeb.richeditor.model.RichTextState] 的全量重建
 * （`updateRichParagraphList`），而重建会把 selection **折叠为单光标**
 * （`TextRange(selection.min)`，见 updateRichParagraphList 尾部的
 * `textFieldValue = TextFieldValue(selection = TextRange(selectionIndex))`）。
 *
 * 宿主在**组合期**设置 config 是常见写法（每次重组都会重新执行），例如：
 * ```
 * val state = rememberRichTextState()
 * state.config.listIndent = 0   // 每次重组都执行
 * ```
 * 没有守卫时：用户全选 (0,len) → 选区写入触发下一帧重组 → 组合期又执行到
 * 这行 config 设置 → 两次全量重建 → 选区被打回 (0,0)——表现为"点全选后
 * 整段不高亮、光标跳到最左侧"。
 */
public class RichTextConfig internal constructor(
    private val updateText: () -> Unit,
) {
    public var linkColor: Color = Color.Blue
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    public var linkTextDecoration: TextDecoration = TextDecoration.Underline
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    public var codeSpanColor: Color = Color.Unspecified
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    public var codeSpanBackgroundColor: Color = Color.Transparent
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    public var codeSpanStrokeColor: Color = Color.LightGray
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    /**
     * The indent for ordered lists.
     */
    public var orderedListIndent: Int = DefaultListIndent
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    /**
     * The indent for unordered lists.
     */
    public var unorderedListIndent: Int = DefaultListIndent
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    /**
     * The indent for both ordered and unordered lists.
     *
     * This property is a shortcut for setting both [orderedListIndent] and [unorderedListIndent].
     */
    public var listIndent: Int = DefaultListIndent
        get() {
            if (orderedListIndent == unorderedListIndent)
                field = orderedListIndent

            return field
        }
        set(value) {
            /** 本 setter 不直接重建（updateText 由下方 ordered/unordered 的 setter 触发）；
             *  两者的值守卫保证：值相同时重复设置本属性不再产生任何副作用。 */
            if (field == value) return
            field = value
            orderedListIndent = value
            unorderedListIndent = value
        }

    /**
     * The prefixes for unordered lists items.
     *
     * The prefixes are used in order if the list is nested.
     *
     * For example, if the list is nested twice, the first prefix is used for the first level,
     * the second prefix is used for the second level, and so on.
     *
     * If the list is nested more than the number of prefixes, the last prefix is used.
     *
     * The default is a single `•` prefix, so **every nesting level uses the same black
     * bullet**（App 需求：缩进只改变位置，列表标识不变）。传多个前缀可恢复「按层级换符号」。
     */
    public var unorderedListStyleType: UnorderedListStyleType = DefaultUnorderedListStyleType
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    public var orderedListStyleType: OrderedListStyleType = DefaultOrderedListStyleType
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    /**
     * Controls how list markers ("•", "1.", etc.) inherit span styles from the
     * list item's text.
     *
     * Default is [ListMarkerStyleBehavior.InheritFromText], which keeps bold /
     * italic / color / font size on the marker but drops underline, strikethrough,
     * background, baseline shift, shadow, and geometric transforms. Matches
     * Google Docs.
     *
     * Set to [ListMarkerStyleBehavior.AlwaysDefault] to render every marker with
     * the default span style regardless of the item's content.
     */
    @ExperimentalRichTextApi
    public var listMarkerStyleBehavior: ListMarkerStyleBehavior = ListMarkerStyleBehavior.InheritFromText
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    /**
     * Controls where list markers ("1.", "10.", "•", ...) sit relative to the
     * indent gutter in ordered and unordered lists.
     *
     * Default is [ListPrefixAlignment.End], which matches HTML: the marker sits
     * inside the gutter and ends at the content start, so "1." and "10." have
     * their dots aligned vertically.
     *
     * Set to [ListPrefixAlignment.Start] to make every item's marker start at
     * the same left edge instead.
     */
    @ExperimentalRichTextApi
    public var listPrefixAlignment: ListPrefixAlignment = ListPrefixAlignment.End
        set(value) {
            /** 值未变化时跳过重建，防御组合期重复设置（见类 KDoc）。 */
            if (field == value) return
            field = value
            updateText()
        }

    /**
     * Whether to preserve the style when the line is empty.
     * The line can be empty when the user deletes all the characters
     * or when the user presses `enter` to create a new line.
     *
     * Default is `true`.
     */
    public var preserveStyleOnEmptyLine: Boolean = true

    /**
     * Whether to exit the list when pressing Enter on an empty list item.
     * When true, pressing Enter on an empty list item will convert it to a normal paragraph.
     * When false, pressing Enter on an empty list item will create a new list item.
     *
     * Default is `true`.
     */
    public var exitListOnEmptyItem: Boolean = true
}

internal const val DefaultListIndent = 38

/**
 * 无序列表默认符号表（v2026-09-08 App 定制）：**只含一个黑色圆点 `•`**。
 *
 * [UnorderedList] 取符号用 `prefixes[(level - 1).coerceIn(prefixes.indices)]`——单元素表
 * 让任意层级都落到同一符号，即「缩进只移动位置，标识恒为黑色圆点」（此前默认
 * `•`/`◦`/`▪` 三符号轮换，缩进后 marker 会变空心圈、黑方块）。
 */
internal val DefaultUnorderedListStyleType =
    UnorderedListStyleType.from("•")

internal val DefaultOrderedListStyleType: OrderedListStyleType =
    OrderedListStyleType.Multiple(
        OrderedListStyleType.Decimal,
        OrderedListStyleType.LowerRoman,
        OrderedListStyleType.LowerAlpha,
    )
