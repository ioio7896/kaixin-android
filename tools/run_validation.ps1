# Compatibility entry point: static checks and timestamped Debug APKs only.
param([switch]$Online)
& (Join-Path $PSScriptRoot 'build_debug.ps1') @PSBoundParameters
exit $LASTEXITCODE
