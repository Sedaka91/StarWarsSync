package com.starwars.sync.storage

import com.starwars.sync.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.read
import kotlin.concurrent.write

class StorageService(private val baseDir: File = File("data/inventory")) {

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    private val lock = ReentrantReadWriteLock()

    private val articlesDir = File(baseDir, "articles").apply { mkdirs() }
    private val contractsDir = File(baseDir, "contracts").apply { mkdirs() }
    private val configFile = File(baseDir, "config.json")
    private val usersFile = File(baseDir, "users.json")

    private val articlesMap = mutableMapOf<String, Article>()
    private val contractsMap = mutableMapOf<String, Contract>()
    private val usersMap = mutableMapOf<String, RegisteredUser>()
    private var currentUserConfig = UserConfig()
    var lastSyncTimestamp: Long? = null
        private set

    init {
        loadAll()
    }

    fun getStoragePath(): String = baseDir.absolutePath

    fun loadAll() = lock.write {
        // Load config
        if (configFile.exists()) {
            runCatching {
                currentUserConfig = json.decodeFromString<UserConfig>(configFile.readText())
            }.onFailure {
                currentUserConfig = UserConfig()
            }
        } else {
            currentUserConfig = UserConfig()
        }

        if (currentUserConfig.deviceId.isBlank()) {
            currentUserConfig = currentUserConfig.copy(deviceId = "SW-HW-ZENTRALE-SEC4")
            saveConfigFile(currentUserConfig)
        }

        // Load users
        usersMap.clear()
        if (usersFile.exists()) {
            runCatching {
                val list = json.decodeFromString<List<RegisteredUser>>(usersFile.readText())
                list.forEach { usersMap[it.hardwareId] = it }
            }
        }

        articlesMap.clear()
        contractsMap.clear()

        articlesDir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            runCatching {
                val article = json.decodeFromString<Article>(file.readText())
                articlesMap[article.id] = article
            }
        }

