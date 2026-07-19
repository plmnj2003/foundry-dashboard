package com.foundry.rag.controller;

import com.foundry.rag.service.DocumentIngestionService;
import com.foundry.rag.service.SearchResult;
import com.foundry.rag.service.VectorSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final VectorSearchService searchService;
    private final JdbcTemplate jdbc;

    public DocumentController(DocumentIngestionService ingestionService,
                               VectorSearchService searchService,
                               JdbcTemplate jdbc) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.jdbc = jdbc;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "빈 파일입니다."));
        }
        try (InputStream in = file.getInputStream()) {
            int documentId = ingestionService.ingest(file.getOriginalFilename(), file.getContentType(), file.getSize(), in);
            return ResponseEntity.ok(Map.of(
                "documentId", documentId,
                "filename", file.getOriginalFilename(),
                "status", "COMPLETED"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
            "SELECT id, filename, content_type, file_size, chunk_count, status, uploaded_at " +
            "FROM document_meta ORDER BY id DESC");
    }

    @PostMapping("/search")
    public List<SearchResult> search(@RequestBody Map<String, Object> body) {
        String query = String.valueOf(body.getOrDefault("query", "")).trim();
        if (query.isBlank()) return List.of();
        return searchService.search(query);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        // document_chunks.document_id는 ON DELETE CASCADE라 청크는 자동으로 함께 삭제된다.
        int affected = jdbc.update("DELETE FROM document_meta WHERE id = ?", id);
        return affected > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
