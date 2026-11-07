# DeepSeek 接入冒烟测试：验证「悬赏结构化」与「匹配判定」确实走了 LLM 链路
# 用法：后端已启动（配置了 DEEPSEEK_API_KEY，或指向 scripts/mock-deepseek.mjs）后执行
#   powershell -File scripts/llm-smoke-test.ps1
$ErrorActionPreference = "Stop"
$Base = "http://127.0.0.1:8080/api"
$script:fail = 0

function Check($name, $cond, $detail) {
    if ($cond) { Write-Output ("PASS  " + $name) }
    else { Write-Output ("FAIL  " + $name + "  -> " + ($detail | Out-String)); $script:fail++ }
}

function Call($method, $path, $token, $obj) {
    $curlArgs = @("-s", "-w", "__HTTP__%{http_code}", "-X", $method, ($Base + $path))
    if ($token) { $curlArgs += @("-H", "Authorization: Bearer $token") }
    if ($null -ne $obj) {
        $f = [System.IO.Path]::GetTempFileName()
        [System.IO.File]::WriteAllText($f, ($obj | ConvertTo-Json -Depth 10 -Compress), (New-Object System.Text.UTF8Encoding($false)))
        $curlArgs += @("-H", "Content-Type: application/json", "--data-binary", "@$f")
    }
    $raw = (& curl.exe @curlArgs | Out-String).TrimEnd()
    $idx = $raw.LastIndexOf("__HTTP__")
    $code = if ($idx -ge 0) { [int]$raw.Substring($idx + 8) } else { 0 }
    $json = $null
    if ($idx -gt 0) { $json = $raw.Substring(0, $idx) | ConvertFrom-Json }
    return [pscustomobject]@{ status = $code; body = $json }
}

$ent = Call "POST" "/auth/login" $null @{ username = "enterprise"; password = "ent12345" }
$demo = Call "POST" "/auth/login" $null @{ username = "demo"; password = "demo1234" }
$entToken = $ent.body.data.token
$demoToken = $demo.body.data.token

$status = Call "GET" "/agent/llm-status" $demoToken
Check "llm-status readable" ($status.status -eq 200) $status.body
$available = $status.body.data.llm.available
Check "deepseek configured" ($available -eq $true) $status.body.data.llm
if (-not $available) {
    Write-Output "提示：管理后台还没有启用的模型平台，请先在「模型管理」新增平台（或设置 DEEPSEEK_API_KEY 播种）"
    Write-Output ("FAILURES: " + $script:fail)
    exit 2
}

# 先用管理端连通性测试确认真有可用平台，Key 失效时直接给出明确提示而不是一串断言失败
$adminForCheck = Call "POST" "/auth/login" $null @{ username = "admin"; password = "admin123" }
$adminToken = $adminForCheck.body.data.token
$providers = (Call "GET" "/admin/ai-providers" $adminToken).body.data
$healthyIds = @()
foreach ($p in $providers) {
    if ($p.status -ne 1) { continue }
    $t = (Call "POST" ("/admin/ai-providers/" + $p.id + "/test") $adminToken).body.data
    if ($t.ok) { $healthyIds += $p.id; Write-Output ("平台可用：" + $p.name + "（" + $p.model + "，" + $t.latencyMs + " ms）") }
    else { Write-Output ("平台不可用：" + $p.name + " -> " + $t.message) }
}
if ($healthyIds.Count -eq 0) {
    Write-Output ""
    Write-Output "所有已启用的模型平台都无法连通（常见原因：API Key 过期/额度用尽/网络不通）。"
    Write-Output "请登录管理后台 → 模型管理 → 编辑对应平台并填入新的 API Key 后重试。"
    Write-Output "（此时 Agent 会自动降级为本地规则判定，扫描功能仍可用）"
    exit 2
}

$created = Call "POST" "/bounty" $entToken @{
    title = "悬赏 LLM｜家常菜原创做法素材"
    rawDesc = "需求：自己整理的家常菜做法。要的内容：菜谱做法文档、食材与步骤记录表格、自己拍的成品照片。文件类型：Word、表格、图片。不要：网上复制的网红菜谱、外卖菜单截图。"
}
Check "bounty created by llm" ($created.status -eq 200 -and $created.body.data.structuredBy -eq "llm") $created.body
$bountyId = $created.body.data.id
$structured = $created.body.data.structuredJson | ConvertFrom-Json
Check "llm keywords present" (@($structured.matchKeywords).Count -ge 4) $structured.matchKeywords
$status2 = Call "GET" "/agent/llm-status" $demoToken
Check "llm call counted" ($status2.body.data.llm.calls -ge 1) $status2.body.data.llm

$folders = Call "GET" "/agent/folders" $demoToken
$tidy = ($folders.body.data | Where-Object { $_.name -eq "个人整理" }).id
$scan = Call "POST" "/agent/scan" $demoToken @{ bountyId = $bountyId; scopeFileIds = @($tidy) }
Check "scan ok" ($scan.status -eq 200) $scan.body
$report = $scan.body.data
$llmResults = @($report.parsedList | Where-Object { $_.judgeSource -eq "llm" })
Check "llm judged files" ($llmResults.Count -ge 1) $report.parsedList
Check "report flags llm usage" ($report.llmUsed -eq $true -and $report.llmJudgedCount -eq $llmResults.Count) $report.llmJudgedCount
Check "llm evidence grounded" (@($llmResults | Where-Object { $_.evidenceText }).Count -ge 1) $llmResults

$riskResults = @($report.highRisk)
Check "sensitive files still excluded locally" (@($riskResults | Where-Object { $_.judgeSource -eq "rule" }).Count -ge 1) $riskResults

Write-Output "--- LLM 判定明细"
$llmResults | ForEach-Object { Write-Output ("  [" + $_.matchLabel + "] " + $_.fileName + " | " + $_.judgeReason + " | 证据：" + $_.evidenceText) }

$stopped = Call "PUT" ("/admin/bounties/" + $bountyId + "/status?status=2") $entToken
Write-Output ""
Write-Output ("FAILURES: " + $script:fail)
Write-Output ("测试悬赏 id=" + $bountyId + " 已停用")
