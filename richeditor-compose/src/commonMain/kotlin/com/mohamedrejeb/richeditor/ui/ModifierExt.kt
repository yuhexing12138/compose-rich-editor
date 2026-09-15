package com.mohamedrejeb.richeditor.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import androidx.compose.ui.util.fastForEach

@OptIn(ExperimentalRichTextApi::class)
internal fun Modifier.drawRichSpanStyle(
    richTextState: RichTextState,
    topPadding: Float = 0f,
    startPadding: Float = 0f,
): Modifier {
    return this
        .drawBehind {
            val styledRichSpanList = mutableListOf<Pair<RichSpanStyle, TextRange>>()

            richTextState.styledRichSpanList.fastForEach { richSpan ->
                val lastAddedItem = styledRichSpanList.lastOrNull()

                val end = richSpan.getLastNonEmptyChild()?.textRange?.end ?: richSpan.textRange.end

                if (
                    lastAddedItem != null &&
                    lastAddedItem.first::class == richSpan.richSpanStyle::class &&
                    lastAddedItem.second.end == richSpan.textRange.start
                )
                    styledRichSpanList[styledRichSpanList.lastIndex] =
                        lastAddedItem.first to TextRange(lastAddedItem.second.start, end)
                else
                    styledRichSpanList.add(richSpan.richSpanStyle to TextRange(richSpan.textRange.start, end))
            }

            /**
             * 段落 marker 的绘制调度（v2026-09-15 新增）。
             *
             * [richTextState.styledRichSpanList] 只收集**段落 children**，不含
             * `paragraph.type.startRichSpan`——在 `RichTextState.updateAnnotatedString`
             * 里 marker 文本是直接 `append(type.startText)` 进文本的（只带 SpanStyle，
             * 不走 richSpan 的收集回调）。因此挂在 marker 上的自定义样式
             * （当前用于 TaskList 段落的勾选框 [RichSpanStyle.CheckBox]）需要在这里
             * 单独补一遍，否则不会被 `drawCustomStyle` 调用到。
             *
             * 追加在 children 之后：marker 属于段首标识，视觉上应画在最上层。
             */
            richTextState.richParagraphList.fastForEach { paragraph ->
                val startRichSpan = paragraph.type.startRichSpan
                val markerStyle = startRichSpan.richSpanStyle
                if (markerStyle !is RichSpanStyle.Default) {
                    styledRichSpanList.add(markerStyle to startRichSpan.textRange)
                }
            }

            styledRichSpanList.fastForEach { (style, textRange) ->
                richTextState.textLayoutResult?.let { textLayoutResult ->
                    with(style) {
                        val textLength = richTextState.annotatedString.length
                        val measuredTextLength = textLayoutResult.multiParagraph.intrinsics.annotatedString.length
                        if (textLength == measuredTextLength) {
                            drawCustomStyle(
                                layoutResult = textLayoutResult,
                                textRange = textRange,
                                richTextConfig = richTextState.config,
                                topPadding = topPadding,
                                startPadding = startPadding
                            )
                        }
                    }
                }
            }
        }
}