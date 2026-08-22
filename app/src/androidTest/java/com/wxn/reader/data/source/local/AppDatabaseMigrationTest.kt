package com.wxn.reader.data.source.local

import android.content.ContentValues
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wxn.reader.data.dto.BookEntity
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    @Throws(IOException::class)
    fun migrationPreservesLegacyNullsAndEnforcesNonNullShaUniqueness() {
        helper.createDatabase(TEST_DATABASE, 1).apply {
            insert("books", 0, legacyBook("file:/legacy-one.epub", "Legacy One"))
            insert("books", 0, legacyBook("file:/legacy-two.epub", "Legacy Two"))
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query("SELECT COUNT(*) FROM books WHERE sha256 IS NULL").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            database.insert("books", 0, legacyBook("file:/legacy-three.epub", "Legacy Three"))
            database.execSQL("UPDATE books SET sha256 = ? WHERE title = ?", arrayOf(HASH, "Legacy One"))
            var rejectedDuplicate = false
            try {
                database.execSQL(
                    "UPDATE books SET sha256 = ? WHERE title = ?",
                    arrayOf(HASH, "Legacy Two"),
                )
            } catch (_: Exception) {
                rejectedDuplicate = true
            }
            assertTrue("The v2 index must reject duplicate non-null hashes", rejectedDuplicate)
        }
    }

    @Test
    @Throws(IOException::class)
    fun insertIgnoreTransactionReturnsTheRealGeneratedIdAndWinningRow() {
        helper.createDatabase(DAO_DATABASE, 1).close()
        helper.runMigrationsAndValidate(
            DAO_DATABASE,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        ).close()
        val database = Room.databaseBuilder(
            instrumentation.targetContext,
            AppDatabase::class.java,
            DAO_DATABASE,
        ).addMigrations(AppDatabase.MIGRATION_1_2).build()
        try {
            val dao = database.bookDao()
            val inserted = kotlinx.coroutines.runBlocking {
                dao.insertOrGetImport(book("file:/private/first.epub", HASH))
            }
            val existing = kotlinx.coroutines.runBlocking {
                dao.insertOrGetImport(book("file:/private/second.epub", HASH))
            }

            assertTrue(inserted.inserted)
            assertTrue(inserted.id > 0L)
            assertEquals("file:/private/first.epub", inserted.uri)
            assertEquals(inserted.id, existing.id)
            assertEquals("file:/private/first.epub", existing.uri)
            assertTrue(!existing.inserted)
        } finally {
            database.close()
        }
    }

    private fun legacyBook(uri: String, title: String) = ContentValues().apply {
        put("uri", uri)
        put("fileType", "epub")
        put("title", title)
        put("authors", "Author")
        putNull("description")
        putNull("publishDate")
        putNull("publisher")
        putNull("language")
        putNull("numberOfPages")
        put("wordCount", 0L)
        putNull("subjects")
        putNull("coverPath")
        put("locator", "")
        put("progression", 0f)
        putNull("lastOpened")
        put("deleted", false)
        put("rating", 0f)
        put("isFavorite", false)
        put("readingStatus", "NOT_STARTED")
        put("readingTime", 0L)
        putNull("startReadingDate")
        putNull("endReadingDate")
        putNull("review")
        putNull("duration")
        putNull("narrator")
        put("scrollIndex", 0)
        put("scrollOffset", 0)
        put("cachedDir", "")
        put("crc", 0)
    }

    private fun book(uri: String, sha256: String) = BookEntity(
        uri = uri,
        fileType = "epub",
        title = "Imported",
        authors = "Author",
        description = null,
        publishDate = null,
        publisher = null,
        language = null,
        numberOfPages = null,
        wordCount = 0,
        subjects = null,
        coverPath = null,
        locator = "",
        sha256 = sha256,
    )

    private companion object {
        const val TEST_DATABASE = "migration-test"
        const val DAO_DATABASE = "migration-dao-test"
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
