package com.katka.adaptivekalmanfilter.ui.menu_screen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katka.engine.neural.NetworkPersistenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context
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
            isNeuralReady = NetworkPersistenceManager.exists(context)
        )
    }
}