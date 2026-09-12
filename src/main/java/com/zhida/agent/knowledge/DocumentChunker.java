package com.zhida.agent.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentChunker {

    public List<String> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (chunkSize < 100 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("切片参数不合法");
        }

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < normalized.length()) {
            int targetEnd = Math.min(start + chunkSize, normalized.length());
            int end = findNaturalBoundary(normalized, start, targetEnd);
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    private int findNaturalBoundary(String text, int start, int targetEnd) {
        if (targetEnd >= text.length()) {
            return text.length();
        }
        int minimum = Math.min(targetEnd, start + (int) ((targetEnd - start) * 0.65));
        for (int index = targetEnd; index > minimum; index--) {
            char previous = text.charAt(index - 1);
            if (previous == '\n' || previous == '。' || previous == '！' || previous == '？'
                    || previous == '.' || previous == '!' || previous == '?') {
                return index;
            }
        }
        return targetEnd;
    }
}
