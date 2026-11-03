# 构建前端（Vue3 + Element Plus）并把产物同步到后端静态目录，实现单端口访问 http://127.0.0.1:8080
Push-Location (Join-Path $PSScriptRoot "..")
try {
    if (-not (Test-Path "frontend/node_modules")) {
        pnpm --dir frontend install
    }
    pnpm --dir frontend run build

    Remove-Item "backend/resources/static" -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory "backend/resources/static" -Force | Out-Null
    Copy-Item "frontend/dist/*" "backend/resources/static" -Recurse -Force
    Write-Output "前端产物已同步到 backend/resources/static"
}
finally {
    Pop-Location
}
