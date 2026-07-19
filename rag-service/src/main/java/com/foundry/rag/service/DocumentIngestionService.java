package com.foundry.rag.service;

import org.apache.tika.Tika;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class DocumentIngestionService {

    private final JdbcTemplate jdbc;
    private final TextChunker chunker;
    private final EmbeddingService embeddingService;
    private final Tika tika = new Tika();

    public DocumentIngestionService(JdbcTemplate jdbc, TextChunker chunker, EmbeddingService embeddingService) {
        this.jdbc = jdbc;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
    }

    public int ingest(String filename, String contentType, long fileSize, InputStream content) {
        String text;
        try {
            text = tika.parseToString(content);
        } catch (Exception e) {
            throw new RuntimeException("문서 파싱 실패: " + e.getMessage(), e);
        }
        return process(filename, contentType, fileSize, text);
    }

    public int ingestPlainText(String filename, String text) {
        return process(filename, "text/plain", text.getBytes(StandardCharsets.UTF_8).length, text);
    }

    private int process(String filename, String contentType, long fileSize, String text) {
        Integer documentId = jdbc.queryForObject(
            "INSERT INTO document_meta (filename, content_type, file_size, status) VALUES (?, ?, ?, 'PROCESSING') RETURNING id",
            Integer.class, filename, contentType, fileSize);

        try {
            List<String> chunks = chunker.chunk(text);
            int index = 0;
            for (String chunk : chunks) {
                float[] embedding = embeddingService.embed(chunk);
                jdbc.update(
                    "INSERT INTO document_chunks (document_id, chunk_index, content, embedding) VALUES (?, ?, ?, ?::vector)",
                    documentId, index, chunk, toVectorLiteral(embedding));
                index++;
            }
            jdbc.update("UPDATE document_meta SET chunk_count = ?, status = 'COMPLETED' WHERE id = ?",
                chunks.size(), documentId);
            return documentId;
        } catch (Exception e) {
            jdbc.update("UPDATE document_meta SET status = 'FAILED' WHERE id = ?", documentId);
            throw new RuntimeException("문서 처리 실패: " + e.getMessage(), e);
        }
    }

    static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
