package com.foundry.dashboard.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public AiChatService(JdbcTemplate jdbc, @Value("${anthropic.api-key}") String apiKey) {
        this.jdbc = jdbc;
        this.anthropic = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    public String answer(String question) {
        try {
            String sql = generateSql(question);
            validate(sql);
            String safeSql = enforceLimitClause(sql);
            List<Map<String, Object>> rows = jdbc.queryForList(safeSql);
            return summarize(question, safeSql, rows);
        } catch (IllegalArgumentException e) {
            return "이 쿼리는 실행할 수 없습니다: " + e.getMessage();
        } catch (Exception e) {
            return "오류: " + e.getMessage();
        }
    }

    private String generateSql(String question) {
        String prompt = SCHEMA + "\n다음 질문에 대한 PostgreSQL SELECT 쿼리만 반환하세요. 마크다운 없이 SQL만 반환:\n" + question;
        Message msg = anthropic.messages().create(
            MessageCreateParams.builder()
                .model(Model.CLAUDE_SONNET_4_6)
                .maxTokens(512L)
                .addUserMessage(prompt)
                .build());
        String raw = msg.content().stream()
            .flatMap(b -> b.text().stream())
            .map(t -> t.text().trim())
            .collect(Collectors.joining());
        // Strip markdown code fences
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
        return msg.content().stream()
            .flatMap(b -> b.text().stream())
            .map(t -> t.text().trim())
            .collect(Collectors.joining());
    }
}
