$ErrorActionPreference = 'Stop'

function Write-Info($Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
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

function Resolve-ProjectPath([string]$ProjectRoot, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    if ([System.IO.Path]::IsPathRooted($Value)) {
        return [System.IO.Path]::GetFullPath($Value)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $Value))
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
$EnvTemplatePath = Join-Path $ProjectRoot '.env.local-server.example'
$EnvPath = Join-Path $ProjectRoot '.env.local-server'

if (-not (Test-Path $EnvTemplatePath)) {
    Write-Fail ".env.local-server.example not found."
}

if (-not (Test-Path $EnvPath)) {
    Copy-Item -Path $EnvTemplatePath -Destination $EnvPath
    Write-WarnMsg "Created .env.local-server from template."
    Write-WarnMsg "Please edit .env.local-server and replace the example passwords and callback secret, then run this script again."
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Fail "Docker Desktop / docker command is not available."
}

& docker compose version | Out-Null

$envValues = Read-EnvFile $EnvPath
$placeholderValues = @(
    'change_this_local_server_mysql_password',
    'change_this_local_server_mysql_root_password',
    'replace_with_a_long_random_secret'
)

if ($placeholderValues -contains $envValues['MYSQL_PASSWORD'] -or
    $placeholderValues -contains $envValues['MYSQL_ROOT_PASSWORD'] -or
    $placeholderValues -contains $envValues['PYTHON_CALLBACK_SECRET']) {
    Write-WarnMsg ".env.local-server still contains example passwords or callback secret."
    Write-WarnMsg "Please replace them before sharing this service with other people on the LAN."
}

$storageRoot = Resolve-ProjectPath $ProjectRoot $envValues['HOST_STORAGE_ROOT']
$mysqlDataRoot = Resolve-ProjectPath $ProjectRoot $envValues['HOST_MYSQL_DATA_ROOT']

foreach ($dir in @($storageRoot, $mysqlDataRoot)) {
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
}

Write-Info "Starting workflow-platform in local server mode..."
Push-Location $ProjectRoot
try {
    & docker compose --env-file $EnvPath up -d --build
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "docker compose up failed."
    }
} finally {
    Pop-Location
}

$httpPort = $envValues['PUBLIC_HTTP_PORT']
if ([string]::IsNullOrWhiteSpace($httpPort)) {
    $httpPort = '18080'
}

$lanIps = Get-LanIpv4Addresses
Write-Info "Local server mode started."
Write-Host ""
Write-Host "Access URLs:" -ForegroundColor Green
Write-Host "  http://127.0.0.1:$httpPort/"
foreach ($ip in $lanIps) {
    Write-Host "  http://$ip`:$httpPort/"
}
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Green
Write-Host "  1. If this is the first run, open .env.local-server and replace the example secrets."
Write-Host "  2. If other computers cannot access the site, allow TCP port $httpPort through Windows Firewall."
Write-Host "  3. Run .\\check-local-server.ps1 to verify health endpoints."
