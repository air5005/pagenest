package com.wxn.reader.presentation.home

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.air5005.pagenest.library.importing.AndroidImportRequestFactory
import com.air5005.pagenest.library.importing.BookImportService
import com.air5005.pagenest.library.importing.ImportRejection
import com.air5005.pagenest.library.importing.ImportRequest
import com.air5005.pagenest.library.importing.ImportResult
import com.wxn.base.bean.Book
import com.wxn.bookparser.FileParser
import com.wxn.reader.BookApplication
import com.wxn.reader.data.model.AppPreferences
import com.wxn.reader.data.model.AppTheme
import com.wxn.reader.data.model.Layout
import com.wxn.reader.data.model.SortOption
import com.wxn.reader.data.model.SortOrder
import com.wxn.reader.data.source.local.AppPreferencesUtil
import com.wxn.reader.domain.repository.PermissionRepository
import com.wxn.reader.domain.use_case.books.DeleteBookByUriUseCase
import com.wxn.reader.domain.use_case.books.DeleteBookUseCase
import com.wxn.reader.domain.use_case.books.GetBookByIdUseCase
import com.wxn.reader.domain.use_case.books.GetAllBooksUseCase
import com.wxn.reader.domain.use_case.books.GetBookUrisUseCase
import com.wxn.reader.domain.use_case.books.GetBooksUseCase
import com.wxn.reader.domain.use_case.books.InsertBookUseCase
import com.wxn.reader.domain.use_case.books.UpdateBookUseCase
import com.wxn.reader.domain.use_case.reading_activity.GetAllReadingActivitiesUseCase
import com.wxn.reader.domain.use_case.shelves.AddBookToShelfUseCase
import com.wxn.reader.domain.use_case.shelves.AddShelfUseCase
import com.wxn.reader.domain.use_case.shelves.GetBooksForShelfUseCase
import com.wxn.reader.domain.use_case.shelves.GetShelvesUseCase
import com.wxn.reader.domain.use_case.shelves.RemoveBooksFromShelfUseCase
import com.wxn.reader.domain.use_case.shelves.RemoveShelfUseCase
import com.wxn.reader.presentation.home.states.ImportProgressState
import com.wxn.reader.presentation.home.states.SnackbarState
import com.wxn.reader.util.DocumentUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.util.concurrent.CancellationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeViewModelImportFlowTest {
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var service: BookImportService
    private lateinit var requestFactory: AndroidImportRequestFactory
    private lateinit var getBookByIdUseCase: GetBookByIdUseCase
    private lateinit var preferencesUtil: AppPreferencesUtil
    private lateinit var getAllBooksUseCase: GetAllBooksUseCase
    private lateinit var getAllReadingActivitiesUseCase: GetAllReadingActivitiesUseCase
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        application = ApplicationProvider.getApplicationContext()
        BookApplication.app = mockk<BookApplication> {
            every { applicationContext } returns application
        }
        service = mockk()
        requestFactory = mockk()
        getBookByIdUseCase = mockk(relaxed = true)
        preferencesUtil = mockk<AppPreferencesUtil>()
        every { preferencesUtil.appPrefsFlow } returns flow { awaitCancellation() }
        getAllBooksUseCase = mockk<GetAllBooksUseCase>()
        getAllReadingActivitiesUseCase = mockk<GetAllReadingActivitiesUseCase>()
        every { getAllBooksUseCase() } returns flow { awaitCancellation() }
        coEvery { getAllReadingActivitiesUseCase() } returns flow { awaitCancellation() }
        viewModel = HomeViewModel(
            getBooksUseCase = mockk(relaxed = true),
            getBookUrisUseCase = mockk(relaxed = true),
            insertBookUseCase = mockk(relaxed = true),
            updateBookUseCase = mockk(relaxed = true),
            deleteBookUseCase = mockk(relaxed = true),
            deleteBookByUriUseCase = mockk(relaxed = true),
            getBookByIdUseCase = getBookByIdUseCase,
            getAllBooksUseCase = getAllBooksUseCase,
            getAllReadingActivitiesUseCase = getAllReadingActivitiesUseCase,
            addShelfUseCase = mockk(relaxed = true),
            removeShelfUseCase = mockk(relaxed = true),
            getShelvesUseCase = mockk(relaxed = true),
            addBookToShelfUseCase = mockk(relaxed = true),
            removeBooksFromShelfUseCase = mockk(relaxed = true),
            getBooksForShelfUseCase = mockk(relaxed = true),
            appPreferencesUtil = preferencesUtil,
            fileParser = mockk(relaxed = true),
            permissionRepository = mockk(relaxed = true),
            bookImportService = service,
            importRequestFactory = requestFactory,
            skinService = mockk(relaxed = true),
            application = application,
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun fixtureKeepsTheProductionInitializerSuspendedUntilTeardown() {
        mainDispatcher.scheduler.runCurrent()

        val initializerIsActive = viewModel.viewModelScope.coroutineContext[Job]
            ?.children
            ?.any { it.isActive }
            ?: false
        assertTrue("The initializer must suspend instead of failing", initializerIsActive)
    }

    @Test
    fun dashboardBookClickPublishesExistingReaderRoute() {
        coEvery { getBookByIdUseCase(42L) } returns dashboardBook(
            id = 42L,
            fileType = "pdf",
            filePath = "content://books/reading copy.pdf",
        )

        viewModel.openDashboardBook(42L)
        mainDispatcher.scheduler.runCurrent()

        assertEquals(
            "pdf_reader_screen/42/content%3A%2F%2Fbooks%2Freading%20copy.pdf",
            viewModel.openLastBookRoute.value,
        )
    }

    @Test
    fun scanReportsEveryOutcomeAndCountsOnlyImportedBooksAsAdded() = runBlocking {
        val imported = document("content://scan/Imported.epub", "Imported.epub")
        val duplicate = document("content://scan/Duplicate.epub", "Duplicate.epub")
        val failed = document("content://scan/Failed.epub", "Failed.epub")
        mockkObject(DocumentUtil)
        coEvery { DocumentUtil.getFilesFromDirectory(any(), any()) } returns
            listOf(imported, duplicate, failed)
        every { requestFactory.create(imported.uri) } returns request("Imported.epub")
        every { requestFactory.create(duplicate.uri) } returns request("Duplicate.epub")
        every { requestFactory.create(failed.uri) } returns request("Failed.epub")
        coEvery { service.execute(match { it.displayName == "Imported.epub" }) } returns
            ImportResult.Imported(1)
        coEvery { service.execute(match { it.displayName == "Duplicate.epub" }) } returns
            ImportResult.Duplicate(1)
        coEvery { service.execute(match { it.displayName == "Failed.epub" }) } returns
            ImportResult.Rejected(ImportRejection.STORAGE_FAILED)

        invokeProductionScan(mockk<AppPreferences> {
            every { scanDirectories } returns setOf("content://scan")
        })
        awaitState {
            viewModel.importProgressState.value == ImportProgressState.Complete &&
                !viewModel.isAddingBooks.value
        }
        mainDispatcher.scheduler.runCurrent()

        assertEquals(
            SnackbarState.Visible(
                "处理完成：新增 1 本，已存在 1 本，失败 1 本",
                unlimited = false,
            ),
            viewModel.snackbarState.value,
        )
        assertFalse(viewModel.isAddingBooks.value)
        assertEquals(1L, viewModel.libraryOpenRequest.value)
    }

    @Test
    fun publicImportFlowUsesCompactDismissibleSummary() = runBlocking {
        val cases = listOf(
            Triple("Imported.epub", ImportResult.Imported(1), "已导入《Imported》"),
            Triple("Duplicate.epub", ImportResult.Duplicate(1), "书籍已在书架中"),
            Triple("Protected.epub", ImportResult.Rejected(ImportRejection.PROTECTED), "不支持受 DRM 保护的书籍"),
            Triple("Unsupported.zip", ImportResult.Rejected(ImportRejection.UNSUPPORTED_FORMAT), "暂不支持此文件格式"),
            Triple("Unreadable.epub", ImportResult.Rejected(ImportRejection.UNREADABLE), "无法读取所选文件"),
            Triple("Broken.epub", ImportResult.Rejected(ImportRejection.PARSE_FAILED), "无法解析这本书"),
            Triple("Full.epub", ImportResult.Rejected(ImportRejection.STORAGE_FAILED), "存储空间不足或复制失败"),
        )
        val uris = cases.mapIndexed { index, (displayName, result, _) ->
            Uri.parse("content://imports/$index").also { uri ->
                val request = request(displayName)
                every { requestFactory.create(uri) } returns request
                coEvery { service.execute(request) } returns result
            }
        }

        viewModel.importBooks(uris)
        awaitState {
            viewModel.importProgressState.value == ImportProgressState.Complete &&
                !viewModel.isAddingBooks.value
        }
        mainDispatcher.scheduler.runCurrent()

        assertEquals(
            SnackbarState.Visible(
                "处理完成：新增 1 本，已存在 1 本，失败 5 本",
                unlimited = false,
            ),
            viewModel.snackbarState.value,
        )
        viewModel.dismissSnackbar()
        assertEquals(SnackbarState.Hidden, viewModel.snackbarState.value)
        assertFalse(viewModel.isAddingBooks.value)
        assertEquals(1L, viewModel.libraryOpenRequest.value)
    }

    @Test
    fun initializerDoesNotRescanPersistedDirectories() = runBlocking {
        viewModel.viewModelScope.cancel()
        mockkObject(DocumentUtil)
        coEvery { DocumentUtil.getFilesFromDirectory(any(), any()) } returns emptyList()
        every { preferencesUtil.appPrefsFlow } returns flowOf(preferences(setOf("content://saved")))

        viewModel = createViewModel()
        mainDispatcher.scheduler.runCurrent()
        delay(300)

        coVerify(exactly = 0) { DocumentUtil.getFilesFromDirectory(any(), any()) }
        coVerify(exactly = 0) { service.execute(any()) }
    }

    @Test
    fun scanPropagatesCancellationWithoutCompletingOrImportingLaterFiles() = runBlocking {
        val cancelled = document("content://scan/Cancelled.epub", "Cancelled.epub")
        val later = document("content://scan/Later.epub", "Later.epub")
        mockkObject(DocumentUtil)
        coEvery { DocumentUtil.getFilesFromDirectory(any(), any()) } returns listOf(cancelled, later)
        every { requestFactory.create(cancelled.uri) } returns request("Cancelled.epub")
        every { requestFactory.create(later.uri) } returns request("Later.epub")
        val cancellationReached = AtomicBoolean(false)
        coEvery { service.execute(match { it.displayName == "Cancelled.epub" }) } answers {
            cancellationReached.set(true)
            throw CancellationException("stop scan")
        }

        invokeProductionScan(mockk<AppPreferences> {
            every { scanDirectories } returns setOf("content://scan")
        })
        awaitState { cancellationReached.get() && !viewModel.isAddingBooks.value }

        assertEquals(
            ImportProgressState.InProgress(current = 1, total = 2),
            viewModel.importProgressState.value,
        )
        coVerify(exactly = 0) { service.execute(match { it.displayName == "Later.epub" }) }
    }

    @Test
    fun scanUnexpectedFailureEndsInErrorAndNeverReportsTheFileAsAdded() = runBlocking {
        val broken = document("content://scan/Broken.epub", "Broken.epub")
        mockkObject(DocumentUtil)
        coEvery { DocumentUtil.getFilesFromDirectory(any(), any()) } returns listOf(broken)
        every { requestFactory.create(broken.uri) } returns request("Broken.epub")
        val callCount = AtomicInteger(0)
        coEvery { service.execute(any()) } answers {
            callCount.incrementAndGet()
            throw IOException("database unavailable")
        }
        val preferences = mockk<AppPreferences> {
            every { scanDirectories } returns setOf("content://scan")
        }

        repeat(20) { attempt ->
            invokeProductionScan(preferences)
            awaitState {
                callCount.get() == attempt + 1 &&
                    viewModel.importProgressState.value is ImportProgressState.Error &&
                    !viewModel.isAddingBooks.value
            }
            mainDispatcher.scheduler.runCurrent()

            assertEquals(
                ImportProgressState.Error("database unavailable"),
                viewModel.importProgressState.value,
            )
            assertEquals(
                SnackbarState.Visible("Error updating library: database unavailable"),
                viewModel.snackbarState.value,
            )
            assertFalse(viewModel.isAddingBooks.value)
        }
    }

    private fun invokeProductionScan(preferences: AppPreferences) {
        HomeViewModel::class.java.getDeclaredMethod("observeBooks", AppPreferences::class.java)
            .apply { isAccessible = true }
            .invoke(viewModel, preferences)
    }

    private fun document(uri: String, name: String): DocumentFile = mockk {
        every { this@mockk.uri } returns Uri.parse(uri)
        every { this@mockk.name } returns name
    }

    private fun request(displayName: String) = ImportRequest(displayName) {
        error("The mocked service must not open the request")
    }

    private fun preferences(scanDirectories: Set<String>) = AppPreferences(
        isFirstLaunch = false,
        isAssetsBooksFetched = true,
        scanDirectories = scanDirectories,
        enablePdfSupport = true,
        language = "zh",
        appTheme = AppTheme.SYSTEM,
        colorScheme = "default",
        homeLayout = Layout.Grid,
        homeBackgroundImage = "",
        gridCount = 3,
        showEntries = true,
        showRating = true,
        showReadingStatus = true,
        showReadingDates = true,
        showFileTypeLabel = true,
        sortBy = SortOption.TITLE,
        sortOrder = SortOrder.ASCENDING,
        isPremium = false,
        autoOpenLastRead = false,
        lastBookId = 0L,
    )

    private fun createViewModel() = HomeViewModel(
        getBooksUseCase = mockk(relaxed = true),
        getBookUrisUseCase = mockk(relaxed = true),
        insertBookUseCase = mockk(relaxed = true),
        updateBookUseCase = mockk(relaxed = true),
        deleteBookUseCase = mockk(relaxed = true),
        deleteBookByUriUseCase = mockk(relaxed = true),
        getBookByIdUseCase = getBookByIdUseCase,
        getAllBooksUseCase = getAllBooksUseCase,
        getAllReadingActivitiesUseCase = getAllReadingActivitiesUseCase,
        addShelfUseCase = mockk(relaxed = true),
        removeShelfUseCase = mockk(relaxed = true),
        getShelvesUseCase = mockk(relaxed = true),
        addBookToShelfUseCase = mockk(relaxed = true),
        removeBooksFromShelfUseCase = mockk(relaxed = true),
        getBooksForShelfUseCase = mockk(relaxed = true),
        appPreferencesUtil = preferencesUtil,
        fileParser = mockk(relaxed = true),
        permissionRepository = mockk(relaxed = true),
        bookImportService = service,
        importRequestFactory = requestFactory,
        skinService = mockk(relaxed = true),
        application = application,
    )

    private fun dashboardBook(id: Long, fileType: String, filePath: String) = Book(
        id = id,
        title = "Dashboard Book",
        author = "PageNest",
        description = null,
        filePath = filePath,
        coverImage = null,
        scrollIndex = 0,
        scrollOffset = 0,
        progress = 10f,
        lastOpened = 1L,
        category = null,
        fileType = fileType,
    )

    private suspend fun awaitState(predicate: () -> Boolean) {
        withTimeout(5_000L) {
            while (!predicate()) delay(10)
        }
    }
}
