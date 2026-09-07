package com.mohamedrejeb.richeditor.paragraph.type

import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.DefaultListIndent
import com.mohamedrejeb.richeditor.model.RichSpan
import com.mohamedrejeb.richeditor.model.RichTextConfig
import com.mohamedrejeb.richeditor.paragraph.RichParagraph

/**
 * 普通段落（无列表 marker）。
 *
 * v2026-09-07 整段缩进（App 需求「纯文本按缩进 = 整体缩进两字符，不出现列表符号」）：
 * 增加 [level] 缩进层级（实现 [ConfigurableListLevel]），样式为
 * `TextIndent(firstLine = restLine = indent × (level-1))`——firstLine 与 restLine 相同
 * 即**整段左移**（区别于仅首行缩进的文本前缀方案）。缩进步长复用
 * [RichTextConfig.orderedListIndent]（App 配 30sp ≈ 两字符，与列表每级一致）。
 *
 * markdown 持久化：编码端（[com.mohamedrejeb.richeditor.parser.markdown]
 * `appendParagraphStartText`）对 level>1 输出段首全角空格（U+2003）前缀（每级 2 个），
 * 解码端（同文件 PARAGRAPH 关闭钩子）剥前缀还原 level——U+2003 是普通文本字符，
 * CommonMark 不视作缩进/代码块前缀，往返无损。
 */
@OptIn(ExperimentalRichTextApi::class)
internal class DefaultParagraph(
    initialLevel: Int = 1,
) : ParagraphType, ConfigurableListLevel {

    /**
     * 缩进层级（1 = 无缩进；2 起每级整段左移 [RichTextConfig.orderedListIndent]）。
     * 写入时重算 [style]（与 [OrderedList.level] 同款模式）。
     */
    override var level: Int = initialLevel
        set(value) {
            field = value
            style = getNewStyle()
        }

    /** 缓存的缩进步长（sp），由 [getStyle] 与 config 同步（config 变化响应式刷新） */
    private var indent = DefaultListIndent

    private var style: ParagraphStyle = getNewStyle()

    override fun getStyle(config: RichTextConfig): ParagraphStyle {
        if (config.orderedListIndent != indent) {
            indent = config.orderedListIndent
            style = getNewStyle()
        }

        return style
    }

    /** base = indent × (level-1)：一级无缩进（默认样式），二级起整段左移（firstLine = restLine） */
    private fun getNewStyle(): ParagraphStyle {
        val base = (indent * (level - 1)).toFloat()
        if (base <= 0f)
            return ParagraphStyle()

        return ParagraphStyle(
            textIndent = TextIndent(firstLine = base.sp, restLine = base.sp)
        )
    }

    override val startRichSpan: RichSpan =
        RichSpan(paragraph = RichParagraph(type = this))

    /** 段落延续（软换行/新段）继承缩进层级（与 [OrderedList.getNextParagraphType] 对称） */
    override fun getNextParagraphType(): ParagraphType =
        DefaultParagraph(initialLevel = level)

    override fun copy(): ParagraphType =
        DefaultParagraph(initialLevel = level)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DefaultParagraph) return false

        return level == other.level
    }

    override fun hashCode(): Int {
        return level
    }
}
