package com.mohamedrejeb.richeditor.parser.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichSpan
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.paragraph.RichParagraph
import com.mohamedrejeb.richeditor.paragraph.type.ConfigurableListLevel
import com.mohamedrejeb.richeditor.paragraph.type.DefaultParagraph
import com.mohamedrejeb.richeditor.paragraph.type.OrderedList
import com.mohamedrejeb.richeditor.paragraph.type.ParagraphType
import com.mohamedrejeb.richeditor.paragraph.type.UnorderedList
import com.mohamedrejeb.richeditor.parser.RichTextStateParser
import com.mohamedrejeb.richeditor.parser.html.BrElement
import com.mohamedrejeb.richeditor.parser.html.RichTextStateHtmlParser
import com.mohamedrejeb.richeditor.parser.html.htmlElementsSpanStyleEncodeMap
import com.mohamedrejeb.richeditor.parser.html.CssEncoder
import com.mohamedrejeb.richeditor.parser.utils.*
import com.mohamedrejeb.richeditor.utils.InlineContentPlaceholder
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import kotlin.math.roundToInt

internal object RichTextStateMarkdownParser : RichTextStateParser<String> {

    @OptIn(ExperimentalRichTextApi::class)
    override fun encode(input: String): RichTextState {
        val openedNodes = mutableListOf<ASTNode>()
        val openedHtmlTags = mutableListOf<String>()
        val richParagraphList = mutableListOf(RichParagraph())
        var brParagraphIndices = mutableListOf<Int>()
        var currentRichSpan: RichSpan? = null
        var currentRichParagraphType: ParagraphType = DefaultParagraph()
        var currentListLevel = 0

        fun onAddLineBreak() {
            val lastParagraph = richParagraphList.lastOrNull()
            val beforeLastParagraph = richParagraphList.getOrNull(richParagraphList.lastIndex - 1)
            val lastBrIndex = brParagraphIndices.lastOrNull()
            val beforeLastBrIndex = brParagraphIndices.getOrNull(brParagraphIndices.lastIndex - 1)

            // We need this for line break to work fine with EOL
            if (
                lastParagraph?.isEmpty() != true ||
                beforeLastParagraph?.isEmpty() != true ||
                lastBrIndex == richParagraphList.lastIndex ||
                beforeLastBrIndex == richParagraphList.lastIndex - 1
            )
                richParagraphList.add(RichParagraph())

            brParagraphIndices.add(richParagraphList.lastIndex)

            currentRichSpan = null
        }

        fun onText(text: String) {
            val text = text.replace('\n', ' ')

            if (text.isEmpty()) return

            if (richParagraphList.isEmpty())
                richParagraphList.add(RichParagraph())

            val currentRichParagraph = richParagraphList.last()
            val safeCurrentRichSpan = currentRichSpan ?: RichSpan(paragraph = currentRichParagraph)

            if (safeCurrentRichSpan.children.isEmpty()) {
                safeCurrentRichSpan.text += text
            } else {
                val newRichSpan = RichSpan(
                    paragraph = currentRichParagraph,
                    parent = safeCurrentRichSpan,
                )
                newRichSpan.text = text
                safeCurrentRichSpan.children.add(newRichSpan)
            }

            if (currentRichSpan == null) {
                currentRichSpan = safeCurrentRichSpan
                currentRichParagraph.children.add(safeCurrentRichSpan)
            }

            val currentRichSpanRichSpanStyle = currentRichSpan?.richSpanStyle
            val lastOpenedNode = openedNodes.lastOrNull()

            if (lastOpenedNode?.type == MarkdownElementTypes.IMAGE && text == "!") {
                currentRichSpan?.text = ""
            }

            if (currentRichSpanRichSpanStyle is RichSpanStyle.Image) {
                currentRichSpan?.richSpanStyle =
                    RichSpanStyle.Image(
                        model = currentRichSpanRichSpanStyle.model,
                        width = currentRichSpanRichSpanStyle.width,
                        height = currentRichSpanRichSpanStyle.height,
                        contentDescription = text
                    )

                // Image owns a single placeholder char in the raw text so span
                // textRanges line up with the rendered annotated string. See #466.
                currentRichSpan?.text = InlineContentPlaceholder
            }
        }

        // Correct the markdown text first so we can use it in callbacks
        val correctedMarkdown = correctMarkdownText(input)

        encodeMarkdownToRichText(
            markdown = correctedMarkdown,
            onText = { text ->
                onText(text)
            },
            onOpenNode = { node ->
                val lastOpenedNode = openedNodes.lastOrNull()

                openedNodes.add(node)

                if (node.type == MarkdownElementTypes.LIST_ITEM) {
                    currentListLevel++
                }

                val tagSpanStyle = markdownElementsSpanStyleEncodeMap[node.type]
                val tagParagraphStyle = markdownElementsParagraphStyleEncodeMap[node.type]

                if (node.type in markdownBlockElements) {
                    val currentRichParagraph = richParagraphList.last()

                    val isList =
                        lastOpenedNode?.type == MarkdownElementTypes.ORDERED_LIST ||
                                lastOpenedNode?.type == MarkdownElementTypes.UNORDERED_LIST

                    // Get paragraph type from markdown element
                    if (currentRichParagraphType is DefaultParagraph || isList) {
                        val paragraphType = encodeRichParagraphTypeFromMarkdownElement(lastOpenedNode ?: node)
                        currentRichParagraphType = paragraphType
                    }

                    // Set paragraph type if an element is a list item
                    if (node.type == MarkdownElementTypes.LIST_ITEM) {
                        currentRichParagraphType = currentRichParagraphType.getNextParagraphType()

                        // v2026-09-05: 列表层级以**源码行首缩进**为准（每级 2 空格，与编码端
                        // appendParagraphStartText 的约定对称），而非 AST 嵌套深度——
                        // 孤儿缩进行（如单列表项独立 setMarkdown("  1. b")，无父链使 AST 深度恒为 1）
                        // 也能保留层级，供块架构（每块单列表项）的层级缩进特性使用。
                        val sourceIndentLevel = run {
                            val lineStart = correctedMarkdown.lastIndexOf('\n', node.startOffset)
                                .let { if (it < 0) 0 else it + 1 }
                            var spaces = 0
                            while (lineStart + spaces < correctedMarkdown.length &&
                                correctedMarkdown[lineStart + spaces] == ' '
                            ) spaces++
                            spaces / 2 + 1
                        }

                        if (currentRichParagraphType is ConfigurableListLevel) {
                            (currentRichParagraphType as ConfigurableListLevel).level = sourceIndentLevel
                        }

                        // Interrupted lists parse as separate list nodes; seed the item
                        // with the literal source number so the author's numbering
                        // survives renumbering (#734).
                        val literalNumber = node.children
                            .firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
                            ?.getTextInNode(correctedMarkdown)
                            ?.toString()
                            ?.takeWhile { char -> char.isDigit() }
                            ?.toIntOrNull()
                        if (literalNumber != null) {
                            currentRichParagraphType = OrderedList(
                                number = literalNumber,
                                initialLevel = sourceIndentLevel,
                                startFrom = literalNumber,
                            )
                        }

                        currentRichParagraph.type = currentRichParagraphType
                    }

                    // Apply paragraph style (if applicable)
                    tagParagraphStyle?.let {
                        currentRichParagraph.paragraphStyle = currentRichParagraph.paragraphStyle.merge(it)
                    }
                    // Record heading level so encoding stays semantic instead of fingerprinting.
                    if (node.type in HeadingStyle.markdownHeadingNodes) {
                        currentRichParagraph.headingStyle = when (node.type) {
                            MarkdownElementTypes.ATX_1 -> HeadingStyle.H1
                            MarkdownElementTypes.ATX_2 -> HeadingStyle.H2
                            MarkdownElementTypes.ATX_3 -> HeadingStyle.H3
                            MarkdownElementTypes.ATX_4 -> HeadingStyle.H4
                            MarkdownElementTypes.ATX_5 -> HeadingStyle.H5
                            MarkdownElementTypes.ATX_6 -> HeadingStyle.H6
                            else -> HeadingStyle.Normal
                        }
                    }

                    val newRichSpan = RichSpan(paragraph = currentRichParagraph)
                    newRichSpan.spanStyle = tagSpanStyle ?: SpanStyle()

                    if (newRichSpan.spanStyle != SpanStyle()) {
                        currentRichSpan = newRichSpan
                        currentRichParagraph.children.add(newRichSpan)
                    } else {
                        currentRichSpan = null
                    }
                } else if (node.type != MarkdownTokenTypes.EOL) {
                    val richSpanStyle = encodeMarkdownElementToRichSpanStyle(node, correctedMarkdown)

                    if (richParagraphList.isEmpty())
                        richParagraphList.add(RichParagraph())

                    val currentRichParagraph = richParagraphList.last()
                    val newRichSpan = RichSpan(paragraph = currentRichParagraph)
                    newRichSpan.spanStyle = tagSpanStyle ?: SpanStyle()
                    newRichSpan.richSpanStyle = richSpanStyle

                    val currentRichSpanParent = currentRichSpan?.parent

                    // Avoid nesting if the current rich span doesn't add a styling
                    if (
                        currentRichSpan?.fullSpanStyle == SpanStyle() &&
                        currentRichSpan?.fullStyle is RichSpanStyle.Default
                    ) {
                        if (currentRichSpan?.isEmpty() == true) {
                            if (currentRichSpanParent != null)
                                currentRichSpanParent.children.removeAt(currentRichSpanParent.children.lastIndex)
                            else
                                currentRichParagraph.children.removeAt(currentRichParagraph.children.lastIndex)
                        }

                        currentRichSpan = null
                    }

                    val newRichSpanParent = currentRichSpan ?: currentRichSpanParent

                    if (newRichSpanParent != null) {
                        newRichSpan.parent = newRichSpanParent
                        newRichSpanParent.children.add(newRichSpan)
                        currentRichSpan = newRichSpan
                    } else {
                        currentRichParagraph.children.add(newRichSpan)
                        currentRichSpan = newRichSpan
                    }

                    if (
                        openedNodes.getOrNull(openedNodes.lastIndex - 1)?.type != GFMElementTypes.INLINE_MATH &&
                        node.type == GFMTokenTypes.DOLLAR
                    )
                        newRichSpan.text = "$".repeat(node.endOffset - node.startOffset)
                }

                if (
                    node.type == GFMTokenTypes.GFM_AUTOLINK ||
                    node.type == MarkdownTokenTypes.CODE_LINE ||
                    // Fenced code blocks (```...```) emit their body as
                    // CODE_FENCE_CONTENT tokens. Without this branch the
                    // content was dropped on decode (#253, #540).
                    node.type == MarkdownTokenTypes.CODE_FENCE_CONTENT
                ) {
                    onText(node.getTextInNode(correctedMarkdown).toString())
                }
            },
            onCloseNode = { node ->
                openedNodes.removeLastOrNull()

                if (node.type == MarkdownElementTypes.LIST_ITEM) {
                    currentListLevel--
                }

                // Remove empty spans
                if (currentRichSpan?.isEmpty() == true) {
                    val parent = currentRichSpan?.parent
                    if (parent != null)
                        currentRichSpan?.parent?.children?.remove(currentRichSpan)
                    else
                        currentRichSpan?.paragraph?.children?.remove(currentRichSpan)
                }

                // Merge spans with only one child
                if (currentRichSpan?.text?.isEmpty() == true && currentRichSpan?.children?.size == 1) {
                    currentRichSpan?.children?.firstOrNull()?.let { child ->
                        currentRichSpan?.text = child.text
                        currentRichSpan?.spanStyle =
                            currentRichSpan?.spanStyle?.merge(child.spanStyle) ?: child.spanStyle
                        currentRichSpan?.richSpanStyle = child.richSpanStyle
                        currentRichSpan?.children?.clear()
                        currentRichSpan?.children?.addAll(child.children)
                    }
                }

                // Add new line if needed.
                // Prevent adding two consecutive new lines
                if (node.type == MarkdownTokenTypes.EOL) {
                    val lastParagraph = richParagraphList.lastOrNull()
                    val beforeLastParagraph = richParagraphList.getOrNull(richParagraphList.lastIndex - 1)
                    val lastBrParagraphIndex = brParagraphIndices.lastOrNull()
                    val beforeLastBrParagraphIndex = brParagraphIndices.getOrNull(brParagraphIndices.lastIndex - 1)

                    if (
                        lastParagraph?.isNotEmpty() == true ||
                        beforeLastParagraph?.isNotEmpty() == true ||
                        lastBrParagraphIndex == richParagraphList.lastIndex ||
                        beforeLastBrParagraphIndex == richParagraphList.lastIndex - 1
                    ) {
                        richParagraphList.add(RichParagraph())
                    }

                    currentRichSpan = null
                }

                val lastOpenedNode = openedNodes.lastOrNull()

                val isList =
                    node.type == MarkdownElementTypes.ORDERED_LIST ||
                            node.type == MarkdownElementTypes.UNORDERED_LIST

                val isLastList =
                    lastOpenedNode != null &&
                            (lastOpenedNode.type == MarkdownElementTypes.ORDERED_LIST ||
                                    lastOpenedNode.type == MarkdownElementTypes.UNORDERED_LIST ||
                                    lastOpenedNode.type == MarkdownElementTypes.LIST_ITEM)

                // Reset paragraph type
                if (isList && !isLastList) {
                    currentRichParagraphType = DefaultParagraph()
                }

                currentRichSpan = currentRichSpan?.parent
            },
            onHtmlTag = { tag ->
                /**
                 * 解析内联 HTML 标签 style 属性里的 CSS 声明，整体转成 [SpanStyle]。
                 *
                 * 例：`<span style="font-size:16px;color:#E88A4D;font-weight:800">`。
                 * 编码侧（[decodeRichSpanToMarkdown]）把 markdown 原生语法无法表达的
                 * 字号 / 文字颜色 / 非 700 字重写成内联 CSS，这里负责还原。
                 *
                 * 复用 [CssEncoder] 现成解析（px/pt/em/rem/% 尺寸、hex 与 rgb/rgba 色值、
                 * 任意整数字重），不重复实现，避免两边规则漂移。
                 *
                 * @return 解析出的 [SpanStyle]；无 style 属性或解析不出任何声明时返回 null。
                 */
                fun parseInlineStyleToSpanStyle(raw: String): SpanStyle? {
                    val style = Regex(
                        """style\s*=\s*["']([^"']*)["']""",
                        RegexOption.IGNORE_CASE,
                    ).find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return null
                    return CssEncoder.parseCssStyleMapToSpanStyle(CssEncoder.parseCssStyle(style))
                }

                val tagName = tag
                    .substringAfter("</")
                    .substringAfter("<")
                    .substringBefore(">")
                    .substringBefore(" ")
                    .trim()
                    .lowercase()

                val isClosingTag = tag.startsWith("</")

                if (isClosingTag) {
                    openedHtmlTags.removeLastOrNull()

                    if (tagName != BrElement)
                        currentRichSpan = currentRichSpan?.parent
                } else {
                    openedHtmlTags.add(tag)

                    val tagSpanStyle = htmlElementsSpanStyleEncodeMap[tagName]

                    if (tagName != BrElement) {
                        val currentRichParagraph = richParagraphList.last()
                        val newRichSpan = RichSpan(paragraph = currentRichParagraph)
                        // 合并 style 属性解析出的完整 SpanStyle（库原生 onHtmlTag 只按标签名查
                        // 映射，会丢弃属性，导致字号/颜色/非 700 字重在 markdown 往返中丢失）。
                        // merge 只覆盖 other 中「已指定」的字段，style 里没写的维度保持标签映射值。
                        val inlineStyle = parseInlineStyleToSpanStyle(tag)
                        // 合并 style 属性解析出的完整 SpanStyle（库原生 onHtmlTag 只按标签名查
                        // 映射，会丢弃属性，导致字号/颜色/非 700 字重在 markdown 往返中丢失）。
                        // merge 只覆盖 other 中「已指定」的字段，style 里没写的维度保持标签映射值。
                        // inlineStyle 为空（标签无 style 属性，如纯 <b>/<i>）时回落标签映射值。
                        newRichSpan.spanStyle = if (inlineStyle != null) {
                            (tagSpanStyle ?: SpanStyle()).merge(inlineStyle)
                        } else {
                            tagSpanStyle ?: SpanStyle()
                        }

                        if (currentRichSpan != null) {
                            newRichSpan.parent = currentRichSpan
                            currentRichSpan?.children?.add(newRichSpan)
                        } else {
                            currentRichParagraph.children.add(newRichSpan)
                        }
                        currentRichSpan = newRichSpan
                    } else {
                        // name == "br"
                        onAddLineBreak()
                    }
                }
            },
            onHtmlBlock = {
                var html = it

                while (true) {
                    val brIndex = html.indexOf("<br>")

                    if (brIndex == -1)
                        break

                    html = html.substring(brIndex + 4)

                    onAddLineBreak()
                }

                if (html.isNotBlank())
                    richParagraphList.addAll(RichTextStateHtmlParser.encode(html).richParagraphList)

                // Todo: support HTML Block in markdown
            }
        )

        val toDeleteParagraphIndices = mutableListOf<Int>()
        var lastNonEmptyParagraphIndex = -1
        var lastBrParagraphIndex = -1

        richParagraphList.forEachIndexed { i, paragraph ->
            paragraph.trim()

            val isEmpty = paragraph.isEmpty()
            val isBr = i in brParagraphIndices

            // Delete empty paragraphs between line breaks to match Markdown rendering
            if (isBr && lastNonEmptyParagraphIndex < lastBrParagraphIndex) {
                val range = (lastBrParagraphIndex + 1)..(i - 1)

                if (!range.isEmpty())
                    toDeleteParagraphIndices.addAll(range)
            }

            if (!isEmpty)
                lastNonEmptyParagraphIndex = i

            if (isBr)
                lastBrParagraphIndex = i
        }

        toDeleteParagraphIndices.reversed().forEach { i ->
            richParagraphList.removeAt(i)
        }

        return RichTextState(
            initialRichParagraphList = richParagraphList,
        )
    }

