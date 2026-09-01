$ErrorActionPreference = 'Stop'

function Write-Info($Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}

function Write-Fail($Message) {
    Write-Host "[FAIL] $Message" -ForegroundColor Red
    exit 1
}

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvPath = Join-Path $ProjectRoot '.env.local-server'

if (-not (Test-Path $EnvPath)) {
    Write-Fail ".env.local-server not found. Nothing to stop."
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Fail "Docker Desktop / docker command is not available."
}

Write-Info "Stopping workflow-platform local server mode..."
Push-Location $ProjectRoot
try {
    & docker compose --env-file $EnvPath down
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "docker compose down failed."
    }
} finally {
    Pop-Location
}

Write-Info "Local server mode stopped."
