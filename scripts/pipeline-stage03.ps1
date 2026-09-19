[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$data = Join-Path $root "data"
$pmtiles = Join-Path $data "tiles\novi-sad.pmtiles"

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
    throw "Node.js is required for stage 3."
}

New-Item -ItemType Directory -Force -Path (Join-Path $data "tmp") | Out-Null

if (-not (Test-Path (Join-Path $root ".env"))) {
    Copy-Item (Join-Path $root ".env.example") (Join-Path $root ".env")
    Write-Host "Created .env from .env.example"
}

$pmtilesBefore = if (Test-Path $pmtiles) { (Get-Item $pmtiles).LastWriteTimeUtc } else { $null }

Push-Location $root
try {
    Write-Host "Step 1: docker compose up postgis meilisearch api..."
    docker compose up -d --build postgis meilisearch api --wait
    Assert-LastExitCode "docker compose up failed"

    Write-Host "Step 2: installing script dependencies..."
    Install-NodeDeps (Join-Path $root "scripts")

    Write-Host "Step 3: indexing PostGIS → Meilisearch..."
    & node (Join-Path $root "scripts\index-meilisearch.mjs")
    Assert-LastExitCode "index-meilisearch failed"

    Write-Host "Step 4: verification..."
    & node (Join-Path $root "scripts\stage03-verify.mjs")
    Assert-LastExitCode "stage03-verify failed"

    Write-Host "Step 5: benchmark..."
    & node (Join-Path $root "scripts\stage03-benchmark.mjs")
    Assert-LastExitCode "stage03-benchmark failed"
}
finally {
    Pop-Location
}

if ($null -ne $pmtilesBefore -and (Test-Path $pmtiles)) {
    $pmtilesAfter = (Get-Item $pmtiles).LastWriteTimeUtc
    if ($pmtilesAfter -ne $pmtilesBefore) {
        throw "R3 failed: data/tiles/novi-sad.pmtiles mtime changed"
    }
}

Write-Host ""
Write-Host "Stage 3 pipeline finished."
Write-Host "  Search: http://127.0.0.1:3000/v1/search?q=apotek"
Write-Host "  Health: http://127.0.0.1:3000/v1/health"