    override fun decode(richTextState: RichTextState): String {
        val builder = StringBuilder()

        var useLineBreak = false

        richTextState.richParagraphList.fastForEachIndexed { index, richParagraph ->
            // Append paragraph start text
            builder.appendParagraphStartText(richParagraph)

            // Read the heading prefix from the first-class field rather than fingerprinting
            // the first child's SpanStyle.
            if (richParagraph.headingStyle != HeadingStyle.Normal) {
                builder.append(richParagraph.headingStyle.markdownPrefix)
            }

            // Append paragraph children. Inside a heading paragraph the heading defaults already
            // imply bold/font-size/etc., so suppress redundant ** formatting on heading-implied
            // attributes.
            val isHeading = richParagraph.headingStyle != HeadingStyle.Normal
            richParagraph.children.fastForEach { richSpan ->
                builder.append(decodeRichSpanToMarkdown(richSpan, isHeading = isHeading))
            }

            // Append line break if needed
            val isBlank = richParagraph.isBlank()

            if (useLineBreak && isBlank)
                builder.append("<br>")

            useLineBreak = isBlank

            if (index < richTextState.richParagraphList.lastIndex) {
                // Append new line
                builder.appendLine()

                // CommonMark requires a list block to be preceded by a blank line when it
                // follows a non-list paragraph; otherwise a lone `-` underneath a non-empty
                // line is parsed as a setext H2 underline (turning the paragraph into a
                // heading and dropping the list). See #441.
                val nextParagraph = richTextState.richParagraphList[index + 1]
                if (
                    !isBlank &&
                    !richParagraph.type.isList() &&
                    nextParagraph.type.isList()
                ) {
                    builder.appendLine()
                }
            }
        }

        return correctMarkdownText(builder.toString())
    }

