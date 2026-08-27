# SuSuMonitor Server 多阶段构建：Maven 构建 → JRE 运行（非 root）
# 遵循 docker-standards：官方镜像固定版本、多阶段、只拷贝产物、专用用户。

# 构建阶段
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
# 构建期专用 Maven 镜像（阿里云，仅本阶段使用，不进运行时镜像）。
COPY .docker/settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# 运行阶段
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S susumonitor && adduser -S -G susumonitor susumonitor
WORKDIR /app
# 通配符只匹配 fat jar（spring-boot 的 .jar.original 不满足 *.jar），解除 jar 名与版本的锁步。
COPY --from=builder /build/target/*.jar app.jar
USER susumonitor
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "app.jar"]
