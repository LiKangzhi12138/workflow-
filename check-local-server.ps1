$ErrorActionPreference = 'Stop'

function Write-Info($Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Pass($Message) {
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Write-WarnMsg($Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Fail($Message) {
    Write-Host "[FAIL] $Message" -ForegroundColor Red
    exit 1
}

function Read-EnvFile([string]$Path) {
    $result = @{}
    foreach ($line in Get-Content -Path $Path) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) {
            continue
        }
        $parts = $trimmed -split '=', 2
        if ($parts.Count -eq 2) {
            $result[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
    return $result
}

function Invoke-HealthCheck([string]$Url, [string]$Keyword, [string]$Label) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 20
        if ($response.StatusCode -eq 200 -and $response.Content -match $Keyword) {
            Write-Pass "$Label is healthy: $Url"
        } else {
            Write-Fail "$Label returned unexpected content: $Url"
        }
    } catch {
        Write-Fail "$Label request failed: $Url"
    }
}

function Get-LanIpv4Addresses {
    try {
        return Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object {
                $_.IPAddress -notlike '127.*' -and
                $_.IPAddress -notlike '169.254.*' -and
                $_.PrefixOrigin -ne 'WellKnown'
            } |
            Sort-Object -Property InterfaceMetric, SkipAsSource |
            Select-Object -ExpandProperty IPAddress -Unique
    } catch {
        return @()
    }
}

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvPath = Join-Path $ProjectRoot '.env.local-server'

if (-not (Test-Path $EnvPath)) {
    Write-Fail ".env.local-server not found."
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Fail "Docker Desktop / docker command is not available."
}

$envValues = Read-EnvFile $EnvPath
$httpPort = $envValues['PUBLIC_HTTP_PORT']
if ([string]::IsNullOrWhiteSpace($httpPort)) {
    $httpPort = '18080'
}

$baseUrl = "http://127.0.0.1:$httpPort"

Write-Info "Checking docker compose service status..."
Push-Location $ProjectRoot
try {
    & docker compose --env-file $EnvPath ps
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "docker compose ps failed."
    }
} finally {
    Pop-Location
}

Invoke-HealthCheck "$baseUrl/healthz" 'ok' 'Gateway'
Invoke-HealthCheck "$baseUrl/api/health" '"code":"OK"' 'Java'
Invoke-HealthCheck "$baseUrl/api/health/python" '"code":"OK"' 'Python via Java'

try {
    $listener = Get-NetTCPConnection -LocalPort ([int]$httpPort) -State Listen -ErrorAction Stop
    if ($listener) {
        Write-Pass "Windows is listening on TCP port $httpPort"
    }
} catch {
    Write-WarnMsg "Could not confirm the listener with Get-NetTCPConnection. If needed, run: netstat -ano | findstr :$httpPort"
}

$lanIps = Get-LanIpv4Addresses
Write-Host ""
Write-Host "Local server URLs:" -ForegroundColor Green
Write-Host "  $baseUrl/"
foreach ($ip in $lanIps) {
    Write-Host "  http://$ip`:$httpPort/"
}
