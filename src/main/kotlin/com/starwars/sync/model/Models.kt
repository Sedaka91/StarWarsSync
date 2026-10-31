package com.starwars.sync.model

import kotlinx.serialization.Serializable

@Serializable
enum class ArticleCategory(val label: String) {
    WEAPON("Waffen & Blaster"),
    ARMOR("Rüstung & Helme"),
    DROID("Droiden & Technik"),
    SHIP_PART("Schiffs- & Antriebsteile"),
    SUPPLY("Vorräte & Rationen"),
    HOLOCRON("Artefakte & Holocron"),
    MISC("Ausrüstung & Sonstiges")
}

@Serializable
enum class ArticleStatus(val label: String) {
    BEREIT("Einsatzbereit"),
    IM_EINSATZ("Im aktiven Einsatz"),
    BESCHAEDIGT("Beschädigt / Reparaturbedarf"),
    IN_LAGER("Im Depot eingelagert"),
    VERSCHOLLEN("Verschollen / Vermisst")
}

@Serializable
enum class ContractType(val label: String) {
    HANDEL("Handelsvertrag"),
    KAUF("Kaufabwicklung"),
    BESCHAFFUNG("Beschaffungsauftrag"),
    KOPFGELD("Kopfgeld / Söldnerauftrag"),
    GEHEIM("Klassifizierte Operation")
}

@Serializable
enum class ContractStatus(val label: String) {
    ENTWURF("Entwurf"),
    LAUFEND("Laufend / Aktiv"),
    ERFUELLT("Erfüllt"),
    GESCHEITERT("Gescheitert / Abgebrochen")
}

@Serializable
data class Contract(
    val id: String = "SW-HW-ZENTRALE-SEC4_0",
    val contractNumber: String = "",
    val title: String = "",
    val type: ContractType = ContractType.HANDEL,
    val status: ContractStatus = ContractStatus.ENTWURF,
    /** Besitzer bzw. Auftraggeber */
    val owner: String = "",
    /** Vertragspartner */
    val partner: String = "",
    /** Datum als UTC-Epoch-Millisekunden, null = nicht gesetzt */
    val startDate: Long? = null,
    val endDate: Long? = null,
    /** Kosten und Preis in Credits */
    val costs: Double = 0.0,
    val price: Double = 0.0,
    val deposit: Double = 0.0,
    val signed: Boolean = false,
    val terms: String = "",
    val notes: String = "",
    val updatedAt: Long = 0L,
    val hardwareId: String = "",
    var deleted: Boolean = false
)

@Serializable
data class Article(
    val id: String = "SW-HW-ZENTRALE-SEC4_0",
    val name: String = "",
    val category: ArticleCategory = ArticleCategory.WEAPON,
    val status: ArticleStatus = ArticleStatus.BEREIT,
    val description: String = "",
    /** Besitzer des Artikels */
    val owner: String = "",
    /** Partner, z. B. Leihgeber oder Lieferant */
    val partner: String = "",
    val quantity: Int = 1,
    val location: String = "",
    /** Szene bzw. Akt, in dem der Artikel gebraucht wird */
    val scene: String = "",
    /** Kosten und Preis pro Stück in Credits */
    val costs: Double = 0.0,
    val price: Double = 0.0,
    val contractId: String? = null,
    val notes: String = "",
    val updatedAt: Long = 0L,
    val hardwareId: String = "",
    var deleted: Boolean = false
)

@Serializable
data class UserConfig(
    val companyName: String = "Logistik-Firma",
    val userName: String = "Spieler",
    val logoPreset: String = "CARGO",
    val customLogoPath: String? = null,
    val deviceId: String = ""
)

@Serializable
data class RegisteredUser(
    val hardwareId: String,
    val userName: String = "Spieler",
    val companyName: String = "Logistik-Firma",
    val logoPreset: String = "CARGO",
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSync: Long = System.currentTimeMillis(),
    val lastIp: String = "",
    val syncCount: Int = 1,
    val articleCount: Int = 0,
    val contractCount: Int = 0,
    val notes: String = ""
)

@Serializable
data class SyncBundle(
    val articles: List<Article> = emptyList(),
    val contracts: List<Contract> = emptyList(),
    val userConfig: UserConfig? = null,
    val clientTimestamp: Long = 0L,
    val syncMode: String = "BIDIRECTIONAL",
    val hardwareId: String = ""
)

@Serializable
data class SyncResult(
    val success: Boolean,
    val message: String,
    val articles: List<Article>,
    val contracts: List<Contract>,
    val userConfig: UserConfig?,
    val serverTimestamp: Long = System.currentTimeMillis(),
    val importedArticlesCount: Int = 0,
    val importedContractsCount: Int = 0,
    val hardwareId: String = ""
)

@Serializable
data class ImportResult(
    val success: Boolean,
    val message: String,
    val articlesCount: Int = 0,
    val contractsCount: Int = 0,
    val configUpdated: Boolean = false,
    val errors: List<String> = emptyList()
)

@Serializable
data class ServerStatus(
    val appName: String = "StarWarsSync Zentrale",
    val version: String = "1.0.0",
    val status: String = "ONLINE",
    val localAddresses: List<String>,
    val port: Int,
    val articleCount: Int,
    val contractCount: Int,
    val userCount: Int = 0,
    val userConfig: UserConfig,
    val lastSyncTimestamp: Long?,
    val serverTime: Long = System.currentTimeMillis()
)

@Serializable
data class SyncLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val clientIp: String,
    val syncType: String,
    val message: String,
    val success: Boolean,
    val hardwareId: String = ""
)
