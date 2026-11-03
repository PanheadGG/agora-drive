# 后端冒烟测试（Windows PowerShell 5.1 兼容，使用 curl.exe 发送 multipart）
# 覆盖 docs/技术选型.md 需求：登录注册 / 浏览 / 上传(小文件+分片) / 容量 / 回收站 / 管理后台
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8080/api"
$script:fail = 0

function Check($name, $cond, $detail) {
    if ($cond) { Write-Output ("PASS  " + $name) }
    else { Write-Output ("FAIL  " + $name + "  -> " + ($detail | Out-String)); $script:fail++ }
}

function Write-JsonFile($obj) {
    $p = [System.IO.Path]::GetTempFileName()
    $json = $obj | ConvertTo-Json -Depth 10 -Compress
    [System.IO.File]::WriteAllText($p, $json, (New-Object System.Text.UTF8Encoding($false)))
    return $p
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Endpoint,
        [string]$Token,
        $BodyObj,
        [string[]]$FormArgs = @()
    )
    $curlArgs = @("-s", "-w", "__HTTP__%{http_code}", "-X", $Method, ($Base + $Endpoint))
    if ($Token) { $curlArgs += @("-H", "Authorization: Bearer $Token") }
    if ($null -ne $BodyObj) {
        $bf = Write-JsonFile $BodyObj
        $curlArgs += @("-H", "Content-Type: application/json", "--data-binary", "@$bf")
    }
    if ($FormArgs.Count -gt 0) { $curlArgs += $FormArgs }
    $raw = & curl.exe @curlArgs
    $text = ($raw | Out-String).TrimEnd()
    $idx = $text.LastIndexOf("__HTTP__")
    $code = if ($idx -ge 0) { [int]$text.Substring($idx + 8) } else { 0 }
    $json = $null
    if ($idx -gt 0) { $json = $text.Substring(0, $idx) | ConvertFrom-Json }
    return [pscustomobject]@{ status = $code; body = $json }
}

$stamp = Get-Date -Format "HHmmss"
$userName = "tester$stamp"
$tmp = Join-Path $env:TEMP ("acd-" + $stamp)
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

$reg = Invoke-Api "POST" "/auth/register" $null @{ username = $userName; password = "pass1234" }
Check "register" ($reg.status -eq 200 -and $reg.body.data.token.Length -gt 20) $reg.body
$token = $reg.body.data.token

$login = Invoke-Api "POST" "/auth/login" $null @{ username = $userName; password = "pass1234" }
Check "login" ($login.status -eq 200 -and $login.body.code -eq 0) $login.body

$noToken = Invoke-Api "GET" "/files" $null
Check "no-token rejected" ($noToken.status -eq 401) $noToken

$folder = Invoke-Api "POST" "/files/folder" $token @{ parentId = $null; name = "文档" }
Check "create folder" ($folder.status -eq 200 -and $folder.body.data.folder) $folder.body
$folderId = $folder.body.data.id

$txt = Join-Path $tmp "hello.txt"
[System.IO.File]::WriteAllText($txt, "阿里云盘 Demo 文本预览", (New-Object System.Text.UTF8Encoding($false)))
$up = Invoke-Api "POST" "/upload/small" $token $null @("-F", "file=@$txt", "-F", "parentId=$folderId")
Check "small upload" ($up.status -eq 200 -and $up.body.data.size -gt 0) $up.body
$txtFileId = $up.body.data.id
$txtSha = $up.body.data.sha256

$list = Invoke-Api "GET" ("/files?parentId=" + $folderId) $token
Check "list folder" (@($list.body.data.items).Count -eq 1) $list.body
Check "breadcrumb" ($list.body.data.breadcrumb[0].name -eq "文档") $list.body.data.breadcrumb

$content = & curl.exe -s -H "Authorization: Bearer $token" "$Base/files/$txtFileId/content"
Check "text preview" (($content | Out-String) -match "Demo") $content

$big = Join-Path $tmp "big.bin"
$bigLen = 12 * 1024 * 1024
$bytes = New-Object byte[] $bigLen
(New-Object System.Random 42).NextBytes($bytes)
[System.IO.File]::WriteAllBytes($big, $bytes)

$init = Invoke-Api "POST" "/upload/chunk/init" $token @{ fileName = "big.bin"; totalSize = $bigLen; parentId = $folderId }
Check "chunk init" ($init.status -eq 200 -and -not $init.body.data.instant) $init.body
$taskId = $init.body.data.taskId
$chunkSize = [long]$init.body.data.chunkSize
$totalChunk = [int]$init.body.data.totalChunk

$fs = [System.IO.File]::OpenRead($big)
try {
    for ($i = 0; $i -lt $totalChunk; $i++) {
        $offset = [long]$i * $chunkSize
        $len = [int][Math]::Min($chunkSize, $bigLen - $offset)
        $buf = New-Object byte[] $len
        $fs.Position = $offset
        $null = $fs.Read($buf, 0, $len)
        $partPath = Join-Path $tmp ("part-" + $i)
        [System.IO.File]::WriteAllBytes($partPath, $buf)
        $resp = Invoke-Api "POST" ("/upload/chunk?taskId=$taskId&index=$i") $token $null @("-F", "chunk=@$partPath")
    }
} finally { $fs.Close() }
Check "chunk upload progress" ([int]$resp.body.data.finishedChunk -eq $totalChunk) $resp.body

