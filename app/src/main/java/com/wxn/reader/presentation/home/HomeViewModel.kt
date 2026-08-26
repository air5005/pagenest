package com.wxn.reader.presentation.home

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.wxn.bookparser.FileParser
import com.wxn.base.bean.Book
import com.air5005.pagenest.library.importing.AndroidImportRequestFactory
import com.air5005.pagenest.library.importing.BookImportService
import com.air5005.pagenest.library.importing.ImportResult
import com.air5005.pagenest.skin.SkinApplyFailure
import com.air5005.pagenest.skin.SkinApplyResult
import com.air5005.pagenest.skin.SkinService
import com.wxn.reader.R
import com.wxn.reader.data.model.AppPreferences
import com.wxn.reader.data.dto.FileType
import com.wxn.reader.data.dto.FileType.Companion.stringToFileType
import com.wxn.reader.data.dto.ReadingStatus
import com.wxn.reader.data.dto.ReadingStatus.Companion.intToReadStatus
import com.wxn.reader.data.model.SortOption
import com.wxn.reader.data.model.SortOrder
import com.wxn.reader.data.source.local.AppPreferencesUtil
import com.wxn.reader.domain.model.Shelf
import com.wxn.reader.domain.use_case.books.DeleteBookByUriUseCase
import com.wxn.reader.domain.use_case.books.DeleteBookUseCase
import com.wxn.reader.domain.use_case.books.GetBookUrisUseCase
import com.wxn.reader.domain.use_case.books.GetBooksUseCase
import com.wxn.reader.domain.use_case.books.InsertBookUseCase
import com.wxn.reader.domain.use_case.books.UpdateBookUseCase
import com.wxn.reader.domain.use_case.shelves.AddBookToShelfUseCase
import com.wxn.reader.domain.use_case.shelves.AddShelfUseCase
import com.wxn.reader.domain.use_case.shelves.GetBooksForShelfUseCase
import com.wxn.reader.domain.use_case.shelves.GetShelvesUseCase
import com.wxn.reader.domain.use_case.shelves.RemoveBooksFromShelfUseCase
import com.wxn.reader.domain.use_case.shelves.RemoveShelfUseCase
import com.wxn.reader.presentation.home.states.ImportProgressState
import com.wxn.reader.presentation.home.states.SnackbarState
import com.wxn.reader.ui.theme.stringResource
import com.wxn.base.util.Logger
import com.wxn.reader.util.PurchaseHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import javax.inject.Inject
import com.wxn.reader.domain.repository.PermissionRepository
import com.wxn.reader.domain.use_case.books.GetBookByIdUseCase
import com.wxn.reader.domain.use_case.books.GetAllBooksUseCase
import com.wxn.reader.domain.use_case.reading_activity.GetAllReadingActivitiesUseCase
import com.wxn.reader.navigation.Screens
import com.wxn.reader.util.DocumentUtil
import androidx.core.net.toUri
import com.wxn.reader.presentation.home.dashboard.HomeDashboardCalculator
import com.wxn.reader.presentation.home.dashboard.HomeDashboardModel
import com.wxn.reader.presentation.home.dashboard.observeHomeDashboard
import java.time.Clock
import java.time.ZoneId

