package com.foundry.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class TextChunker {

    private final int targetChars;
    private final double overlapRatio;

    public TextChunker(@Value("${rag.chunk.target-chars}") int targetChars,
                        @Value("${rag.chunk.overlap-ratio}") double overlapRatio) {
        this.targetChars = targetChars;
        this.overlapRatio = overlapRatio;
    }

    /** 문단 단위로 묶어 목표 길이에 맞추고, 청크 경계마다 꼬리 부분을 다음 청크에 겹쳐 넣는다(overlap). */
    public List<String> chunk(String text) {
        List<String> paragraphs = Arrays.stream(text.split("\\n\\s*\\n"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            if (current.length() > 0 && current.length() + para.length() > targetChars) {
                chunks.add(current.toString().trim());
                current = new StringBuilder(tailOverlap(current.toString()));
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(para);
        }
        if (!current.isEmpty()) chunks.add(current.toString().trim());
        return chunks;
    }

    private String tailOverlap(String text) {
        int overlapLen = (int) (targetChars * overlapRatio);
        if (text.length() <= overlapLen) return text;
        return text.substring(text.length() - overlapLen);
    }
}
