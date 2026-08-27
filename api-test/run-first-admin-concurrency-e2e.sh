#!/usr/bin/env bash
# 首管理员空库并发真实验收编排器（Git Bash + MySQL CLI）。
#
# 用途：在一个独立空库 schema 中并发注册 N 个合法用户，并复核数据库持久化结果，证明：
#   1) 全部 N 个注册均成功（HTTP 200、业务 code=0）；
#   2) 恰好 1 个用户为 admin/approved；
#   3) 其余 N-1 个用户为 user/pending；
#   4) 管理员可登录且 /me 为 admin/approved，待审核用户登录返回 403（由 Node 验证器断言）；
#   5) 数据库 auth_bootstrap_state.admin_initialized=1，且 initialized_user_id 指向唯一已审核管理员。
#
# 为什么用 bash 而非 PowerShell 编排：
#   本机 PowerShell 5.1 + Set-StrictMode/错误策略与 Start-Process 启动长生命周期 Java、再在新进程
#   调用 Node 的交互存在偶发状态污染（变量被误报为 null/未设置、server 日志为空、schema 未迁移），
#   已反复实测复现。bash 直启 java + curl 探活 + node + mysql 复核在本环境稳定可复现。
#
# 环境变量（运行时提供，不落盘、不写入文档）：
#   SUSUMONITOR_VALIDATION_DB_ADMIN_USER     （默认 root）具备 CREATE/DROP DATABASE 权限的 MySQL 账号
#   SUSUMONITOR_VALIDATION_DB_ADMIN_PASSWORD
#   SUSUMONITOR_VALIDATION_CONCURRENCY       （2..20，默认 8）
#   SUSUMONITOR_VALIDATION_PREFIX            （注册用户名前缀，默认 first_admin_）
# 数据库口令只通过 MYSQL_PWD 环境变量传给 mysql，绝不写入脚本、日志、文档或代码。
#
# 退出码：0=通过；2=服务未就绪；3=验收断言失败；4=预检/权限失败。
set -u

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="$PROJECT_ROOT/server-java-SuMon"
JAR="$SERVER_DIR/target/server-java-SuMon-0.0.1-SNAPSHOT.jar"
VERIFIER="$PROJECT_ROOT/api-test/verify-first-admin-concurrency.mjs"

ADMIN_USER="${SUSUMONITOR_VALIDATION_DB_ADMIN_USER:-root}"
ADMIN_PASS="${SUSUMONITOR_VALIDATION_DB_ADMIN_PASSWORD:-}"
CONCURRENCY="${SUSUMONITOR_VALIDATION_CONCURRENCY:-8}"

# 校验并发数与凭据
case "$CONCURRENCY" in *[!0-9]*|'') echo "SUSUMONITOR_VALIDATION_CONCURRENCY must be an integer (2..20): got [$CONCURRENCY]"; exit 4;; esac
if [ "$CONCURRENCY" -lt 2 ] || [ "$CONCURRENCY" -gt 20 ]; then echo "SUSUMONITOR_VALIDATION_CONCURRENCY out of range 2..20: $CONCURRENCY"; exit 4; fi
if [ -z "$ADMIN_PASS" ]; then echo "Set SUSUMONITOR_VALIDATION_DB_ADMIN_PASSWORD to a local MySQL account allowed to CREATE/DROP the run-owned validation schema."; exit 4; fi
if [ ! -f "$JAR" ]; then echo "Server jar is missing; run mvn package first: $JAR"; exit 4; fi

RUNID="fac_$(date +%s%3N)_$(head -c6 /dev/urandom | od -An -tx1 | tr -d ' \n' | head -c6)"
DB="susumonitor_first_admin_${RUNID}"
PORT=18183
export MYSQL_PWD="$ADMIN_PASS"

echo "== schema=$DB port=$PORT concurrency=$CONCURRENCY =="
mysql -h 127.0.0.1 -P 3306 -u "$ADMIN_USER" -e "CREATE DATABASE \`$DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" || { echo "CREATE_DB_FAIL"; unset MYSQL_PWD; exit 4; }
mysql -h 127.0.0.1 -P 3306 -u "$ADMIN_USER" -e "GRANT ALL PRIVILEGES ON \`$DB\`.* TO 'susumonitor'@'127.0.0.1'; FLUSH PRIVILEGES;" || { echo "GRANT_FAIL"; unset MYSQL_PWD; exit 4; }

