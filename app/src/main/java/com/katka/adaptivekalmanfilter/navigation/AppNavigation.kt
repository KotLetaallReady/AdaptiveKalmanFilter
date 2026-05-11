package com.katka.adaptivekalmanfilter.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.katka.adaptivekalmanfilter.ui.FilterScreen
import com.katka.adaptivekalmanfilter.ui.KalmanViewModel
import com.katka.adaptivekalmanfilter.ui.MenuScreen


// ── Route constants ───────────────────────────────────────────────────────────

object Routes {
    const val MENU   = "menu"
    const val FILTER = "filter"
}

// ── NavHost ───────────────────────────────────────────────────────────────────

/**
 * Единый [NavHost] приложения.
 *
 * ViewModel живёт в одном экземпляре на весь NavHost (передаётся как параметр),
 * поэтому состояние сохраняется при навигации между экранами.
 *
 * Навигационная логика:
 *  MENU  ─► FILTER   когда стартует сессия (Running)
 *  FILTER ─► MENU    когда сессия сброшена (Idle)
 */
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    viewModel: KalmanViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NavHost(
        navController    = navController,
        startDestination = Routes.MENU
    ) {
        // ── Экран меню ────────────────────────────────────────────────────────
        composable(Routes.MENU) {
            MenuScreen(
                uiState          = uiState,
                onStartSession   = {
                    viewModel.startSession()
                    navController.navigate(Routes.FILTER) {
                        // Оставляем Menu в back-стеке — кнопка «назад» работает
                        launchSingleTop = true
                    }
                },
                onPermissionGranted = viewModel::onPermissionGranted,
                onPermissionDenied  = viewModel::onPermissionDenied
            )
        }

        // ── Экран фильтра ─────────────────────────────────────────────────────
        composable(Routes.FILTER) {
            FilterScreen(
                uiState = uiState,
                onStop  = viewModel::stopSession,
                onReset = {
                    viewModel.resetToIdle()
                    navController.navigate(Routes.MENU) {
                        // Очищаем back-стек до Menu, чтобы не накапливались экраны
                        popUpTo(Routes.MENU) { inclusive = true }
                    }
                }
            )
        }
    }
}