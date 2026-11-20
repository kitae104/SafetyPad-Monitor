<#
.SYNOPSIS
  SafetyPad Monitor - 개발 서버 기동/종료 (PowerShell 5.1/7+, Windows/Linux/macOS)

.DESCRIPTION
  .\run.ps1 start   [all|db|backend|frontend]   기본: all (DB -> 백엔드 -> 프런트)
  .\run.ps1 stop    [all|db|backend|frontend]   기본: all (프런트 -> 백엔드 -> DB). DB 데이터(볼륨)는 보존
  .\run.ps1 restart [all|db|backend|frontend]
  .\run.ps1 status
  .\run.ps1 logs    [backend|frontend]          로그 따라가기 (Ctrl+C로 중단)

  개발 프로파일 전용입니다(.env 의 SPRING_PROFILES_ACTIVE=dev). 프로세스는 PID 파일이 아니라 "리슨 포트"로 찾습니다.
  필요한 것: Docker(Compose v2), Java 21, Node 22+, PowerShell 5.1 또는 7+.
  ※ .env 가 없으면 만들어 주지 않습니다(Linux/macOS 는 make env, Windows 는 .env.example 을 복사해 값을 채우세요).
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0)][ValidateSet('start', 'stop', 'restart', 'status', 'logs')][string]$Command = 'status',
  [Parameter(Position = 1)][string]$Target = ''
)
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 은 네이티브 명령(docker 등)의 stderr 출력을 종료 오류로 취급하므로 Continue 로 두고 $LASTEXITCODE 로 판단한다.
if ($PSVersionTable.PSEdition -eq 'Desktop') { $ErrorActionPreference = 'Continue' }
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)   # 한글 출력 깨짐 방지
$OutputEncoding = [Console]::OutputEncoding
Set-Location $PSScriptRoot

$BackendPort = 8080
$FrontendPort = 5173
$RunDir = Join-Path $PSScriptRoot '.run'
New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
$IsWin = $PSVersionTable.PSEdition -eq 'Desktop' -or $IsWindows

function Fail([string]$msg) { Write-Host "오류: $msg" -ForegroundColor Red; exit 1 }
function Need([string]$cmd) { if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { Fail "'$cmd' 명령을 찾을 수 없습니다." } }

# .env 를 현재 프로세스 환경변수로 읽어 들입니다(값은 출력하지 않음). 자식 프로세스가 상속합니다.
function Import-DotEnv {
  if (-not (Test-Path .env)) { Fail ".env 가 없습니다. .env.example 을 복사해 값을 채우세요(비밀 값은 커밋 금지)." }
  Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $i = $line.IndexOf('=')
    if ($i -lt 1) { return }
    [Environment]::SetEnvironmentVariable($line.Substring(0, $i).Trim(), $line.Substring($i + 1).Trim().Trim('"').Trim("'"), 'Process')
  }
}

# 해당 포트를 LISTEN 중인 프로세스의 PID (없으면 $null)
function Get-PortPid([int]$port) {
  if ($IsWin) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return [int]$c.OwningProcess } else { return $null }
  }
  $out = (& ss -ltnp 2>$null) | Where-Object { ($_ -split '\s+')[3] -match ":$port$" } | Select-Object -First 1
  if ($out -and $out -match 'pid=(\d+)') { return [int]$Matches[1] }
  return $null
}

function Wait-Http([string]$url, [int]$seconds) {
  $deadline = (Get-Date).AddSeconds($seconds)
  while ((Get-Date) -lt $deadline) {
    try { Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3 | Out-Null; return $true } catch { Start-Sleep -Seconds 2 }
  }
  return $false
}

function Start-Detached([string]$file, [string[]]$arguments, [string]$workDir, [string]$name) {
  $p = @{
    FilePath = $file; ArgumentList = $arguments; WorkingDirectory = $workDir
    RedirectStandardOutput = (Join-Path $RunDir "$name.log"); RedirectStandardError = (Join-Path $RunDir "$name.err.log")
    PassThru = $true
  }
  if ($IsWin) { $p.WindowStyle = 'Hidden' }
  Start-Process @p | Out-Null
}

# ---------- DB ----------
function Test-Db { return [bool](& docker compose ps --status running -q postgres 2>$null) }

function Start-Db {
  Need docker
  if (Test-Db) { Write-Host 'DB        이미 실행 중'; return }
  Write-Host 'DB        기동 중...'
  Import-DotEnv
  & docker compose up -d --wait postgres *> $null
  if ($LASTEXITCODE -ne 0) { Fail 'DB 기동 실패 (docker compose logs postgres 확인)' }
  Write-Host "DB        실행 중 (localhost:$(if ($env:DB_PORT) { $env:DB_PORT } else { '5433' }))"
}

function Stop-Db {
  Need docker
  if (-not (Test-Db)) { Write-Host 'DB        이미 중지됨'; return }
  & docker compose stop postgres *> $null
  if ($LASTEXITCODE -ne 0) { Fail 'DB 중지 실패' }
  Write-Host 'DB        중지됨 (데이터는 보존)'
}

