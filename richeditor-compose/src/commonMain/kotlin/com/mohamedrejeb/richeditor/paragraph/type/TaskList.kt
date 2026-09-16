package com.mohamedrejeb.richeditor.paragraph.type

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.DefaultListIndent
import com.mohamedrejeb.richeditor.model.DefaultTaskListCheckBoxCornerRadius
import com.mohamedrejeb.richeditor.model.DefaultTaskListCheckBoxGap
import com.mohamedrejeb.richeditor.model.DefaultTaskListCheckBoxSize
import com.mohamedrejeb.richeditor.model.DefaultTaskListCheckBoxStrokeWidth
import com.mohamedrejeb.richeditor.model.DefaultTaskListCheckedColor
import com.mohamedrejeb.richeditor.model.DefaultTaskListCheckmarkColor
import com.mohamedrejeb.richeditor.model.DefaultTaskListCheckmarkStrokeWidth
import com.mohamedrejeb.richeditor.model.DefaultTaskListUncheckedColor
import com.mohamedrejeb.richeditor.model.RichSpan
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextConfig
import com.mohamedrejeb.richeditor.paragraph.RichParagraph

/**
 * 任务列表（复选框）段落类型（v2026-09-15 新增）。
 *
 * **为什么需要它**：App 的「块内换行」要求每一行都有自己的复选框标识——而复选框
 * 原先是 App 的**块级属性**（整块左侧一个图标），块内多行只能共用一个勾选框。
 * 把复选框下沉为**库的段落类型**后，回车时库走 `RichParagraph.slice()` 自动继承
 * 段落类型并把 `startText` 写回文本（与列表 bullet 完全同机制），块内每行因此都能
 * 独立渲染勾选框与勾选态。
 *
 * **构成**（与 [UnorderedList] 同构）：
 * - `startRichSpan`：marker 载体，文本为**不可见占位字符**（NBSP，只用于占位与
 *   定位），其 `richSpanStyle` 是 [RichSpanStyle.CheckBox]——勾选框由绘制层按 marker
 *   的排版位置画出来，编辑态与只读态共用同一套绘制代码；
 * - `ParagraphStyle`：`TextIndent(firstLine = restLine = base + 预留宽)`，把正文整体
 *   推到「勾选框 + 间距」右侧，左侧预留区交给勾选框绘制；
 * - [checked]：行 0（首行）勾选态；[checkedLines]：行号 ≥ 1 的行级勾选态；
 *   [taskLines]：任务行集合（null = 全部行都是任务行，支持「仅光标行转换」的
 *   行级混合段落——非任务行渲染无勾选框、markdown 编码为裸行）。
 *
 * **行级渲染（v2026-09-16）**：段落保持**单段落 + 段内 `\n`**（方案 D 定稿——
 * 实验矩阵证明"多段落行粒度"与"手柄行尾归属"在库架构下互斥），`RichSpanStyle.CheckBox`
 * 按段落文本的 `\n` 分行、每行行首绘制勾选框，勾选形态由
 * [checked]（行 0）/ [checkedLines]（行 ≥ 1）决定；命中翻转走
 * [com.mohamedrejeb.richeditor.model.RichTextState.toggleTaskListCheckedAtTextOffset]
 * 的行级判定。行结构变化（增删 `\n`）由 [reconcileCheckedLines] 对账重置。
 *
 * **markdown 往返**：编码端
 * [com.mohamedrejeb.richeditor.parser.markdown.RichTextStateMarkdownParser] 的
 * `appendParagraphStartText` 输出行 0 前缀 `- [ ] ` / `- [x] `（含层级缩进前缀），
 * 多行段落的行 ≥ 1 由编码端在段内每个 `\n` 后插入各自前缀（`- [ ] 行1\n- [x] 行2`）；
 * 解码端把连续任务行合并回单段落并逐行还原勾选态。存量单行数据格式不变，天然兼容。
 *
 * **层级**：实现 [ConfigurableListLevel]，层级缩进沿用无序列表的 `indent` 配置
 * （[RichTextConfig.unorderedListIndent]），与列表行为对称。
 */
