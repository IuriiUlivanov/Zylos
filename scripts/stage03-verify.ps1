[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script = Join-Path $PSScriptRoot "stage03-verify.mjs"

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required for stage 3 verify."
}

Write-Host "Running stage 3 verify..."
& node $script
if ($LASTEXITCODE -ne 0) {
    throw "stage03-verify failed (exit code $LASTEXITCODE)"
}
