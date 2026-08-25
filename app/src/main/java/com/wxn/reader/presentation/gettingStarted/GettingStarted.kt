package com.wxn.reader.presentation.gettingStarted

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wxn.reader.navigation.LocalNavController
import com.wxn.reader.R
import com.wxn.reader.navigation.Screens
import com.wxn.reader.presentation.gettingStarted.components.StorageAccessDialog

@Composable
fun GettingStartedScreen(
    viewModel: GettingStartedViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current

    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val isButtonsEnabled by viewModel.isButtonsEnabled.collectAsStateWithLifecycle()
    var showSelectDirectoryDialog by remember { mutableStateOf(false) }
//    var showStoragePermissionDialog by remember { mutableStateOf(false) }

    val getDirectoryPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val prefs = appPreferences
            prefs ?: return@rememberLauncherForActivityResult
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                val updatedDirectories = prefs.scanDirectories + it.toString()
                viewModel.updateAppPreferences(
                    prefs.copy(
                        isFirstLaunch = false,
                        scanDirectories = updatedDirectories
                    )
                )
                navController.popBackStack()
                navController.navigate(Screens.HomeScreen.route)
            }
        }

    Scaffold { innerPadding ->
        GettingStartedContent(
            buttonsEnabled = isButtonsEnabled,
            onSelectDirectory = { showSelectDirectoryDialog = true },
            onSkip = {
                viewModel.skipGettingStarted()
                navController.popBackStack()
                navController.navigate(Screens.HomeScreen.route)
            },
        )

        if (showSelectDirectoryDialog) {
            StorageAccessDialog(
                title = stringResource(R.string.select_scan_directory),
                message = stringResource(R.string.please_select_a_directory_where_your_ebooks_are_stored_you_can_edit_this_later_in_settings),
                confirmButtonText = stringResource(R.string.select),
                onConfirm = {
                    if (appPreferences != null) {
                        showSelectDirectoryDialog = false
                        getDirectoryPermissionLauncher.launch(null)
                    }
                },
                onDismiss = { showSelectDirectoryDialog = false },
            )
        }
    }
}

