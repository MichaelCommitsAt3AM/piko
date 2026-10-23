package com.piko.agent

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.piko.agent.ui.PikoApp
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.theme.PikoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PikoViewModel by viewModels()

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("openAssistant", false)) viewModel.openAssistant.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.openAssistant.value = intent.getBooleanExtra("openAssistant", false)
        enableEdgeToEdge()
        setContent {
            PikoTheme {
                PikoApp(viewModel = viewModel)
            }
        }
    }
}