@HiltViewModel
class HomeViewModel
@Inject constructor(
    private val getBooksUseCase: GetBooksUseCase,
    private val getBookUrisUseCase: GetBookUrisUseCase,
    private val insertBookUseCase: InsertBookUseCase,
    private val updateBookUseCase: UpdateBookUseCase,
    private val deleteBookUseCase: DeleteBookUseCase,
    private val deleteBookByUriUseCase: DeleteBookByUriUseCase,
    private val getBookByIdUseCase : GetBookByIdUseCase,
    private val getAllBooksUseCase: GetAllBooksUseCase,
    private val getAllReadingActivitiesUseCase: GetAllReadingActivitiesUseCase,

    private val addShelfUseCase: AddShelfUseCase,
    private val removeShelfUseCase: RemoveShelfUseCase,
    private val getShelvesUseCase: GetShelvesUseCase,
    private val addBookToShelfUseCase: AddBookToShelfUseCase,
    private val removeBooksFromShelfUseCase: RemoveBooksFromShelfUseCase,
    private val getBooksForShelfUseCase: GetBooksForShelfUseCase,
    private val appPreferencesUtil: AppPreferencesUtil,
    private val fileParser: FileParser,
    private val permissionRepository: PermissionRepository,
    private val bookImportService: BookImportService,
    private val importRequestFactory: AndroidImportRequestFactory,
    private val skinService: SkinService,
    application: Application,
) : AndroidViewModel(application) {

    private val context: Context
        get() = getApplication<Application>().applicationContext

    private val _shelves = MutableStateFlow<List<Shelf>>(emptyList())
    val shelves: StateFlow<List<Shelf>> = _shelves.asStateFlow()


    private val _appPreferences = MutableStateFlow<AppPreferences?>(null)
    val appPreferences: StateFlow<AppPreferences?> = _appPreferences.asStateFlow()

    private val _isAddingBooks = MutableStateFlow(false)
    val isAddingBooks: StateFlow<Boolean> = _isAddingBooks.asStateFlow()

    private val _books = MutableStateFlow<PagingData<Book>>(PagingData.empty())
    val books: StateFlow<PagingData<Book>> = _books.asStateFlow()

    private val _selectedBooks = MutableStateFlow<List<Book>>(emptyList())
    val selectedBooks: StateFlow<List<Book>> = _selectedBooks.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()


    private val _booksInShelfSet = MutableStateFlow<Set<Long>>(emptySet())
    val booksInShelfSet: StateFlow<Set<Long>> = _booksInShelfSet.asStateFlow()

    private val _currentShelf = MutableStateFlow<Shelf?>(null)
    private val currentShelf: StateFlow<Shelf?> = _currentShelf.asStateFlow()


    private val _selectedTabRow = MutableStateFlow(0)
    val selectedTabRow: StateFlow<Int> = _selectedTabRow.asStateFlow()

    var selectedTab = mutableStateOf(0)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private @Volatile var hasOpenedLastBook: Boolean = false

    private var refreshJob: Job? = null

    private val _importProgressState = MutableStateFlow<ImportProgressState>(ImportProgressState.Idle)
    val importProgressState: StateFlow<ImportProgressState> = _importProgressState.asStateFlow()

    private val _libraryOpenRequest = MutableStateFlow(0L)
    val libraryOpenRequest: StateFlow<Long> = _libraryOpenRequest.asStateFlow()

    private val _snackbarState = MutableStateFlow<SnackbarState>(SnackbarState.Hidden)
    val snackbarState: StateFlow<SnackbarState> = _snackbarState.asStateFlow()

    private val _openLastBookRoute = MutableStateFlow<String>("")
    val openLastBookRoute :StateFlow<String> = _openLastBookRoute.asStateFlow()

    private val _dashboardState = MutableStateFlow(HomeDashboardModel())
    val dashboardState: StateFlow<HomeDashboardModel> = _dashboardState.asStateFlow()

    private var snackbarJob: Job? = null

    var showLayoutModal = mutableStateOf(false)
    var showSortModal = mutableStateOf(false)
    var showMetadataModal = mutableStateOf(false)

    init {
        initializeApp()
        observeDashboard()
    }

    private fun observeDashboard() {
        viewModelScope.launch {
            val readingActivities = try {
                getAllReadingActivitiesUseCase()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Logger.e("HomeViewModel: reading dashboard activity source unavailable")
                kotlinx.coroutines.flow.flowOf(emptyList())
            }
            observeHomeDashboard(
                books = getAllBooksUseCase(),
                activities = readingActivities,
                calculator = HomeDashboardCalculator(),
                clock = Clock.systemDefaultZone(),
                zoneId = ZoneId.systemDefault(),
            ).collect { dashboard ->
                _dashboardState.value = dashboard
            }
        }
    }

    private fun initializeApp() {
        viewModelScope.launch {
            Logger.running("LIBRARY", "Shelf initialization started")
            skinService.reconcile()
            val storedPreferences = appPreferencesUtil.appPrefsFlow.first()
            val preferences = storedPreferences.copy(
                homeBackgroundImage = skinService.effectiveHomeBackground(storedPreferences.homeBackgroundImage),
            )
            coroutineScope {
                launch { loadShelves() }
                launch { observeAppPreferences() }
                if (preferences.scanDirectories.isEmpty()) {
                    launch {
                        when (seedPublicDomainBookSafely { addPublicDomainBooksIfNeeded() }) {
                            PublicDomainBookSeedResult.Seeded -> Unit
                            PublicDomainBookSeedResult.Failed -> Logger.e(
                                "HomeViewModel: optional public-domain sample could not be loaded",
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun addPublicDomainBooksIfNeeded() {
        val publicDomainBook = "alice_in_wonderlands.epub"
        val existingUris = getBookUrisUseCase().toSet()
        val internalFile = copyAssetToInternalStorage(publicDomainBook)
        val internalUri = Uri.fromFile(internalFile).toString()
        if (!existingUris.contains(internalUri)) {
            getBookInfoFromInternalFile(internalFile)?.let { book ->
                insertBookUseCase.insert(arrayListOf(book.copy(filePath = internalUri)))
            }
        }
    }

    private fun copyAssetToInternalStorage(fileName: String): File {
        val assetManager = context.assets
        val internalDir = File(context.filesDir, "books")
        if (!internalDir.exists()) internalDir.mkdirs()
        val internalFile = File(internalDir, fileName)
        if (!internalFile.exists()) {
            assetManager.open("books/$fileName").use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return internalFile
    }

    private suspend fun getBookInfoFromInternalFile(file: File): Book? {
        val documentFile = DocumentFile.fromFile(file)
        return fileParser.parse(documentFile)
    }

    private suspend fun loadBooks(preferences: AppPreferences) {
        val sortBy = preferences.sortBy
        val sortOrder = preferences.sortOrder
        val readingStatus = preferences.readingStatus
        val fileType = preferences.fileTypes
        val isAscending = sortOrder == SortOrder.ASCENDING

        val autoLoadLastBook = preferences.autoOpenLastRead
        val lastOpenBookId = preferences.lastBookId

        Logger.d("HomeViewModel::loadBooks::hasOpenedLastBook[$hasOpenedLastBook],lastOpenBookId=[$lastOpenBookId]:autoLoadLastBook[$autoLoadLastBook]")
        val flag = hasOpenedLastBook
        hasOpenedLastBook = true
        if (!flag && autoLoadLastBook && lastOpenBookId > 0) {
            openLastOpenBook(lastOpenBookId){ route ->
                _openLastBookRoute.value = route
            }
        }

        combine(
            getBooksUseCase.getSortedBooks(sortBy, isAscending, readingStatus, fileType),
            searchQuery,
            currentShelf,
            booksInShelfSet,
            selectedTabRow
        ) { books, query, shelf, shelfBookIds, selectedTabRow ->
            books.filter { book ->
                val matchesSearch =
                    query.isBlank() || book.title.contains(query, ignoreCase = true)
                val matchesShelf = shelf == null || book.id in shelfBookIds
                val matchesTab = if (selectedTabRow == HomeTopLevelDestination.AUDIO.index) {
                    stringToFileType(book.fileType) == FileType.AUDIOBOOK
                } else {
                    stringToFileType(book.fileType) != FileType.AUDIOBOOK
                }
                matchesSearch && matchesShelf && matchesTab
            }
        }.collect { data ->
            _books.value =  PagingData.from(data)
        }
    }

    fun resetLastBookOpenRoute() {
        _openLastBookRoute.value = ""
    }

    private fun loadShelves() {
        viewModelScope.launch {
            getShelvesUseCase().collect { shelves ->
                _shelves.value = shelves
            }
        }
    }

    fun updateCurrentShelf(shelf: Shelf?) {
        _currentShelf.value = shelf
    }


    fun updateCurrentTabRow(tab: Int) {
        _selectedTabRow.value = tab
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    private fun observeAppPreferences() {
        viewModelScope.launch {
            appPreferencesUtil.appPrefsFlow.collectLatest { preferences ->
                _appPreferences.value = preferences.copy(
                    homeBackgroundImage = skinService.effectiveHomeBackground(preferences.homeBackgroundImage),
                )
                // Cancel the previous long-lived query before applying new sort/filter preferences.
                loadBooks(preferences)
            }
        }
    }

    fun refreshBooks() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(500)
            showSnackbar(stringResource(R.string.refreshing_library))
                _appPreferences.value = appPreferencesUtil.appPrefsFlow.first()
            val appPref = _appPreferences.value ?: return@launch
            val scanDirectory = appPref.scanDirectories
            if (scanDirectory.isNotEmpty()) {
                Logger.running("BOOK_IMPORT", "Manual directory scan requested directories=${scanDirectory.size}")
                observeBooks(appPref)
            } else {
                Logger.warning("BOOK_IMPORT", "Manual directory scan skipped reason=NO_DIRECTORY")
                showSnackbar(stringResource(R.string.no_scan_directory))
            }
        }
    }

    private fun showSnackbar(
        message: String,
        unlimited: Boolean = false,
    ) {
        snackbarJob?.cancel()
        snackbarJob = viewModelScope.launch {
            _snackbarState.value = SnackbarState.Visible(
                message = message,
                unlimited = unlimited,
            )

            if(!unlimited) {
                delay(3000)
                hideSnackbar()
            }
        }


    }

    fun dismissSnackbar() {
        snackbarJob?.cancel()
        _snackbarState.value = SnackbarState.Hidden
    }

    private fun hideSnackbar() = dismissSnackbar()

    private fun observeBooks(preferences: AppPreferences) {
        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Logger.e(throwable)
            _importProgressState.value =
                ImportProgressState.Error(throwable.message ?: "Unknown error occurred")
            _isAddingBooks.value = false
            showSnackbar(
                message = "Error during import: ${throwable.message}",
            )
        }) {
            val start = System.currentTimeMillis()
            try {
                val step1 = System.currentTimeMillis()
                Logger.d("HomeViewModel::observeBooks::step1=${step1 - start}")

                //从用户目录中导入的书籍文件列表
                val documentFiles = mutableListOf<DocumentFile>()
                preferences.scanDirectories.forEach { directoryPath ->
                    Logger.d("HomeViewModel::observeBooks::directoryPath=$directoryPath")
                    val filesInDirectory = DocumentUtil.getFilesFromDirectory(context, directoryPath.toUri())
                    Logger.d("HomeViewModel::observeBooks::filesInDirectory=${filesInDirectory.size}")
                    documentFiles.addAll(filesInDirectory)
                }
                val step2 = System.currentTimeMillis()
                Logger.d("HomeViewModel::observeBooks::step2=${step2 - step1}")
                //从文件列表中的文件去重复
                val uniqueFiles = documentFiles.distinctBy { it.uri.toString() } //去重复

                // Imported rows point at durable private files, so source URIs are not database keys.
                // The SHA-256 catalog performs the authoritative duplicate check.
                val newBooks = uniqueFiles
                Logger.d("HomeViewModel::observeBooks::newBooks.size=${newBooks.size}")
                Logger.running("BOOK_IMPORT", "Directory scan completed candidates=${newBooks.size}")
                if (newBooks.isNotEmpty()) {        //有新增加的，则将新增加的加入到数据库中
                    _isAddingBooks.value = true
                    _importProgressState.value = ImportProgressState.InProgress(0, newBooks.size)
                    val importResults = mutableListOf<ImportResult>()
                    showSnackbar(
                        message = stringResource(R.string.adding_new_book_to_library)
                    )

//                    // Process books in smaller batches
                    newBooks.chunked(5).forEachIndexed { batchIndex, batch ->
                        // Add delay between batches to prevent overwhelming the system
                        if (batchIndex > 0) delay(100)

                        batch.forEachIndexed { index, documentFile ->
                            val totalProcessed = (batchIndex * 5) + index + 1
                            _importProgressState.value =
                                ImportProgressState.InProgress(totalProcessed, newBooks.size)

                            // Update snackbar with progress
                            showSnackbar(
                                message = stringResource(R.string.adding_books_num, totalProcessed, newBooks.size),
                                unlimited = true
                            )
                            val request = importRequestFactory.create(documentFile.uri)
                            val result = bookImportService.execute(request)
                            importResults += result
                        }
                    }
                    _importProgressState.value = ImportProgressState.Complete
                    requestLibraryOpenAfterImport(importResults)
                    showSnackbar(
                        message = importSummary(importResults),
                    )
                    Logger.running("BOOK_IMPORT", importSummaryForLog(importResults))
                    _isAddingBooks.value = false
                }

            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                _importProgressState.value = ImportProgressState.Error(e.message ?: "Unknown error occurred")
                Logger.error("BOOK_IMPORT", "Directory scan failed", e)
                showSnackbar(
                    message = stringResource(R.string.error_updateing_library, e.message ?: "Unknown error occurred")
                )
            } finally {
                _isAddingBooks.value = false
            }
        }
    }

    fun updateAppPreferences(newPreferences: AppPreferences) {
        viewModelScope.launch {
//            Logger.d("HomeViewModel::updateAppPreferences:the home viewModel")
            appPreferencesUtil.updateAppPreferences(newPreferences)
            _appPreferences.value = newPreferences
        }
    }

    fun importAndApplySkin(source: String) {
        viewModelScope.launch {
            when (val result = skinService.importAndApply(source)) {
                is SkinApplyResult.Success -> {
                    _appPreferences.value = appPreferencesUtil.appPrefsFlow.first()
                    showSnackbar(stringResource(R.string.skin_apply_success))
                }
                is SkinApplyResult.Failure -> showSnackbar(result.reason.localizedSkinError())
            }
        }
    }

    fun resetSkin() {
        viewModelScope.launch {
            when (val result = skinService.reset()) {
                is SkinApplyResult.Success -> {
                    _appPreferences.value = appPreferencesUtil.appPrefsFlow.first()
                    showSnackbar(stringResource(R.string.skin_reset_success))
                }
                is SkinApplyResult.Failure -> showSnackbar(result.reason.localizedSkinError())
            }
        }
    }

    private fun SkinApplyFailure.localizedSkinError(): String = when (this) {
        SkinApplyFailure.INVALID_IMAGE -> stringResource(R.string.skin_error_invalid_image)
        SkinApplyFailure.IMAGE_TOO_LARGE -> stringResource(R.string.skin_error_too_large)
        SkinApplyFailure.IO -> stringResource(R.string.skin_error_io)
        SkinApplyFailure.PREFERENCES -> stringResource(R.string.skin_error_save)
    }

    fun resetLayoutPreferences() {
        viewModelScope.launch {
            appPreferencesUtil.resetLayoutPreferences()
            _appPreferences.value = appPreferencesUtil.appPrefsFlow.first()
        }
    }

    fun addShelf(shelfName: String) {
        viewModelScope.launch {
            try {
                val currentShelves = _shelves.value
                val newOrder = currentShelves.size
                val newShelfId = addShelfUseCase(shelfName, newOrder)
                _shelves.value += Shelf(id = newShelfId, name = shelfName, order = newOrder)
            } catch (e: Exception) {
//                showSnackbar("Failed to add shelf: ${e.message}" )
                showSnackbar(stringResource(R.string.failed_to_add_shelf, e.message.orEmpty()))
            }
        }
    }

    fun removeShelf(shelf: Shelf) {
        viewModelScope.launch {
            try {
                removeShelfUseCase(shelf)
                _shelves.value = _shelves.value.filter { it.id != shelf.id }
            } catch (e: Exception) {
                showSnackbar(stringResource(R.string.failed_remove_shelf, e.message.orEmpty()))
            }
        }
    }

    fun removeBooks(books: List<Book>, hardRemove: Boolean) {
        viewModelScope.launch {
            try {
                if (hardRemove) {
                    books.forEach { book ->
                        try {
                            val uri = Uri.parse(book.filePath)
                            if (uri.scheme == "content") {
                                // Use ContentResolver to delete the file
                                val contentResolver = context.contentResolver
                                val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                                    uri,
                                    DocumentsContract.getDocumentId(uri)
                                )
                                if (DocumentsContract.deleteDocument(
                                        contentResolver,
                                        documentUri
                                    )
                                ) {
                                    deleteBookUseCase(book)
                                } else {
                                    showSnackbar(stringResource(R.string.failed_delete_book, book.title))
                                }
                            } else {
                                // Handle cases where the URI is not a content URI (e.g., file://)
                                val bookFile = uri.path?.let { File(it) }
                                if (bookFile != null) {
                                    if (bookFile.exists() && bookFile.delete()) {
                                        deleteBookUseCase(book)
                                    } else {
                                        showSnackbar(stringResource(R.string.failed_delete_book, book.title))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            showSnackbar(
                                stringResource(R.string.failed_delete_book_info, book.title, e.message.orEmpty())
                            )
                        }
                    }
                } else {
                    books.map { book ->
                        book.copy(deleted = true)
                    }.forEach {
                        updateBookUseCase(it)
                    }
                }
                showSnackbar(stringResource(R.string.book_remove_success))
            } catch (e: Exception) {
                showSnackbar(stringResource(R.string.failed_delete_book, e.message.toString()))
            }
        }
    }

    fun addBooksToShelves(bookIds: List<Long>, shelfIds: List<Long>) {
        viewModelScope.launch {
            try {
                bookIds.forEach { bookId ->
                    shelfIds.forEach { shelfId ->
                        addBookToShelfUseCase(bookId, shelfId)
                    }
                }
                showSnackbar(stringResource(R.string.books_add_shelf_success))
            } catch (e: Exception) {
                showSnackbar(stringResource(R.string.add_book_shelf_failed, e.message.toString()))
            }
        }
    }

    fun removeBooksFromShelves(bookIds: List<Long>, shelfIds: List<Long>) {
        viewModelScope.launch {
            try {
                bookIds.forEach { bookId ->
                    shelfIds.forEach { shelfId ->
                        removeBooksFromShelfUseCase(bookId, shelfId)
                    }
                }

                _booksInShelfSet.value -= bookIds.toSet()
//                showSnackbar("Books removed from shelf successfully" )
                showSnackbar(stringResource(R.string.remove_books_from_shelf_success))
            } catch (e: Exception) {
//                showSnackbar("Failed to remove books from shelf: ${e.message}" )
                showSnackbar(stringResource(R.string.remove_books_from_shelf_fail, e.message.toString()))
            }
        }
    }

    fun getBooksForShelfSelection(shelfId: Long): Flow<List<Book>> {
        return getBooksForShelfUseCase(shelfId)
    }

    fun getBooksForShelf(shelfId: Long): List<Book> {
        var booksList: List<Book> = emptyList()
        viewModelScope.launch {
            getBooksForShelfUseCase(shelfId).collect { books: List<Book> ->
                booksList = books
                _booksInShelfSet.value = books.map { it.id }.toSet()
            }
        }
        return booksList
    }


    fun toggleBookSelection(book: Book) {
        _selectedBooks.value = if (_selectedBooks.value.contains(book)) {
            _selectedBooks.value - book
        } else {
            _selectedBooks.value + book
        }
        _selectionMode.value = _selectedBooks.value.isNotEmpty()
    }

    fun selectAllBooks(books: List<Book>){
        _selectedBooks.value = books
    }

    fun clearBookSelection() {
        _selectedBooks.value = emptyList()
        _selectionMode.value = false
    }

    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Logger.running("BOOK_IMPORT", "Manual file import started count=${uris.size}")
        viewModelScope.launch(Dispatchers.IO) {
            _isAddingBooks.value = true
            _importProgressState.value = ImportProgressState.InProgress(0, uris.size)
            val results = mutableListOf<ImportResult>()
            try {
                uris.forEachIndexed { index, uri ->
                    val request = importRequestFactory.create(uri)
                    val result = bookImportService.execute(request)
                    results += result
                    _importProgressState.value = ImportProgressState.InProgress(index + 1, uris.size)
                }
                _importProgressState.value = ImportProgressState.Complete
                requestLibraryOpenAfterImport(results)
                showSnackbar(importSummary(results))
                Logger.running("BOOK_IMPORT", importSummaryForLog(results))
            } catch (cancellation: CancellationException) {
                Logger.running("BOOK_IMPORT", "Manual file import cancelled")
                throw cancellation
            } catch (failure: Exception) {
                _importProgressState.value = ImportProgressState.Error("Import failed")
                Logger.error("BOOK_IMPORT", "Manual file import failed", failure)
                showSnackbar(stringResource(R.string.discovery_error_import))
            } finally {
                _isAddingBooks.value = false
            }
        }
    }

    fun updateBook(updatedBook: Book, updatedReadingStatus: Boolean = false) {
        viewModelScope.launch {
            var updateBook: Book = updatedBook
            if (updatedReadingStatus) {
                updateBook = when (intToReadStatus(updatedBook.readingStatus)) {
                    ReadingStatus.NOT_STARTED -> updatedBook.copy(
                        startReadingDate = null,
                        endReadingDate = null,
                        readingTime = 0,
                        progress = 0f
                    )

                    ReadingStatus.IN_PROGRESS -> updatedBook.copy(
                        startReadingDate = System.currentTimeMillis(),
                        endReadingDate = null,
                        readingTime = 0,
                        progress = 0f
                    )

                    ReadingStatus.FINISHED -> updatedBook.copy(
                        endReadingDate = System.currentTimeMillis(),
                        progress = 100f
                    )

                    else -> updatedBook
                }
            }

            updateBookUseCase(updateBook)
        }
    }

    fun sortBooks(sortOption: SortOption, sortOrder: SortOrder) {
        viewModelScope.launch {
            val appPref = _appPreferences.value ?: return@launch
            val isAscending = sortOrder == SortOrder.ASCENDING
            val readingStatus = appPref.readingStatus
            val fileType = appPref.fileTypes
            try {
                getBooksUseCase(sortOption, isAscending, readingStatus, fileType)
                    .cachedIn(viewModelScope)
                    .collect { pagingData ->
                        _books.value = pagingData
                    }
            } catch (e: Exception) {
                Logger.e("HomeViewModel:Error sorting books: ${e.message}")
            }
        }
    }

    fun filterBooks(option: Any) {
        viewModelScope.launch {
            val currentPreferences = _appPreferences.value ?: return@launch
            val newPreferences = when (option) {
                is ReadingStatus -> {
                    val newStatuses = if (option in currentPreferences.readingStatus) {
                        currentPreferences.readingStatus - option
                    } else {
                        currentPreferences.readingStatus + option
                    }
                    Logger.d("HomeViewModel:filterBooks:readingStatus[${newStatuses}]")
                    currentPreferences.copy(readingStatus = newStatuses)
                }

                is FileType -> {
                    val newFileTypes = if (option in currentPreferences.fileTypes) {
                        emptySet()  // Deselect if it's the only selected option
                    } else {
                        setOf(option)  // Select only this option
                    }
                    Logger.d("HomeViewModel:filterBooks:fileType[${newFileTypes}]")
                    currentPreferences.copy(fileTypes = newFileTypes)
                }

                else -> currentPreferences
            }

            updateAppPreferences(newPreferences)
        }
    }

    fun purchasePremium(purchaseHelper: PurchaseHelper) {
        purchaseHelper.makePurchase()
        viewModelScope.launch {
            purchaseHelper.isPremium.collect { isPremium ->
                updatePremiumStatus(isPremium)
            }
        }
    }

    fun updatePremiumStatus(isPremium: Boolean) {
        viewModelScope.launch {
            val currentPreferences = appPreferencesUtil.appPrefsFlow.first()
            if (currentPreferences.isPremium != isPremium) {
                val updatedPreferences = currentPreferences.copy(isPremium = isPremium)
                Logger.d("HomeViewModel:updatePremiumStatus:the home viewModel")
                appPreferencesUtil.updateAppPreferences(updatedPreferences)
                _appPreferences.value = updatedPreferences
            }
        }
    }

    fun addScanDirectory(uri: Uri) {
        viewModelScope.launch {
            val appPref = _appPreferences.value
            val currentDirectories = appPref?.scanDirectories ?: return@launch
            val directory = uri.toString()
            permissionRepository.grantPersistableUriPermission(uri)
            if (!currentDirectories.contains(directory)) {
                val updatedDirectories = currentDirectories + directory
                Logger.d("SettingsViewModel:addScanDirectory:the Settings viewModel")
                val newPrefs = appPref.copy(scanDirectories = updatedDirectories)
                appPreferencesUtil.updateAppPreferences(newPrefs)
                _appPreferences.value = newPrefs
                refreshBooks()
            }
        }
    }

    suspend fun openLastOpenBook(lastOpenBookId: Long, onRouteNav: (String)->Unit) {
        Logger.d("HomeViewModel::openLastOpenBook::lastBookId[$lastOpenBookId]")
        if (lastOpenBookId > 0) {
            getBookByIdUseCase(lastOpenBookId)?.let { lastBook ->
                Logger.d("HomeViewModel::openLastOpenBook::lastBook[$lastBook]")
                openBook(lastBook, onRouteNav)
            }
        }
    }

    private fun importSummary(results: List<ImportResult>): String {
        val imported = results.count { it is ImportResult.Imported }
        val duplicates = results.count { it is ImportResult.Duplicate }
        val failed = results.size - imported - duplicates
        return stringResource(R.string.import_result_summary, imported, duplicates, failed)
    }

    private fun requestLibraryOpenAfterImport(results: List<ImportResult>) {
        if (results.none { it is ImportResult.Imported }) return
        _currentShelf.value = null
        _libraryOpenRequest.value += 1
    }

    private fun importSummaryForLog(results: List<ImportResult>): String {
        val imported = results.count { it is ImportResult.Imported }
        val duplicates = results.count { it is ImportResult.Duplicate }
        val failed = results.size - imported - duplicates
        return "Import completed imported=$imported duplicates=$duplicates failed=$failed"
    }

    fun openDashboardBook(bookId: Long) {
        viewModelScope.launch {
            val book = getBookByIdUseCase(bookId)
            if (book == null) {
                Logger.warning("READER_ROUTE", "Reader open skipped reason=BOOK_NOT_FOUND")
            } else {
                Logger.running("READER_ROUTE", "Reader open requested format=${book.fileType.uppercase()}")
                openBook(book) { route -> _openLastBookRoute.value = route }
            }
        }
    }

    fun openBook(openedBook: Book, onRouteNav: (String)->Unit) {
        val encodedUri = Uri.encode(openedBook.filePath)
        val route = when (stringToFileType(openedBook.fileType)) {
            FileType.PDF -> Screens.PdfReaderScreen.route + "/${openedBook.id}/${encodedUri}"
            FileType.AUDIOBOOK -> Screens.AudiobookReaderScreen.route + "/${openedBook.id}/${encodedUri}"
            else -> {
                Screens.MainReaderScreen.route + "/${openedBook.id}/${encodedUri}"
            }
        }
        if (route.isNotEmpty()) {
            onRouteNav(route)
        }
    }
}
