package com.foundry.dashboard.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 프론트엔드는 단일 오리진(/api)만 호출하도록 유지하고,
 * 문서 업로드/조회 요청은 내부적으로 rag-service(별도 모듈)로 그대로 전달한다.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentProxyController {

    private final RestClient ragClient;

    public DocumentProxyController(@Value("${rag.service.base-url}") String ragServiceBaseUrl) {
        this.ragClient = RestClient.builder().baseUrl(ragServiceBaseUrl).build();
    }

    @PostMapping("/upload")
    public ResponseEntity<Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(file.getBytes()) {
            @Override
            public String getFilename() {
                return file.getOriginalFilename();
            }
        });
        Object response = ragClient.post()
            .uri("/api/documents/upload")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .retrieve()
            .body(Object.class);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public Object list() {
        return ragClient.get().uri("/api/documents").retrieve().body(Object.class);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        ragClient.delete().uri("/api/documents/{id}", id).retrieve().toBodilessEntity();
        return ResponseEntity.noContent().build();
    }
}
