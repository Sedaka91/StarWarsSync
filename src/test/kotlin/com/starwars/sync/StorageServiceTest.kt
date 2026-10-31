package com.starwars.sync

import com.starwars.sync.model.*
import com.starwars.sync.storage.StorageService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StorageServiceTest {

    private lateinit var tempDir: File
    private lateinit var storage: StorageService

    @BeforeEach
    fun setUp() {
        tempDir = File.createTempFile("sw_sync_test_", "").apply {
            delete()
            mkdirs()
        }
        storage = StorageService(tempDir)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testSeedAndCrud() {
        val articles = storage.getAllArticles()
        assertTrue(articles.isNotEmpty(), "Sample articles should be seeded")

        val contracts = storage.getAllContracts()
        assertTrue(contracts.isNotEmpty(), "Sample contracts should be seeded")

        // Create new article
        val newArt = Article(
            name = "Thermal-Detonator",
            category = ArticleCategory.WEAPON,
            status = ArticleStatus.BEREIT,
            quantity = 5,
            costs = 200.0,
            price = 600.0
        )
        val saved = storage.saveArticle(newArt)
        assertTrue(saved.id != "0")
        assertEquals("Thermal-Detonator", storage.getArticle(saved.id)?.name)

        // Delete article
        val deleted = storage.deleteArticle(saved.id)
        assertTrue(deleted)
        assertEquals(null, storage.getArticle(saved.id))
    }

    @Test
    fun testBidirectionalSync() {
        // Prepare incoming bundle with an existing updated article and a brand new article
        val existing = storage.getAllArticles().first()
        val modifiedExisting = existing.copy(
            price = 9999.0,
            updatedAt = System.currentTimeMillis() + 10000L
        )

        val brandNew = Article(
            id = "999",
            name = "Mandalorian Jetpack",
            category = ArticleCategory.MISC,
            price = 12000.0,
            updatedAt = System.currentTimeMillis()
        )

        val incomingBundle = SyncBundle(
            articles = listOf(modifiedExisting, brandNew),
            contracts = emptyList(),
            userConfig = UserConfig(companyName = "Mando Mercenaries")
        )

        val result = storage.syncWithBundle(incomingBundle, mode = "BIDIRECTIONAL")
        assertTrue(result.success)

        val checkExisting = storage.getArticle(existing.id)
        assertNotNull(checkExisting)
        assertEquals(9999.0, checkExisting.price)

        val checkNew = storage.getArticle("999")
        assertNotNull(checkNew)
        assertEquals("Mandalorian Jetpack", checkNew.name)

        assertEquals("Mando Mercenaries", storage.getUserConfig().companyName)
    }

    @Test
    fun testZipExportAndImport() {
        val out = ByteArrayOutputStream()
        storage.exportZip(out)
        val zipBytes = out.toByteArray()
        assertTrue(zipBytes.isNotEmpty())

        // Create fresh second storage
        val secondDir = File.createTempFile("sw_sync_test2_", "").apply {
            delete()
            mkdirs()
        }
        val secondStorage = StorageService(secondDir)
        // Clear it
        secondStorage.getAllArticles().forEach { secondStorage.deleteArticle(it.id) }
        secondStorage.getAllContracts().forEach { secondStorage.deleteContract(it.id) }
        assertEquals(0, secondStorage.getAllArticles().size)

        // Import zip into second storage
        val importResult = secondStorage.importZip(ByteArrayInputStream(zipBytes))
        assertTrue(importResult.success)
        assertTrue(secondStorage.getAllArticles().isNotEmpty())
        assertTrue(secondStorage.getAllContracts().isNotEmpty())

        secondDir.deleteRecursively()
    }

    @Test
    fun testJsonImportText() {
        val jsonText = """
            {
                "name": "Bacta-Tank",
                "category": "SUPPLY",
                "status": "IN_LAGER",
                "quantity": 2,
                "price": 3500.0
            }
        """.trimIndent()

        val res = storage.importJsonText(jsonText)
        assertTrue(res.success)
        assertEquals(1, res.articlesCount)

        val found = storage.getAllArticles().find { it.name == "Bacta-Tank" }
        assertNotNull(found)
        assertEquals(ArticleCategory.SUPPLY, found.category)
    }
}
