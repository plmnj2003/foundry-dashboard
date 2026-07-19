package com.foundry.rag.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VectorSearchService {

    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddingService;
    private final int topK;

    public VectorSearchService(JdbcTemplate jdbc, EmbeddingService embeddingService,
                                @Value("${rag.search.top-k}") int topK) {
        this.jdbc = jdbc;
        this.embeddingService = embeddingService;
        this.topK = topK;
    }

    public List<SearchResult> search(String query) {
        String literal = DocumentIngestionService.toVectorLiteral(embeddingService.embed(query));
        return jdbc.query(
            "SELECT dc.document_id, dm.filename, dc.chunk_index, dc.content, " +
            "       1 - (dc.embedding <=> ?::vector) AS similarity " +
            "FROM document_chunks dc JOIN document_meta dm ON dc.document_id = dm.id " +
            "WHERE dm.status = 'COMPLETED' " +
            "ORDER BY dc.embedding <=> ?::vector LIMIT ?",
            (rs, rowNum) -> new SearchResult(
                rs.getInt("document_id"),
                rs.getString("filename"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getDouble("similarity")),
            literal, literal, topK);
    }
}
