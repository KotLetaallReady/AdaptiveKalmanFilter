package com.katka.adaptivekalmanfilter.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.katka.adaptivekalmanfilter.ui.comparison_screen.ComparisonScreen
import com.katka.adaptivekalmanfilter.ui.classical_filter.FilterScreen
import com.katka.adaptivekalmanfilter.ui.classical_filter.ClassicalFilterViewModel
import com.katka.adaptivekalmanfilter.ui.comparison_screen.ComparisonViewModel
import com.katka.adaptivekalmanfilter.ui.menu_screen.MenuScreen
import com.katka.adaptivekalmanfilter.ui.menu_screen.MenuViewModel
import com.katka.adaptivekalmanfilter.ui.neural_filter.NeuralFilterScreen
import com.katka.adaptivekalmanfilter.ui.neural_filter.NeuralFilterViewModel

object Routes {
    const val MENU       = "menu"
    const val FILTER     = "filter"
    const val NEURAL_FILTER = "neural_filter"
    const val COMPARISON = "comparison"
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.MENU
    ) {
        composable(Routes.MENU) {
            val menuVm: MenuViewModel = hiltViewModel()
            val uiState by menuVm.uiState.collectAsState()
            MenuScreen(
                uiState = uiState,
                onStartClassicalSession = { navController.navigate(Routes.FILTER) },
                onStartNeuralSession    = { navController.navigate(Routes.NEURAL_FILTER) },
                onStartComparison       = { navController.navigate(Routes.COMPARISON) },
                onPermissionGranted     = menuVm::onPermissionGranted,
                onPermissionDenied      = menuVm::onPermissionDenied
            )
        }

        composable(Routes.FILTER) {
            val vm: ClassicalFilterViewModel = hiltViewModel()
            val context = LocalContext.current

            FilterScreen(
                uiState = vm.uiState.collectAsState().value,
                onStart = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (granted) vm.startSession()
                    else         vm.onPermissionDenied()
                },
                onStop  = vm::stopSession,
                onReset = {
                    vm.resetToIdle()
                    navController.navigate(Routes.MENU) {
                        popUpTo(Routes.MENU) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.NEURAL_FILTER) {
            val vm: NeuralFilterViewModel = hiltViewModel()
            NeuralFilterScreen(
                uiState              = vm.neuralUiState.collectAsState().value,
                onStartCollection    = vm::startDataCollection,
                onStopAndTrain       = vm::stopDataCollectionAndTrain,
                onStartNeuralSession = vm::startNeuralSession,
                onStopNeuralSession  = vm::stopNeuralSession,
                onReset = {
                    vm.resetNeuralToReady()
                    navController.navigate(Routes.MENU) {
                        popUpTo(Routes.MENU) { inclusive = true }
                    }
                },
                onRetrain = vm::deleteTrainedNetwork
            )
        }

        composable(Routes.COMPARISON) {
            val vm: ComparisonViewModel = hiltViewModel()
            val context = LocalContext.current

            LaunchedEffect(Unit) {
                vm.refreshNetworkStatus()
            }

            ComparisonScreen(
                uiState = vm.comparisonUiState.collectAsState().value,
                onStart = vm::startComparisonSession,
                onStop  = vm::stopComparisonSession,
                onReset = {
                    vm.resetComparisonToIdle()
                    navController.navigate(Routes.MENU) {
                        popUpTo(Routes.MENU) { inclusive = true }
                    }
                },
                onShare = vm::shareComparisonCsv
            )
        }    }
}