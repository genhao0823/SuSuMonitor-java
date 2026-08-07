#!/usr/bin/env bash
# SuSuMonitor 数据库异地备份脚本（本机 Git Bash 运行）
#
# 流程：
#   1) 同步仓库的 backup.sh 到云端并执行（mysqldump 一致性全量 + server.env 密钥）
#   2) scp 拉回本机
#   3) openssl AES-256-CBC（pbkdf2）加密落盘
#   4) 保留 N 份轮换
#
# 用法：
#   ENCRYPT_PASS='你的加密口令' bash remote-backup.sh [--keep 7]
# 环境变量覆盖：REMOTE / SSH_KEY / REMOTE_BACKUP_DIR / LOCAL_DIR / KEEP / ENCRYPT_PASS
#
# 恢复：export ENCRYPT_PASS=你的口令 && \
#   openssl enc -d -aes-256-cbc -pbkdf2 -pass pass:"$ENCRYPT_PASS" \
#     -in susumonitor-xxx.tar.gz.enc -out backup.tar.gz && tar -xzf backup.tar.gz
set -euo pipefail

REMOTE="${REMOTE:-root@82.156.245.102}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/susumonitor_cloud_ed25519}"
SSH_OPTS=(-i "$SSH_KEY" -o ConnectTimeout=20 -o StrictHostKeyChecking=no)
REMOTE_DIR="${REMOTE_BACKUP_DIR:-/var/backups/susumonitor}"
REMOTE_SCRIPT="${REMOTE_SCRIPT:-/opt/susumonitor/server/deploy/backup.sh}"
LOCAL_DIR="${LOCAL_DIR:-D:/SuSuMonitor-Backups}"
KEEP="${KEEP:-7}"
SCRIPT_SRC="$(cd "$(dirname "$0")/.." && pwd)/server-java-SuMon/deploy/backup.sh"

KEEP_ARG=1
while [ $# -gt 0 ]; do
    case "$1" in
        --keep) KEEP="$2"; shift 2 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

if [ -z "${ENCRYPT_PASS:-}" ]; then
    echo "FATAL: 请设置 ENCRYPT_PASS 环境变量（备份加密口令，恢复时需同口令）" >&2
    exit 1
fi
if [ ! -f "$SCRIPT_SRC" ]; then
    echo "FATAL: 未找到 backup.sh: $SCRIPT_SRC" >&2
    exit 1
fi

mkdir -p "$LOCAL_DIR"
TS=$(date +%Y%m%d-%H%M%S)
RAW="$LOCAL_DIR/.raw-${TS}.tar.gz"
ENC="$LOCAL_DIR/susumonitor-${TS}.tar.gz.enc"
trap 'rm -f "$RAW"' EXIT

echo "[1/4] 同步 backup.sh 到云端并执行（云端保留 1 份中间产物）..."
scp -q "${SSH_OPTS[@]}" "$SCRIPT_SRC" "$REMOTE:/tmp/backup.sh"
ssh "${SSH_OPTS[@]}" "$REMOTE" "sudo bash /tmp/backup.sh --dir '$REMOTE_DIR' --keep 1" 2>&1 | tail -3
ssh "${SSH_OPTS[@]}" "$REMOTE" "rm -f /tmp/backup.sh"

echo "[2/4] 拉取最新备份..."
LATEST=$(ssh "${SSH_OPTS[@]}" "$REMOTE" "ls -1t '$REMOTE_DIR'/susumonitor-*.tar.gz 2>/dev/null | head -1")
if [ -z "$LATEST" ]; then
    echo "FATAL: 云端未生成备份文件" >&2
    exit 1
fi
scp -q "${SSH_OPTS[@]}" "$REMOTE:$LATEST" "$RAW"

echo "[3/4] AES-256 加密..."
# 口令经 -pass pass: 显式传递，避免 env: 在部分 shell 上下文下解析失败导致等待 stdin
openssl enc -aes-256-cbc -salt -pbkdf2 -iter 100000 \
    -pass pass:"$ENCRYPT_PASS" -in "$RAW" -out "$ENC"
sha256sum "$ENC" > "$ENC.sha256"
rm -f "$RAW"

echo "[4/4] 校验并轮换（保留 $KEEP 份）..."
sha256sum -c "$ENC.sha256" >/dev/null
ls -1t "$LOCAL_DIR"/susumonitor-*.tar.gz.enc 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f
ls -1t "$LOCAL_DIR"/susumonitor-*.tar.gz.enc.sha256 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f

echo "OK: $ENC ($(du -h "$ENC" | cut -f1))"
echo "提示: 加密口令 ENCRYPT_PASS 请妥善保管（与备份分开存放）"