        contractsDir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            runCatching {
                val contract = json.decodeFromString<Contract>(file.readText())
                contractsMap[contract.id] = contract
            }
        }

        if (articlesMap.isEmpty() && contractsMap.isEmpty()) {
            seedSampleData()
        }
    }

    private fun seedSampleData() {
        val serverHwId = currentUserConfig.deviceId.ifBlank { "SW-HW-ZENTRALE-SEC4" }
        val sampleContract = Contract(
            id = "SW-HW-ZENTRALE-SEC4_1",
            contractNumber = "K-2024-SW1",
            title = "Beschaffung von Blaster-Zellen",
            type = ContractType.BESCHAFFUNG,
            status = ContractStatus.LAUFEND,
            owner = "Karthon Logistik",
            partner = "Black Sun Syndikat",
            startDate = System.currentTimeMillis() - 86400000L * 7,
            endDate = System.currentTimeMillis() + 86400000L * 14,
            costs = 12000.0,
            price = 18000.0,
            deposit = 3000.0,
            signed = true,
            terms = "Lieferung in Sektor 4 binnen 14 Standardtagen. Barzahlung bei Übergabe.",
            notes = "Wichtig: Wachsamkeit bei der Übergabe auf Ord Mantell.",
            updatedAt = System.currentTimeMillis(),
            hardwareId = serverHwId
        )
        saveContractInternal(sampleContract)

        val sampleArticles = listOf(
            Article(
                id = "SW-HW-ZENTRALE-SEC4_1",
                name = "DL-44 Schwerer Blaster",
                category = ArticleCategory.WEAPON,
                status = ArticleStatus.BEREIT,
                description = "Modifiziertes Modell mit verstärktem Energiekonverter",
                owner = "Captain Solo",
                partner = "Corellian Arms",
                quantity = 4,
                location = "Hangar Bay 3, Kiste A-12",
                scene = "Akt 1 - Flucht von Corellia",
                costs = 500.0,
                price = 1200.0,
                contractId = "1",
                notes = "Sehr zuverlässig bei Kurzstreckengefechten.",
                updatedAt = System.currentTimeMillis(),
                hardwareId = serverHwId
            ),
            Article(
                id = "SW-HW-ZENTRALE-SEC4_2",
                name = "Astromech-Droiden-Einheit R2",
                category = ArticleCategory.DROID,
                status = ArticleStatus.IM_EINSATZ,
                description = "Vollständig gewarteter R2-Mechaniker mit Scomp-Link",
                owner = "Rebellen-Zelle",
                partner = "Industrial Automaton",
                quantity = 1,
                location = "Frachter Cockpit",
                scene = "Akt 2 - Hyperraum-Navigation",
                costs = 2500.0,
                price = 4500.0,
                notes = "Besitzt archivierte Sternenkarten des Outer Rim.",
                updatedAt = System.currentTimeMillis(),
                hardwareId = serverHwId
            ),
            Article(
                id = "SW-HW-ZENTRALE-SEC4_3",
                name = "Beskar-Brustpanzer",
                category = ArticleCategory.ARMOR,
                status = ArticleStatus.IN_LAGER,
                description = "Reines mandalorianisches Eisen, widersteht Blasterfeuer",
                owner = "Mandalorianische Enklave",
                partner = "Schmied von Nevarro",
                quantity = 1,
                location = "Tresorraum Deck B",
                scene = "Akt 3 - Das Duell",
                costs = 8000.0,
                price = 15000.0,
                contractId = "1",
                notes = "Streng gehütetes Relikt.",
                updatedAt = System.currentTimeMillis(),
                hardwareId = serverHwId
            )
        )
        sampleArticles.forEach { saveArticleInternal(it) }
    }

    fun getAllArticles(): List<Article> = lock.read {
        articlesMap.values.sortedBy { it.name.lowercase() }
    }

    fun getArticle(id: String): Article? = lock.read {
        articlesMap[id]
    }

    fun saveArticle(article: Article): Article = lock.write {
        val targetId = article.id.ifBlank { "SW-HW-ZENTRALE-SEC4_" + System.currentTimeMillis() }

        val now = System.currentTimeMillis()
        val toSave = article.copy(
            id = targetId,
            updatedAt = if (article.updatedAt > 0L) article.updatedAt else now,
            hardwareId = article.hardwareId.ifBlank { currentUserConfig.deviceId.ifBlank { "SW-HW-ZENTRALE-SEC4" } }
        )
        saveArticleInternal(toSave)
        toSave
    }

    private fun saveArticleInternal(article: Article) {
        articlesMap[article.id] = article
        val file = File(articlesDir, "${article.id}.json")
        file.writeText(json.encodeToString(article))
    }

    fun deleteArticle(id: String): Boolean = lock.write {
        articlesMap[id]?.deleted = true;
        val removed = articlesMap[id]?.let { saveArticle(it) } != null;
        removed
    }

    fun getAllContracts(): List<Contract> = lock.read {
        contractsMap.values.sortedBy { it.title.lowercase() }
    }

    fun getContract(id: String): Contract? = lock.read {
        contractsMap[id]
    }

    fun saveContract(contract: Contract): Contract = lock.write {
        val targetId = contract.id.ifBlank { "SW-HW-ZENTRALE-SEC4_" + System.currentTimeMillis() }

        val now = System.currentTimeMillis()
        val toSave = contract.copy(
            id = targetId,
            updatedAt = if (contract.updatedAt > 0L) contract.updatedAt else now,
            hardwareId = contract.hardwareId.ifBlank { currentUserConfig.deviceId.ifBlank { "SW-HW-ZENTRALE-SEC4" } }
        )
        saveContractInternal(toSave)
        toSave
    }

    private fun saveContractInternal(contract: Contract) {
        contractsMap[contract.id] = contract
        val file = File(contractsDir, "${contract.id}.json")
        file.writeText(json.encodeToString(contract))
    }

    fun deleteContract(id: String): Boolean = lock.write {
        contractsMap[id]?.deleted = true;
        val removed = contractsMap[id]?.let { saveContract(it) } != null;
        removed
    }

    fun getUserConfig(): UserConfig = lock.read { currentUserConfig }

    fun saveUserConfig(config: UserConfig): UserConfig = lock.write {
        val finalConfig = if (config.deviceId.isBlank()) {
            config.copy(deviceId = currentUserConfig.deviceId.ifBlank { "SW-HW-ZENTRALE-SEC4" })
        } else {
            config
        }
        currentUserConfig = finalConfig
        saveConfigFile(finalConfig)
        finalConfig
    }

    private fun saveConfigFile(config: UserConfig) {
        configFile.writeText(json.encodeToString(config))
    }

    // User Management
    fun getAllUsers(): List<RegisteredUser> = lock.read {
        usersMap.values.sortedByDescending { it.lastSync }
    }

    fun registerOrUpdateUser(
        hwId: String,
        userName: String,
        companyName: String,
        logoPreset: String,
        clientIp: String,
        articlesCount: Int,
        contractsCount: Int
    ): RegisteredUser = lock.write {
        val now = System.currentTimeMillis()
        val existing = usersMap[hwId]
        val updated = if (existing == null) {
            RegisteredUser(
                hardwareId = hwId,
                userName = userName.ifBlank { "Datapad-Nutzer" },
                companyName = companyName.ifBlank { "Logistik-Firma" },
                logoPreset = logoPreset.ifBlank { "CARGO" },
                firstSeen = now,
                lastSync = now,
                lastIp = clientIp,
                syncCount = 1,
                articleCount = articlesCount,
                contractCount = contractsCount
            )
        } else {
            existing.copy(
                userName = userName.ifBlank { existing.userName },
                companyName = companyName.ifBlank { existing.companyName },
                logoPreset = logoPreset.ifBlank { existing.logoPreset },
                lastSync = now,
                lastIp = clientIp.ifBlank { existing.lastIp },
                syncCount = existing.syncCount + 1,
                articleCount = articlesCount,
                contractCount = contractsCount
            )
        }
        usersMap[hwId] = updated
        saveUsersFile()
        updated
    }

    fun deleteUser(hwId: String): Boolean = lock.write {
        val removed = usersMap.remove(hwId) != null
        if (removed) saveUsersFile()
        removed
    }

    fun updateUserNotes(hwId: String, notes: String): Boolean = lock.write {
        val user = usersMap[hwId] ?: return@write false
        usersMap[hwId] = user.copy(notes = notes)
        saveUsersFile()
        true
    }

    private fun saveUsersFile() {
        usersFile.writeText(json.encodeToString(usersMap.values.toList()))
    }

    fun createBundle(): SyncBundle = lock.read {
        SyncBundle(
            articles = articlesMap.values.sortedBy { it.id },
            contracts = contractsMap.values.sortedBy { it.id },
            userConfig = currentUserConfig,
            clientTimestamp = System.currentTimeMillis(),
            syncMode = "BIDIRECTIONAL",
            hardwareId = currentUserConfig.deviceId
        )
    }

    fun syncWithBundle(bundle: SyncBundle, mode: String = "BIDIRECTIONAL", clientIp: String = ""): SyncResult = lock.write {
        val now = System.currentTimeMillis()
        var importedArticles = 0
        var importedContracts = 0

        // Extract client hardware-ID
        val clientHwId = bundle.hardwareId.ifBlank {
            bundle.userConfig?.deviceId ?: ""
        }

        // Register or update user upon sync
        if (clientHwId.isNotBlank()) {
            registerOrUpdateUser(
                hwId = clientHwId,
                userName = bundle.userConfig?.userName ?: "Datapad-Bediener",
                companyName = bundle.userConfig?.companyName ?: "Logistik-Firma",
                logoPreset = bundle.userConfig?.logoPreset ?: "CARGO",
                clientIp = clientIp,
                articlesCount = bundle.articles.size,
                contractsCount = bundle.contracts.size
            )
        }

        when (mode.uppercase()) {
            "PULL_FROM_SERVER" -> {
                lastSyncTimestamp = now
                return@write SyncResult(
                    success = true,
                    message = "Server-Daten erfolgreich bereitgestellt (Pull)",
                    articles = articlesMap.values.sortedBy { it.id },
                    contracts = contractsMap.values.sortedBy { it.id },
                    userConfig = currentUserConfig,
                    serverTimestamp = now,
                    hardwareId = currentUserConfig.deviceId
                )
            }
            "PUSH_TO_SERVER" -> {
                bundle.articles.forEach { art ->
                    val artHwId = art.hardwareId.ifBlank { clientHwId.ifBlank { currentUserConfig.deviceId } }
                    saveArticleInternal(art.copy(
                        updatedAt = if (art.updatedAt > 0L) art.updatedAt else now,
                        hardwareId = artHwId
                    ))
                    importedArticles++
                }
                bundle.contracts.forEach { con ->
                    val conHwId = con.hardwareId.ifBlank { clientHwId.ifBlank { currentUserConfig.deviceId } }
                    saveContractInternal(con.copy(
                        updatedAt = if (con.updatedAt > 0L) con.updatedAt else now,
                        hardwareId = conHwId
                    ))
                    importedContracts++
                }
            }
            else -> {
                // BIDIRECTIONAL
                bundle.articles.forEach { incoming ->
                    val artHwId = incoming.hardwareId.ifBlank { clientHwId.ifBlank { currentUserConfig.deviceId } }
                    val existing = articlesMap[incoming.id]
                    if (existing == null) {
                        val newId = incoming.id.ifBlank { "SW-HW-ZENTRALE-SEC4_" + System.currentTimeMillis() }
                        val toAdd = incoming.copy(
                            id = newId,
                            updatedAt = if (incoming.updatedAt > 0L) incoming.updatedAt else now,
                            hardwareId = artHwId
                        )
                        saveArticleInternal(toAdd)
                        importedArticles++
                    } else {
                        if (incoming.updatedAt >= existing.updatedAt || incoming != existing.copy(updatedAt = incoming.updatedAt, hardwareId = incoming.hardwareId)) {
                            val toUpdate = incoming.copy(
                                updatedAt = incoming.updatedAt,
                                hardwareId = artHwId
                            )
                            saveArticleInternal(toUpdate)
                            importedArticles++
                        }
                    }
                }

                bundle.contracts.forEach { incoming ->
                    val conHwId = incoming.hardwareId.ifBlank { clientHwId.ifBlank { currentUserConfig.deviceId } }
                    val existing = contractsMap[incoming.id]
                    if (existing == null) {
                        val newId = incoming.id.ifBlank { "SW-HW-ZENTRALE-SEC4_" + System.currentTimeMillis() }
                        val toAdd = incoming.copy(
                            id = newId,
                            updatedAt = if (incoming.updatedAt > 0L) incoming.updatedAt else now,
                            hardwareId = conHwId
                        )
                        saveContractInternal(toAdd)
                        importedContracts++
                    } else {
                        if (incoming.updatedAt >= existing.updatedAt || incoming != existing.copy(updatedAt = incoming.updatedAt, hardwareId = incoming.hardwareId)) {
                            val toUpdate = incoming.copy(
                                updatedAt = maxOf(incoming.updatedAt, now),
                                hardwareId = conHwId
                            )
                            saveContractInternal(toUpdate)
                            importedContracts++
                        }
                    }
                }
            }
        }

        lastSyncTimestamp = now
        SyncResult(
            success = true,
            message = "Synchronisation erfolgreich ($importedArticles Artikel, $importedContracts Verträge aktualisiert)",
            articles = articlesMap.values.sortedBy { it.id },
            contracts = contractsMap.values.sortedBy { it.id },
            userConfig = currentUserConfig,
            serverTimestamp = now,
            importedArticlesCount = importedArticles,
            importedContractsCount = importedContracts,
            hardwareId = currentUserConfig.deviceId
        )
    }

    fun importJsonText(rawText: String, overwrite: Boolean = false): ImportResult = lock.write {
        val trimmed = rawText.trim()
        val errors = mutableListOf<String>()
        var artCount = 0
        var conCount = 0
        var configUpdated = false

        if (overwrite) {
            articlesDir.listFiles()?.forEach { it.delete() }
            contractsDir.listFiles()?.forEach { it.delete() }
            articlesMap.clear()
            contractsMap.clear()
        }

        try {
            val element = json.parseToJsonElement(trimmed)
            when (element) {
                is JsonObject -> {
                    if (element.containsKey("articles") || element.containsKey("contracts")) {
                        val bundle = json.decodeFromJsonElement<SyncBundle>(element)
                        val hwId = bundle.hardwareId.ifBlank { bundle.userConfig?.deviceId ?: currentUserConfig.deviceId }
                        bundle.articles.forEach {
                            saveArticle(it.copy(hardwareId = it.hardwareId.ifBlank { hwId }))
                            artCount++
                        }
                        bundle.contracts.forEach {
                            saveContract(it.copy(hardwareId = it.hardwareId.ifBlank { hwId }))
                            conCount++
                        }
                        if (bundle.userConfig != null) {
                            saveUserConfig(bundle.userConfig)
                            configUpdated = true
                        }
                    } else if (element.containsKey("contractNumber") || element.containsKey("terms")) {
                        val contract = json.decodeFromJsonElement<Contract>(element)
                        saveContract(contract)
                        conCount++
                    } else if (element.containsKey("companyName") && element.containsKey("userName")) {
                        val config = json.decodeFromJsonElement<UserConfig>(element)
                        saveUserConfig(config)
                        configUpdated = true
                    } else {
                        val article = json.decodeFromJsonElement<Article>(element)
                        saveArticle(article)
                        artCount++
                    }
                }
                is JsonArray -> {
                    element.forEach { item ->
                        if (item is JsonObject) {
                            if (item.containsKey("contractNumber") || item.containsKey("terms")) {
                                runCatching {
                                    val c = json.decodeFromJsonElement<Contract>(item)
                                    saveContract(c)
                                    conCount++
                                }.onFailure { errors.add("Fehler beim Vertrag-Import: ${it.message}") }
                            } else {
                                runCatching {
                                    val a = json.decodeFromJsonElement<Article>(item)
                                    saveArticle(a)
                                    artCount++
                                }.onFailure { errors.add("Fehler beim Artikel-Import: ${it.message}") }
                            }
                        }
                    }
                }
                else -> {
                    return@write ImportResult(
                        success = false,
                        message = "Ungültiges JSON-Format",
                        errors = listOf("Format nicht erkannt")
                    )
                }
            }
        } catch (e: Exception) {
            return@write ImportResult(
                success = false,
                message = "JSON Parsing-Fehler: ${e.message}",
                errors = listOf(e.localizedMessage ?: "Unbekannter Fehler")
            )
        }

        ImportResult(
            success = errors.isEmpty() || (artCount > 0 || conCount > 0),
            message = "Import abgeschlossen: $artCount Artikel, $conCount Verträge importiert" +
                    if (configUpdated) " (Firmenprofil aktualisiert)" else "",
            articlesCount = artCount,
            contractsCount = conCount,
            configUpdated = configUpdated,
            errors = errors
        )
    }

    fun exportZip(out: OutputStream) = lock.read {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("config.json"))
            zip.write(json.encodeToString(currentUserConfig).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (usersMap.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("users.json"))
                zip.write(json.encodeToString(usersMap.values.toList()).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            articlesMap.values.forEach { article ->
                zip.putNextEntry(ZipEntry("articles/${article.id}.json"))
                zip.write(json.encodeToString(article).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            contractsMap.values.forEach { contract ->
                zip.putNextEntry(ZipEntry("contracts/${contract.id}.json"))
                zip.write(json.encodeToString(contract).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    fun importZip(input: InputStream): ImportResult = lock.write {
        var artCount = 0
        var conCount = 0
        var configUpdated = false
        val errors = mutableListOf<String>()

        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                    val bytes = zip.readBytes()
                    val content = String(bytes, Charsets.UTF_8)
                    val filename = entry.name.replace("\\", "/")

                    if (filename.contains("articles/") || filename.startsWith("article_")) {
                        runCatching {
                            val article = json.decodeFromString<Article>(content)
                            saveArticle(article)
                            artCount++
                        }.onFailure { errors.add("Fehler in $filename: ${it.message}") }
                    } else if (filename.contains("contracts/") || filename.startsWith("contract_")) {
                        runCatching {
                            val contract = json.decodeFromString<Contract>(content)
                            saveContract(contract)
                            conCount++
                        }.onFailure { errors.add("Fehler in $filename: ${it.message}") }
                    } else if (filename.endsWith("config.json")) {
                        runCatching {
                            val config = json.decodeFromString<UserConfig>(content)
                            saveUserConfig(config)
                            configUpdated = true
                        }.onFailure { errors.add("Fehler in config.json: ${it.message}") }
                    } else if (filename.endsWith("users.json")) {
                        runCatching {
                            val users = json.decodeFromString<List<RegisteredUser>>(content)
                            users.forEach { usersMap[it.hardwareId] = it }
                            saveUsersFile()
                        }.onFailure { errors.add("Fehler in users.json: ${it.message}") }
                    } else {
                        val res = importJsonText(content, overwrite = false)
                        artCount += res.articlesCount
                        conCount += res.contractsCount
                        if (res.configUpdated) configUpdated = true
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        ImportResult(
            success = errors.isEmpty() || (artCount > 0 || conCount > 0 || configUpdated),
            message = "ZIP-Import abgeschlossen: $artCount Artikel, $conCount Verträge" +
                    if (configUpdated) ", 1 Firmenprofil" else "",
            articlesCount = artCount,
            contractsCount = conCount,
            configUpdated = configUpdated,
            errors = errors
        )
    }
}
