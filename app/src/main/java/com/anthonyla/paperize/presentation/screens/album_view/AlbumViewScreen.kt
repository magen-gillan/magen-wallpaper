package com.anthonyla.paperize.presentation.screens.album_view

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import android.net.Uri
import com.anthonyla.paperize.R
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.presentation.common.components.AddAlbumAnimatedFab
import com.anthonyla.paperize.presentation.screens.album_view.components.AlbumViewTopBar
import com.anthonyla.paperize.presentation.screens.album_view.components.FolderItem
import com.anthonyla.paperize.presentation.screens.album_view.components.ImportProgressDialog
import com.anthonyla.paperize.presentation.screens.album_view.components.SortBottomSheet
import com.anthonyla.paperize.presentation.screens.album_view.components.SortOption
import com.anthonyla.paperize.presentation.screens.album_view.components.WallpaperItem
import com.anthonyla.paperize.presentation.theme.AppGrid
import com.anthonyla.paperize.presentation.theme.AppSpacing

@Composable
fun AlbumViewScreen(
    onBackClick: () -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onNavigateToWallpaperView: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumViewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lazyListState = rememberLazyGridState()

    val album by viewModel.album.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val wallpapers by viewModel.wallpapers.collectAsStateWithLifecycle()

    // Selection state
    val selectedWallpapers by viewModel.selectedWallpapers.collectAsStateWithLifecycle()
    val selectedFolders by viewModel.selectedFolders.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionMode.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val selectedCount = selectedWallpapers.size + selectedFolders.size

    // Check if all items are selected
    val totalItemsCount = wallpapers.size + folders.size
    val allSelected = selectedCount == totalItemsCount && totalItemsCount > 0

    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var showDeleteAlbumDialog by rememberSaveable { mutableStateOf(false) }
    var sortOption by rememberSaveable { mutableStateOf(SortOption.DATE_ADDED_DESC) }

    // Handle back press when in selection mode
    BackHandler(enabled = isSelectionMode) {
        viewModel.clearSelection()
    }

    // Sort wallpapers and folders
    val sortedFolders = remember(folders, sortOption) {
        when (sortOption) {
            SortOption.NAME_ASC -> folders.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> folders.sortedByDescending { it.name.lowercase() }
            SortOption.DATE_ADDED_ASC -> folders.sortedBy { it.addedAt }
            SortOption.DATE_ADDED_DESC -> folders.sortedByDescending { it.addedAt }
            SortOption.DATE_MODIFIED_ASC -> folders.sortedBy { it.dateModified }
            SortOption.DATE_MODIFIED_DESC -> folders.sortedByDescending { it.dateModified }
        }
    }

    val sortedWallpapers = remember(wallpapers, sortOption) {
        when (sortOption) {
            SortOption.NAME_ASC -> wallpapers.sortedBy { it.fileName.lowercase() }
            SortOption.NAME_DESC -> wallpapers.sortedByDescending { it.fileName.lowercase() }
            SortOption.DATE_ADDED_ASC -> wallpapers.sortedBy { it.addedAt }
            SortOption.DATE_ADDED_DESC -> wallpapers.sortedByDescending { it.addedAt }
            SortOption.DATE_MODIFIED_ASC -> wallpapers.sortedBy { it.dateModified }
            SortOption.DATE_MODIFIED_DESC -> wallpapers.sortedByDescending { it.dateModified }
        }
    }

    val commonItemModifier = remember {
        Modifier
            .fillMaxWidth()
            .aspectRatio(Constants.WALLPAPER_ASPECT_RATIO)
    }

    // Image picker
    // ActivityResultContracts.OpenMultipleDocuments does NOT set FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    // on its launch intent, so a follow-up takePersistableUriPermission throws SecurityException and
    // the wallpaper service cannot reopen the URIs after process death. Use a custom contract that
    // requests the persistable flag on ACTION_OPEN_DOCUMENT, then persist READ only (some providers
    // reject WRITE persistable, which would silently leave no grant at all).
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = OpenMultipleDocumentsPersistable()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Permission already persisted or provider refused — best effort.
                }
            }
            viewModel.addWallpapers(uris.map { it.toString() })
        }
    }

    // Folder picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // OpenDocumentTree DOES grant persistable permission; request READ only because the app
            // never writes to the picked folder, and some read-only providers reject WRITE.
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
                viewModel.addFolder(it.toString())
            } catch (_: Exception) {
                // Handle permission failure
            }
        }
    }

    Scaffold(
        topBar = {
            AlbumViewTopBar(
                title = album?.name ?: "",
                isSelectionMode = isSelectionMode,
                selectedCount = selectedCount,
                allSelected = allSelected,
                onBackClick = onBackClick,
                onSortClick = { showSortSheet = true },
                onDeleteAlbum = { showDeleteAlbumDialog = true },
                onSelectAll = { if (allSelected) viewModel.clearSelection() else viewModel.selectAll() },
                onDeleteSelected = { viewModel.deleteSelected() },
                onClearSelection = { viewModel.clearSelection() }
            )
        },
        floatingActionButton = {
            // Hide FAB when in selection mode
            if (!isSelectionMode) {
                AddAlbumAnimatedFab(
                    isLoading = importProgress !is ImportProgress.Idle,
                    onImageClick = { imagePickerLauncher.launch(arrayOf("image/*")) },
                    onFolderClick = { folderPickerLauncher.launch(null) }
                )
            }
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            state = lazyListState,
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            columns = GridCells.Adaptive(AppGrid.itemMinSize),
            contentPadding = PaddingValues(AppSpacing.gridPadding),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.gridSpacing),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.gridSpacing)
        ) {
            // Folders
            items(
                items = sortedFolders,
                key = { folder -> "folder-${folder.id}" }
            ) { folder ->
                FolderItem(
                    folder = folder,
                    isSelected = folder.id in selectedFolders,
                    isSelectionMode = isSelectionMode,
                    onClick = {
                        if (isSelectionMode) {
                            viewModel.toggleFolderSelection(folder.id)
                        } else {
                            onNavigateToFolder(folder.id)
                        }
                    },
                    onLongClick = {
                        viewModel.toggleFolderSelection(folder.id)
                    },
                    modifier = commonItemModifier
                        .then(
                            Modifier.animateItem(
                                placementSpec = tween(
                                    durationMillis = Constants.ANIMATION_DURATION_LONG_MS,
                                    delayMillis = 0,
                                    easing = FastOutSlowInEasing
                                )
                            )
                        )
                )
            }

            // Wallpapers
            items(
                items = sortedWallpapers,
                key = { wallpaper -> "wallpaper-${wallpaper.id}" }
            ) { wallpaper ->
                WallpaperItem(
                    wallpaperUri = wallpaper.uri,
                    isSelected = wallpaper.id in selectedWallpapers,
                    isSelectionMode = isSelectionMode,
                    onClick = {
                        if (isSelectionMode) {
                            viewModel.toggleWallpaperSelection(wallpaper.id)
                        } else {
                            onNavigateToWallpaperView(wallpaper.uri, wallpaper.fileName)
                        }
                    },
                    onLongClick = {
                        viewModel.toggleWallpaperSelection(wallpaper.id)
                    },
                    modifier = commonItemModifier
                        .then(
                            Modifier.animateItem(
                                placementSpec = tween(
                                    durationMillis = Constants.ANIMATION_DURATION_LONG_MS,
                                    delayMillis = 0,
                                    easing = FastOutSlowInEasing
                                )
                            )
                        )
                )
            }
        }
    }

    // Sort Bottom Sheet
    if (showSortSheet) {
        SortBottomSheet(
            onSortSelected = { sortOption = it },
            onDismiss = { showSortSheet = false }
        )
    }

    // Import progress (add folder / add wallpapers)
    ImportProgressDialog(progress = importProgress)

    // Delete Album Confirmation Dialog
    if (showDeleteAlbumDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAlbumDialog = false },
            title = { Text(stringResource(R.string.delete_album_question)) },
            text = { Text(stringResource(R.string.are_you_sure_you_want_to_delete_this)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAlbum()
                        onBackClick()
                    }
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAlbumDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Custom [ActivityResultContract] that mirrors [ActivityResultContracts.OpenMultipleDocuments] but
 * adds [Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION] to the launch intent. Without this flag,
 * [android.content.ContentResolver.takePersistableUriPermission] throws [SecurityException] and the
 * picked image URIs cannot be reopened after process death — which would silently break wallpaper
 * rotation once Android froze or recycled the app.
 *
 * The persisted grant is requested as READ only at the call site (see [imagePickerLauncher]) to
 * avoid being rejected outright by read-only providers when WRITE is also requested.
 */
private class OpenMultipleDocumentsPersistable :
    ActivityResultContract<Array<String>, List<@JvmSuppressWildcards Uri>>() {

    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        return intent?.let {
            val clipData = it.clipData
            if (clipData != null) {
                (0 until clipData.itemCount).mapNotNull { i -> clipData.getItemAt(i).uri }
            } else {
                listOfNotNull(it.data)
            }
        } ?: emptyList()
    }
}
