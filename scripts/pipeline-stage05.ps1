[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$data = Join-Path $root "data"
$pmtiles = Join-Path $data "tiles\novi-sad.pmtiles"
$web = Join-Path $root "apps\web"
$api = Join-Path $root "apps\api"
$scripts = Join-Path $root "scripts"

function Assert-LastExitCode([string]$Message) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Message (exit code $LASTEXITCODE)"
    }
}

function Install-NodeDeps([string]$Directory) {
    Push-Location $Directory
    try {
        if (Test-Path (Join-Path $Directory "package-lock.json")) {
            & npm ci
        }
        else {
            & npm install
        }
        Assert-LastExitCode "npm install failed in $Directory"
    }
    finally {
        Pop-Location
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required but was not found in PATH."
}
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required for stage 5."
}

New-Item -ItemType Directory -Force -Path (Join-Path $data "tmp") | Out-Null

if (-not (Test-Path (Join-Path $root ".env"))) {
    Copy-Item (Join-Path $root ".env.example") (Join-Path $root ".env")
    Write-Host "Created .env from .env.example"
}

$pmtilesBefore = if (Test-Path $pmtiles) { (Get-Item $pmtiles).LastWriteTimeUtc } else { $null }

Push-Location $root
try {
    Write-Host "Step 1: docker compose up postgis meilisearch api tiles..."
    docker compose up -d --build postgis meilisearch api tiles --wait
    Assert-LastExitCode "docker compose up failed"

    Write-Host "Step 2: installing script dependencies..."
    Install-NodeDeps $scripts

    Write-Host "Step 3: installing, testing and building apps/web..."
    Install-NodeDeps $web
    Push-Location $web
    try {
        & npm test
        Assert-LastExitCode "apps/web tests failed"
        & npm run build
        Assert-LastExitCode "apps/web build failed"
    }
    finally {
        Pop-Location
    }

    Write-Host "Step 4: building apps/api..."
    Install-NodeDeps $api
    Push-Location $api
    try {
        & npm run build
        Assert-LastExitCode "apps/api build failed"
    }
    finally {
        Pop-Location
    }

    Write-Host "Step 5: verification..."
    $env:SKIP_WEB_BUILD = "1"
    & node (Join-Path $scripts "stage05-verify.mjs")
    Assert-LastExitCode "stage05-verify failed"

    Write-Host "Step 6: benchmark..."
    & node (Join-Path $scripts "stage05-benchmark.mjs")
    Assert-LastExitCode "stage05-benchmark failed"

    if ($env:SKIP_STAGE04 -ne "1") {
        Write-Host "Step 7: stage 4 verify subset/full..."
        $env:SKIP_WEB_BUILD = "1"
        & node (Join-Path $scripts "stage04-verify.mjs")
        Assert-LastExitCode "stage04-verify failed"
    }
}
finally {
    Pop-Location
}

if ($null -ne $pmtilesBefore -and (Test-Path $pmtiles)) {
    $pmtilesAfter = (Get-Item $pmtiles).LastWriteTimeUtc
    if ($pmtilesAfter -ne $pmtilesBefore) {
        throw "R2 failed: data/tiles/novi-sad.pmtiles mtime changed"
    }
}

Write-Host ""
Write-Host "Stage 5 pipeline finished."
Write-Host "  Web:   http://127.0.0.1:5173"
Write-Host "  API:   http://127.0.0.1:3000/v1/buildings/at?lon=19.8435&lat=45.2458"
Write-Host "  Tiles: http://127.0.0.1:8080"
