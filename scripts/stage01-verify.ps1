[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$verifyDir = Join-Path $root "scripts\map-verify"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required for stage 1 map verify."
}

if (-not (Test-Path (Join-Path $verifyDir "node_modules"))) {
    Write-Host "Installing map-verify dependencies..."
    Push-Location $verifyDir
    try {
        if (Test-Path (Join-Path $verifyDir "package-lock.json")) {
            & npm ci
        }
        else {
            & npm install
        }
        if ($LASTEXITCODE -ne 0) {
            throw "npm install failed for scripts/map-verify (exit code $LASTEXITCODE)"
        }
    }
    finally {
        Pop-Location
    }
}

Write-Host "Running stage 1 map verify..."
& node (Join-Path $verifyDir "verify.mjs")
if ($LASTEXITCODE -ne 0) {
    throw "stage01-verify failed (exit code $LASTEXITCODE)"
}
