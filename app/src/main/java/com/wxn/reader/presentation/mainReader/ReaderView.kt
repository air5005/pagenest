package com.wxn.reader.presentation.mainReader

import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.air5005.pagenest.speech.settings.SpeechSettingsViewModel
import com.air5005.pagenest.speech.settings.SpeechUiEvent
import com.air5005.pagenest.speech.ui.SpeechControlPolicy
import com.air5005.pagenest.speech.ui.ReaderSpeechEventPolicy
import com.air5005.pagenest.speech.ui.SpeechSettingsEventPolicy
import com.air5005.pagenest.speech.ui.SpeechControlSheet
import com.air5005.pagenest.speech.ui.SpeechControlUiState
import androidx.lifecycle.viewModelScope
import com.wxn.base.ext.toAndroidColor
import com.wxn.base.ext.toCompatibleArgb
import com.wxn.base.util.Logger
import com.wxn.bookread.data.model.preference.ReaderPreferences
import com.wxn.bookread.ui.PageView
import com.wxn.bookread.ui.TextPageFactory
import com.wxn.reader.R
import com.wxn.reader.navigation.LocalNavController
import com.wxn.reader.navigation.Screens
import com.wxn.reader.presentation.bookReader.components.TextToolbar
import com.wxn.reader.presentation.bookReader.components.dialogs.NoteContent
import com.wxn.reader.presentation.bookReader.components.dialogs.NoteDialog
import com.wxn.reader.presentation.bookReader.components.drawers.AnnotationsDrawer
import com.wxn.reader.presentation.bookReader.components.drawers.BookmarksDrawer
import com.wxn.reader.presentation.bookReader.components.drawers.ChaptersDrawer2
import com.wxn.reader.presentation.bookReader.components.drawers.NotesDrawer
import com.wxn.reader.presentation.bookReader.components.modals.FontSettings
import com.wxn.reader.presentation.bookReader.components.modals.PageSettings
import com.wxn.reader.presentation.bookReader.components.modals.ReaderSettings
import com.wxn.reader.presentation.bookReader.components.modals.UiSettings
import com.wxn.reader.presentation.mainReader.chrome.ReaderChrome
import com.wxn.reader.presentation.mainReader.chrome.ReaderChromeReducer
import com.wxn.reader.util.LogCompositions
import com.wxn.reader.util.TopPopupPositionProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderView(
    readerPreferences: ReaderPreferences,
    viewModel: MainReadViewModel,
) {
    val speechSettings: SpeechSettingsViewModel = hiltViewModel()
    val speechState by speechSettings.state.collectAsStateWithLifecycle()
    val speechSnapshot by speechSettings.playbackSnapshot.collectAsStateWithLifecycle()
    val routeIndicator by speechSettings.routeIndicator.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var speechEvent by remember { mutableStateOf<SpeechUiEvent?>(null) }
    LaunchedEffect(speechSettings) {
        speechSettings.events.collect { event ->
            if (ReaderSpeechEventPolicy.shouldPresent(event)) {
                speechEvent = event
            }
        }
    }
    LogCompositions("Composition:ReaderView")
    val navController = LocalNavController.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val book by viewModel.book.collectAsStateWithLifecycle()
    val chromeState by viewModel.readerChromeState.collectAsStateWithLifecycle()
    val appPreferences by viewModel.appPreferences.collectAsStateWithLifecycle()
    val readProgression by viewModel.readProgression.collectAsStateWithLifecycle()
    val readerIndexUiState by viewModel.readerIndexUiState.collectAsStateWithLifecycle()
    var showMoreTools by remember { mutableStateOf(false) }
    var showDisplayTools by remember { mutableStateOf(false) }

    val isChaptersDrawerOpen by viewModel.isChaptersDrawerOpen.collectAsStateWithLifecycle()
    val isNotesDrawerOpen by viewModel.isNotesDrawerOpen.collectAsStateWithLifecycle()
    val isBookmarksDrawerOpen by viewModel.isBookmarksDrawerOpen.collectAsStateWithLifecycle()
    val isHighlightsDrawerOpen by viewModel.isHighlightsDrawerOpen.collectAsStateWithLifecycle()

    val showTextToolbar by viewModel.showTextToolbar.collectAsStateWithLifecycle()
    val textToolbarRect by viewModel.textToolbarRect.collectAsStateWithLifecycle()

    val showColorSelectionPanel by viewModel.showColorSelectionPanel.collectAsStateWithLifecycle()

    val showUISettings by viewModel.showUISettings.collectAsStateWithLifecycle()
    val showFontSettings by viewModel.showFontSettings.collectAsStateWithLifecycle()
    val showPageSettings by viewModel.showPageSettings.collectAsStateWithLifecycle()
    val showReaderSettings by viewModel.showReaderSettings.collectAsStateWithLifecycle()
    val showNoteDialog by viewModel.showNoteDialog.collectAsStateWithLifecycle()
    val noteDialogSelectedText by viewModel.noteDialogSelectedText.collectAsStateWithLifecycle()

    val selectedNote by viewModel.selectedNote.collectAsStateWithLifecycle()

    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val annotations by viewModel.annotations.collectAsStateWithLifecycle()
    val selectedAnnotation by viewModel.selectedAnnotation.collectAsStateWithLifecycle()

    val clickedLinkContent by viewModel.clickedLinkContent.collectAsStateWithLifecycle()

    val isBookmarked by viewModel.isBookmarked.collectAsStateWithLifecycle()

    val isTtsOn by viewModel.isTtsOn.collectAsStateWithLifecycle()
    val enableTts by viewModel.enableTts.collectAsStateWithLifecycle()
//    val isTtsPlaying by viewModel.isTtsPlaying.collectAsStateWithLifecycle()
//    val ttsSpeed by viewModel.ttsSpeed.collectAsStateWithLifecycle()
//    val ttsPitch by viewModel.ttsPitch.collectAsStateWithLifecycle()
//    val ttsLanguage by viewModel.ttsLanguage.collectAsStateWithLifecycle()

    val outHref by viewModel.outHref.collectAsStateWithLifecycle()
    val showOutHrefDialog by viewModel.showOutHrefDialog.collectAsStateWithLifecycle()

    val hasBlockingOverlay =
        isChaptersDrawerOpen ||
            isNotesDrawerOpen ||
            isBookmarksDrawerOpen ||
            isHighlightsDrawerOpen ||
            showTextToolbar ||
            showColorSelectionPanel ||
            showUISettings ||
            showFontSettings ||
            showPageSettings ||
            showReaderSettings ||
            showNoteDialog ||
            selectedNote != null ||
            clickedLinkContent != null ||
            showOutHrefDialog ||
            showMoreTools ||
            showDisplayTools ||
            chromeState.speechPanelExpanded ||
            SpeechSettingsEventPolicy.shouldShowOnlineConsent(speechState) ||
            speechEvent != null

    LaunchedEffect(hasBlockingOverlay) {
        viewModel.setBlockingOverlayVisible(hasBlockingOverlay)
    }

    LaunchedEffect(
        chromeState.controlsVisible,
        chromeState.interactionGeneration,
        chromeState.blockingOverlayVisible,
        chromeState.speechPanelExpanded,
    ) {
        if (ReaderChromeReducer.shouldScheduleAutoHide(chromeState)) {
            val generation = chromeState.interactionGeneration
            delay(ReaderChromeReducer.AUTO_HIDE_MILLIS)
            viewModel.onChromeAutoHide(generation)
        }
    }

    val speechControlState = SpeechControlUiState(
        playback = speechSnapshot.playbackState,
        mode = speechState.preferences.mode,
        activeEngineLabel = SpeechControlPolicy.engineLabel(
            routeIndicator.engineId,
            routeIndicator.fellBack,
        ),
        rate = speechState.preferences.rate,
        pitch = speechState.preferences.pitch,
        voiceId = speechState.preferences.voiceId,
        sleepTimerMinutes = null,
    )


    fun navigateToHref(href: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(href))
        context.startActivity(Intent.createChooser(intent, "Search with"))
    }

    if (appPreferences != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            AndroidView(
                factory = { context ->
                    PageView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                        viewModel.pageController.pageFactory = TextPageFactory(this, viewModel.pageController)
                        this.dataProvider = viewModel.pageController
                        viewModel.pageController.callBack = this
                        viewModel.pageController.clickListener = viewModel
                        setSelectTextCallback(viewModel.pageController)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    Logger.d("ReaderView::update by AndroidView")
                    view.dataProvider?.book = book
                    view.upStyle()
                    view.upTipStyle()
                    view.upBg()
                    view.upStatusBar()
                    view.dataProvider?.loadContent(true)
                }
            )

            val curChapterName by viewModel.curChapterName.collectAsStateWithLifecycle()

            ReaderChrome(
                state = chromeState,
                bookTitle = book?.title.orEmpty(),
                chapterTitle = curChapterName,
                progression = readProgression,
                isBookmarked = isBookmarked,
                speech = speechControlState,
                onBack = { backDispatcher?.onBackPressed() },
                onBookmark = {
                    if (isBookmarked) viewModel.deleteBookmark() else viewModel.addBookmark()
                },
                onMore = { showMoreTools = true },
                onChapters = { viewModel.chaptersDrawerOpen() },
                onProgressToggle = {
                    viewModel.setProgressPanelExpanded(!chromeState.progressPanelExpanded)
                },
                onProgressChange = { viewModel.changePageByProgress(it) },
                onPreviousPage = { viewModel.pageController.pageFactory?.moveToPrev(true) },
                onNextPage = { viewModel.pageController.pageFactory?.moveToNext(true) },
                onSpeech = {
                    if (isTtsOn) {
                        viewModel.setSpeechPanelExpanded(true)
                    } else if (enableTts) {
                        viewModel.prepareSpeech()
                        speechSettings.start()
                    }
                },
                onDisplay = { showDisplayTools = true },
                onPlaySpeech = {
                    if (SpeechControlPolicy.requiresPreparation(speechSnapshot.playbackState)) {
                        viewModel.prepareSpeech()
                    }
                    speechSettings.start()
                },
                onPauseSpeech = speechSettings::pause,
                onPreviousSpeech = speechSettings::previous,
                onNextSpeech = speechSettings::next,
                onStopSpeech = speechSettings::stop,
                onExpandSpeech = { viewModel.setSpeechPanelExpanded(true) },
                onInteraction = viewModel::onReaderInteraction,
            )

            ReaderIndexProgressNotice(
                state = readerIndexUiState,
                topPadding = if (chromeState.controlsVisible) 76.dp else 8.dp,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (chromeState.speechPanelExpanded) {
                SpeechControlSheet(
                    state = speechControlState,
                    onPlay = {
                        if (SpeechControlPolicy.requiresPreparation(speechSnapshot.playbackState)) viewModel.prepareSpeech()
                        speechSettings.start()
                    },
                    onPause = speechSettings::pause,
                    onStop = speechSettings::stop,
                    onPrevious = speechSettings::previous,
                    onNext = speechSettings::next,
                    onRateChange = speechSettings::setRate,
                    onPitchChange = speechSettings::setPitch,
                    onTimerChange = speechSettings::setSleepTimer,
                    onDismiss = { viewModel.setSpeechPanelExpanded(false) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

    //        TtsPlayer(
    //            areToolbarsVisible = areToolbarsVisible,
    //            isTtsOn = isTtsOn,
    //            isTtsPlaying = isTtsPlaying,
    //            speed = ttsSpeed,
    //            pitch = ttsPitch,
    //            language = ttsLanguage,
    //            onPlay = {
    //                viewModel.setTtsPlaying(true)
    //            },
    //            onPause = {
    //                viewModel.setTtsPlaying(false)
    //            },
    //            onEnd = {
    //                viewModel.toggleTts()
    //            },
    //            onSpeedChange = { viewModel.setTtsSpeed(it.toDouble()) },
    //            onPitchChange = { viewModel.setTtsPitch(it.toDouble()) },
    //            onLanguageChange = { viewModel.setTtsLanguage(it) },
    //            onSkipToNextUtterance = { viewModel.skipToNextUtterance() },
    //            onSkipToPreviousUtterance = { viewModel.skipToPreviousUtterance() }
    //        )
            // ActionModeLayout
            if (showTextToolbar || isHighlightsDrawerOpen || isChaptersDrawerOpen || isNotesDrawerOpen || isBookmarksDrawerOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {

                            viewModel.notesDrawerOpen(false)
                            viewModel.bookmarksDrawerOpen(false)
                            viewModel.highlightsDrawerOpen(false)
                            viewModel.chaptersDrawerOpen(false)

                            if (showTextToolbar) {
                                viewModel.textToolbarOpen(false)
                                viewModel.cancelTextSelected()
                            }
                            viewModel.showColorSelectionPanel(false)
                        }
                )
            }

            ChaptersDrawer2(
                isOpen = isChaptersDrawerOpen,
                viewModel = viewModel,
                tableOfContents = viewModel.showOutChapters,
                onChapterSelect = { selectedChapter ->
                    viewModel.onChapterClick(selectedChapter)
                    viewModel.chaptersDrawerOpen(false)
    //                val locator = publication.locatorFromLink(selectedChapter)
    //                locator?.let {
    //                    onChapterChange(it)
    //                    isChaptersDrawerOpen = false
    //                }
                },
                onClose = { viewModel.chaptersDrawerOpen(false) }
            )

            NotesDrawer(
                navController = navController,
                appPreferences = appPreferences!!,
                isOpen = isNotesDrawerOpen,
                onClose = { viewModel.notesDrawerOpen(false) },
                notes = notes,
                onNoteClick = { note ->
                    // Handle note click, e.g., navigate to the note's location in the book
                    viewModel.viewModelScope.launch {
                        viewModel.notesDrawerOpen(false)
                        viewModel.navigateTo(note.locatorInfo)
                    }
                },
                onUpdateNote = { updatedNote ->
                    viewModel.updateNote(updatedNote)
                },
                onRemoveNote = { note ->
                    viewModel.deleteNote(note)
                }
            )

            BookmarksDrawer(
                navController = navController,
                appPreferences = appPreferences!!,
                isOpen = isBookmarksDrawerOpen,
                onClose = { viewModel.bookmarksDrawerOpen(false) },
                bookmarks = bookmarks,
                onBookmarkClick = { bookmark ->
                    viewModel.viewModelScope.launch {
                        viewModel.bookmarksDrawerOpen(false)
                        viewModel.navigateTo(bookmark.locatorInfo)
                    }
                },
                onRemoveBookmark = { bookmark ->
                    viewModel.deleteBookmark(bookmark)
                }
            )

            AnnotationsDrawer(
                navController = navController,
                appPreferences = appPreferences!!,
                annotations = annotations,
                onRemoveAnnotation = viewModel::deleteAnnotation,
                onUpdateAnnotation = viewModel::updateAnnotation,
                onClickAnnotation = { annotation ->
                    viewModel.navigateTo(annotation.locatorInfo)
                },
                isOpen = isHighlightsDrawerOpen,
                onClose = { viewModel.highlightsDrawerOpen(false) }
            )

            if (showMoreTools) {
                AlertDialog(
                    onDismissRequest = { showMoreTools = false },
                    title = { Text(stringResource(R.string.reader_more)) },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = {
                                    showMoreTools = false
                                    viewModel.notesDrawerOpen()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.notes)) }
                            TextButton(
                                onClick = {
                                    showMoreTools = false
                                    viewModel.highlightsDrawerOpen()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.highlights)) }
                            TextButton(
                                onClick = {
                                    showMoreTools = false
                                    viewModel.bookmarksDrawerOpen()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.bookmarks)) }
                            TextButton(
                                onClick = {
                                    showMoreTools = false
                                    book?.let { openedBook ->
                                        navController.navigate(
                                            Screens.BookDetailsScreen.route +
                                                "/${openedBook.id}/${Uri.encode(openedBook.filePath)}",
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.about)) }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showMoreTools = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }

            if (showDisplayTools) {
                AlertDialog(
                    onDismissRequest = { showDisplayTools = false },
                    title = { Text(stringResource(R.string.reader_display)) },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = {
                                    showDisplayTools = false
                                    viewModel.uiSettingsOpen()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.theme)) }
                            TextButton(
                                onClick = {
                                    showDisplayTools = false
                                    viewModel.fontSettingsOpen()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.font_settings)) }
                            TextButton(
                                onClick = {
                                    showDisplayTools = false
                                    viewModel.pageSettingsOpen()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.page_settings)) }
                            TextButton(
                                onClick = {
                                    showDisplayTools = false
                                    viewModel.readerSettingsOpen()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.reader_settings)) }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showDisplayTools = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }

            if (showNoteDialog) {
                NoteDialog(
                    appPreferences = appPreferences!!,
                    selectedText = noteDialogSelectedText,
                    onSave = { noteText, selectedColor ->
                        viewModel.viewModelScope.launch {
                            viewModel.addNote(noteText, selectedColor)
                        }
                        viewModel.noteDialogOpen(false)
                    },
                    onDismiss = {
                        viewModel.noteDialogOpen(false)
                        viewModel.cancelTextSelected()
                    },
                    showPremiumModal = {
                        viewModel.noteDialogOpen(false)
                        navController.navigate(Screens.PremiumScreen.route)
    //                    viewModel.purchasePremium(purchaseHelper)
    //                    showPremiumModal = true
                    }
                )
            }

            //选中的笔记
            selectedNote?.let { note ->
                NoteContent(
                    appPreferences = appPreferences!!,
                    note = note,
                    onDismiss = {
                        viewModel.clearSelectedNote()
                        viewModel.cancelTextSelected()
                    },
                    onEdit = { editedNote ->
                        viewModel.updateNote(editedNote)
                        viewModel.clearSelectedNote()
                    },
                    onDelete = { noteToDelete ->
                        viewModel.deleteNote(noteToDelete)
                        viewModel.clearSelectedNote()
                    },
                    showPremiumModal = {
    //                    viewModel.clearSelectedNote()
    //                    viewModel.purchasePremium(purchaseHelper)
    //                    navController.navigate(Screens.PremiumScreen.route)
                    }
                )
            }

            //字体设置
            if (showFontSettings) {
                FontSettings(
                    viewModel = viewModel,
                    readerPreferences = readerPreferences,
                    onDismiss = { viewModel.fontSettingsOpen(false) },
                )
            }

            //页面设置
            if (showPageSettings) {
                PageSettings(
                    viewModel = viewModel,
                    readerPreferences = readerPreferences,
                    onDismiss = { viewModel.pageSettingsOpen(false) },
                )
            }

            //UI 设置
            if (showUISettings) {
                UiSettings(
                    navController = navController,
    //                purchaseHelper = purchaseHelper,
                    appPreferences = appPreferences!!,
                    viewModel = viewModel,
                    readerPreferences = readerPreferences,
                    onDismiss = { viewModel.uiSettingsOpen(false) }
                )
            }

            //阅读设置
            if (showReaderSettings) {
                ReaderSettings(
                    viewModel = viewModel,
                    readerPreferences = readerPreferences,
                    onDismiss = { viewModel.readerSettingsOpen(false) }
                )
            }

            var dp16 = remember { 0f }
            with(LocalDensity.current) {
                dp16 = 16.dp.toPx()
            }

            if (clickedLinkContent != null) { //点击的链接内容popup
                Popup(
                    popupPositionProvider = TopPopupPositionProvider(
                        Alignment.TopStart,
                        IntOffset(0, dp16.toInt()),
                        anchor = IntOffset(clickedLinkContent?.clickX?.toInt() ?: 0, clickedLinkContent?.clickY?.toInt() ?: 0)
                    ),
                    onDismissRequest = {
                        viewModel.clearClickedLinkContent()
                    }
                ) {

                    Box(
                        modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth().background(Color.Yellow)
                    ) {
                        IconButton(
                            onClick = {
                                viewModel.clearClickedLinkContent()
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close Popup"
                            )
                        }
                        Column(Modifier.padding(8.dp, 36.dp, 8.dp, 8.dp)) {
                            Text(text = clickedLinkContent?.content.orEmpty(), fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        if (showTextToolbar) {
            TextToolbar(
                navController = navController,
                viewModel = viewModel,
                selectedText = "", // actionSelectedText,
                rect = textToolbarRect,
                onHighlight = { color ->    //高亮
                    viewModel.handleHighlight(color)
                },
                onUnderline = { color ->    //下划线
                    viewModel.handleUnderline(color)
                },
                onNote = {                  //新增笔记
                    viewModel.handleNote()
                    viewModel.textToolbarOpen(false)
    //                showTextToolbar = false
                },
                onDismiss = {
                    viewModel.textToolbarOpen(false)
                    viewModel.cancelTextSelected()
                },
                appPreferences = appPreferences!!,
                selectedAnnotation = selectedAnnotation,
                onRemoveAnnotation = {
                    viewModel.deleteAnnotation(it)
                },
                colorHistory = readerPreferences.colorHistory.map { it ->
                    Color(it.toCompatibleArgb())
                },
                onColorHistoryUpdated = { newHistory ->
    //                Logger.d("TextToolbar::onColorHistoryUpdated")
                    viewModel.updateReaderPreferences(readerPreferences.copy(colorHistory = newHistory.mapNotNull { it ->
                        it.toAndroidColor()
                    }), false)
                },
                showColorSelectionPanel = showColorSelectionPanel
            )
        }

        if (showOutHrefDialog) {
            AlertDialog(
                onDismissRequest = {
                    viewModel.hideOutHrefDialog()

               },
                title = { Text("") },
                text = { Text(stringResource(R.string.dialog_content_to_out_href, outHref)) },
                dismissButton = {
                    Button(
                        onClick = {
                            viewModel.hideOutHrefDialog()
                        }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        onClick = {
                            navigateToHref(outHref)
                            viewModel.hideOutHrefDialog()
                        }
                    ) {
                        Text(stringResource(R.string.navigate_to))
                    }
                },
            )
        }

        if (SpeechSettingsEventPolicy.shouldShowOnlineConsent(speechState)) {
            AlertDialog(
                onDismissRequest = speechSettings::cancelOnlineConsent,
                text = { Text(stringResource(R.string.speech_online_consent)) },
                confirmButton = { Button(onClick = speechSettings::confirmOnlineConsent) { Text(stringResource(R.string.confirm)) } },
                dismissButton = { Button(onClick = speechSettings::cancelOnlineConsent) { Text(stringResource(R.string.cancel)) } },
            )
        }

        when (val event = speechEvent) {
            SpeechUiEvent.RequestOnlineConsent -> Unit
            is SpeechUiEvent.ShowMessage -> AlertDialog(
                onDismissRequest = { speechEvent = null },
                text = { Text(event.message) },
                confirmButton = { Button(onClick = { speechEvent = null }) { Text(stringResource(R.string.ok)) } },
            )
            is SpeechUiEvent.ShowFallbackMessage -> Unit
            null -> Unit
        }
    }
}
