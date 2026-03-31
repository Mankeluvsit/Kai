package com.inspiredandroid.kai

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.inspiredandroid.kai.integrations.LocalServiceCommandResult
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import java.util.concurrent.TimeUnit

actual suspend fun executeLocalServiceCommand(command: String): LocalServiceCommandResult = withContext(Dispatchers.IO) {
    val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
    val sandboxState = sandboxManager.state.value

    if (sandboxState is SandboxState.Ready) {
        val result = sandboxManager.createProotExecutor().execute(
            command = command,
            timeoutSeconds = 60,
        )
        val stdout = (result["stdout"] as? String).orEmpty()
        val stderr = (result["stderr"] as? String).orEmpty()
        val error = (result["error"] as? String).orEmpty()
        val combinedOutput = listOf(stdout, stderr, error)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        return@withContext LocalServiceCommandResult(
            success = result["success"] as? Boolean == true,
            exitCode = result["exit_code"] as? Int,
            output = combinedOutput.ifBlank { "Command finished in Linux sandbox." },
        )
    }

    runCatching {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching LocalServiceCommandResult(
                success = false,
                output = "Command timed out after 60 seconds.",
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        LocalServiceCommandResult(
            success = process.exitValue() == 0,
            exitCode = process.exitValue(),
            output = output,
        )
    }.getOrElse { error ->
        LocalServiceCommandResult(
            success = false,
            output = error.message ?: "Command failed.",
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun EmbeddedBrowserView(
    url: String,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl(url)
            }
        },
        update = { webView ->
            if (webView.url != url) {
                webView.loadUrl(url)
            }
        },
    )
}