# ---------- 백엔드 / 프런트 ----------
function Start-Backend {
  Need java
  if (Get-PortPid $BackendPort) { Write-Host "백엔드    이미 실행 중 (:$BackendPort)"; return }
  if (-not (Test-Db)) { Fail 'DB가 꺼져 있습니다. .\run.ps1 start db 를 먼저 실행하세요.' }
  Write-Host '백엔드    기동 중... (처음에는 1~2분 걸릴 수 있습니다)'
  Import-DotEnv
  $gradle = if ($IsWin) { Join-Path $PSScriptRoot 'backend\gradlew.bat' } else { Join-Path $PSScriptRoot 'backend/gradlew' }
  Start-Detached $gradle @('bootRun') (Join-Path $PSScriptRoot 'backend') 'backend'
  if (Wait-Http "http://localhost:$BackendPort/actuator/health" 240) { Write-Host "백엔드    실행 중 http://localhost:$BackendPort" }
  else { Fail "백엔드가 4분 안에 올라오지 않았습니다. 로그: .run/backend.log, .run/backend.err.log" }
}

function Start-Frontend {
  Need npm
  if (Get-PortPid $FrontendPort) { Write-Host "프런트    이미 실행 중 (:$FrontendPort)"; return }
  $fe = Join-Path $PSScriptRoot 'frontend'
  if (-not (Test-Path (Join-Path $fe 'node_modules'))) {
    Write-Host '프런트    의존성 설치 중 (npm ci)...'
    Push-Location $fe; try { & npm ci *> (Join-Path $RunDir 'npm-install.log') } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { Fail 'npm ci 실패: .run/npm-install.log' }
  }
  Write-Host '프런트    기동 중...'
  $npm = if ($IsWin) { 'npm.cmd' } else { 'npm' }
  Start-Detached $npm @('run', 'dev') $fe 'frontend'
  if (Wait-Http "http://localhost:$FrontendPort/" 60) { Write-Host "프런트    실행 중 http://localhost:$FrontendPort" }
  else { Fail '프런트가 올라오지 않았습니다. 로그: .run/frontend.log, .run/frontend.err.log' }
}

function Stop-Port([string]$label, [int]$port) {
  $procId = Get-PortPid $port
  if (-not $procId) { Write-Host "$label 이미 중지됨"; return }
  # 프로세스 트리째 종료(자식 포함). Windows: taskkill /T, 그 외: kill.
  if ($IsWin) { & taskkill /PID $procId /T /F *> $null } else { & kill $procId 2>$null }
  for ($i = 0; $i -lt 20; $i++) {
    if (-not (Get-PortPid $port)) { Write-Host "$label 중지됨"; return }
    Start-Sleep -Seconds 1
  }
  if (-not $IsWin) { & kill -9 $procId 2>$null; Start-Sleep -Seconds 1 }
  if (-not (Get-PortPid $port)) { Write-Host "$label 강제 종료됨" } else { Fail "$label 를 종료하지 못했습니다 (PID $procId)" }
}

# ---------- 명령 ----------
function Show-Status {
  if (Test-Db) { Write-Host 'DB        실행 중' } else { Write-Host 'DB        중지됨' }
  $code = 0
  try { $code = (Invoke-WebRequest -Uri "http://localhost:$BackendPort/actuator/health" -UseBasicParsing -TimeoutSec 3).StatusCode } catch { }
  if ($code -eq 200) { Write-Host "백엔드    실행 중 http://localhost:$BackendPort" }
  elseif (Get-PortPid $BackendPort) { Write-Host '백엔드    기동 중이거나 비정상' }
  else { Write-Host '백엔드    중지됨' }
  if (Get-PortPid $FrontendPort) { Write-Host "프런트    실행 중 http://localhost:$FrontendPort" } else { Write-Host '프런트    중지됨' }
}

function Invoke-Start([string]$t) {
  switch ($t) {
    'all'      { Start-Db; Start-Backend; Start-Frontend; Write-Host ''; Write-Host "접속: http://localhost:$FrontendPort  (아이디/비밀번호는 .env 의 ADMIN_BOOTSTRAP_*)" }
    'db'       { Start-Db }
    'backend'  { Start-Backend }
    'frontend' { Start-Frontend }
    default    { Fail "알 수 없는 대상: $t (all|db|backend|frontend)" }
  }
}

function Invoke-Stop([string]$t) {
  switch ($t) {
    'all'      { Stop-Port '프런트   ' $FrontendPort; Stop-Port '백엔드   ' $BackendPort; Stop-Db }
    'db'       { Stop-Db }
    'backend'  { Stop-Port '백엔드   ' $BackendPort }
    'frontend' { Stop-Port '프런트   ' $FrontendPort }
    default    { Fail "알 수 없는 대상: $t (all|db|backend|frontend)" }
  }
}

$t = if ($Target) { $Target } else { 'all' }
switch ($Command) {
  'start'   { Invoke-Start $t }
  'stop'    { Invoke-Stop $t }
  'restart' { Invoke-Stop $t; Invoke-Start $t }
  'status'  { Show-Status }
  'logs'    {
    $name = if ($Target) { $Target } else { 'backend' }
    $f = Join-Path $RunDir "$name.log"
    if (-not (Test-Path $f)) { Fail "로그 파일이 없습니다: $f" }
    Get-Content $f -Tail 50 -Wait
  }
}
