$targets = @(
    "$env:USERPROFILE\.gradle\caches",
    "$env:USERPROFILE\.gradle\daemon",
    "$env:USERPROFILE\.android\cache"
)

$initialSize = 0
foreach ($t in $targets) {
    if (Test-Path $t) {
        $size = (Get-ChildItem -Path $t -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
        $initialSize += $size
        Remove-Item -Path "$t\*" -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Start-Sleep -Seconds 2

$finalSize = 0
foreach ($t in $targets) {
    if (Test-Path $t) {
        $size = (Get-ChildItem -Path $t -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
        $finalSize += $size
    }
}

$freed = $initialSize - $finalSize
$freedGb = [math]::Round($freed / 1GB, 2)
Write-Output "Espacio liberado: $freedGb GB"
