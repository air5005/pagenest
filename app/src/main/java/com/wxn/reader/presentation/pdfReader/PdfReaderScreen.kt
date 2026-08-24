package com.wxn.reader.presentation.pdfReader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import com.wxn.reader.navigation.LocalNavController
import com.wxn.reader.presentation.pdfReader.components.PdfReaderBottomBar
import com.wxn.reader.presentation.pdfReader.components.PdfReaderTopBar
import com.wxn.reader.util.KeepScreenOn
import com.wxn.reader.util.SetFullScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.air5005.pagenest.speech.settings.SpeechSettingsViewModel
import com.air5005.pagenest.speech.settings.SpeechUiEvent
import com.air5005.pagenest.speech.ui.SpeechControlPolicy
import com.air5005.pagenest.speech.ui.SpeechControlSheet
import com.air5005.pagenest.speech.ui.SpeechControlUiState

@Composable
fun PdfReaderScreen(
    viewModel: PdfReaderViewModel = hiltViewModel()
) {
    val speechSettings: SpeechSettingsViewModel = hiltViewModel()
    val speechState by speechSettings.state.collectAsStateWithLifecycle()
    val speechSnapshot by speechSettings.playbackSnapshot.collectAsStateWithLifecycle()
    val routeIndicator by speechSettings.routeIndicator.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var speechEvent by remember { mutableStateOf<SpeechUiEvent?>(null) }
    LaunchedEffect(speechSettings) {
        speechSettings.events.collect { event ->
            if (event is SpeechUiEvent.ShowFallbackMessage) {
                Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
            } else {
                speechEvent = event
            }
        }
    }
    val navController: NavHostController = LocalNavController.current
    KeepScreenOn(true)
    val coroutineScope = rememberCoroutineScope()
    var areToolbarsVisible by remember { mutableStateOf(false) }
    SetFullScreen(context, showSystemBars = areToolbarsVisible)


    val book by viewModel.book.collectAsStateWithLifecycle()
    val pdfPages by viewModel.pdfPages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val backgroundColor by viewModel.backgroundColor.collectAsStateWithLifecycle()
    val pageCount by viewModel.pageCount.collectAsStateWithLifecycle()
    val initialPage by viewModel.initialPage.collectAsStateWithLifecycle()
    val speechPage by viewModel.speechPage.collectAsStateWithLifecycle()

    var currentPage by remember { mutableIntStateOf(initialPage) }


    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    var showReader by remember { mutableStateOf(false) }
    var coverAlpha by remember { mutableFloatStateOf(1f) }
    var readerAlpha by remember { mutableFloatStateOf(0f) }

    var pagerState = rememberPagerState(initialPage = initialPage) { pageCount }


    LaunchedEffect(Unit) {
        viewModel.loadInitialPages()
        delay(1000) // Delay to show the cover
        showReader = true
        // Animate the transition
        animate(1f, 0f, animationSpec = tween(durationMillis = 500)) { value, _ ->
            coverAlpha = value
        }
        animate(0f, 1f, animationSpec = tween(durationMillis = 500)) { value, _ ->
            readerAlpha = value
        }
    }

    LaunchedEffect(speechPage) {
        speechPage?.let { page ->
            if (page in 0 until pageCount) pagerState.scrollToPage(page)
        }
    }


    DisposableEffect(currentPage) {
        onDispose {
            viewModel.saveReadingProgress(currentPage)
        }
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val screenWidth = size.width
                    val screenHeight = size.height
                    val middleThirdWidth = screenWidth / 3f
                    val middleThirdHeight = screenHeight / 3f

                    val middleThirdRect = Rect(
                        left = middleThirdWidth,
                        top = middleThirdHeight,
                        right = (2 * middleThirdWidth),
                        bottom = (2 * middleThirdHeight)
                    )

                    if (middleThirdRect.contains(offset)) {
                        areToolbarsVisible = !areToolbarsVisible
                    }
                }
            }
    ) {
        // Book cover
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .alpha(coverAlpha),
            contentAlignment = Alignment.Center
        ) {
            val request = ImageRequest.Builder(LocalContext.current)
                .data(book?.coverImage)
                .size(300)
                .scale(Scale.FIT)
                .build()
            AsyncImage(
                model = request,
                contentDescription = "Book cover",
                modifier = Modifier
                    .fillMaxSize(0.7f)
                    .padding(16.dp),
                contentScale = ContentScale.Fit
            )
        }

        // PDF reader content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(readerAlpha)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                errorMessage != null -> {
                    Text(
                        text = errorMessage ?: "An error occurred",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    pagerState = rememberPagerState(initialPage = initialPage) { pageCount }

                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.currentPage }.collect { page ->
                            currentPage = page
                            viewModel.seekSpeechToPage(page)
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 3f)
                                    if (newScale == 1f) {
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    }
                                    scale = newScale
                                }
                            }
                    ) { page ->
                        LaunchedEffect(page) {
                            viewModel.loadPage(page)
                            if (page < pdfPages.size - 1) {
                                viewModel.loadPage(page + 1)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                            contentAlignment = Alignment.Center
                        ) {
                            pdfPages[page]?.let { bitmap ->
                                AsyncImage(
                                    model = bitmap,
                                    contentDescription = "PDF page ${page + 1}",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offsetX,
                                            translationY = offsetY
                                        ),
                                    contentScale = ContentScale.Fit
                                )
                            } ?: CircularProgressIndicator()
                        }
                    }
                }
            }
        }






        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.TopCenter),
            visible = areToolbarsVisible,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it })
        ) {
            PdfReaderTopBar(
                book = book,
                onBackClick = {
                    navController.navigateUp()
                }
            )
        }




        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.BottomCenter),
            visible = areToolbarsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
        ) {
            PdfReaderBottomBar(
                pageCount = pageCount,
                currentPage = currentPage,
                onPageChange = { newPage ->
                    currentPage = newPage
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(newPage - 1)
                    }
                },
                onSpeech = {
                    coroutineScope.launch {
                        if (viewModel.prepareSpeech(currentPage)) speechSettings.start()
                    }
                },
            )
        }

        if (speechSnapshot.playbackState !is SpeechPlaybackState.Idle) {
            SpeechControlSheet(
                state = SpeechControlUiState(
                    playback = speechSnapshot.playbackState,
                    mode = speechState.preferences.mode,
                    activeEngineLabel = SpeechControlPolicy.engineLabel(routeIndicator.engineId, routeIndicator.fellBack),
                    rate = speechState.preferences.rate,
                    pitch = speechState.preferences.pitch,
                    voiceId = speechState.preferences.voiceId,
                    sleepTimerMinutes = null,
                ),
                onPlay = {
                    coroutineScope.launch {
                        if (!SpeechControlPolicy.requiresPreparation(speechSnapshot.playbackState) || viewModel.prepareSpeech(currentPage)) {
                            speechSettings.start()
                        }
                    }
                },
                onPause = speechSettings::pause,
                onStop = speechSettings::stop,
                onPrevious = speechSettings::previous,
                onNext = speechSettings::next,
                onRateChange = speechSettings::setRate,
                onPitchChange = speechSettings::setPitch,
                onTimerChange = speechSettings::setSleepTimer,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        when (val event = speechEvent) {
            SpeechUiEvent.RequestOnlineConsent -> AlertDialog(
                onDismissRequest = { speechEvent = null; speechSettings.cancelOnlineConsent() },
                text = { Text(stringResource(com.wxn.reader.R.string.speech_online_consent)) },
                confirmButton = { Button(onClick = { speechEvent = null; speechSettings.confirmOnlineConsent() }) { Text(stringResource(com.wxn.reader.R.string.confirm)) } },
                dismissButton = { Button(onClick = { speechEvent = null; speechSettings.cancelOnlineConsent() }) { Text(stringResource(com.wxn.reader.R.string.cancel)) } },
            )
            is SpeechUiEvent.ShowMessage -> AlertDialog(
                onDismissRequest = { speechEvent = null },
                text = { Text(event.message) },
                confirmButton = { Button(onClick = { speechEvent = null }) { Text(stringResource(com.wxn.reader.R.string.ok)) } },
            )
            is SpeechUiEvent.ShowFallbackMessage -> Unit
            null -> Unit
        }


    }
}





