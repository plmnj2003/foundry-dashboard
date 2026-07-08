package com.foundry.dashboard.controller;

import com.foundry.dashboard.service.AiChatService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final JdbcTemplate jdbc;
    private final AiChatService aiChatService;

    public DashboardController(JdbcTemplate jdbc, AiChatService aiChatService) {
        this.jdbc = jdbc;
        this.aiChatService = aiChatService;
    }

    @GetMapping("/kpis")
    public Map<String, Object> getKpis() {
        Double totalRevenue = jdbc.queryForObject(
            "SELECT COALESCE(SUM(total_amount),0) FROM sales_orders WHERE status != 'CANCELLED'",
            Double.class);

        Integer activeOrders = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales_orders WHERE status IN ('PENDING','IN_PRODUCTION','SHIPPED')",
            Integer.class);

        Double avgYield = jdbc.queryForObject(
            "SELECT COALESCE(AVG(yield_rate),0) FROM production_lots WHERE status='COMPLETED' AND yield_rate IS NOT NULL",
            Double.class);

        Integer activeLots = jdbc.queryForObject(
            "SELECT COUNT(*) FROM production_lots WHERE status IN ('QUEUED','IN_PROGRESS')",
            Integer.class);

        return Map.of(
            "totalRevenue", totalRevenue,
            "activeOrders", activeOrders,
            "avgYieldRate", Math.round(avgYield * 100.0) / 100.0,
            "activeLots", activeLots
        );
    }

    @GetMapping("/revenue-by-customer")
    public List<Map<String, Object>> getRevenueByCustomer() {
        return jdbc.queryForList(
            "SELECT c.name, c.tier, SUM(so.total_amount) AS total_revenue, COUNT(so.id) AS order_count " +
            "FROM sales_orders so " +
            "JOIN customers c ON so.customer_id = c.id " +
            "WHERE so.status != 'CANCELLED' " +
            "GROUP BY c.id, c.name, c.tier " +
            "ORDER BY total_revenue DESC");
    }

    @GetMapping("/revenue-trend")
    public List<Map<String, Object>> getRevenueTrend() {
        return jdbc.queryForList(
            "SELECT TO_CHAR(order_date,'YYYY-MM') AS month, SUM(total_amount) AS revenue " +
            "FROM sales_orders WHERE status != 'CANCELLED' " +
            "GROUP BY TO_CHAR(order_date,'YYYY-MM') ORDER BY month");
    }

    @GetMapping("/yield-by-product")
    public List<Map<String, Object>> getYieldByProduct() {
        return jdbc.queryForList(
            "SELECT p.name, p.technology_node, AVG(pl.yield_rate) AS avg_yield, COUNT(pl.id) AS lot_count " +
            "FROM production_lots pl " +
            "JOIN products p ON pl.product_id = p.id " +
            "WHERE pl.status = 'COMPLETED' AND pl.yield_rate IS NOT NULL " +
            "GROUP BY p.id, p.name, p.technology_node ORDER BY avg_yield DESC");
    }

    @GetMapping("/defects-by-type")
    public List<Map<String, Object>> getDefectsByType() {
        return jdbc.queryForList(
            "SELECT defect_type, severity, SUM(count) AS total_count " +
            "FROM defect_records GROUP BY defect_type, severity ORDER BY total_count DESC");
    }

    @GetMapping("/production-lots")
    public List<Map<String, Object>> getProductionLots() {
        return jdbc.queryForList(
            "SELECT pl.lot_number, p.name AS product, pl.quantity, pl.status, " +
            "pl.start_date, pl.end_date, pl.yield_rate " +
            "FROM production_lots pl JOIN products p ON pl.product_id = p.id " +
            "ORDER BY pl.id DESC LIMIT 20");
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> getOrders() {
        return jdbc.queryForList(
            "SELECT so.id, c.name AS customer, p.name AS product, " +
            "so.quantity, so.total_amount, so.order_date, so.status " +
            "FROM sales_orders so " +
            "JOIN customers c ON so.customer_id = c.id " +
            "JOIN products p ON so.product_id = p.id " +
            "ORDER BY so.order_date DESC LIMIT 20");
    }

    @GetMapping("/defect-trend")
    public List<Map<String, Object>> getDefectTrend() {
        return jdbc.queryForList(
            "SELECT TO_CHAR(detected_at,'YYYY-MM') AS month, severity, SUM(count) AS total " +
            "FROM defect_records GROUP BY TO_CHAR(detected_at,'YYYY-MM'), severity ORDER BY month");
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        if (question.isBlank()) return Map.of("answer", "질문을 입력해주세요.");
        String answer = aiChatService.answer(question);
        return Map.of("answer", answer);
    }
}
