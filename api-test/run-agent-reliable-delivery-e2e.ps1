[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverDirectory = Join-Path $projectRoot 'server-java-SuMon'
$agentExecutable = Join-Path $projectRoot 'agent-go-SuMon\bin\susumonitor-agent.exe'
$databasePassword = $env:DB_PASSWORD
$databaseAdminUser = $env:SUSUMONITOR_VALIDATION_DB_ADMIN_USER
$databaseAdminPassword = $env:SUSUMONITOR_VALIDATION_DB_ADMIN_PASSWORD
if ([string]::IsNullOrWhiteSpace($databasePassword)) { throw 'Set DB_PASSWORD before running this isolated E2E.' }
if ([string]::IsNullOrWhiteSpace($databaseAdminUser) -or [string]::IsNullOrWhiteSpace($databaseAdminPassword)) {
    throw 'Set SUSUMONITOR_VALIDATION_DB_ADMIN_USER and SUSUMONITOR_VALIDATION_DB_ADMIN_PASSWORD to a local MySQL account allowed to CREATE/DROP the run-owned validation schema.'
}
if (-not (Test-Path -LiteralPath $agentExecutable)) { throw "Agent executable is missing: $agentExecutable" }

$runId = "rd-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())-$([Guid]::NewGuid().ToString('N').Substring(0, 6))"
$databaseName = "susumonitor_agent_$($runId.Replace('-', '_'))"
$port = 18181
$workspace = Join-Path $env:TEMP "susumonitor-agent-reliable-$runId"
$serverLog = Join-Path $workspace 'server.out.log'
$serverErrorLog = Join-Path $workspace 'server.err.log'
$keepArtifacts = $env:KEEP_E2E_ARTIFACTS -eq 'true'
$serverProcess = $null
$originalMySqlPwd = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
$originalBaseUrl = $env:SUSUMONITOR_RELIABLE_E2E_BASE_URL
$originalUsername = $env:SUSUMONITOR_RELIABLE_E2E_ADMIN_USERNAME
$originalPassword = $env:SUSUMONITOR_RELIABLE_E2E_ADMIN_PASSWORD
$originalBootstrapToken = $env:SUSUMONITOR_RELIABLE_E2E_BOOTSTRAP_TOKEN

# 批次 8：隔离空库实例注册管理员必须携带一次性初始化令牌（缺失 403/40310）。
# 本编排器生成 32 字节随机令牌（base64url 43 字符，与服务器自动生成口径一致），
# 同一值双路注入：服务器 env AUTH_BOOTSTRAP_TOKEN + 验证器 SUSUMONITOR_RELIABLE_E2E_BOOTSTRAP_TOKEN。
$bootstrapTokenBytes = New-Object 'byte[]' 32
$bootstrapTokenRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bootstrapTokenRng.GetBytes($bootstrapTokenBytes)
$bootstrapTokenRng.Dispose()
$bootstrapToken = [Convert]::ToBase64String($bootstrapTokenBytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
$bootstrapTokenBytes.Clear()

New-Item -ItemType Directory -Path $workspace -Force | Out-Null
try {
    $env:MYSQL_PWD = $databaseAdminPassword
    & mysql -h 127.0.0.1 -P 3306 -u $databaseAdminUser -e "CREATE DATABASE ``$databaseName`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create isolated reliable-delivery schema.' }
    & mysql -h 127.0.0.1 -P 3306 -u $databaseAdminUser -e "GRANT ALL PRIVILEGES ON ``$databaseName``.* TO 'susumonitor'@'127.0.0.1'; FLUSH PRIVILEGES;"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to grant the application account access to the isolated schema.' }
    $env:MYSQL_PWD = $databasePassword

    $serverEnvironment = @{
        DB_HOST = '127.0.0.1'; DB_PORT = '3306'; DB_NAME = $databaseName; DB_USER = 'susumonitor'; DB_PASSWORD = $databasePassword
        SERVER_ADDRESS = '127.0.0.1'; SERVER_PORT = "$port"; APP_ENV = 'test'; SPRING_PROFILES_ACTIVE = 'test'
        JWT_SECRET = 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='; AES_GCM_KEY = 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='
        OUTBOX_ENABLED = 'false'; AGENT_METRICS_RATE_PER_MINUTE = '600'; AGENT_METRICS_BURST = '100'
        AGENT_HEARTBEAT_RATE_PER_MINUTE = '60'; AGENT_HEARTBEAT_BURST = '10'
        AUTH_BOOTSTRAP_TOKEN = $bootstrapToken
    }
    $saved = @{}
    foreach ($entry in $serverEnvironment.GetEnumerator()) {
        $saved[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    try {
        $serverProcess = Start-Process -FilePath 'java.exe' -ArgumentList '-jar', 'target\server-java-SuMon-0.0.1-SNAPSHOT.jar' -WorkingDirectory $serverDirectory -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog -PassThru
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        while ([DateTime]::UtcNow -lt $deadline) {
            try {
                if ((Invoke-WebRequest -Uri "http://127.0.0.1:$port/api/health" -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200) { break }
            } catch { Start-Sleep -Milliseconds 250 }
        }
        if (-not (Test-NetConnection -ComputerName '127.0.0.1' -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue)) { throw 'Isolated Java Server did not become reachable.' }
        $env:SUSUMONITOR_RELIABLE_E2E_BASE_URL = "http://127.0.0.1:$port"
        $env:SUSUMONITOR_RELIABLE_E2E_ADMIN_USERNAME = "reliable$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        $env:SUSUMONITOR_RELIABLE_E2E_ADMIN_PASSWORD = [Guid]::NewGuid().ToString('N')
        $env:SUSUMONITOR_RELIABLE_E2E_BOOTSTRAP_TOKEN = $bootstrapToken
        $env:SUSUMONITOR_AGENT_EXECUTABLE = $agentExecutable
        node (Join-Path $PSScriptRoot 'verify-agent-reliable-delivery-e2e.mjs')
        if ($LASTEXITCODE -ne 0) { throw "Reliable delivery E2E failed; workspace retained at $workspace" }
    } finally {
        if ($null -ne $serverProcess -and -not $serverProcess.HasExited) { Stop-Process -Id $serverProcess.Id -Force }
        foreach ($entry in $saved.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    }
} finally {
    $env:MYSQL_PWD = $databaseAdminPassword
    & mysql -h 127.0.0.1 -P 3306 -u $databaseAdminUser -e "DROP DATABASE IF EXISTS ``$databaseName``;" | Out-Null
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $originalMySqlPwd, 'Process')
    $env:SUSUMONITOR_RELIABLE_E2E_BASE_URL = $originalBaseUrl
    $env:SUSUMONITOR_RELIABLE_E2E_ADMIN_USERNAME = $originalUsername
    $env:SUSUMONITOR_RELIABLE_E2E_ADMIN_PASSWORD = $originalPassword
    $env:SUSUMONITOR_RELIABLE_E2E_BOOTSTRAP_TOKEN = $originalBootstrapToken
    if (-not $keepArtifacts -and $LASTEXITCODE -eq 0) { Remove-Item -LiteralPath $workspace -Recurse -Force -ErrorAction SilentlyContinue }
    elseif (Test-Path -LiteralPath $workspace) { Write-Host "Reliable E2E artifacts retained: $workspace" }
}