@OptIn(ExperimentalRichTextApi::class)
internal class TaskList private constructor(
    initialIndent: Int = DefaultListIndent,
    startTextWidth: TextUnit = 0.sp,
    initialLevel: Int = 1,
    initialChecked: Boolean = false,
    initialCheckedLines: Map<Int, Boolean> = emptyMap(),
    initialTaskLines: Set<Int>? = null,
    initialLastKnownLineCount: Int = -1,
    private val checkBoxSize: TextUnit = DefaultTaskListCheckBoxSize,
    private val checkBoxGap: TextUnit = DefaultTaskListCheckBoxGap,
    private val checkBoxCornerRadius: TextUnit = DefaultTaskListCheckBoxCornerRadius,
    private val checkBoxStrokeWidth: TextUnit = DefaultTaskListCheckBoxStrokeWidth,
    private val checkmarkStrokeWidth: TextUnit = DefaultTaskListCheckmarkStrokeWidth,
    private val checkedColor: Color = DefaultTaskListCheckedColor,
    private val uncheckedColor: Color = DefaultTaskListUncheckedColor,
    private val checkmarkColor: Color = DefaultTaskListCheckmarkColor,
) : ParagraphType, ConfigurableStartTextWidth, ConfigurableListLevel {

    constructor(
        initialLevel: Int = 1,
        checked: Boolean = false,
    ) : this(
        initialIndent = DefaultListIndent,
        initialLevel = initialLevel,
        initialChecked = checked,
    )

    constructor(
        config: RichTextConfig,
        initialLevel: Int = 1,
        checked: Boolean = false,
    ) : this(
        initialIndent = config.unorderedListIndent,
        initialLevel = initialLevel,
        initialChecked = checked,
    )

    override var startTextWidth: TextUnit = startTextWidth
        set(value) {
            field = value
            style = getNewParagraphStyle()
        }

    private var indent = initialIndent
        set(value) {
            field = value
            style = getNewParagraphStyle()
        }

    override var level = initialLevel
        set(value) {
            field = value
            style = getNewParagraphStyle()
            /** 层级只影响缩进，marker 文本不变；重建仅为与 [UnorderedList] 保持同构 */
            startRichSpan = getNewStartRichSpan()
        }

    /**
     * 段落级勾选态（v2026-09-15）——**行 0（首行）**的勾选状态。
     *
     * 变更时必须重建 `startRichSpan`——勾选外观由
     * [RichSpanStyle.CheckBox] 对象承载，而该对象的相等性包含 [checked]
     * （见其 `equals`），只有换新对象绘制层才会刷新。
     */
    var checked: Boolean = initialChecked
        set(value) {
            field = value
            startRichSpan = getNewStartRichSpan()
        }

    /**
     * **行级勾选态**（v2026-09-16 TaskList 行级渲染改造）：行号 → 勾选状态，
     * **只承载行号 ≥ 1 的行**（行 0 一律由 [checked] 承载，避免双源不同步）；
     * map 中不存在的行 = 未勾选。
     *
     * 段落保持**单段落 + 段内 `\n`**（方案 D 定稿），每个逻辑行由渲染层按
     * `\n` 分行绘制勾选框（[RichSpanStyle.CheckBox]），本 map 提供各行状态。
     *
     * ⚠️ **行号会随编辑失效**：增删 `\n` 会使行号平移，运行时由
     * [reconcileCheckedLines] 在 `updateAnnotatedString` 里检测行数变化并**重置**
     * （行 0 的 [checked] 保留）。重置比平移保守——宁可丢状态也不显示错位状态。
     *
     * 变更时同样重建 `startRichSpan`（CheckBox 的相等性包含本 map）。
     */
    var checkedLines: Map<Int, Boolean> = initialCheckedLines
        set(value) {
            field = value
            startRichSpan = getNewStartRichSpan()
        }

    /**
     * **行级任务行集合**（v2026-09-16「仅光标行转换」）：哪些行是任务行（有勾选框）。
     *
     * - `null` = **全部行都是任务行**（段落级任务列表，旧行为，向后兼容——纯任务
     *   块、markdown 解码的全前缀段落都用 null）；
     * - 非 null = 只有集合内的行是任务行，其余行是**普通文本行**（无勾选框、
     *   命中放行、markdown 编码为裸行——与 GFM 的 lazy continuation 语义对齐）。
     *
     * 典型来源：App 复选框按钮的「仅光标行转换」——多行普通块里只把光标行变任务行，
     * 段落类型整体切为 [TaskList]（**不拆段**，方案 D 的手柄/空行成果保留），
     * 其余行靠本集合排除。
     *
     * ⚠️ 行 0 恒为段落类型的来源（markdown 首行前缀决定段落类型），但**可以不
     * 在集合内**（行 0 是普通行、后面某行是任务行）——此时 markdown 编码行 0 为
     * 裸行，解码端会把它还原为 Default 段落 + 任务行段落（视觉等价、结构漂移可接受）。
     *
     * ⚠️ 行结构变化（增删 `\n`）时由 [reconcileCheckedLines] 重置为 `null`
     * （退回段落级全任务语义——保守恢复，框不丢）。
     *
     * 变更时同样重建 `startRichSpan`（CheckBox 的相等性包含本集合）。
     */
    var taskLines: Set<Int>? = initialTaskLines
        set(value) {
            field = value
            startRichSpan = getNewStartRichSpan()
        }

    /**
     * 上次已知的行数（`'\n'` 数 + 1），-1 = 尚未初始化（首次
     * [reconcileCheckedLines] 直接采纳，用于解析构建期不误清）。
     * 纯簿记字段：不参与 equals/hashCode。
     */
    internal var lastKnownLineCount: Int = initialLastKnownLineCount

    /** 指定行是否为任务行（[taskLines] == null 视为全部行都是任务行）。 */
    fun isTaskLine(line: Int): Boolean =
        taskLines == null || line in taskLines!!

    /** [isTaskLine] 的运行时全集展开（[taskLines] == null → 0 until [lineCount]）。 */
    internal fun effectiveTaskLines(lineCount: Int): Set<Int> =
        taskLines ?: (0 until lineCount).toSet()

    /** 指定行的勾选状态（行 0 由 [checked] 承载；越界/缺省 = 未勾选）。 */
    fun isCheckedLine(line: Int): Boolean =
        if (line <= 0) checked else checkedLines[line] == true

    /**
     * 解析构建期写入行级状态（v2026-09-16 markdown 解码：连续任务行合并为
     * 单段落后逐行回填；同时把该行标记为任务行）。运行时翻转走
     * [withCheckedLines] / [withTaskLines]（新实例、可撤销），**不得**用本方法
     * （就地变更会破坏历史快照语义）。
     */
    internal fun setLineChecked(line: Int, checked: Boolean) {
        if (taskLines != null && line >= 0) {
            taskLines = taskLines!! + line
        }
        if (line <= 0) {
            this.checked = checked
        } else {
            checkedLines = checkedLines + (line to checked)
        }
        lastKnownLineCount = maxOf(lastKnownLineCount, line + 1)
    }

    /**
     * 解析构建期把指定行标记为**非任务行**（v2026-09-16 解码端 lazy
     * continuation 行）：[taskLines] 为 null（全任务）时显式化为
     * `{0 until line}`（此前各行都是任务行）；已是显式集合则该行天然不在内。
     */
    internal fun excludeTaskLine(line: Int) {
        if (taskLines == null && line > 0) {
            taskLines = (0 until line).toSet()
        }
    }

    /**
     * 渲染期行结构对账（v2026-09-16）：行数变化 ⇒ 行号全部平移 ⇒ 行级状态失义。
     *
     * - 首次（-1）＝解析构建后的第一帧：直接采纳当前行数，不清状态；
     * - 行数不变：无操作（勾选翻转不改变行数，状态保留 ✅）；
     * - 行数变化：清空 [checkedLines]、[taskLines] 重置为 `null`
     *   （退回**段落级全任务**语义——勾选丢、框不丢；行 0 的 [checked] 不受影响）。
     *   保守口径：宁可退化到旧行为，也不显示错位状态。
     */
    internal fun reconcileCheckedLines(lineCount: Int) {
        if (lastKnownLineCount == lineCount) return
        if (lastKnownLineCount == -1) {
            lastKnownLineCount = lineCount
            return
        }
        lastKnownLineCount = lineCount
        if (checkedLines.isNotEmpty()) checkedLines = emptyMap()
        if (taskLines != null) taskLines = null
    }

    private var style: ParagraphStyle =
        getNewParagraphStyle()

    override fun getStyle(config: RichTextConfig): ParagraphStyle {
        if (config.unorderedListIndent != indent) {
            indent = config.unorderedListIndent
        }

        return style
    }

    /**
     * 段落缩进样式：正文（含 marker）整体右移「层级缩进 + 勾选框预留宽」。
     *
     * 与 [UnorderedList] 的差异：列表 marker 是**可见文本**（`"• "`），自身就占位，
     * 故只需把 marker 摆进缩进 gutter；而勾选框是**绘制的图形**，必须在段首留出
     * 一块与文本无关的空白区，否则图形会压在正文上。因此这里 firstLine 与
     * restLine 同步右移（默认段落缩进语义），预留宽 = [checkBoxSize] + [checkBoxGap]，
     * 与 [RichSpanStyle.CheckBox] 的绘制约定严格一致（两处必须同步修改）。
     */
    private fun getNewParagraphStyle(): ParagraphStyle {
        val base = (indent * (level - 1)).toFloat()
        val reserved = checkBoxSize.value + checkBoxGap.value

        return ParagraphStyle(
            textIndent = TextIndent(
                firstLine = (base + reserved).sp,
                restLine = (base + reserved).sp,
            )
        )
    }

    override var startRichSpan: RichSpan =
        getNewStartRichSpan()

    /**
     * 生成段落 marker：文本是**不可见占位字符**（NBSP）。
     *
     * 为什么不用空串或零宽字符：marker 需要参与排版才能被测出位置——
     * [RichSpanStyle] 的 `drawCustomStyle` 依赖 `TextLayoutResult.getBoundingBoxes`
     * 拿到 marker 所在行的行盒（用于垂直居中与左缘定位），折叠 range / 零宽字符
     * 会拿不到有效 box。NBSP 有正常宽度且视觉不可见，同时与 App 侧空块占位符
     * （`EMPTY_BLOCK_PLACEHOLDER`）是同一字符，语义一致。
     */
    private fun getNewStartRichSpan(textRange: TextRange = TextRange(0)): RichSpan {
        val text = TaskListMarkerText

        val richSpan = RichSpan(
            paragraph = RichParagraph(type = this),
            text = text,
            textRange = TextRange(
                textRange.min,
                textRange.min + text.length
            )
        )
        richSpan.richSpanStyle = RichSpanStyle.CheckBox(
            checked = checked,
            checkedLines = checkedLines,
            taskLines = taskLines,
            boxSize = checkBoxSize,
            gap = checkBoxGap,
            cornerRadius = checkBoxCornerRadius,
            strokeWidth = checkBoxStrokeWidth,
            checkmarkStrokeWidth = checkmarkStrokeWidth,
            checkedColor = checkedColor,
            uncheckedColor = uncheckedColor,
            checkmarkColor = checkmarkColor,
        )
        return richSpan
    }

    /**
     * 回车续行的下一段落类型：**同层级、同外观、未勾选**（与 App 侧
     * 「回车新行 = 未勾选复选框项」的既有行为一致）。行级状态不带（新项空白起步）。
     */
    override fun getNextParagraphType(): ParagraphType =
        TaskList(
            initialIndent = indent,
            startTextWidth = startTextWidth,
            initialLevel = level,
            initialChecked = false,
            initialCheckedLines = emptyMap(),
            checkBoxSize = checkBoxSize,
            checkBoxGap = checkBoxGap,
            checkBoxCornerRadius = checkBoxCornerRadius,
            checkBoxStrokeWidth = checkBoxStrokeWidth,
            checkmarkStrokeWidth = checkmarkStrokeWidth,
            checkedColor = checkedColor,
            uncheckedColor = uncheckedColor,
            checkmarkColor = checkmarkColor,
        )

    override fun copy(): TaskList =
        TaskList(
            initialIndent = indent,
            startTextWidth = startTextWidth,
            initialLevel = level,
            initialChecked = checked,
            initialCheckedLines = checkedLines,
            initialTaskLines = taskLines,
            initialLastKnownLineCount = lastKnownLineCount,
            checkBoxSize = checkBoxSize,
            checkBoxGap = checkBoxGap,
            checkBoxCornerRadius = checkBoxCornerRadius,
            checkBoxStrokeWidth = checkBoxStrokeWidth,
            checkmarkStrokeWidth = checkmarkStrokeWidth,
            checkedColor = checkedColor,
            uncheckedColor = uncheckedColor,
            checkmarkColor = checkmarkColor,
        )

    /**
     * 以新的勾选态派生**同配置**的新任务列表类型（保留层级、marker 宽度与勾选框外观）。
     *
     * 供 [com.mohamedrejeb.richeditor.model.RichTextState.setTaskListChecked] 就地换类型
     * 使用——主构造是 private，而勾选框外观参数（尺寸/配色）也需要随之透传，
     * 因此由本类内部派生而不是让调用方重新构造。
     */
    internal fun withChecked(checked: Boolean): TaskList =
        TaskList(
            initialIndent = indent,
            startTextWidth = startTextWidth,
            initialLevel = level,
            initialChecked = checked,
            initialCheckedLines = checkedLines,
            initialTaskLines = taskLines,
            initialLastKnownLineCount = lastKnownLineCount,
            checkBoxSize = checkBoxSize,
            checkBoxGap = checkBoxGap,
            checkBoxCornerRadius = checkBoxCornerRadius,
            checkBoxStrokeWidth = checkBoxStrokeWidth,
            checkmarkStrokeWidth = checkmarkStrokeWidth,
            checkedColor = checkedColor,
            uncheckedColor = uncheckedColor,
            checkmarkColor = checkmarkColor,
        )

    /**
     * 以新的**行级勾选态**派生同配置新类型（v2026-09-16 行级翻转入口）。
     *
     * 与 [withChecked] 同款设计：运行时翻转必须换新实例（历史快照走
     * [copy] 的深拷贝，就地变更会让 undo 恢复出错位状态）。行 0 的翻转
     * 继续走 [withChecked]。
     */
    internal fun withCheckedLines(checkedLines: Map<Int, Boolean>): TaskList =
        TaskList(
            initialIndent = indent,
            startTextWidth = startTextWidth,
            initialLevel = level,
            initialChecked = checked,
            initialCheckedLines = checkedLines,
            initialTaskLines = taskLines,
            initialLastKnownLineCount = lastKnownLineCount,
            checkBoxSize = checkBoxSize,
            checkBoxGap = checkBoxGap,
            checkBoxCornerRadius = checkBoxCornerRadius,
            checkBoxStrokeWidth = checkBoxStrokeWidth,
            checkmarkStrokeWidth = checkmarkStrokeWidth,
            checkedColor = checkedColor,
            uncheckedColor = uncheckedColor,
            checkmarkColor = checkmarkColor,
        )

    /**
     * 以新的**任务行集合**派生同配置新类型（v2026-09-16「仅光标行转换」的
     * 行级 toggle 入口）。与 [withChecked] 同款换实例设计（undo 快照安全）。
     */
    internal fun withTaskLines(taskLines: Set<Int>?): TaskList =
        TaskList(
            initialIndent = indent,
            startTextWidth = startTextWidth,
            initialLevel = level,
            initialChecked = checked,
            initialCheckedLines = checkedLines,
            initialTaskLines = taskLines,
            initialLastKnownLineCount = lastKnownLineCount,
            checkBoxSize = checkBoxSize,
            checkBoxGap = checkBoxGap,
            checkBoxCornerRadius = checkBoxCornerRadius,
            checkBoxStrokeWidth = checkBoxStrokeWidth,
            checkmarkStrokeWidth = checkmarkStrokeWidth,
            checkedColor = checkedColor,
            uncheckedColor = uncheckedColor,
            checkmarkColor = checkmarkColor,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TaskList) return false

        if (indent != other.indent) return false
        if (startTextWidth != other.startTextWidth) return false
        if (level != other.level) return false
        if (checked != other.checked) return false
        if (checkedLines != other.checkedLines) return false
        if (taskLines != other.taskLines) return false
        if (checkBoxSize != other.checkBoxSize) return false
        if (checkBoxGap != other.checkBoxGap) return false
        if (checkBoxCornerRadius != other.checkBoxCornerRadius) return false
        if (checkBoxStrokeWidth != other.checkBoxStrokeWidth) return false
        if (checkmarkStrokeWidth != other.checkmarkStrokeWidth) return false
        if (checkedColor != other.checkedColor) return false
        if (uncheckedColor != other.uncheckedColor) return false
        if (checkmarkColor != other.checkmarkColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = indent
        result = 31 * result + startTextWidth.hashCode()
        result = 31 * result + level
        result = 31 * result + checked.hashCode()
        result = 31 * result + checkedLines.hashCode()
        result = 31 * result + taskLines.hashCode()
        result = 31 * result + checkBoxSize.hashCode()
        result = 31 * result + checkBoxGap.hashCode()
        result = 31 * result + checkBoxCornerRadius.hashCode()
        result = 31 * result + checkBoxStrokeWidth.hashCode()
        result = 31 * result + checkmarkStrokeWidth.hashCode()
        result = 31 * result + checkedColor.hashCode()
        result = 31 * result + uncheckedColor.hashCode()
        result = 31 * result + checkmarkColor.hashCode()
        return result
    }

    internal companion object {
        /**
         * 段落 marker 的占位文本（NBSP，U+00A0）。
         *
         * 不可见 + 有正常字宽：既能被排版测出位置（供勾选框绘制定位），
         * 又不会在视觉上留下痕迹（其宽度并入勾选框与正文之间的间距）。
         */
        internal const val TaskListMarkerText: String = "\u00A0"
    }
}