    private fun ParagraphType.isList(): Boolean =
        this is OrderedList || this is UnorderedList

    @OptIn(ExperimentalRichTextApi::class)
    private fun decodeRichSpanToMarkdown(
        richSpan: RichSpan,
        isHeading: Boolean = false,
    ): String {
        val stringBuilder = StringBuilder()

        // Check if span is empty
        if (richSpan.isEmpty()) return ""

        // Check if span is blank
        val isBlank = richSpan.isBlank()

        // Convert span style to CSS string
        val markdownOpen = mutableListOf<String>()
        val markdownClose = mutableListOf<String>()

        // ---- 需要以 HTML 内联样式表达的 SpanStyle（markdown 原生语法承载不了的那些）----
        // markdown 只有二值粗体 `**`、斜体 `*`、删除线 `~~`、下划线 `<u>`，
        // 无法承载**字号**与**文字颜色**；非 700 字重（如 ExtraBold 800）同理。
        // 这些统一合并进**同一个** `<span style="...">`：
        //  - 合并而非嵌套多层 span：解码侧 onHtmlTag 每遇到一个标签就建一个 RichSpan，
        //    嵌套会产生多余的空 RichSpan，合并可避免；
        //  - 放在 markdownOpen/markdownClose 的**最外层**（先 open、最后 close）：
        //    保证 `**text**` 这类标记仍紧贴文字，不被 HTML 标签隔断而失效。
        //  - 单位：CssEncoder.parseCssSize 只认 px|pt|em|rem|%（**不认 sp**），
        //    故按库内 CssDecoder.decodeTextUnitToCss 的既有约定把 sp 数值写成 px（1:1）。
        val cssDeclarations = mutableListOf<String>()

        // Bold is based off fontWeight. Skip the ** markers inside headings since headings
        // already imply bold; emitting ** would produce `# **Title**` which round-trips back to
        // a double-bold span.
        // 字重分档：标准 Bold(700) 用 `**` 兼容通用 markdown；非 700 字重（如 ExtraBold 800）
        // 走内联 CSS，使字重数值在 markdown 往返中保留。
        val fontWeight = richSpan.spanStyle.fontWeight
        val isStandardBold = fontWeight?.weight == 700
        if (!isHeading && fontWeight != null && fontWeight.weight > 400 && !isStandardBold) {
            cssDeclarations += "font-weight:${fontWeight.weight}"
        }

        if (richSpan.spanStyle.fontSize.isSpecified) {
            cssDeclarations += "font-size:${richSpan.spanStyle.fontSize.value}px"
        }

        if (richSpan.spanStyle.color.isSpecified) {
            cssDeclarations += "color:${encodeColorToCssHex(richSpan.spanStyle.color)}"
        }

        if (cssDeclarations.isNotEmpty()) {
            markdownOpen += """<span style="${cssDeclarations.joinToString(";")}">"""
            markdownClose += "</span>"
        }

        if (!isHeading && isStandardBold) {
            markdownOpen += "**"
            markdownClose += "**"
        }

        if (richSpan.spanStyle.fontStyle == FontStyle.Italic) {
            markdownOpen += "*"
            markdownClose += "*"
        }

        if (richSpan.spanStyle.textDecoration?.contains(TextDecoration.LineThrough) == true) {
            markdownOpen += "~~"
            markdownClose += "~~"
        }

        if (richSpan.spanStyle.textDecoration?.contains(TextDecoration.Underline) == true) {
            markdownOpen += "<u>"
            markdownClose += "</u>"
        }

        // Append markdown open
        if (!isBlank && markdownOpen.isNotEmpty())
            stringBuilder.append(markdownOpen.joinToString(separator = ""))

        // Apply rich span style to markdown
        val spanMarkdown = decodeMarkdownElementFromRichSpan(richSpan.text, richSpan.richSpanStyle)

        // Append text
        stringBuilder.append(spanMarkdown)

        // Append children
        richSpan.children.fastForEach { child ->
            stringBuilder.append(decodeRichSpanToMarkdown(child, isHeading = isHeading))
        }

        // Append markdown close
        if (!isBlank && markdownClose.isNotEmpty())
            stringBuilder.append(markdownClose.reversed().joinToString(separator = ""))

        return stringBuilder.toString()
    }

