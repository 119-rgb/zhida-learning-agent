package com.zhida.agent.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void shouldSplitLongChineseTextWithOverlap() {
        String text = "第一段用于介绍背景。".repeat(40) + "\n" + "第二段用于说明结论。".repeat(40);

        List<String> chunks = chunker.split(text, 300, 60);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allMatch(chunk -> !chunk.isBlank() && chunk.length() <= 300);
    }

    @Test
    void shouldReturnEmptyListForBlankText() {
        assertThat(chunker.split("  \n", 300, 60)).isEmpty();
    }

    @Test
    void shouldRejectInvalidChunkSettings() {
        assertThatThrownBy(() -> chunker.split("有效文本", 100, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
