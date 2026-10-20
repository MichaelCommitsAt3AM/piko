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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            PikoTheme {
                PikoApp(viewModel = viewModel)
            }
        }
    }
}
