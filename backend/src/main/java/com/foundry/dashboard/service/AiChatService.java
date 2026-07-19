package com.foundry.dashboard.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.foundry.dashboard.dto.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AiChatService {

    private static final Pattern DDL = Pattern.compile("(?i)\\b(CREATE|DROP|ALTER|TRUNCATE|RENAME)\\b");
    private static final Pattern DML = Pattern.compile("(?i)\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|REPLACE)\\b");
    private static final Pattern DANGEROUS = Pattern.compile("(?i)\\b(GRANT|REVOKE|EXECUTE|EXEC|CALL|COPY|LOAD)\\b");
    private static final Pattern SELECT_ONLY = Pattern.compile("(?i)^\\s*SELECT\\b");
    private static final Pattern LIMIT_RE = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)\\b");

    private static final String SCHEMA = """
        Database schema (semiconductor foundry):
        - customers(id, name, country, tier)
        - products(id, name, technology_node, wafer_size, unit_price)
        - sales_orders(id, customer_id, product_id, quantity, unit_price, total_amount, order_date, status)
        - production_lots(id, product_id, lot_number, quantity, start_date, end_date, status, yield_rate)
        - defect_records(id, lot_id, defect_type, process_step, count, severity, detected_at)
        """;

    private final JdbcTemplate jdbc;
    private final AnthropicClient anthropic;
    private final RestClient ragClient;

    public AiChatService(JdbcTemplate jdbc,
                          @Value("${anthropic.api-key}") String apiKey,
                          @Value("${rag.service.base-url}") String ragServiceBaseUrl) {
        this.jdbc = jdbc;
        this.anthropic = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.ragClient = RestClient.builder().baseUrl(ragServiceBaseUrl).build();
    }

    /** 기존 단순 채팅 엔드포인트(/api/chat) 하위 호환용. */
    public String answer(String question) {
        return answerStructured(question).answer();
    }

    public ChatResponse answerStructured(String question) {
        try {
            String intent = classifyIntent(question);
            return "DOCUMENT".equals(intent) ? answerFromDocuments(question) : answerFromSql(question);
        } catch (IllegalArgumentException e) {
            return new ChatResponse("이 쿼리는 실행할 수 없습니다: " + e.getMessage(), "sql", List.of(), 0.0);
        } catch (Exception e) {
            return new ChatResponse("오류: " + e.getMessage(), "error", List.of(), 0.0);
        }
    }

    private String classifyIntent(String question) {
        String prompt = "다음 질문을 분류하세요. 매출/생산/고객/불량 등 정형 데이터베이스 조회가 필요한 질문이면 SQL, "
            + "사내 규정·매뉴얼 같은 문서 내용을 찾아야 하는 질문이면 DOCUMENT 라고, 다른 말 없이 한 단어로만 답하세요.\n"
            + "질문: " + question;
        Message msg = anthropic.messages().create(
            MessageCreateParams.builder()
                .model(Model.CLAUDE_SONNET_4_6)
                .maxTokens(10L)
                .addUserMessage(prompt)
                .build());
        String raw = extractText(msg);
        return raw.toUpperCase(Locale.ROOT).contains("DOCUMENT") ? "DOCUMENT" : "SQL";
    }

    // ── SQL (NL2SQL) 경로 ────────────────────────────────────────────────

    private ChatResponse answerFromSql(String question) {
        String sql = generateSql(question);
        validate(sql);
        String safeSql = enforceLimitClause(sql);
        List<Map<String, Object>> rows = jdbc.queryForList(safeSql);
        String summary = summarize(question, safeSql, rows);
        return new ChatResponse(summary, "sql", List.of(), rows.isEmpty() ? 0.5 : 1.0);
    }

    private String generateSql(String question) {
        String prompt = SCHEMA + "\n다음 질문에 대한 PostgreSQL SELECT 쿼리만 반환하세요. 마크다운 없이 SQL만 반환:\n" + question;
        Message msg = anthropic.messages().create(
            MessageCreateParams.builder()
                .model(Model.CLAUDE_SONNET_4_6)
                .maxTokens(512L)
                .addUserMessage(prompt)
                .build());
        String raw = extractText(msg);
        return raw.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("(?s)```\\s*$", "").trim();
    }

    private void validate(String sql) {
        if (DDL.matcher(sql).find()) throw new IllegalArgumentException("DDL 문은 허용되지 않습니다.");
        if (DML.matcher(sql).find()) throw new IllegalArgumentException("DML 문은 허용되지 않습니다.");
        if (DANGEROUS.matcher(sql).find()) throw new IllegalArgumentException("위험한 명령은 허용되지 않습니다.");
        if (!SELECT_ONLY.matcher(sql).find()) throw new IllegalArgumentException("SELECT 쿼리만 허용됩니다.");
        if (sql.contains(";") && sql.indexOf(";") < sql.length() - 1)
            throw new IllegalArgumentException("복수 문장 쿼리는 허용되지 않습니다.");
    }

    private String enforceLimitClause(String sql) {
        var m = LIMIT_RE.matcher(sql);
        if (m.find()) {
            int limit = Integer.parseInt(m.group(1));
            return limit > 100 ? m.replaceFirst("LIMIT 100") : sql;
        }
        return sql.replaceAll(";?\\s*$", "") + " LIMIT 100";
    }

    private String summarize(String question, String sql, List<Map<String, Object>> rows) {
        String prompt = "질문: " + question + "\nSQL: " + sql
            + "\n결과 (최대 10건): " + rows.subList(0, Math.min(10, rows.size()))
            + "\n위 결과를 한국어로 간결하게 요약해주세요.";
        Message msg = anthropic.messages().create(
            MessageCreateParams.builder()
                .model(Model.CLAUDE_SONNET_4_6)
                .maxTokens(512L)
                .addUserMessage(prompt)
                .build());
        return extractText(msg);
    }

    // ── 문서 RAG 경로 ────────────────────────────────────────────────────

    private ChatResponse answerFromDocuments(String question) {
        List<Map<String, Object>> results = searchDocuments(question);
        if (results.isEmpty()) {
            return new ChatResponse("관련된 사내 문서를 찾지 못했습니다.", "document", List.of(), 0.0);
        }

        String context = results.stream()
            .map(r -> "[" + r.get("filename") + " #" + r.get("chunkIndex") + "]\n" + r.get("content"))
            .collect(Collectors.joining("\n\n---\n\n"));
        String prompt = "다음은 사내 문서에서 검색된 내용입니다. 이 내용만 근거로 질문에 답하고, "
            + "근거가 없는 내용은 추측하지 말고 모른다고 답하세요.\n\n" + context + "\n\n질문: " + question;
        Message msg = anthropic.messages().create(
            MessageCreateParams.builder()
                .model(Model.CLAUDE_SONNET_4_6)
                .maxTokens(768L)
                .addUserMessage(prompt)
                .build());

        List<ChatResponse.Source> sources = results.stream()
            .map(r -> new ChatResponse.Source(
                String.valueOf(r.get("filename")),
                ((Number) r.get("chunkIndex")).intValue(),
                Math.round(((Number) r.get("similarity")).doubleValue() * 1000) / 1000.0))
            .toList();
        double confidence = sources.stream().mapToDouble(ChatResponse.Source::similarity).max().orElse(0.0);

        return new ChatResponse(extractText(msg), "document", sources, confidence);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> searchDocuments(String question) {
        try {
            List<Map<String, Object>> results = ragClient.post()
                .uri("/api/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", question))
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            return results != null ? results : List.of();
        } catch (Exception e) {
            throw new RuntimeException("문서 검색 서비스 호출 실패: " + e.getMessage(), e);
        }
    }

    private String extractText(Message msg) {
        return msg.content().stream()
            .flatMap(b -> b.text().stream())
            .map(t -> t.text().trim())
            .collect(Collectors.joining());
    }
}