    /**
     * Compose [Color] → CSS 十六进制色值（"#RRGGBB"），供内联 `style="color:..."` 使用。
     *
     * **为什么用 hex 而不是 `rgba(...)`**：hex 不含括号与逗号，嵌在 markdown 内联 HTML
     * 的 style 属性里不会被 `CssEncoder.parseCssStyle`（它按 `;`、`:` 切分）误解析；
     * 且 `CssEncoder.parseCssColor` 原生支持 6 位 hex，可无损往返。
     *
     * **为什么不用 [Color.toArgb]**：该 API 在 KMP common 源码里不可靠，而 red/green/blue
     * 分量（0..1 Float）是全平台通用的，换算更稳。
     *
     * **取舍**：hex 只承载 RGB，不承载 alpha。正文字色均为不透明色（面板产出的也是
     * "#RRGGBB"），故丢弃 alpha 无实际影响。
     */
    private fun encodeColorToCssHex(color: Color): String {
        val hexDigits = "0123456789ABCDEF"

        /** 单个分量：0..1 Float → 两位大写 hex（越界先夹取，避免极端值产生负数索引）。 */
        fun component(value: Float): String {
            val v = (value * 255).roundToInt().coerceIn(0, 255)
            return "${hexDigits[v shr 4]}${hexDigits[v and 0xF]}"
        }

        return "#${component(color.red)}${component(color.green)}${component(color.blue)}"
    }

