package com.foundry.rag.config;

import com.foundry.rag.service.DocumentIngestionService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DemoRagSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final DocumentIngestionService ingestionService;

    public DemoRagSeeder(JdbcTemplate jdbc, DocumentIngestionService ingestionService) {
        this.jdbc = jdbc;
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(String... args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM document_meta", Integer.class);
        if (count != null && count > 0) return;

        // 실제 임베딩 파이프라인(EmbeddingService)을 그대로 태워서 시드하므로,
        // OPENAI_API_KEY 유무와 무관하게 쿼리 시점 임베딩과 항상 동일한 방식으로 생성된다.
        ingestionService.ingestPlainText("사내_출장_규정.txt", TRAVEL_POLICY);
        ingestionService.ingestPlainText("품질관리_매뉴얼.txt", QUALITY_MANUAL);
    }

    private static final String TRAVEL_POLICY = """
        제1조 (목적)
        이 규정은 임직원의 국내외 출장 시 여비 산정 기준과 절차를 정함을 목적으로 한다.

        제2조 (출장 신청)
        출장을 계획하는 임직원은 출장 개시 3영업일 전까지 전자결재 시스템을 통해 출장신청서를 제출해야 한다.
        긴급 출장의 경우 사후 승인으로 대체할 수 있으며, 출장 종료 후 3일 이내에 사유서를 첨부하여 제출한다.

        제3조 (교통비)
        국내 출장의 교통비는 실비 정산을 원칙으로 하며, KTX 특실 및 항공기 비즈니스석은 임원급 이상만 이용할 수 있다.
        해외 출장의 항공권은 8시간 이상 비행 시 비즈니스석을, 8시간 미만은 이코노미석을 기준으로 한다.

        제4조 (숙박비)
        국내 출장 숙박비는 1박 기준 15만원, 해외 출장은 지역별 상한액표(별표 1)를 따른다.
        상한액을 초과하는 경우 사전 승인을 받아야 하며, 초과분은 개인 부담을 원칙으로 한다.

        제5조 (일비 및 식비)
        국내 출장 일비는 1일 3만원, 해외 출장 일비는 1일 50달러를 지급한다.
        식비는 일비에 포함되며 별도로 청구할 수 없다.

        제6조 (정산)
        출장 종료 후 5영업일 이내에 법인카드 사용 내역과 영수증을 첨부하여 경비 정산을 완료해야 한다.
        기한 내 정산하지 않을 경우 다음 출장 신청이 제한될 수 있다.
        """;

    private static final String QUALITY_MANUAL = """
        1장. 품질관리 개요
        본 매뉴얼은 반도체 파운드리 생산 공정에서 발생하는 불량을 체계적으로 관리하기 위한 절차를 규정한다.

        2장. 불량 등급 분류
        불량은 심각도에 따라 CRITICAL, MAJOR, MINOR 세 등급으로 분류한다.
        CRITICAL 등급 불량이 발생한 Lot은 즉시 생산을 중단하고 품질팀에 통보해야 한다.
        MAJOR 등급은 24시간 이내 원인 분석 보고서를 제출해야 하며, MINOR 등급은 주간 리포트에 통합하여 보고한다.

        3장. 공정 단계별 관리 포인트
        리소그래피(Lithography) 공정에서는 오버레이 오차와 파티클 오염을 중점 관리한다.
        식각(Etch) 공정에서는 CD(Critical Dimension) 변동을 실시간 모니터링한다.
        이온 주입(Ion Implant) 공정에서는 도핑 균일도와 문턱전압 변화를 추적 관리한다.

        4장. 수율 관리 기준
        평균 수율이 95% 미만으로 3개 Lot 연속 발생 시 공정 엔지니어링팀의 정밀 진단을 의무화한다.
        수율 90% 미만 Lot은 스크랩 여부를 품질위원회에서 별도 심의한다.
        """;
}
