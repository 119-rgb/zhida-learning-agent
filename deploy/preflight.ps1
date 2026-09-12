[CmdletBinding()]
param(
    [switch]$Https
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"

function Read-DotEnv([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $parts = $trimmed.Split("=", 2)
        if ($parts.Count -eq 2) {
            $values[$parts[0].Trim()] = $parts[1].Trim().Trim('"').Trim("'")
        }
    }
    return $values
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker command was not found. Install and start Docker Desktop first."
}

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop is not running. Start it and try again."
}

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example and fill it locally."
}

$settings = Read-DotEnv $envFile
foreach ($name in @("DB_PASSWORD", "ZHIDA_JWT_SECRET")) {
    if (-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])) {
        throw "Required .env setting is missing: $name"
    }
}

$secretBytes = [Text.Encoding]::UTF8.GetByteCount($settings["ZHIDA_JWT_SECRET"])
if ($secretBytes -lt 32) {
    throw "ZHIDA_JWT_SECRET must contain at least 32 UTF-8 bytes."
}

if ($settings["ZHIDA_AI_ENABLED"] -eq "true" -and
    (-not $settings.ContainsKey("DEEPSEEK_API_KEY") -or [string]::IsNullOrWhiteSpace($settings["DEEPSEEK_API_KEY"]))) {
    throw "DEEPSEEK_API_KEY is required when ZHIDA_AI_ENABLED=true."
}

$composeFiles = @("-f", (Join-Path $projectRoot "compose.yaml"))
if ($Https) {
    $composeVersionText = (docker compose version --short).Trim().TrimStart("v")
    if ($composeVersionText -notmatch "^(\d+)\.(\d+)\.(\d+)") {
        throw "Unrecognized Docker Compose version: $composeVersionText"
    }
    $composeVersion = [version]"$($Matches[1]).$($Matches[2]).$($Matches[3])"
    if ($composeVersion -lt [version]"2.24.4") {
        throw "The HTTPS override requires Docker Compose 2.24.4 or newer."
    }
    foreach ($certificate in @("fullchain.pem", "privkey.pem")) {
        $certificatePath = Join-Path $projectRoot "deploy\certs\$certificate"
        if (-not (Test-Path -LiteralPath $certificatePath)) {
            throw "Missing HTTPS certificate: deploy\certs\$certificate"
        }
    }
    $composeFiles += @("-f", (Join-Path $projectRoot "compose.https.yaml"))
}

Push-Location $projectRoot
try {
    docker compose @composeFiles config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose configuration validation failed."
    }
}
finally {
    Pop-Location
}

$mode = if ($Https) { "HTTPS" } else { "local HTTP" }
Write-Host "Deployment preflight passed for $mode. No secret values were printed."
