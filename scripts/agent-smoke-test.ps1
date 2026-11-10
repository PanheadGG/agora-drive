# Agent 协同链路冒烟测试（Windows PowerShell 5.1 兼容）
# 覆盖：企业发布悬赏 / 角色限制 / 个人授权扫描 / 内容解析证据 / 风险与搬运识别 / 复核 / 越权访问 / 管理员审计
# 用法：先启动后端，再执行 powershell -ExecutionPolicy Bypass -File scripts/agent-smoke-test.ps1
# 说明：脚本会临时创建一条测试悬赏，结束时自动停用。
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8080/api"
$script:fail = 0

function Check($name, $cond, $detail) {
    if ($cond) { Write-Output ("PASS  " + $name) }
    else { Write-Output ("FAIL  " + $name + "  -> " + ($detail | Out-String)); $script:fail++ }
}

function Call-Api {
    param([string]$Method, [string]$Endpoint, [string]$Token, $BodyObj)
    $curlArgs = @("-s", "-w", "__HTTP__%{http_code}", "-X", $Method, ($Base + $Endpoint))
    if ($Token) { $curlArgs += @("-H", "Authorization: Bearer $Token") }
    if ($null -ne $BodyObj) {
        $f = [System.IO.Path]::GetTempFileName()
        [System.IO.File]::WriteAllText($f, ($BodyObj | ConvertTo-Json -Depth 10 -Compress), (New-Object System.Text.UTF8Encoding($false)))
        $curlArgs += @("-H", "Content-Type: application/json", "--data-binary", "@$f")
    }
    $raw = (& curl.exe @curlArgs | Out-String).TrimEnd()
    $idx = $raw.LastIndexOf("__HTTP__")
    $code = if ($idx -ge 0) { [int]$raw.Substring($idx + 8) } else { 0 }
    $json = $null
    if ($idx -gt 0) { $json = $raw.Substring(0, $idx) | ConvertFrom-Json }
    return [pscustomobject]@{ status = $code; body = $json }
}

$ent = Call-Api "POST" "/auth/login" $null @{ username = "enterprise"; password = "ent12345" }
Check "enterprise login" ($ent.status -eq 200 -and $ent.body.data.user.role -eq 2) $ent.body
$entToken = $ent.body.data.token

$demo = Call-Api "POST" "/auth/login" $null @{ username = "demo"; password = "demo1234" }
$demoToken = $demo.body.data.token
Check "personal login" ($demo.status -eq 200 -and $demo.body.data.user.role -eq 0) $demo.body

$denied = Call-Api "POST" "/bounty" $demoToken @{ title = "个人用户发布"; rawDesc = "个人用户不应该能发布悬赏，这条请求必须被拒绝。" }
Check "personal cannot publish" ($denied.status -eq 403) $denied.body

$created = Call-Api "POST" "/bounty" $entToken @{ title = "悬赏 TEST｜自动化测试悬赏"; rawDesc = "需求：自己整理的徒步路线记录。要的内容：路线文档、沿途记录表格。文件类型：Word、表格、纯文本。不要：小红书搬运攻略。" }
Check "enterprise publish" ($created.status -eq 200 -and $created.body.data.structuredJson.Length -gt 50) $created.body
$testBountyId = $created.body.data.id

$bounties = Call-Api "GET" "/bounty" $entToken
Check "bounty list" (@($bounties.body.data).Count -ge 5) $bounties.body

# 扫描是异步的：POST 立即返回 taskId，这里轮询进度直到结束再取报告
function Start-Scan($token, $bountyId, $scopeIds) {
    $started = Call-Api "POST" "/agent/scan" $token @{ bountyId = $bountyId; scopeFileIds = $scopeIds }
    $taskId = $started.body.data.taskId
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        $p = Call-Api "GET" ("/agent/tasks/" + $taskId + "/progress") $token
        if ($p.body.data.done) { break }
        Start-Sleep -Milliseconds 300
    }
    return (Call-Api "GET" ("/agent/tasks/" + $taskId) $token)
}

$allBounties = Call-Api "GET" "/bounty" $entToken
$commuteBounty = ($allBounties.body.data | Where-Object { $_.title -like "*R-05*" } | Select-Object -First 1).id
$hikingBounty = ($allBounties.body.data | Where-Object { $_.title -like "*R-02*" } | Select-Object -First 1).id
Check "sample bounties available" ($commuteBounty -gt 0 -and $hikingBounty -gt 0) $allBounties.body.data

$folders = Call-Api "GET" "/agent/folders" $demoToken
Check "folder scope list" (@($folders.body.data).Count -ge 2) $folders.body
$tidy = ($folders.body.data | Where-Object { $_.name -eq "个人整理" }).id
$shots = ($folders.body.data | Where-Object { $_.name -eq "随手拍" }).id

