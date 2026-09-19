[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script = Join-Path $PSScriptRoot "stage05-verify.mjs"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required for stage 5 verify."
}

Write-Host "Running stage 5 verify..."
& node $script
if ($LASTEXITCODE -ne 0) {
    throw "stage05-verify failed (exit code $LASTEXITCODE)"
}
