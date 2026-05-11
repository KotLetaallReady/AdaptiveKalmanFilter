package com.katka.adaptivekalmanfilter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.katka.adaptivekalmanfilter.design_system.Background
import com.katka.adaptivekalmanfilter.design_system.KalmanTheme
import com.katka.adaptivekalmanfilter.design_system.Surface
import com.katka.adaptivekalmanfilter.navigation.AppNavigation
import dagger.hilt.android.AndroidEntryPoint


@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KalmanTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Background)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    // Единый NavHost — ViewModel создаётся здесь и живёт
                    // пока жив NavBackStackEntry корневого графа
                    AppNavigation()
                }
            }
        }
    }
}
