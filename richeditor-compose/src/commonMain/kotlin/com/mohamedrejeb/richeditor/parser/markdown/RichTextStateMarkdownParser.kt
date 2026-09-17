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
import com.mohamedrejeb.richeditor.paragraph.type.TaskList
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

        /**
         * 纯文本整段缩进解码（v2026-09-07）：当前 PARAGRAPH 对应的起始段落 index
         * （onOpenNode(PARAGRAPH) 时记录、onCloseNode 时消费并复位 -1）。
         * EM 前缀只出现在源段落首行行首，故只需处理起始段落。
         */
        var plainIndentParagraphStartIndex = -1

        /**
         * 任务列表前缀解码（v2026-09-15）：当前 LIST_ITEM 对应的段落 index
         * （onOpenNode(LIST_ITEM) 命中 `[ ] `/`[x] ` 前缀时记录，
         * onCloseNode(LIST_ITEM) 时把该前缀从正文中剥除并复位 -1）。
         */
        var taskListParagraphStartIndex = -1

        /**
         * 任务列表**续行**剥除（v2026-09-16 行级渲染）：续行 LIST_ITEM 对应的段落
         * （onOpenNode 命中续行分支时记录，onCloseNode 时把该行前缀从**该行首 span**
         * 中剥除并复位 null）。与 [taskListParagraphStartIndex] 互斥：首行走 index
         * 路径（首个文本 span），续行走本路径（段落 children 的最后一个 span）。
         */
        var taskListContinuationParagraph: RichParagraph? = null

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
            /**
             * v2026-09-15 方案 D（配套）：**保留软换行 `\n`**，不再替换成空格。
             *
             * 原实现 `text.replace('\n', ' ')` 会把段内换行抹成空格 —— 于是"保存 → 重进"
             * 后段内 `\n` 消失、行尾失去 offset 归属，选区手柄拖到行尾又会跳到下一行
             * （App 实测复现）。段落分隔由 EOL 处理（只有空行才新起段落），
             * 因此这里保留 `\n` 不会改变段落数。
             */
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

                /**
                 * 纯文本整段缩进解码（v2026-09-07）：记录 PARAGRAPH 对应的起始段落 index，
                 * 供 PARAGRAPH 关闭时剥段首 EM 编码前缀并还原 [DefaultParagraph.level]
                 * （见 [PLAIN_INDENT_CHAR] 与 onCloseNode 的 PARAGRAPH 分支）。
                 */
                if (node.type == MarkdownElementTypes.PARAGRAPH) {
                    plainIndentParagraphStartIndex = richParagraphList.lastIndex
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

                        /**
                         * 任务列表项判定（v2026-09-15）：解析器（intellij-markdown 的 GFM
                         * flavour）**不产出 task-list 节点**，`- [ ] a` 只会被解析成普通
                         * 无序列表项，故只能按**源码前缀**识别（与 App 侧原有的 checkbox
                         * 文本前缀约定一致）。命中时把段落类型换成 [TaskList]——勾选态存入
                         * 类型，前缀文本随后在 LIST_ITEM 关闭时从正文中剥除。
                         *
                         * v2026-09-16 行级渲染：**续行合并**——上一段落已是**同层级**
                         * [TaskList] 且未被分段隔开（EOL 分段的 mergeTaskLine 保证任务行
                         * 之间不分段）时，本行是同一任务列表段落的下一行：不改段类型
                         * （避免覆盖行 0 的勾选态），把本行勾选态按**行号**（段文本已累计
                         * 的 `\n` 数）写入 [TaskList.checkedLines]，前缀随后在 LIST_ITEM
                         * 关闭时从该行首 span 中剥除（[stripTaskListLinePrefix]）。
                         */
                        val taskListChecked = parseTaskListCheckboxState(
                            markdown = correctedMarkdown,
                            listItemNode = node,
                        )
                        if (taskListChecked != null) {
                            val lastParagraph = richParagraphList.lastOrNull()
                            val lastType = lastParagraph?.type as? TaskList
                            if (
                                lastType != null &&
                                lastType.level == sourceIndentLevel &&
                                currentRichParagraph === lastParagraph
                            ) {
                                val lineIndex = countParagraphNewlines(lastParagraph)
                                lastType.setLineChecked(line = lineIndex, checked = taskListChecked)
                                currentRichParagraphType = lastType
                                taskListContinuationParagraph = lastParagraph
                            } else {
                                currentRichParagraphType = TaskList(
                                    initialLevel = sourceIndentLevel,
                                    checked = taskListChecked,
                                )
                                taskListParagraphStartIndex = richParagraphList.lastIndex
                            }
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

                    /**
                     * 任务列表前缀剥除（v2026-09-15）：勾选态已由 [TaskList] 段落类型
                     * 承载，`[ ] `/`[x] ` 这段源码前缀不能留在正文文本里。
                     * v2026-09-16 行级渲染：续行行（合并进单段落）的前缀落在该行首
                     * span（段落 children 最后一个 span，PARAGRAPH open 创建、前缀
                     * tokens 累积其中），由 [stripTaskListLinePrefix] 剥除。
                     */
                    if (taskListParagraphStartIndex >= 0) {
                        richParagraphList.getOrNull(taskListParagraphStartIndex)?.let { paragraph ->
                            if (paragraph.type is TaskList) {
                                stripTaskListPrefix(paragraph)
                            }
                        }
                        taskListParagraphStartIndex = -1
                    } else if (taskListContinuationParagraph != null) {
                        stripTaskListLinePrefix(taskListContinuationParagraph!!)
                        taskListContinuationParagraph = null
                    }
                }

                /**
                 * 纯文本整段缩进解码（v2026-09-07）：PARAGRAPH 关闭时把段首 EM 编码前缀
                 * （[PLAIN_INDENT_CHAR]，每级 [PLAIN_INDENT_STEP] 个，由编码端
                 * [appendParagraphStartText] 输出）从首个文本 span 中剥除，并还原为
                 * [DefaultParagraph.level] 缩进属性（整段左移）。
                 *
                 * - 仅处理 [DefaultParagraph] 段落（列表/标题等有自己的编码体系，互斥）；
                 * - EM 前缀是连续源文本前缀，必落在首个非空文本 span 开头，单 span 剥除安全；
                 * - 剥后 span 变空则从树上移除（纯缩进空段落）；level=1 时无前缀、无操作。
                 */
                if (node.type == MarkdownElementTypes.PARAGRAPH && plainIndentParagraphStartIndex >= 0) {
                    val paragraph = richParagraphList.getOrNull(plainIndentParagraphStartIndex)
                    plainIndentParagraphStartIndex = -1
                    if (paragraph != null && paragraph.type is DefaultParagraph) {
                        val firstTextSpan = paragraph.findFirstTextSpan()
                        val leadingChars = firstTextSpan?.text?.takeWhile { it == PLAIN_INDENT_CHAR }?.length ?: 0
                        if (firstTextSpan != null && leadingChars >= PLAIN_INDENT_STEP) {
                            firstTextSpan.text = firstTextSpan.text.substring(leadingChars)
                            if (firstTextSpan.text.isEmpty()) {
                                // 纯缩进空段落：剥空前缀后 span 为空，从树上移除保持结构干净
                                val parent = firstTextSpan.parent
                                if (parent != null)
                                    parent.children.remove(firstTextSpan)
                                else
                                    paragraph.children.remove(firstTextSpan)
                            }
                            val level = leadingChars / PLAIN_INDENT_STEP + 1
                            (paragraph.type as DefaultParagraph).level = level
                        }
                    }
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

                    /**
                     * v2026-09-15 方案 D（配套）：区分**软换行**与**段落分隔**。
                     *
                     * 原实现是"上一段非空就新起段落" → markdown 里**每一行都变成独立段落**，
                     * 于是"保存 → 重进"后块内从「单段落 + 段内 `\n`」退化成「多段落」，
                     * 段内 `\n` 丢失、行尾失去 offset 归属 → 手柄拖到行尾又跳到下一行（实测复现）。
                     *
                     * 判定：本 EOL 之后（跳过行内空白）**紧跟另一个换行** ⇒ 空行 ⇒ 真段落分隔；
                     * 否则是单 `\n` 的软换行，留在同一段落内。
                     */
                    val isParagraphBreak = run {
                        var i = node.endOffset
                        while (
                            i < correctedMarkdown.length &&
                            correctedMarkdown[i] != '\n' &&
                            correctedMarkdown[i].isWhitespace()
                        ) {
                            i++
                        }
                        i < correctedMarkdown.length && correctedMarkdown[i] == '\n'
                    }

                    /**
                     * v2026-09-16 TaskList 行级渲染（配套）：**列表项行**的分段规则。
                     *
                     * 方案 D 的软换行规则曾把「连续列表项」也并进同一段落（`- a\n- b`
                     * 重载后只剩行 0 有 bullet）——本处恢复列表项分段，但**任务列表行
                     * 例外**：连续同级任务行合并为单段落（行级勾选态的承载结构，
                     * 见 [TaskList] 与 LI open 的续行分支）。
                     *
                     * 判定（对 EOL 之后的下一行做**行首**匹配，取行内文本锚定 `^`）：
                     * - 普通列表项行（`- `/`1. ` 开头）⇒ 分段（恢复逐项段落）；
                     * - 任务行（`- [ ] `/`- [x] `）且当前段落是**同层级 TaskList** ⇒ 续行
                     *   （`\n` 写进段内，勾选态由 LI open 的续行分支按行回填）；
                     * - 其余 ⇒ 维持方案 D 的软换行语义。
                     */
                    val nextLineListInfo: Pair<Boolean, Int>? = run {
                        var i = node.endOffset
                        var spaces = 0
                        while (
                            i < correctedMarkdown.length &&
                            (correctedMarkdown[i] == ' ' || correctedMarkdown[i] == '\t')
                        ) {
                            i++
                            spaces++
                        }
                        if (i >= correctedMarkdown.length || correctedMarkdown[i] == '\n') {
                            null
                        } else {
                            val lineEnd = correctedMarkdown.indexOf('\n', i)
                                .let { if (it < 0) correctedMarkdown.length else it }
                            val nextLineText = correctedMarkdown.substring(i, lineEnd)
                            val isTaskLine = TaskListItemSourceRegex.containsMatchIn(nextLineText)
                            val isListItem = isTaskLine ||
                                ListItemMarkerSourceRegex.containsMatchIn(nextLineText)
                            if (isListItem) Pair(isTaskLine, spaces / 2 + 1) else null
                        }
                    }
                    val lastParagraphType = lastParagraph?.type
                    val mergeTaskLine =
                        nextLineListInfo != null &&
                            nextLineListInfo.first &&
                            lastParagraphType is TaskList &&
                            nextLineListInfo.second == lastParagraphType.level

                    val hasContentToBreak =
                        lastParagraph?.isNotEmpty() == true ||
                            beforeLastParagraph?.isNotEmpty() == true ||
                            lastBrParagraphIndex == richParagraphList.lastIndex ||
                            beforeLastBrParagraphIndex == richParagraphList.lastIndex - 1

                    if (isParagraphBreak && hasContentToBreak) {
                        richParagraphList.add(RichParagraph())
                    } else if (!isParagraphBreak) {
                        if (nextLineListInfo != null && !mergeTaskLine && hasContentToBreak) {
                            /** 普通列表项行：分段（不写 `\n`），恢复逐项段落 */
                            richParagraphList.add(RichParagraph())
                        } else {
                            /**
                             * 软换行：`\n` 作为**段内文本**写入当前段落（不分段）——
                             * 含任务行续行（行级合并的行分隔符）。
                             *
                             * ⚠️ 2026-09-15 定稿结论：曾实验「软换行也分段」（方案 D'，段间
                             * 占位空格 / ZWSP / 真实 `\n` / 不写 四种占位都试过）——分段虽给
                             * 段落级操作（复选框/列表）行粒度，但手柄拖拽的行尾归属无解
                             * （占位空格/ZWSP 跳行、`\n` 空行、无字符重合），且段间 `\n` 会与
                             * 段落边界叠加产生空行。**定稿：普通段落块保持单段落 + 段内 `\n`**，
                             * 行粒度由 TaskList 行级渲染承载（v2026-09-16）。
                             */
                            onText("\n")

                            /**
                             * v2026-09-16「仅光标行转换」：软换行的下一行**不是列表项**
                             * （lazy continuation 普通行）且当前段落是 TaskList ⇒ 该行
                             * 非任务行——把 [TaskList.taskLines] 从 null（全任务）显式化
                             * 为 `{0 until 该行行号}`（此前各行都是任务行）。显式化后
                             * 后续任务行由 LI open 的续行分支逐行加回集合。
                             */
                            if (nextLineListInfo == null && lastParagraphType is TaskList) {
                                lastParagraphType.excludeTaskLine(
                                    countParagraphNewlines(lastParagraph)
                                )
                            }
                        }
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

            /**
             * v2026-09-16 行级渲染：TaskList 多行段落按行输出前缀——行 0 的前缀由
             * [appendParagraphStartText] 输出，children 文本里的每个 `\n`（段内软换行）
             * 之后插入该行各自的前缀（`- [ ] 行1\n- [x] 行2`），使解码端能把连续任务行
             * 合并回单段落并逐行还原勾选态（见 encode 的续行分支）。
             *
             * ⚠️ 已知限制：跨行样式（如 `**` 包住两行）会被插入的前缀截断——任务列表
             * 段落内的软换行行各自独立渲染勾选框，跨行样式在行级语义下本就不成立，
             * 往返后样式按行拆分（内容不丢）。
             */
            val childrenChunkStart = builder.length
            richParagraph.children.fastForEach { richSpan ->
                builder.append(decodeRichSpanToMarkdown(richSpan, isHeading = isHeading))
            }
            if (richParagraph.type is TaskList) {
                builder.applyTaskListLinePrefixes(
                    chunkStart = childrenChunkStart,
                    type = richParagraph.type as TaskList,
                )
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

    /**
     * 是否为列表类段落（v2026-09-15：任务列表与有序/无序列表同族）。
     *
     * 用途：markdown 编码时判断「非列表段落 → 列表段落」的边界，需要按 CommonMark
     * 规则补一个空行——否则紧跟在非空段落下的 `-` 会被解析成 setext H2 下划线，
     * 段落被吃掉（见 #441）。
     */
    private fun ParagraphType.isList(): Boolean =
        this is OrderedList || this is UnorderedList || this is TaskList

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

    /**
     * 纯文本整段缩进（v2026-09-07）的 markdown 编码载体：全角空格 U+2003（EM SPACE），
     * 每级 [PLAIN_INDENT_STEP] 个，见 [appendParagraphStartText] 与 PARAGRAPH 解码钩子。
     * 选 U+2003 是因为它是普通文本字符——CommonMark 不把它当缩进空格（不会因
     * ≥4 字符落入缩进代码块），可无损往返。
     */
    private const val PLAIN_INDENT_CHAR = '\u2003'

    /** 纯文本缩进步长：每个缩进层级对应的段首 EM SPACE 个数（≈ 两字符宽） */
    private const val PLAIN_INDENT_STEP = 2

    /**
     * 深度优先找段落树中第一个非空文本 span（EM 编码前缀是连续源文本前缀，
     * 必落在该 span 开头）。全空树返回 null。
     */
    private fun RichSpan.findFirstTextSpanRecursive(): RichSpan? {
        if (text.isNotEmpty()) return this
        children.fastForEach { child ->
            child.findFirstTextSpanRecursive()?.let { return it }
        }
        return null
    }

    private fun RichParagraph.findFirstTextSpan(): RichSpan? =
        children.firstNotNullOfOrNull { it.findFirstTextSpanRecursive() }

    private fun StringBuilder.appendParagraphStartText(paragraph: RichParagraph) {
        when (val type = paragraph.type) {
            is OrderedList ->
                append("  ".repeat(type.level - 1) + "${type.number}. ")

            is UnorderedList ->
                append("  ".repeat(type.level - 1) + "- ")

            /**
             * 任务列表（v2026-09-15）：输出 GFM 任务列表前缀，勾选态随前缀往返
             * （`- [x] ` = 已勾选 / `- [ ] ` = 未勾选）。层级仍按每级 2 空格编码，
             * 与列表一致；解码端由 `[ ] `/`[x] ` 前缀识别为 [TaskList]。
             *
             * v2026-09-16「仅光标行转换」：行 0 不是任务行（[TaskList.taskLines]
             * 显式集合不含 0）时**不输出前缀**（裸行）——解码端把裸行还原为
             * Default 段落开头、任务行段落从首个前缀行开始（视觉等价）。
             */
            is TaskList ->
                if (type.isTaskLine(0))
                    append(
                        "  ".repeat(type.level - 1) +
                            if (type.checked) "- [x] " else "- [ ] "
                    )

            /**
             * 纯文本整段缩进（v2026-09-07）：level>1 时输出段首 EM 前缀（每级 2 个），
             * 供解码端（[encode] 的 PARAGRAPH 关闭钩子）还原 [DefaultParagraph.level]。
             * level=1（无缩进）不输出前缀，与旧行为完全一致。
             */
            is DefaultParagraph ->
                if (type.level > 1)
                    append(PLAIN_INDENT_CHAR.toString().repeat(PLAIN_INDENT_STEP * (type.level - 1)))

            else ->
                Unit
        }
    }

    /**
     * 把 children 输出 chunk（[chunkStart] 起）里的段内 `\n` 后面插入**各行前缀**
     * （v2026-09-16 行级渲染）。仅对**任务行**（[TaskList.isTaskLine]）插入：
     * 行 k ≥ 1 的前缀 = 层级缩进 + `- [x] ` / `- [ ] `（行 0 前缀由
     * [appendParagraphStartText] 按 `isTaskLine(0)` 输出，chunk 内不再重复）；
     * 非任务行输出裸行（GFM lazy continuation 形态，解码端还原为普通行）。
     *
     * 例：段落 "行1\n行2"（行 1 已勾选）→ chunk "行1\n行2" →
     * `- [ ] 行1\n- [x] 行2`（chunk 前已输出 `- [ ] `）。
     * 尾随 `\n`（段末空行）：该行是任务行时输出空任务项前缀，非任务行时裸行。
     */
    private fun StringBuilder.applyTaskListLinePrefixes(chunkStart: Int, type: TaskList) {
        val chunk = substring(chunkStart, length)
        if (!chunk.contains('\n')) return

        val lines = chunk.split('\n')
        delete(chunkStart, length)
        append(lines[0])
        for (line in 1 until lines.size) {
            append('\n')
            if (type.isTaskLine(line)) {
                append("  ".repeat(type.level - 1))
                append(if (type.isCheckedLine(line)) "- [x] " else "- [ ] ")
            }
            append(lines[line])
        }
    }

    /**
     * 从 LIST_ITEM 源码判定 GFM 任务列表勾选态（v2026-09-15）。
     *
     * intellij-markdown 的 GFM flavour **不产出 task-list 节点**，`- [ ] a` 只会被
     * 解析成普通无序列表项，因此只能按**源文本前缀**识别（与 App 侧原有的 checkbox
     * 前缀约定一致，故存量数据天然兼容）。
     *
     * @param markdown 当前用于解析的源文本。
     * @param listItemNode LIST_ITEM 节点。
     * @return true = `[x]`（已勾选）；false = `[ ]`（未勾选）；null = 不是任务列表项。
     */
    private fun parseTaskListCheckboxState(
        markdown: String,
        listItemNode: ASTNode,
    ): Boolean? {
        val itemText = listItemNode.getTextInNode(markdown).toString()
        val stateChar = TaskListItemSourceRegex.find(itemText)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        return stateChar.equals("x", ignoreCase = true)
    }

    /**
     * 剥除任务列表项的 `[ ] `/`[x] ` 正文前缀（v2026-09-15）。
     *
     * 勾选态已由 [TaskList] 段落类型承载，这段源码前缀不能留在正文里。剥除方式与
     * [PLAIN_INDENT_CHAR] 前缀同款：前缀是连续源文本前缀，必落在首个非空文本 span
     * 开头，单 span 剥除安全；剥空则从树上移除（保持结构干净）。
     */
    private fun stripTaskListPrefix(paragraph: RichParagraph) {
        val firstTextSpan = paragraph.findFirstTextSpan() ?: return
        val match = TaskListContentPrefixRegex.find(firstTextSpan.text) ?: return

        firstTextSpan.text = firstTextSpan.text.substring(match.range.last + 1)

        if (firstTextSpan.text.isBlank()) {
            /**
             * 空任务项（v2026-09-16）：children 补一个 NBSP 占位——children 无实宽
             * 字符时（全空，或剩余全是普通空格）行宽为 0，光标定位退化为行盒左缘
             * （跑到勾选框左侧，真机实测）。⚠️ 判定必须用 isBlank 而非 isEmpty：
             * markdown 里的 NBSP 会被 intellij-markdown 归类为 WHITE_SPACE token、
             * 经 `onText(" ")` 归一成**普通空格**，行尾普通空格在文本布局中塌缩为
             * 零宽——必须换成 NBSP（有宽度、不可见、`Char.isWhitespace == false`
             * 故 `trim()` 不剥；App 字数统计/序列化已兼容）。
             */
            firstTextSpan.text = "\u00A0"
        }
    }

    /**
     * 数段落的已累计 `\n` 数（v2026-09-16 行级渲染，续行行号判定用）：
     * 深度优先遍历 children 文本（marker 是零宽 ZWSP 无 `\n`，不计）。
     * 段文本 = 各 span 文本的深度优先拼接（与 [com.mohamedrejeb.richeditor.model.RichTextState]
     * `computeTextFromTree` 的拼接口径一致）。
     */
    private fun countParagraphNewlines(paragraph: RichParagraph): Int {
        var count = 0

        fun walk(span: RichSpan) {
            count += span.text.count { it == '\n' }
            span.children.fastForEach { walk(it) }
        }

        paragraph.children.fastForEach { walk(it) }
        return count
    }

    /**
     * 剥除任务列表**续行**的 `[ ] `/`[x] ` 前缀（v2026-09-16 行级渲染）。
     *
     * 与 [stripTaskListPrefix]（行 0，首个文本 span）的差异：续行合并进单段落后，
     * 该行前缀落在**该行首 span**——EOL 软换行把 `currentRichSpan` 置 null 后，
     * PARAGRAPH open 创建的新 span 承接本行全部前缀 tokens，即段落 children 的
     * **最后一个** span（行内后续样式 span 挂到它下面，不会排到它后面）。
     * 剥空则从树上移除（空任务行保持结构干净）。
     */
    private fun stripTaskListLinePrefix(paragraph: RichParagraph) {
        val lineSpan = paragraph.children.lastOrNull() ?: return
        val match = TaskListContentPrefixRegex.find(lineSpan.text) ?: return

        lineSpan.text = lineSpan.text.substring(match.range.last + 1)

        if (lineSpan.text.isEmpty()) {
            /** 空任务行：同 [stripTaskListPrefix]，children 补 NBSP 撑光标位置 */
            lineSpan.text = "\u00A0"
        }
    }

    /**
     * 任务列表项**源码**前缀：行首（可选列表 marker）后的 `[ ] ` / `[x] ` / `[X] `，
     * 捕获组 1 为勾选字符。
     */
    private val TaskListItemSourceRegex = Regex("""^[ \t]*(?:[-*+][ \t]+)?\[([ xX])\][ \t]+""")

    /**
     * 任务列表项**正文**前缀：列表 marker 由段落 startText 机制承载、不进 children
     * 文本，但这里仍多兼容一层 marker 形式，避免异常数据下残留字面量。
     */
    private val TaskListContentPrefixRegex = Regex("""^[ \t]*(?:[-*+][ \t]+)?\[[ xX]\][ \t]?""")

    /**
     * 行首**普通列表项** marker（v2026-09-16 行级渲染，EOL 分段判定用）：
     * 无序 `[-*+]` 或有序 `\d{1,9}[.)]`，后接空白。任务行由
     * [TaskListItemSourceRegex] 先行判定（它包含 marker + `[ ]` 前缀的完整形态），
     * 命中任务行时本 regex 不参与（任务行按行级合并/新建任务段落处理）。
     */
    private val ListItemMarkerSourceRegex = Regex("""^[ \t]*(?:[-*+][ \t]|\d{1,9}[.)][ \t])""")

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