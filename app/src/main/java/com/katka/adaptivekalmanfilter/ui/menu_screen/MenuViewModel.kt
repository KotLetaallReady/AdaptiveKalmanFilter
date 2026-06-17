package com.katka.adaptivekalmanfilter.ui.menu_screen

import androidx.lifecycle.ViewModel
import com.katka.engine.neural.SmootherRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class MenuUiState(
    val isPermissionGranted: Boolean = false,
    val isNeuralReady: Boolean = false
)

@HiltViewModel
class MenuViewModel @Inject constructor(
    private val smootherRepository: SmootherRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MenuUiState())
    val uiState: StateFlow<MenuUiState> = _uiState.asStateFlow()

    init {
        refreshNeuralReady()
    }

    fun onPermissionGranted() {
        _uiState.value = _uiState.value.copy(isPermissionGranted = true)
        refreshNeuralReady()
    }

    fun onPermissionDenied() {
        _uiState.value = _uiState.value.copy(isPermissionGranted = false, isNeuralReady = false)
    }

    fun refreshNeuralReady() {
        _uiState.value = _uiState.value.copy(
            isNeuralReady = smootherRepository.exists()
        )
    }
}