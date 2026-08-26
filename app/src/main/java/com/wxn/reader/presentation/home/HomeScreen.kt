package com.wxn.reader.presentation.home

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.ModeEdit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.rememberAsyncImagePainter
import com.air5005.pagenest.discovery.ui.DiscoveryRoute
import com.wxn.base.util.Logger
import com.wxn.reader.R
import com.wxn.reader.navigation.LocalNavController
import com.wxn.reader.navigation.PurchaseHelperController
import com.wxn.reader.presentation.home.components.CustomBottomAppBar
import com.wxn.reader.presentation.home.components.CustomSearchBar
import com.wxn.reader.presentation.home.components.CustomSnackbar
import com.wxn.reader.presentation.home.components.CustomTopAppBar
import com.wxn.reader.util.PurchaseHelper
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val purchaseHelper: PurchaseHelper = PurchaseHelperController.current
    val coroutineScope = rememberCoroutineScope()
    val navController = LocalNavController.current

    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val shelves by viewModel.shelves.collectAsStateWithLifecycle()
    val isAddingBooks by viewModel.isAddingBooks.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val booksInShelf by viewModel.booksInShelfSet.collectAsStateWithLifecycle()
    val books = viewModel.books.collectAsLazyPagingItems()
    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()

    val importProgress by viewModel.importProgressState.collectAsStateWithLifecycle()
    val snackbarState by viewModel.snackbarState.collectAsStateWithLifecycle()

    val selectedTabRow by viewModel.selectedTabRow.collectAsStateWithLifecycle()
    val currentDestination = HomeTopLevelDestination.fromIndex(selectedTabRow)
        ?: HomeTopLevelDestination.SHELF
    var selectedTab by viewModel.selectedTab

//    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val allShelves = remember(shelves) { listOf("All Books") + shelves.map { it.name } }
    val pagerState = rememberPagerState { allShelves.size }

    var searchMode by remember { mutableStateOf(false) }
    val selectedBooks by viewModel.selectedBooks.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()

    var showLayoutModal by viewModel.showLayoutModal
    var showSortModal by viewModel.showSortModal
    var showMetadataModal by viewModel.showMetadataModal

    var showSelectDirectoryDialog by remember { mutableStateOf(false) }
    var showAllBooks by rememberSaveable { mutableStateOf(false) }

    val lastOpenBookRoute by viewModel.openLastBookRoute.collectAsStateWithLifecycle()

    BackHandler(
        enabled = showAllBooks && currentDestination == HomeTopLevelDestination.SHELF &&
            !selectionMode && !searchMode,
    ) {
        showAllBooks = false
        selectedTab = 0
    }

    val getDirectoryPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
//                context.contentResolver.takePersistableUriPermission(
//                    it,
//                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
//                )
//                viewModel.addScanDirectory(it.toString())
                viewModel.addScanDirectory(it)
            }
        }

