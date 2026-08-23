package com.air5005.pagenest.speech.content

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.air5005.pagenest.library.importing.SupportedBookFormat
import com.wxn.base.bean.Book
import com.wxn.base.bean.BookChapter
import com.wxn.base.bean.ReaderText
import com.wxn.bookparser.domain.file.CachedFileCompat
import com.wxn.bookparser.impl.TextParserImpl
import com.wxn.bookparser.parser.base.DocumentParser
import com.wxn.bookparser.parser.base.MarkdownParser
import com.wxn.bookparser.parser.epub.EpubTextParser
import com.wxn.bookparser.parser.fb2.Fb2TextParser
import com.wxn.bookparser.parser.html.HtmlTextParser
import com.wxn.bookparser.parser.mobi.MobiTextParser
import com.wxn.bookparser.parser.pdf.PdfTextParser
import com.wxn.bookparser.parser.txt.TxtTextParser
import com.wxn.bookread.provider.ChapterProvider
import com.wxn.mobi.EpubParser
import com.wxn.mobi.MobiParser
import com.wxn.reader.data.source.local.AppPreferencesUtil
import com.wxn.reader.domain.use_case.annotations.GetAnnotationsUseCase
import com.wxn.reader.domain.use_case.bookmarks.GetBookmarksForBookUseCase
import com.wxn.reader.domain.use_case.books.UpdateBookUseCase
import com.wxn.reader.domain.use_case.chapters.GetChapterByIdUserCase
import com.wxn.reader.domain.use_case.chapters.GetChapterCountByBookIdUserCase
import com.wxn.reader.domain.use_case.chapters.UpdateChapterWordCountUserCase
import com.wxn.reader.domain.use_case.notes.GetNotesForBookUseCase
import com.wxn.reader.presentation.mainReader.PageViewController
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.commonmark.parser.Parser
import org.junit.rules.TemporaryFolder
import org.robolectric.shadows.ShadowContentResolver

internal data class RealParserSpeechPipeline(
    val file: File,
    val source: ReflowableSpeechContentSource,
)

