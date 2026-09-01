param(
  [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [switch]$Execute
)

$ErrorActionPreference = "Stop"

function Resolve-ControlledPath([string]$PathText) {
  return [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $PathText))
}

function Remove-ControlledChildren([string]$RelativePath, [string]$Category) {
  $root = Resolve-ControlledPath $RelativePath
  if (-not (Test-Path -LiteralPath $root)) {
    Write-Host "[SKIP] $Category not found: $root"
    return
  }

  $projectFull = [System.IO.Path]::GetFullPath($ProjectRoot)
  if (-not $root.StartsWith($projectFull, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refuse to clean outside project root: $root"
  }

  Write-Host "[SCAN] $Category => $root"
  $children = Get-ChildItem -LiteralPath $root -Force
  foreach ($child in $children) {
    $target = [System.IO.Path]::GetFullPath($child.FullName)
    if (-not $target.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
      throw "Refuse to clean path outside controlled root: $target"
    }
    if ($Execute) {
      Remove-Item -LiteralPath $target -Recurse -Force
      Write-Host "[DELETED] $Category => $target"
    } else {
      Write-Host "[DRY-RUN] $Category => $target"
    }
  }
}

Write-Host "Workflow Platform result-artifact cleanup"
Write-Host "ProjectRoot: $ProjectRoot"
Write-Host "Mode: $(if ($Execute) { 'EXECUTE' } else { 'DRY-RUN' })"
Write-Host ""

Remove-ControlledChildren "storage\dev\validation-cache" "Java validation image cache / recognition samples"
Remove-ControlledChildren "storage\dev\results" "Java workflow result directory"
Remove-ControlledChildren "storage\dev\logs" "Java controlled result logs"
Remove-ControlledChildren "service-python\app\storage\results" "Python validation result JSON"
Remove-ControlledChildren "service-python\app\storage\result-samples" "Python recognition sample images"
Remove-ControlledChildren ".runtime\local-server\storage\validation-cache" "Local-server validation image cache"
Remove-ControlledChildren ".runtime\local-server\storage\results" "Local-server Java result directory"
Remove-ControlledChildren ".runtime\local-server\storage\python\results" "Local-server Python validation result JSON"
Remove-ControlledChildren ".runtime\local-server\storage\python\result-samples" "Local-server Python recognition sample images"
Remove-ControlledChildren ".runtime\local-server\logs\java" "Local-server Java logs"
Remove-ControlledChildren ".runtime\local-server\logs\python" "Local-server Python logs"

Write-Host ""
Write-Host "Done. Formal model assets and dataset assets were not touched."
if (-not $Execute) {
  Write-Host "Re-run with -Execute to delete the listed artifacts."
}
