package com.inspiredandroid.kai

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.inspiredandroid.kai.integrations.LocalServiceCommandResult

expect suspend fun executeLocalServiceCommand(command: String): LocalServiceCommandResult

@Composable
expect fun EmbeddedBrowserView(
    url: String,
    modifier: Modifier = Modifier,
)