    private fun StringBuilder.appendParagraphStartText(paragraph: RichParagraph) {
        when (val type = paragraph.type) {
            is OrderedList ->
                append("  ".repeat(type.level - 1) + "${type.number}. ")

            is UnorderedList ->
                append("  ".repeat(type.level - 1) + "- ")

            else ->
                Unit
        }
    }

    /**
     * Encodes Markdown elements to [SpanStyle].
     * Some Markdown elements have both an associated SpanStyle and ParagraphStyle.
     * Ensure both the [SpanStyle] (via [markdownElementsSpanStyleEncodeMap] - if applicable) and
     * [androidx.compose.ui.text.ParagraphStyle] (via [markdownElementsParagraphStyleEncodeMap] - if applicable)
     * are applied to the text.
     * @see <a href="https://www.w3schools.com/html/html_formatting.asp">HTML formatting</a>
     */
    private val markdownElementsSpanStyleEncodeMap = mapOf(
        MarkdownElementTypes.STRONG to BoldSpanStyle,
        MarkdownElementTypes.EMPH to ItalicSpanStyle,
        GFMElementTypes.STRIKETHROUGH to StrikethroughSpanStyle,
        MarkdownElementTypes.ATX_1 to H1SpanStyle,
        MarkdownElementTypes.ATX_2 to H2SpanStyle,
        MarkdownElementTypes.ATX_3 to H3SpanStyle,
        MarkdownElementTypes.ATX_4 to H4SpanStyle,
        MarkdownElementTypes.ATX_5 to H5SpanStyle,
        MarkdownElementTypes.ATX_6 to H6SpanStyle,
    )