$scan = Start-Scan $demoToken $commuteBounty @($tidy)
Check "scan runs" ($scan.status -eq 200 -and $scan.body.data.taskStatus -eq 2) $scan.body
$report = $scan.body.data
Check "scan scope recorded" ($report.scopeNames -contains "个人整理") $report.scopeNames
Check "content actually parsed" ($report.successParseCount -ge 6) $report.successParseCount
Check "parsed list has methods" (@($report.parsedList | Where-Object { $_.parseMethod -ne "未解析" }).Count -ge 6) $report.parsedList
Check "streaming parsers used" (@($report.parsedList | Where-Object { $_.parseMethod -like "*流式*" }).Count -ge 6) $report.parsedList
Check "recommendation with evidence" (@($report.recommendations | Where-Object { $_.evidenceText }).Count -ge 1) $report.recommendations
Check "phone risk detected" (@($report.highRisk | Where-Object { $_.riskTag -eq "手机号" }).Count -eq 1) $report.highRisk
Check "web-copied excluded" (@($report.highRisk | Where-Object { $_.riskTag -eq "网络搬运" }).Count -eq 1) $report.highRisk
Check "excluded files not recommended" (@($report.recommendations | Where-Object { $_.fileName -eq "微信账单导出.xlsx" -or $_.fileName -eq "长沙攻略下载.docx" }).Count -eq 0) $report.recommendations
Check "review tips present" (@($report.reviewTips).Count -ge 2) $report.reviewTips
Check "understanding restated" ($report.understanding.Length -gt 20) $report.understanding

$scanShots = Start-Scan $demoToken $hikingBounty @($shots)
$reportShots = $scanShots.body.data
Check "real content parsing (OCR/PDF/ZIP)" (
    @($reportShots.parsedList | Where-Object { $_.parseMethod -like "图片 OCR*" }).Count -ge 3 -and
    @($reportShots.parsedList | Where-Object { $_.parseMethod -like "受控解包*" }).Count -eq 1 -and
    @($reportShots.parsedList | Where-Object { $_.parseMethod -like "PDF 抽文本*" }).Count -eq 1
) $reportShots.parsedList
Check "pdf sensitive info detected" (@($reportShots.highRisk | Where-Object { $_.fileName -eq "体检单扫描件.pdf" }).Count -eq 1) $reportShots.highRisk
Check "image content matched by OCR" (@($reportShots.recommendations | Where-Object { $_.fileName -like "*.png" }).Count -ge 1) $reportShots.recommendations

$first = @($report.recommendations) | Select-Object -First 1
if ($first) {
    $reviewed = Call-Api "PUT" ("/agent/results/" + $first.id + "/review") $demoToken @{ review = 1; note = "确认可用，脱敏后交付" }
    Check "review confirm" ($reviewed.status -eq 200 -and $reviewed.body.data.userReview -eq 1) $reviewed.body
    $rejected = Call-Api "PUT" ("/agent/results/" + $first.id + "/review") $demoToken @{ review = 2; note = "内容不符合需求" }
    Check "review reject" ($rejected.body.data.userReview -eq 2) $rejected.body
} else {
    Check "review confirm" $false "没有可复核的推荐条目"
}

$tasks = Call-Api "GET" "/agent/tasks" $demoToken
Check "task history" (@($tasks.body.data).Count -ge 2) $tasks.body

$entRead = Call-Api "GET" ("/agent/tasks/" + $report.id) $entToken
Check "enterprise cannot read personal task" ($entRead.status -eq 403) $entRead.body
$otherRead = Call-Api "GET" ("/agent/tasks/" + $report.id) $entToken
Check "personal result not exposed" ($otherRead.body.data -eq $null) $otherRead.body

$admin = Call-Api "POST" "/auth/login" $null @{ username = "admin"; password = "admin123" }
$adminToken = $admin.body.data.token
$stats = Call-Api "GET" "/admin/stats" $adminToken
Check "admin stats include agent data" ($stats.body.data.bountyCount -ge 5 -and $stats.body.data.scanTaskCount -ge 2) $stats.body
$audit = Call-Api "GET" "/admin/scan-tasks" $adminToken
Check "admin audit list" (@($audit.body.data).Count -ge 2) $audit.body
$adminBounties = Call-Api "GET" "/admin/bounties" $adminToken
Check "admin bounty list" (@($adminBounties.body.data).Count -ge 5) $adminBounties.body
$stopped = Call-Api "PUT" ("/admin/bounties/" + $testBountyId + "/status?status=2") $adminToken
Check "admin stop bounty" ($stopped.body.data.status -eq 2) $stopped.body

Write-Output ""
Write-Output ("FAILURES: " + $script:fail)
Write-Output ("测试悬赏 id=" + $testBountyId + " 已停用，可在管理后台确认")
