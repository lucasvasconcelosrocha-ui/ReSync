package com.resync.app.ui.screens

import androidx.compose.runtime.Composable
import com.resync.app.ui.viewmodel.MainViewModel

/**
 * CustomBackupWizardScreen unificado com BackupWizardScreen.
 * Centraliza todo o fluxo de backup em um assistente consistente de 4 etapas,
 * eliminando código legado e duplicado.
 */
@Composable
fun CustomBackupWizardScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    onStartBackup: () -> Unit = {},
    onNavigateToActive: () -> Unit = {}
) {
    BackupWizardScreen(
        viewModel = viewModel,
        onBack = onBack,
        onFinished = onFinished,
        onStartBackup = onStartBackup,
        onNavigateToActive = onNavigateToActive
    )
}