    /**
     * Encodes the Markdown elements to [androidx.compose.ui.text.ParagraphStyle].
     * Some Markdown elements have both an associated SpanStyle and ParagraphStyle.
     * Ensure both the [SpanStyle] (via [markdownElementsSpanStyleEncodeMap] - if applicable) and
     * [androidx.compose.ui.text.ParagraphStyle] (via [markdownElementsParagraphStyleEncodeMap] if applicable)
     * are applied to the text.
     * @see <a href="https://github.com/chrisalley/markdown-garden/blob/master/source/guides/headers/atx-headers.md">ATX Header formatting</a>
     */
    private val markdownElementsParagraphStyleEncodeMap = mapOf(
        MarkdownElementTypes.ATX_1 to H1ParagraphStyle,
        MarkdownElementTypes.ATX_2 to H2ParagraphStyle,
        MarkdownElementTypes.ATX_3 to H3ParagraphStyle,
        MarkdownElementTypes.ATX_4 to H4ParagraphStyle,
        MarkdownElementTypes.ATX_5 to H5ParagraphStyle,
        MarkdownElementTypes.ATX_6 to H6ParagraphStyle,
    )

    /**
     * Encodes Markdown elements to [RichSpanStyle].
     */
    @OptIn(ExperimentalRichTextApi::class)
    private fun encodeMarkdownElementToRichSpanStyle(
        node: ASTNode,
        markdown: String,
    ): RichSpanStyle {
        val isImage = node.parent?.type == MarkdownElementTypes.IMAGE

        return when (node.type) {
            GFMTokenTypes.GFM_AUTOLINK -> {
                val destination = node.getTextInNode(markdown).toString()
                RichSpanStyle.Link(url = destination)
            }

            MarkdownElementTypes.INLINE_LINK -> {
                val destination = node
                    .findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                    ?.getTextInNode(markdown)
                    ?.toString()
                    .orEmpty()

                val linkLabel = node
                    .findChildOfType(MarkdownElementTypes.LINK_TEXT)
                    ?.getTextInNode(markdown)
                    ?.toString()
                    ?.removeSurrounding("[", "]")
                    .orEmpty()

                val token = parseTokenDestination(destination, linkLabel)

                when {
                    token != null -> token
                    isImage ->
                        RichSpanStyle.Image(
                            model = destination,
                            width = 0.sp,
                            height = 0.sp,
                        )
                    else ->
                        RichSpanStyle.Link(url = destination)
                }
            }

            MarkdownElementTypes.CODE_SPAN ->
                RichSpanStyle.Code()

            else ->
                RichSpanStyle.Default
        }
    }

