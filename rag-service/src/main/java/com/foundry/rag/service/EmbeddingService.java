package com.foundry.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@Service
public class EmbeddingService {

    private static final int DIMENSIONS = 1536;

    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public EmbeddingService(@Value("${rag.openai.api-key}") String apiKey,
                             @Value("${rag.openai.embedding-model}") String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            return hashingFallback(text);
        }
        try {
            return callOpenAi(text);
        } catch (Exception e) {
            // 데모 안정성 우선: 외부 API 장애/키 오류 시에도 채팅 흐름이 끊기지 않도록 폴백
            return hashingFallback(text);
        }
    }

    public int dimensions() {
        return DIMENSIONS;
    }

    private float[] callOpenAi(String text) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(Map.of("model", model, "input", text));
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/embeddings"))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("OpenAI embeddings API error: " + res.statusCode() + " " + res.body());
        }
        JsonNode vec = mapper.readTree(res.body()).at("/data/0/embedding");
        float[] result = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) result[i] = (float) vec.get(i).asDouble();
        return result;
    }

    /**
     * OPENAI_API_KEY 미설정 시 데모가 끊기지 않도록 하는 결정론적 해싱(bag-of-words feature hashing) 임베딩.
     * 실제 의미 기반 임베딩이 아니라 어휘 중복도 기반 근사치이므로, 검색 품질을 위해서는 실제 키 설정을 권장한다.
     */
    private float[] hashingFallback(String text) {
        float[] vec = new float[DIMENSIONS];
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) continue;
            int h = token.hashCode();
            int bucket = Math.floorMod(h, DIMENSIONS);
            float sign = ((h >>> 31) & 1) == 0 ? 1f : -1f;
            vec[bucket] += sign;
        }
        double norm = 0;
        for (float v : vec) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm > 1e-9) {
            for (int i = 0; i < vec.length; i++) vec[i] = (float) (vec[i] / norm);
        }
        return vec;
    }
}
