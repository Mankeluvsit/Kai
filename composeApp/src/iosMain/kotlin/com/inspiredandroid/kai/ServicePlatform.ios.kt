package com.inspiredandroid.kai

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.inspiredandroid.kai.integrations.LocalServiceCommandResult

actual suspend fun executeLocalServiceCommand(command: String): LocalServiceCommandResult = LocalServiceCommandResult(
    success = false,
    output = "Local service control is only implemented on Android.",
)

@Composable
actual fun EmbeddedBrowserView(
    url: String,
    modifier: Modifier,
) {
    Text("Embedded browser is only implemented on Android.", modifier = modifier)
}
