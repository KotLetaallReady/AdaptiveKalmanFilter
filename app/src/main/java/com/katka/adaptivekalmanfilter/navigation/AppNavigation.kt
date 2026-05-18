package com.katka.adaptivekalmanfilter.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.katka.adaptivekalmanfilter.ui.FilterScreen
import com.katka.adaptivekalmanfilter.ui.KalmanViewModel
import com.katka.adaptivekalmanfilter.ui.MenuScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.katka.adaptivekalmanfilter.ui.NeuralFilterScreen


// ── Route constants ───────────────────────────────────────────────────────────

object Routes {
    const val MENU   = "menu"
    const val FILTER = "filter"
    const val NEURAL_FILTER = "neural_filter"
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
                uiState                 = viewModel.uiState.collectAsState().value,
                onStartClassicalSession = { navController.navigate("filter") },
                onStartNeuralSession    = { navController.navigate("neural_filter") },
                onPermissionGranted     = viewModel::onPermissionGranted,
                onPermissionDenied      = viewModel::onPermissionDenied
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
        composable(Routes.NEURAL_FILTER) {
            NeuralFilterScreen(
                uiState              = viewModel.neuralUiState.collectAsState().value,
                onStartCollection    = viewModel::startDataCollection,
                onStopAndTrain       = viewModel::stopDataCollectionAndTrain,
                onStartNeuralSession = viewModel::startNeuralSession,
                onStopNeuralSession  = viewModel::stopNeuralSession,
                onReset              = viewModel::resetNeuralToReady,
                onRetrain            = viewModel::deleteTrainedNetwork
            )
        }
    }
}