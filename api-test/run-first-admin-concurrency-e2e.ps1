[CmdletBinding()]
param()

# 本文件是本模块的 PowerShell 编排器。本机（Git Bash + Windows PowerShell 5.1）环境实测发现：
# Start-Process 启动长生命周期 Java、再跨进程调用 Node，存在偶发状态污染（变量被误报为 null/未设置、
# server 日志为空、schema 未迁移），导致 .ps1 在本环境不可稳定复现。已完成的真实验收统一改由
# run-first-admin-concurrency-e2e.sh（bash 编排器，稳定可复现）执行；本 .ps1 保留为业务逻辑等价的可选实现，
# 供在行为正常的 PowerShell Core / 非 Windows 5.1 环境使用。两套脚本共享 verify-first-admin-concurrency.mjs 验证器。

# 不使用 Set-StrictMode -Version Latest、也不把 $ErrorActionPreference 设为 Stop：PowerShell 5.1 下这两者与
# 健康探针的 WebException / 子进程调用交互，会把已正确赋值的变量误报为“未设置”或 null 的终止性错误（已实测复现），
# 并让错误行号错乱。改用 Continue + 显式 throw：所有关键失败点（schema 创建、Node 退出码、DB 断言）都已用
# $LASTEXITCODE 检查或 throw 显式抛出，足以保证真实错误仍能沿 finally 失败路径反映出来。
$ErrorActionPreference = 'Continue'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverDirectory = Join-Path $projectRoot 'server-java-SuMon'
$databasePassword = $env:DB_PASSWORD
$databaseAdminUser = $env:SUSUMONITOR_VALIDATION_DB_ADMIN_USER
$databaseAdminPassword = $env:SUSUMONITOR_VALIDATION_DB_ADMIN_PASSWORD
if ([string]::IsNullOrWhiteSpace($databasePassword)) { throw 'Set DB_PASSWORD before running this isolated E2E.' }
if ([string]::IsNullOrWhiteSpace($databaseAdminUser) -or [string]::IsNullOrWhiteSpace($databaseAdminPassword)) {
    throw 'Set SUSUMONITOR_VALIDATION_DB_ADMIN_USER and SUSUMONITOR_VALIDATION_DB_ADMIN_PASSWORD to a local MySQL account allowed to CREATE/DROP the run-owned validation schema.'
}
if (-not (Test-Path -LiteralPath (Join-Path $serverDirectory 'target\server-java-SuMon-0.0.1-SNAPSHOT.jar'))) {
    throw 'Server jar is missing; run mvn package first.'
}

$runId = "fac-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())-$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
$databaseName = "susumonitor_first_admin_$($runId.Replace('-', '_'))"
$port = 18183
$workspace = Join-Path $env:TEMP "susumonitor-first-admin-$runId"
$serverLog = Join-Path $workspace 'server.out.log'
$serverErrorLog = Join-Path $workspace 'server.err.log'
$keepArtifacts = $env:KEEP_E2E_ARTIFACTS -eq 'true'
$serverProcess = $null
$runSucceeded = $false
$originalMySqlPwd = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
$originalBaseUrl = $env:SUSUMONITOR_VALIDATION_BASE_URL
$originalConfirm = $env:SUSUMONITOR_VALIDATION_CONFIRM
$originalBootstrapToken = $env:SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN

# 批次 8：为空库实例预置一次性初始化令牌（32 字节随机 → base64url 43 字符，与服务器自动生成口径一致）。
# 同一值双路注入：服务器 env AUTH_BOOTSTRAP_TOKEN + Node 验证器 SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN，
# 缺任一路都会在注册时收到 403/40310。令牌只存在于本次运行的环境变量与服务器进程内存/密文库，不落盘。
$bootstrapTokenBytes = New-Object 'byte[]' 32
$bootstrapTokenRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bootstrapTokenRng.GetBytes($bootstrapTokenBytes)
$bootstrapTokenRng.Dispose()
$bootstrapToken = [Convert]::ToBase64String($bootstrapTokenBytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
$bootstrapTokenBytes.Clear()

# 捕获未预期异常的完整内容，供失败路径报告根因。

New-Item -ItemType Directory -Path $workspace -Force | Out-Null
try {
    $env:MYSQL_PWD = $databaseAdminPassword
    & mysql -h 127.0.0.1 -P 3306 -u $databaseAdminUser -e "CREATE DATABASE ``$databaseName`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create isolated first-admin schema.' }
    & mysql -h 127.0.0.1 -P 3306 -u $databaseAdminUser -e "GRANT ALL PRIVILEGES ON ``$databaseName``.* TO 'susumonitor'@'127.0.0.1'; FLUSH PRIVILEGES;"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to grant the application account access to the isolated schema.' }
    $env:MYSQL_PWD = $databasePassword

    $serverEnvironment = @{
        DB_HOST = '127.0.0.1'; DB_PORT = '3306'; DB_NAME = $databaseName; DB_USER = 'susumonitor'; DB_PASSWORD = $databasePassword
        SERVER_ADDRESS = '127.0.0.1'; SERVER_PORT = "$port"; APP_ENV = 'test'; SPRING_PROFILES_ACTIVE = 'test'
        JWT_SECRET = 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='; AES_GCM_KEY = 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='
        OUTBOX_ENABLED = 'false'; AUTH_BOOTSTRAP_TOKEN = $bootstrapToken
    }
    $saved = @{}
    foreach ($entry in $serverEnvironment.GetEnumerator()) {
        $saved[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    try {
        # 启动隔离空库 Java 服务。服务就绪探测由 Node 侧 waitForServer() 完成（health=200 意味着 Flyway 迁移已完成），
        # 避免 PowerShell 健康探针 / 轮询循环在 5.1 下引入偶发变量污染、或与 JVM 竞争导致启动迟迟无法推进。
        $serverProcess = Start-Process -FilePath 'java.exe' -ArgumentList '-jar', 'target\server-java-SuMon-0.0.1-SNAPSHOT.jar' -WorkingDirectory $serverDirectory -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog -PassThru
        $env:SUSUMONITOR_VALIDATION_BASE_URL = "http://127.0.0.1:$port"
        $env:SUSUMONITOR_VALIDATION_CONFIRM = 'FIRST_ADMIN_CONCURRENCY'
        # 批次 8 一次性初始化令牌经本环境变量传给 Node 验证器（与服务器 env 同值，见上方双路注入说明）。
        $env:SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN = $bootstrapToken
        # 并发数由编排器统一从环境变量读取，并作为子进程环境传给 Node；Node 侧与 PowerShell 复用同一值，
        # 无需跨进程传递文件/路径（实测跨“& node”读写同一 PowerShell 变量在 5.1 下偶发读到 null）。
        & node (Join-Path $PSScriptRoot 'verify-first-admin-concurrency.mjs')
        if ($LASTEXITCODE -ne 0) { throw "First-admin concurrency E2E failed (exit $LASTEXITCODE); workspace retained at $workspace" }

        $concurrencyRaw = $env:SUSUMONITOR_VALIDATION_CONCURRENCY
        if ([string]::IsNullOrWhiteSpace($concurrencyRaw)) { $concurrencyRaw = '8' }
        if ($concurrencyRaw -notmatch '^\d+$') { throw "Invalid SUSUMONITOR_VALIDATION_CONCURRENCY: $concurrencyRaw" }
        $concurrency = [int]$concurrencyRaw
        if ($concurrency -lt 2 -or $concurrency -gt 20) { throw "SUSUMONITOR_VALIDATION_CONCURRENCY out of range 2..20: $concurrency" }

        $env:MYSQL_PWD = $databasePassword
        $mysqlArgs = @('-h', '127.0.0.1', '-P', '3306', '-u', 'susumonitor', '-N', '-B')
        function Get-DbScalar([string]$query) {
            $value = & mysql @mysqlArgs -e $query 2>$null
            if ($LASTEXITCODE -ne 0) { throw 'Database verification query failed.' }
            return ([string]$value).Trim()
        }

        $totalUsers = [int](Get-DbScalar "SELECT COUNT(*) FROM ``$databaseName``.users")
        $adminCount = [int](Get-DbScalar "SELECT COUNT(*) FROM ``$databaseName``.users WHERE role='admin' AND review_status='approved'")
        $pendingCount = [int](Get-DbScalar "SELECT COUNT(*) FROM ``$databaseName``.users WHERE role='user' AND review_status='pending'")
        $invalidCount = [int](Get-DbScalar "SELECT COUNT(*) FROM ``$databaseName``.users WHERE NOT (role='admin' AND review_status='approved') AND NOT (role='user' AND review_status='pending')")
        $adminInitializedRaw = (Get-DbScalar "SELECT admin_initialized FROM ``$databaseName``.auth_bootstrap_state WHERE id=1")
        $bootstrapConsistent = [int](Get-DbScalar "SELECT COUNT(*) FROM ``$databaseName``.users u JOIN ``$databaseName``.auth_bootstrap_state b ON b.initialized_user_id = u.id WHERE b.id=1 AND u.role='admin' AND u.review_status='approved'")

        if ($totalUsers -ne $concurrency) { throw "DB user count $totalUsers != concurrency $concurrency" }
        if ($adminCount -ne 1) { throw "DB admin/approved count $adminCount != 1" }
        if ($pendingCount -ne ($concurrency - 1)) { throw "DB user/pending count $pendingCount != $($concurrency - 1)" }
        if ($invalidCount -ne 0) { throw "DB contains $invalidCount unexpected role/reviewStatus rows" }
        if ($adminInitializedRaw -ne '1') { throw "auth_bootstrap_state.admin_initialized = $adminInitializedRaw, want 1" }
        if ($bootstrapConsistent -ne 1) { throw "auth_bootstrap_state does not reference a single admin/approved user" }

        $runSucceeded = $true
        Write-Host ("DB verification PASS: users=$totalUsers admin=$adminCount pending=$pendingCount")
    } finally {
        if ($null -ne $serverProcess -and -not $serverProcess.HasExited) { Stop-Process -Id $serverProcess.Id -Force }
        foreach ($entry in $saved.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    }
} finally {
    $env:SUSUMONITOR_VALIDATION_BASE_URL = $originalBaseUrl
    $env:SUSUMONITOR_VALIDATION_CONFIRM = $originalConfirm
    $env:SUSUMONITOR_VALIDATION_BOOTSTRAP_TOKEN = $originalBootstrapToken
    if ($runSucceeded) {
        $env:MYSQL_PWD = $databaseAdminPassword
        & mysql -h 127.0.0.1 -P 3306 -u $databaseAdminUser -e "DROP DATABASE IF EXISTS ``$databaseName``;" | Out-Null
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $originalMySqlPwd, 'Process')
        if (-not $keepArtifacts) { Remove-Item -LiteralPath $workspace -Recurse -Force -ErrorAction SilentlyContinue }
        elseif (Test-Path -LiteralPath $workspace) { Write-Host "First-admin E2E artifacts retained: $workspace" }
    } else {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $originalMySqlPwd, 'Process')
        $errDump = Join-Path $workspace 'error-dump.txt'
        try {
            ($Error | ForEach-Object { ($_.InvocationInfo.ScriptLineNumber.ToString() + ': ' + $_.Exception.GetType().FullName + ' :: ' + $_.Exception.Message) }) | Out-File -LiteralPath $errDump -Encoding utf8
            Write-Host "ERROR_DUMP_WRITTEN: $errDump"
        } catch { Write-Host 'ERROR_DUMP_WRITE_FAILED' }
        Write-Host "First-admin E2E FAILED: schema retained for inspection => $databaseName"
        $cleanup = "mysql -h 127.0.0.1 -P 3306 -u $databaseAdminUser -p -e 'DROP DATABASE IF EXISTS ``$databaseName``;'"
        Write-Host "Cleanup when done: $cleanup"
        Write-Host "Logs retained: $workspace"
    }
}