    /**
     * Encode [ParagraphType] from Markdown [ASTNode].
     */
    private fun encodeRichParagraphTypeFromMarkdownElement(
        node: ASTNode,
    ): ParagraphType {
        return when (node.type) {
            MarkdownElementTypes.UNORDERED_LIST -> UnorderedList()
            MarkdownElementTypes.ORDERED_LIST -> OrderedList(0)
            else -> DefaultParagraph()
        }
    }

    /**
     * Decodes Markdown elements from [RichSpan].
     */
    @OptIn(ExperimentalRichTextApi::class)
    private fun decodeMarkdownElementFromRichSpan(
        text: String,
        richSpanStyle: RichSpanStyle,
    ): String {
        return when (richSpanStyle) {
            is RichSpanStyle.Link -> "[$text](${richSpanStyle.url})"
            is RichSpanStyle.Code -> "`$text`"
            is RichSpanStyle.Token -> {
                // Pseudo-link syntax: [label](trigger:triggerId:id)
                val label = richSpanStyle.label.ifEmpty { text }
                "[$label]($TokenDestinationPrefix${richSpanStyle.triggerId}:${richSpanStyle.id})"
            }
            is RichSpanStyle.Image -> {
                // Standard Markdown image syntax `![alt](url)`. Only models
                // that are strings (URLs) round-trip to Markdown; other
                // painter models have no representable form and are
                // dropped. The raw `text` at this point is the inline-
                // content placeholder char and must not leak into the
                // output.
                val model = richSpanStyle.model
                if (model is String) {
                    val alt = richSpanStyle.contentDescription.orEmpty()
                    "![$alt]($model)"
                } else {
                    ""
                }
            }
            else -> text
        }
    }