internal class RealParserSpeechHarness(
    private val temporaryFolder: TemporaryFolder,
    private val testScheduler: TestCoroutineScheduler,
) {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repositoryRoot = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) {
        it.parentFile
    }.first { File(it, "settings.gradle.kts").isFile }

    suspend fun open(format: SupportedBookFormat): RealParserSpeechPipeline {
        val fixture = when (format) {
            SupportedBookFormat.EPUB -> repositoryFile("app/src/main/assets/books/alice_in_wonderlands.epub")
            SupportedBookFormat.TXT -> temporaryFolder.newFile("literal.txt").apply {
                writeText("TXT literal title\nTXT literal body")
            }
            SupportedBookFormat.MOBI -> repositoryFile(
                "mobi/src/main/cpp/libmobi/tests/samples/sample-unicode-uncompressed.mobi",
            )
            SupportedBookFormat.AZW3 -> temporaryFolder.newFile("literal.azw3").also { target ->
                repositoryFile("mobi/src/main/cpp/libmobi/tests/samples/sample-unicode-uncompressed.mobi")
                    .copyTo(target, overwrite = true)
            }
            SupportedBookFormat.PDF -> error("PDF is not a reflowable parser fixture")
        }
        check(fixture.isFile && fixture.length() > 0) { "Missing real fixture: $fixture" }
        val bookId = when (format) {
            SupportedBookFormat.EPUB -> 201L
            SupportedBookFormat.TXT -> 202L
            SupportedBookFormat.MOBI -> 203L
            SupportedBookFormat.AZW3 -> 204L
            SupportedBookFormat.PDF -> error("PDF is not a reflowable parser fixture")
        }
        val chapterBodies = when (format) {
            SupportedBookFormat.EPUB -> listOf("EPUB literal body", "EPUB second chapter")
            SupportedBookFormat.MOBI -> listOf("MOBI literal body", "MOBI second chapter")
            SupportedBookFormat.AZW3 -> listOf("AZW3 literal body", "AZW3 second chapter")
            SupportedBookFormat.TXT -> emptyList()
            SupportedBookFormat.PDF -> error("PDF is not a reflowable parser fixture")
        }
        return openPipeline(bookId, format, fixture, chapterBodies)
    }

    suspend fun openEpubNavigationFixture(): RealParserSpeechPipeline = openPipeline(
        bookId = 205L,
        format = SupportedBookFormat.EPUB,
        fixture = repositoryFile("app/src/main/assets/books/alice_in_wonderlands.epub"),
        chapterBodies = listOf("EPUB page zero EPUB page one", "EPUB unloaded target"),
        navigationFixture = true,
    )

    private suspend fun openPipeline(
        bookId: Long,
        format: SupportedBookFormat,
        fixture: File,
        chapterBodies: List<String>,
        navigationFixture: Boolean = false,
    ): RealParserSpeechPipeline {
        initializePagination()
        val chapters = if (format == SupportedBookFormat.TXT) {
            emptyList()
        } else {
            chapterBodies.indices.map { index ->
                chapter(bookId, index, chapterBodies.size, chapterBodies[index].length.toLong())
            }
        }
        var platformPath: String? = null
        when (format) {
            SupportedBookFormat.EPUB -> {
                mockkObject(EpubParser)
                every { EpubParser.getEpubChapter(any(), bookId, any()) } answers {
                    platformPath = thirdArg()
                    chapters.toTypedArray()
                }
                every { EpubParser.getEpubChapterData(any(), any(), any()) } answers {
                    val chapter = thirdArg<BookChapter>()
                    if (navigationFixture && chapter.chapterIndex == 0) {
                        arrayOf(
                            ReaderText.Text("EPUB page zero"),
                            ReaderText.Image("fixture-page-break.png", width = 10, height = 100),
                            ReaderText.Text("EPUB page one"),
                        )
                    } else {
                        arrayOf(ReaderText.Text(chapterBodies[chapter.chapterIndex]))
                    }
                }
                every { EpubParser.getEpubCssInfo(any(), bookId, any(), any(), any()) } returns emptyList()
            }
            SupportedBookFormat.MOBI, SupportedBookFormat.AZW3 -> {
                mockkObject(MobiParser)
                every { MobiParser.getMobiChapter(any(), bookId, any()) } answers {
                    platformPath = thirdArg()
                    chapters.toTypedArray()
                }
                every { MobiParser.getMobiChapterData(any(), any(), any()) } answers {
                    val chapter = thirdArg<BookChapter>()
                    arrayOf(ReaderText.Text(chapterBodies[chapter.chapterIndex]))
                }
            }
            SupportedBookFormat.TXT -> Unit
            SupportedBookFormat.PDF -> error("PDF is not a reflowable parser fixture")
        }

        val parser = realTextParser()
        val fixtureUri = registerFixture(fixture, bookId)
        val cachedFile = CachedFileCompat.fromUri(context, fixtureUri)
        val parsedChapters = parser.parseChapterInfo(bookId, cachedFile)
        check(parsedChapters.isNotEmpty()) { "The real ${format.name} parser returned no chapters" }
        val chapterUseCase = mockk<GetChapterByIdUserCase>()
        parsedChapters.forEach { parsed ->
            every { chapterUseCase(bookId, parsed.chapterIndex) } returns flowOf(parsed)
        }
        val controller = PageViewController(
            context = context,
            getChapterByIdUserCase = chapterUseCase,
            getChapterCountByBookIdUserCase = mockk(relaxed = true),
            getAnnotationsUseCase = mockk<GetAnnotationsUseCase>(relaxed = true),
            getNotesForBookUseCase = mockk<GetNotesForBookUseCase>(relaxed = true),
            getBookmarksForBookUseCase = mockk<GetBookmarksForBookUseCase>(relaxed = true),
            updateChapterWordCountUserCase = mockk<UpdateChapterWordCountUserCase>(relaxed = true),
            updateBookUseCase = mockk<UpdateBookUseCase>(relaxed = true),
            appPreferencesUtil = AppPreferencesUtil(context),
            textParser = parser,
        ).apply {
            book = book(bookId, fixtureUri, format)
            chapterSize = parsedChapters.size
            durChapterIndex = 0
            durPageIndex = 0
            curTextChapter = null
            nextTextChapter = null
            prevTextChapter = null
        }
        return RealParserSpeechPipeline(
            file = File(platformPath ?: fixture.absolutePath),
            source = ReflowableSpeechContentSource(bookId, controller, SpeechSegmenter()),
        )
    }

    private fun realTextParser(): TextParserImpl {
        val markdown = MarkdownParser(Parser.builder().build())
        return TextParserImpl(
            txtTextParser = TxtTextParser(markdown),
            pdfTextParser = PdfTextParser(markdown, context as Application),
            epubTextParser = EpubTextParser(context),
            htmlTextParser = HtmlTextParser(context, DocumentParser(markdown)),
            fb2TextParser = Fb2TextParser(context),
            mobiTextParser = MobiTextParser(context),
        )
    }

    private fun initializePagination() {
        ChapterProvider.readerPreferencesUtil = null
        ChapterProvider.readTipPreferencesUtil = null
        ChapterProvider.viewWidth = 320
        ChapterProvider.viewHeight = 25
        val initialized = CompletableDeferred<Unit>()
        ChapterProvider.upStyle(context) { initialized.complete(Unit) }
        testScheduler.advanceUntilIdle()
        check(initialized.isCompleted) { "ChapterProvider did not initialize on Main" }
    }

    private fun chapter(bookId: Long, index: Int, size: Int, wordCount: Long) = BookChapter(
        id = index + 1L,
        chapterId = "chapter-$index",
        parentChapterId = "",
        bookId = bookId,
        chapterIndex = index,
        chapterName = "Chapter $index",
        createTimeValue = 0,
        updateDate = "",
        updateTimeValue = 0,
        chapterUrl = "chapter-$index.xhtml",
        srcName = "chapter-$index.xhtml",
        chaptersSize = size,
        wordCount = wordCount,
        picCount = 0,
        count = wordCount,
        chapterProgress = index.toFloat() / size.coerceAtLeast(1),
    )

    private fun book(bookId: Long, fixtureUri: Uri, format: SupportedBookFormat) = Book(
        id = bookId,
        title = "${format.name} fixture",
        author = "Parser test",
        description = "Real parser/controller speech pipeline fixture",
        filePath = fixtureUri.toString(),
        coverImage = null,
        scrollIndex = 0,
        scrollOffset = 0,
        progress = 0f,
        lastOpened = null,
        category = null,
        fileType = format.extension,
    )

    private fun repositoryFile(relativePath: String) = File(repositoryRoot, relativePath)

    private fun registerFixture(fixture: File, bookId: Long): Uri {
        val authority = "com.air5005.pagenest.parser.$bookId.${System.nanoTime()}"
        val provider = ParserFixtureProvider().apply { servedFile = fixture }
        provider.attachInfo(
            context,
            ProviderInfo().apply { this.authority = authority },
        )
        ShadowContentResolver.registerProviderInternal(authority, provider)
        return Uri.parse("content://$authority/${fixture.name}")
    }
}

internal class ParserFixtureProvider : ContentProvider() {
    lateinit var servedFile: File

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(servedFile, ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> servedFile.name
                    DocumentsContract.Document.COLUMN_SIZE -> servedFile.length()
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> servedFile.lastModified()
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/octet-stream"
                    else -> null
                }
            })
        }
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
