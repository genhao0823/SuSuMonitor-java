# SuSuMonitor Server 多阶段构建：Maven 构建 → JRE 运行（非 root）
# 遵循 docker-standards：官方镜像固定版本、多阶段、只拷贝产物、专用用户。

# 构建阶段
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S susumonitor && adduser -S -G susumonitor susumonitor
WORKDIR /app
COPY --from=builder /build/target/server-java-SuMon-0.0.1-SNAPSHOT.jar app.jar
USER susumonitor
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "app.jar"]
