# SuSuMonitor Agent 多阶段构建：Go 交叉编译 → alpine 运行（非 root）
# 遵循 docker-standards：官方镜像固定版本、CGO_ENABLED=0 静态编译、专用用户。

# 构建阶段
FROM golang:1.23-alpine AS builder
WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /out/susumonitor-agent ./cmd/susumonitor-agent

# 运行阶段
FROM alpine:3.20
RUN addgroup -S susumonitor && adduser -S -G susumonitor susumonitor \
    && mkdir -p /var/lib/susumonitor \
    && chown -R susumonitor:susumonitor /var/lib/susumonitor
COPY --from=builder /out/susumonitor-agent /usr/local/bin/susumonitor-agent
USER susumonitor
ENTRYPOINT ["susumonitor-agent"]
