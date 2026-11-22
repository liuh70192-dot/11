package com.turrit.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.turrit.assistant.account.AccountRepository
import com.turrit.assistant.ui.TurritAssistantApp
import com.turrit.assistant.ui.theme.TurritAssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountRepository.initialize(applicationContext)
        setContent {
            TurritAssistantTheme {
                TurritAssistantApp()
            }
        }
    }
}
