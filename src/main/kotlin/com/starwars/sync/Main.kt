package com.starwars.sync

import com.starwars.sync.server.SyncServer
import com.starwars.sync.storage.StorageService
import java.awt.Desktop
import java.io.File
import java.net.URI

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() 
        ?: System.getenv("PORT")?.toIntOrNull() 
        ?: 8080

    val dataDir = File("data/inventory")
    val storage = StorageService(dataDir)
    val server = SyncServer(storage, port = port)

    val banner = """
    ========================================================================
     _______ _______ _______ ______  _     _ _______  ______ _______
     |______    |    |_____| |_____/ |  |  | |_____| |_____/ |______
     ______|    |    |     | |    \_ |__|__| |     | |    \_ ______|
     
     ______  _______ _______ _______  _____  _______ ______ 
     |     \ |_____|    |    |_____| |_____] |_____| |     \
     |_____/ |     |    |    |     | |       |     | |_____/
                    [ LOGISTIK SYNC-ZENTRALE v1.0 ]
    ========================================================================
    """.trimIndent()

    println(banner)
    println("» Initialisiere lokalen Speicher unter: ${storage.getStoragePath()}")
    println("» Geladen: ${storage.getAllArticles().size} Artikel, ${storage.getAllContracts().size} Verträge")
    println("» Starte Datapad WLAN-Server auf Port $port...")

    server.start()

    val ips = server.getLocalIpAddresses()
    println()
    println("════════════════════════════════════════════════════════════════════════")
    println("✓ DATAPAD SYNC SERVER IST ONLINE!")
    println("  Web-Oberfläche für Desktop & Browser:")
    println("  » http://localhost:$port")
    ips.forEach { ip ->
        println("  » http://$ip:$port (WLAN / Netzwerk)")
    }
    println("════════════════════════════════════════════════════════════════════════")
    println("» Synchronisation für Android App (StarWarsInventar):")
    println("  Gib in der Android App unter '📡 WLAN SYNC' die IP-Adresse ein:")
    println("  Host: ${ips.firstOrNull() ?: "127.0.0.1"}   Port: $port")
    println("════════════════════════════════════════════════════════════════════════")
    println("Drücke Strg+C zum Beenden.")
    println()

    // Try opening browser automatically on desktop
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI("http://localhost:$port"))
        }
    }

    // Keep server running
    Runtime.getRuntime().addShutdownHook(Thread {
        println("\nFahre Datapad Sync Server herunter...")
        server.stop()
        println("Server beendet. Möge die Macht mit dir sein.")
    })

    // Block main thread
    Thread.currentThread().join()
}
