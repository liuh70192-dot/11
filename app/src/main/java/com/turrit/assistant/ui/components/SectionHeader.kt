package com.turrit.assistant.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
}
