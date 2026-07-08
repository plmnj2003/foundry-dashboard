# ── Stage 1: Vue 3 빌드 ──────────────────────────────────────
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build
# dist/ 폴더 생성됨

# ── Stage 2: Spring Boot 빌드 ────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS backend-build
WORKDIR /app

# 의존성 캐싱 (pom.xml 변경 없으면 재다운로드 안 함)
COPY backend/pom.xml .
RUN mvn dependency:go-offline -q

# Vue 빌드 결과물을 Spring Boot 정적 리소스로 복사
COPY --from=frontend-build /app/frontend/dist \
     src/main/resources/static/

# 소스 빌드
COPY backend/src ./src
RUN mvn package -DskipTests -q

# ── Stage 3: 실행 이미지 (경량) ──────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN useradd -r -s /bin/false appuser
COPY --from=backend-build /app/target/*.jar app.jar
RUN chown appuser:appuser app.jar

USER appuser
EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