# 启动隔离 Java 服务（bash 直启，产生产物在临时日志，便于失败排查）
cd "$SERVER_DIR" || exit 4
DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME="$DB" DB_USER=susumonitor DB_PASSWORD="$ADMIN_PASS" \
SERVER_ADDRESS=127.0.0.1 SERVER_PORT=$PORT APP_ENV=test SPRING_PROFILES_ACTIVE=test \
JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY= \
AES_GCM_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY= OUTBOX_ENABLED=false \
java -jar "$JAR" > "/tmp/srv-$RUNID.log" 2>&1 &
JPID=$!
cd "$PROJECT_ROOT" || exit 4

cleanup() {
  kill "$JPID" 2>/dev/null || true
  wait "$JPID" 2>/dev/null || true
  export MYSQL_PWD="$ADMIN_PASS"
  if [ -n "${DB:-}" ]; then
    mysql -h 127.0.0.1 -P 3306 -u "$ADMIN_USER" -e "DROP DATABASE IF EXISTS \`$DB\`;" 2>/dev/null || true
    mysql -h 127.0.0.1 -P 3306 -u "$ADMIN_USER" -e "REVOKE ALL PRIVILEGES ON \`$DB\`.* FROM 'susumonitor'@'127.0.0.1';" 2>/dev/null || true
  fi
  unset MYSQL_PWD
}
trap cleanup EXIT

# 等服务就绪（health=200 意味着 Flyway 迁移已完成、服务已上线）
READY=0
for _ in $(seq 1 180); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/health" 2>/dev/null || true)"
  if [ "$code" = "200" ]; then READY=1; break; fi
  sleep 1
done
if [ "$READY" != "1" ]; then echo "SERVER_NOT_READY; log=/tmp/srv-$RUNID.log"; exit 2; fi
echo "SERVER_READY"

# 运行 Node 验证器（API 层：全部注册成功、恰好一个 admin/approved、其余 pending、管理员登录/me、待审核 403）
SUSUMONITOR_VALIDATION_CONFIRM=FIRST_ADMIN_CONCURRENCY \
SUSUMONITOR_VALIDATION_BASE_URL="http://127.0.0.1:$PORT" \
SUSUMONITOR_VALIDATION_CONCURRENCY="$CONCURRENCY" \
SUSUMONITOR_VALIDATION_PREFIX="${SUSUMONITOR_VALIDATION_PREFIX:-first_admin_}" \
node "$VERIFIER"
VERIFIER_EXIT=$?
echo "VERIFIER_EXIT=$VERIFIER_EXIT"

# 数据库持久化复核
MU=(mysql -h 127.0.0.1 -P 3306 -u root -N -B)
TOTAL=$("${MU[@]}" -e "SELECT COUNT(*) FROM \`$DB\`.users;")
ADMIN=$("${MU[@]}" -e "SELECT COUNT(*) FROM \`$DB\`.users WHERE role='admin' AND review_status='approved';")
PENDING=$("${MU[@]}" -e "SELECT COUNT(*) FROM \`$DB\`.users WHERE role='user' AND review_status='pending';")
INVALID=$("${MU[@]}" -e "SELECT COUNT(*) FROM \`$DB\`.users WHERE NOT (role='admin' AND review_status='approved') AND NOT (role='user' AND review_status='pending');")
BS_INIT=$("${MU[@]}" -e "SELECT admin_initialized FROM \`$DB\`.auth_bootstrap_state WHERE id=1;")
BS_CONSIST=$("${MU[@]}" -e "SELECT COUNT(*) FROM \`$DB\`.users u JOIN \`$DB\`.auth_bootstrap_state b ON b.initialized_user_id=u.id WHERE b.id=1 AND u.role='admin' AND u.review_status='approved';")
echo "DB: total=$TOTAL admin=$ADMIN pending=$PENDING invalid=$INVALID bootstrap_init=$BS_INIT bootstrap_consistent=$BS_CONSIST"

if [ "$VERIFIER_EXIT" = "0" ] && [ "$TOTAL" = "$CONCURRENCY" ] && [ "$ADMIN" = "1" ] \
   && [ "$PENDING" = "$((CONCURRENCY-1))" ] && [ "$INVALID" = "0" ] \
   && [ "$BS_INIT" = "1" ] && [ "$BS_CONSIST" = "1" ]; then
  echo "=== ACCEPTANCE PASS conc=$CONCURRENCY ==="
  exit 0
else
  echo "=== ACCEPTANCE FAIL conc=$CONCURRENCY ==="
  exit 3
fi
