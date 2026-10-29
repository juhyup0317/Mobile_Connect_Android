package com.example.mobile_connect_android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mobile_connect_android.domain.AppModel
import com.example.mobile_connect_android.ui.Brand
import com.example.mobile_connect_android.ui.LoginScreen
import com.example.mobile_connect_android.ui.MainScreen
import com.example.mobile_connect_android.ui.theme.Mobile_connect_androidTheme

import androidx.activity.SystemBarStyle

class MainActivity : ComponentActivity() {
    private var model: AppModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appModel: AppModel = viewModel()
            model = appModel

            LaunchedEffect(Unit) {
                appModel.restoreSession()
                handleIntent(intent, appModel)
            }

            val checking by appModel.checking.collectAsState()
            val signedIn by appModel.signedIn.collectAsState()
            val prefs by appModel.prefs.collectAsState()
            
            val isDarkTheme = when (prefs.theme) {
                com.example.mobile_connect_android.data.Prefs.Theme.DARK -> true
                com.example.mobile_connect_android.data.Prefs.Theme.LIGHT -> false
                com.example.mobile_connect_android.data.Prefs.Theme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            Mobile_connect_androidTheme(darkTheme = isDarkTheme) {
                Crossfade(targetState = Pair(checking, signedIn), label = "app_state_crossfade") { (isChecking, isSignedIn) ->
                    when {
                        isChecking -> SplashScreen()
                        isSignedIn -> MainScreen(model = appModel)
                        else -> LoginScreen(model = appModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model?.let { handleIntent(intent, it) }
    }

    private fun handleIntent(intent: Intent?, model: AppModel) {
        val termId = intent?.getStringExtra("open_terminal_id")
        if (!termId.isNullOrEmpty()) {
            model.openTerminal(termId)
        }
    }
}

/** The launch screen: the product name and the company behind it, on black. */
@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Brand.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Powered by ${Brand.company}", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}
