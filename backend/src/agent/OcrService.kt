package com.pgigi.agentclouddisk.agent

import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 图片文字识别：调用 Windows 自带的 Windows.Media.Ocr 引擎（离线、无需第三方服务）。
 * 不可用时返回 null，由调用方如实标记为「未解析」，不编造内容。
 */
@Service
class OcrService(environment: Environment) {

    private val log = LoggerFactory.getLogger(OcrService::class.java)
    private val enabled: Boolean = environment.getProperty("app.ocr.enabled", Boolean::class.java, true) ?: true
    private val timeoutSeconds: Long = environment.getProperty("app.ocr.timeout-seconds", Long::class.java, 90L) ?: 90L

    @Volatile
    private var scriptPath: Path? = null

    @Volatile
    private var available: Boolean? = null

    @Volatile
    private var lastError: String? = null

    fun status(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "available" to available,
        "engine" to "Windows.Media.Ocr",
        "lastError" to lastError,
    )

    /** 返回识别出的文字；识别不到或引擎不可用时返回 null */
    fun recognize(imagePath: Path): String? {
        if (!enabled) return null
        if (!Files.exists(imagePath)) return null
        val script = runCatching { ensureScript() }.getOrElse {
            lastError = it.message
            null
        } ?: return null

        return runCatching {
            val process = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-Path", imagePath.toString(),
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                lastError = "ocr timeout"
                available = false
                return null
            }
            val text = output.lineSequence()
                .firstOrNull { it.startsWith(TEXT_PREFIX) }
                ?.removePrefix(TEXT_PREFIX)
                ?.trim()
            if (text == null) {
                lastError = output.take(300)
                available = false
                return null
            }
            available = true
            normalize(text).takeIf { it.isNotBlank() }
        }.getOrElse {
            lastError = it.message
            log.warn("ocr failed: {}", it.message)
            null
        }
    }

    /** Windows OCR 会把中文逐字用空格分开，这里去掉汉字之间的空白，否则关键词匹配全废 */
    private fun normalize(text: String): String {
        val joined = Regex("""(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])""").replace(text, "")
        val collapsed = Regex("""(?<=[\u4e00-\u9fff])\s+(?=[A-Za-z0-9])|(?<=[A-Za-z0-9])\s+(?=[\u4e00-\u9fff])""").replace(joined, "")
        return Regex("""\s{2,}""").replace(collapsed, " ").trim()
    }

    private fun ensureScript(): Path? {
        scriptPath?.let { if (Files.exists(it)) return it }
        if (!enabled) return null
        val dir = Files.createTempDirectory("acd-ocr")
        val script = dir.resolve("ocr.ps1")
        Files.writeString(script, SCRIPT_TEMPLATE.replace('§', '$'), Charsets.UTF_8)
        scriptPath = script
        return script
    }

    companion object {
        private const val TEXT_PREFIX = "TEXT>>>"

        private val SCRIPT_TEMPLATE = """
param([string]§Path)
§ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType=WindowsRuntime] | Out-Null
§engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if (§engine -eq §null) {
  Write-Output 'TEXT>>>'
  exit 1
}
§asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  §_.Name -eq 'AsTask' -and §_.GetParameters().Count -eq 1 -and §_.GetParameters()[0].ParameterType.Name -like 'IAsyncOperation*'
})[0]
function Await(§op, §type) {
  §task = §asTask.MakeGenericMethod(§type).Invoke(§null, @(§op))
  §task.Wait()
  return §task.Result
}
try {
  §file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync(§Path)) ([Windows.Storage.StorageFile])
  §stream = Await (§file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
  §decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync(§stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
  §bitmap = Await (§decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
  §result = Await (§engine.RecognizeAsync(§bitmap)) ([Windows.Media.Ocr.OcrResult])
  Write-Output ('TEXT>>>' + §result.Text)
} catch {
  Write-Output ('TEXT>>>')
  Write-Output ('ERROR>>>' + §_.Exception.Message)
  exit 1
}
""".trimIndent()
    }
}