//    LaunchedEffect(Unit) {
//        delay(5000)
//        if (!appPreferences.isPremium && Random.nextFloat() <= 0.10f) {
//            navController.navigate(Screens.PremiumScreen.route)
//        }
//    }

    LaunchedEffect(selectedTab) {
        pagerState.animateScrollToPage(selectedTab)
        viewModel.clearBookSelection()
        if (selectedTab == 0) {
            viewModel.updateCurrentShelf(null)
        } else {
            val shelf = shelves.getOrNull(selectedTab - 1)
            viewModel.updateCurrentShelf(shelf)
            shelf?.let { viewModel.getBooksForShelf(it.id) }
        }
    }

    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            selectedTab = pagerState.currentPage
        }
    }

    if (appPreferences != null) {
        Box(modifier = Modifier.fillMaxSize()) {
            PageBackground(viewModel)

            Scaffold(
                topBar = {
                    AnimatedVisibility(
                        visible = searchMode && (
                            currentDestination == HomeTopLevelDestination.AUDIO ||
                                currentDestination == HomeTopLevelDestination.SHELF && showAllBooks
                            ),
                        enter = slideInHorizontally(initialOffsetX = { it }),
                        exit = slideOutHorizontally(targetOffsetX = { it })
                    ) {
                        TopAppBar(
                            modifier = Modifier.fillMaxWidth(),
                            title = {
                                CustomSearchBar(
                                    query = searchQuery,
                                    onQueryChange = { viewModel.updateSearchQuery(it) },
                                    onClose = {
                                        searchMode = false
                                        viewModel.updateSearchQuery("")
                                    }
                                )
                            }
                        )
                    }
                    AnimatedVisibility(
                        visible = !searchMode && (
                            currentDestination == HomeTopLevelDestination.AUDIO ||
                                currentDestination == HomeTopLevelDestination.SHELF && showAllBooks
                            ),
                        enter = slideInHorizontally(initialOffsetX = { -it }),
                        exit = slideOutHorizontally(targetOffsetX = { -it })
                    ) {
                        CustomTopAppBar(
                            viewModel = viewModel,
                            selectedTab = selectedTab,
                            shelves = shelves,
                            selectedBooks = selectedBooks,
                            selectionMode = selectionMode,
                            clearSelection = {
                                viewModel.clearBookSelection()
                            },
                            selectAll = {
                                viewModel.selectAllBooks(books.itemSnapshotList.items)
                            },
                            appPreferences = appPreferences!!,
                            toggleLayoutModal = {
                                showAllBooks = true
                                showLayoutModal = true
                            },
                            toggleSortFilterModal = {
                                showAllBooks = true
                                showSortModal = true
                            },
                            totalBooks = books.itemCount,
                            currentShelfBookCount = booksInShelf.size,
                            toggleSearchMode = {
                                showAllBooks = true
                                searchMode = true
                            },
    //                        openDrawer = {
    //                            coroutineScope.launch {
    //                                drawerState.open()
    //                            }
    //                        },
                            showOutAddDirDialog = {
                                showSelectDirectoryDialog = true
                            }
                        )
                    }
                },
                bottomBar = {
                    AnimatedVisibility(
                        visible = !selectionMode,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                    ) {
                        HomeNavigationBar(currentDestination) { destination ->
                            viewModel.updateCurrentTabRow(destination.index)
                            when (destination) {
                                HomeTopLevelDestination.SHELF -> {
                                    showAllBooks = false
                                    selectedTab = 0
                                }
                                HomeTopLevelDestination.AUDIO -> showAllBooks = true
                                HomeTopLevelDestination.DISCOVERY,
                                HomeTopLevelDestination.MINE,
                                -> Unit
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = selectionMode,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                    ) {
                        CustomBottomAppBar(
                            shelves = shelves,
                            selectedBooks = selectedBooks,
                            viewModel = viewModel,
                            clearSelection = {
                                viewModel.clearBookSelection()
                            },
                            navController = navController
                        )
                    }
                },
                floatingActionButton = {
                    AnimatedVisibility(
                        visible = (selectionMode && selectedBooks.size == 1),
                        enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
                        exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
                    ) {
                        FloatingActionButton(
                            shape = CircleShape,
                            onClick = {
                                showMetadataModal = true
    //                            val encodedUri = Uri.encode(selectedBooks[0].uri)
    //                            navController.navigate(
    //                                Screens.BookDetailsScreen.route + "/${selectedBooks[0].id}/${encodedUri}"
    //                            )
                            }
                        ) {
                            Icon(Icons.Default.ModeEdit, contentDescription = "Edit Book")
                        }
                    }
                },
                snackbarHost = {
                    CustomSnackbar(
                        snackbarState = snackbarState,
                        importProgressState = importProgress,
                        onDismiss = viewModel::dismissSnackbar,
                    )
                },
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) { innerPadding ->
                if (currentDestination.showsLibraryContent) {
                    HomeShelfsPanel(
                        innerPadding = innerPadding,
                        pagerState = pagerState,
                        viewModel = viewModel,
                        dashboardModel = dashboardState,
                        showAllBooks = showAllBooks || currentDestination == HomeTopLevelDestination.AUDIO,
                        onShowAllBooks = {
                            showAllBooks = true
                            selectedTab = 0
                        },
                        onImportClick = { showSelectDirectoryDialog = true },
                        onRecentBookClick = viewModel::openDashboardBook,
                    )
                } else if (currentDestination == HomeTopLevelDestination.DISCOVERY) {
                    DiscoveryRoute(
                        modifier = Modifier.padding(innerPadding),
                        onBookReady = viewModel::openDashboardBook,
                    )
                } else if (currentDestination == HomeTopLevelDestination.MINE) {
                    HomeMinePanel(innerPadding, viewModel)
                }
            }
        }
    }

    if (showSelectDirectoryDialog) {
        AlertDialog(
            onDismissRequest = { showSelectDirectoryDialog = false },
            title = { Text(stringResource(R.string.select_directory)) },
            text = { Text(stringResource(R.string.choose_a_directory_to_add_to_the_scan_list)) },
            confirmButton = {
                Button(onClick = {
                    showSelectDirectoryDialog = false
                    getDirectoryPermissionLauncher.launch(null)
                }) {
                    Text(stringResource(R.string.select))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelectDirectoryDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (lastOpenBookRoute.isNotEmpty()) {
        navController.navigate(lastOpenBookRoute)
        viewModel.resetLastBookOpenRoute()
    }
}

@Composable fun PageBackground(viewModel: HomeViewModel) {
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()

    if (!appPreferences?.homeBackgroundImage.isNullOrEmpty()) { //自定义背景
        Image(
            painter = rememberAsyncImagePainter(appPreferences?.homeBackgroundImage),
            contentDescription = "Book cover",
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.7f),
            contentScale = ContentScale.Crop
        )
        // Gradient overlay
        Box(                    //默认背景
            modifier = Modifier.fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.background
                        ),
                        startY = 0f,
                        endY = 2000f
                    )
                )
        )
    } else {
        //默认纯色背景
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    }
}