$merged = Invoke-Api "POST" "/upload/chunk/merge" $token @{ taskId = $taskId; parentId = $folderId; fileName = "big.bin" }
Check "chunk merge" ($merged.status -eq 200 -and [long]$merged.body.data.size -eq $bigLen) $merged.body
$bigId = $merged.body.data.id

$quota = Invoke-Api "GET" "/files/quota" $token
Check "quota accounting" ([long]$quota.body.data.usedSize -eq ($bigLen + (Get-Item $txt).Length)) $quota.body

$headers = & curl.exe -s -D - -o NUL "$Base/files/$bigId/content?download=true" -H "Authorization: Bearer $token"
Check "download header" (($headers | Out-String) -match "attachment") $headers

$instant = Invoke-Api "POST" "/upload/chunk/init" $token @{ fileName = "hello-copy.txt"; totalSize = (Get-Item $txt).Length; parentId = $folderId; sha256 = $txtSha }
Check "instant upload" ($instant.status -eq 200 -and $instant.body.data.instant -eq $true) $instant.body
$copyId = $instant.body.data.file.id
Invoke-Api "DELETE" "/files/$copyId" $token | Out-Null
Invoke-Api "DELETE" "/files/recycle/$copyId" $token | Out-Null
$quotaCopy = Invoke-Api "GET" "/files/quota" $token
Check "instant copy removed" ([long]$quotaCopy.body.data.usedSize -eq ($bigLen + (Get-Item $txt).Length)) $quotaCopy.body

Invoke-Api "DELETE" "/files/$txtFileId" $token | Out-Null
$rec = Invoke-Api "GET" "/files/recycle" $token
Check "recycle contains txt" (@($rec.body.data | Where-Object { $_.id -eq $txtFileId }).Count -eq 1) $rec.body
Invoke-Api "POST" "/files/recycle/$txtFileId/restore" $token | Out-Null
$list2 = Invoke-Api "GET" ("/files?parentId=" + $folderId) $token
Check "restored to folder" (@($list2.body.data.items | Where-Object { $_.id -eq $txtFileId }).Count -eq 1) $list2.body

Invoke-Api "DELETE" "/files/$txtFileId" $token | Out-Null
Invoke-Api "DELETE" "/files/recycle/$txtFileId" $token | Out-Null
$quota2 = Invoke-Api "GET" "/files/quota" $token
Check "purge frees quota" ([long]$quota2.body.data.usedSize -eq $bigLen) $quota2.body

Invoke-Api "DELETE" "/files/$folderId" $token | Out-Null
$rec2 = Invoke-Api "GET" "/files/recycle" $token
Check "folder recycled recursively" (@($rec2.body.data | Where-Object { $_.id -eq $folderId }).Count -eq 1) $rec2.body
Invoke-Api "DELETE" "/files/recycle" $token | Out-Null
$quota3 = Invoke-Api "GET" "/files/quota" $token
Check "empty recycle frees quota" ([long]$quota3.body.data.usedSize -eq 0) $quota3.body

$admin = Invoke-Api "POST" "/auth/login" $null @{ username = "admin"; password = "admin123" }
$adminToken = $admin.body.data.token
$stats = Invoke-Api "GET" "/admin/stats" $adminToken
Check "admin stats" ($stats.status -eq 200 -and $stats.body.data.userCount -ge 2) $stats.body
$users = Invoke-Api "GET" ("/admin/users?keyword=" + $userName) $adminToken
Check "admin user search" (@($users.body.data).Count -eq 1) $users.body
$uid = $users.body.data[0].id
$q = Invoke-Api "PUT" ("/admin/users/$uid/quota?totalQuota=1073741824") $adminToken
Check "admin set quota" ([long]$q.body.data.totalQuota -eq 1073741824) $q.body
$st = Invoke-Api "PUT" ("/admin/users/$uid/status?status=0") $adminToken
Check "admin disable user" ([int]$st.body.data.status -eq 0) $st.body
$denied = Invoke-Api "POST" "/auth/login" $null @{ username = $userName; password = "pass1234" }
Check "disabled user cannot login" ($denied.status -eq 403) $denied
$nonAdmin = Invoke-Api "GET" "/admin/stats" $token
Check "non-admin blocked" ($nonAdmin.status -eq 403) $nonAdmin

# 配额上限校验：1KB 配额的账号无法上传 4KB 文件
$quotaUser = "quota$stamp"
$quotaReg = Invoke-Api "POST" "/auth/register" $null @{ username = $quotaUser; password = "pass1234" }
$quotaToken = $quotaReg.body.data.token
$quotaUsers = Invoke-Api "GET" ("/admin/users?keyword=" + $quotaUser) $adminToken
$quotaUid = $quotaUsers.body.data[0].id
Invoke-Api "PUT" ("/admin/users/$quotaUid/quota?totalQuota=1024") $adminToken | Out-Null
$bigTxt = Join-Path $tmp "four-k.txt"
[System.IO.File]::WriteAllText($bigTxt, ("x" * 4096), (New-Object System.Text.UTF8Encoding($false)))
$over = Invoke-Api "POST" "/upload/small" $quotaToken $null @("-F", "file=@$bigTxt")
Check "quota limit enforced" ($over.status -eq 400) $over.body

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
Write-Output ""
Write-Output ("FAILURES: " + $script:fail)