    /**
     * Parses a link destination of the form `trigger:<triggerId>:<id>` into a [RichSpanStyle.Token].
     * Returns `null` if the destination doesn't match the token shape.
     */
    @OptIn(ExperimentalRichTextApi::class)
    private fun parseTokenDestination(
        destination: String,
        label: String,
    ): RichSpanStyle.Token? {
        if (!destination.startsWith(TokenDestinationPrefix)) return null
        val payload = destination.removePrefix(TokenDestinationPrefix)
        val separatorIndex = payload.indexOf(':')
        if (separatorIndex <= 0) return null
        val triggerId = payload.substring(0, separatorIndex)
        val id = payload.substring(separatorIndex + 1)
        if (triggerId.isEmpty() || id.isEmpty()) return null
        return RichSpanStyle.Token(
            triggerId = triggerId,
            id = id,
            label = label,
        )
    }

    private const val TokenDestinationPrefix = "trigger:"

    /**
     * Markdown block elements.
     *
     * @see <a href="https://www.w3schools.com/html/html_blocks.asp">HTML blocks</a>
     */
    private val markdownBlockElements = setOf(
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.ORDERED_LIST,
        MarkdownElementTypes.UNORDERED_LIST,
        MarkdownElementTypes.LIST_ITEM,
    )

}