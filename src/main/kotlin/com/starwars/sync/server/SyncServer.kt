package com.starwars.sync.server

import com.starwars.sync.model.*
import com.starwars.sync.storage.StorageService
import com.starwars.sync.web.WebInterface
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class SyncServer(
    private val storage: StorageService,
    val port: Int = 8080
) {
    private var server: HttpServer? = null
    private val syncLogs = CopyOnWriteArrayList<SyncLogEntry>()

    fun start() {
        val address = InetSocketAddress(port)
        val http = HttpServer.create(address, 0)
        http.executor = Executors.newFixedThreadPool(8)

        http.createContext("/", RootHandler())
        http.createContext("/api/", ApiHandler())

        http.start()
        server = http
    }

    fun stop() {
        server?.stop(1)
        server = null
    }

    fun getLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ips.add(addr.hostAddress)
                    }
                }
            }
        }
        if (ips.isEmpty()) {
            ips.add("127.0.0.1")
        }
        return ips
    }

    private inner class RootHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            if (path == "/" || path == "/index.html") {
                val html = WebInterface.getHtml().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, html.size.toLong())
                exchange.responseBody.use { it.write(html) }
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
        }
    }

    private inner class ApiHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
            exchange.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Accept, Authorization")

            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(204, -1)
                return
            }

            val path = exchange.requestURI.path
            val method = exchange.requestMethod.uppercase()
            val query = exchange.requestURI.query ?: ""
            val clientIp = exchange.remoteAddress.address.hostAddress

            try {
                when {
                    // Status
                    path == "/api/status" && method == "GET" -> {
                        val status = ServerStatus(
                            localAddresses = getLocalIpAddresses(),
                            port = port,
                            articleCount = storage.getAllArticles().size,
                            contractCount = storage.getAllContracts().size,
                            userCount = storage.getAllUsers().size,
                            userConfig = storage.getUserConfig(),
                            lastSyncTimestamp = storage.lastSyncTimestamp
                        )
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(status))
                    }

                    // Registered Users
                    path == "/api/users" && method == "GET" -> {
                        val users = storage.getAllUsers()
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(users))
                    }
                    path.startsWith("/api/users/") && method == "DELETE" -> {
                        val hwId = URLDecoder.decode(path.substringAfterLast("/"), "UTF-8")
                        val success = storage.deleteUser(hwId)
                        sendJsonResponse(exchange, if (success) 200 else 404, """{"success":$success}""")
                    }
                    path.startsWith("/api/users/") && path.endsWith("/notes") && method == "POST" -> {
                        val hwId = URLDecoder.decode(path.removePrefix("/api/users/").removeSuffix("/notes"), "UTF-8")
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val success = storage.updateUserNotes(hwId, body)
                        sendJsonResponse(exchange, if (success) 200 else 404, """{"success":$success}""")
                    }

                    // Sync bundle (GET)
                    path == "/api/sync/bundle" && method == "GET" -> {
                        val bundle = storage.createBundle()
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(bundle))
                    }

                    // Sync bidirectional (POST)
                    path == "/api/sync/bidirectional" && method == "POST" -> {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val incoming = storage.json.decodeFromString<SyncBundle>(body)
                        val clientHwId = incoming.hardwareId.ifBlank { incoming.userConfig?.deviceId ?: "" }
                        val result = storage.syncWithBundle(incoming, mode = "BIDIRECTIONAL", clientIp = clientIp)
                        syncLogs.add(0, SyncLogEntry(
                            clientIp = clientIp,
                            syncType = "BIDIREKTIONAL",
                            message = result.message,
                            success = result.success,
                            hardwareId = clientHwId
                        ))
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(result))
                    }

                    // Sync Push (POST)
                    path == "/api/sync/push" && method == "POST" -> {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val incoming = storage.json.decodeFromString<SyncBundle>(body)
                        val clientHwId = incoming.hardwareId.ifBlank { incoming.userConfig?.deviceId ?: "" }
                        val result = storage.syncWithBundle(incoming, mode = "PUSH_TO_SERVER", clientIp = clientIp)
                        syncLogs.add(0, SyncLogEntry(
                            clientIp = clientIp,
                            syncType = "PUSH_VON_CLIENT",
                            message = result.message,
                            success = result.success,
                            hardwareId = clientHwId
                        ))
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(result))
                    }

                    // Sync Pull (GET)
                    path == "/api/sync/pull" && method == "GET" -> {
                        val result = storage.syncWithBundle(SyncBundle(), mode = "PULL_FROM_SERVER", clientIp = clientIp)
                        syncLogs.add(0, SyncLogEntry(
                            clientIp = clientIp,
                            syncType = "PULL_VOM_SERVER",
                            message = result.message,
                            success = result.success
                        ))
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(result))
                    }

                    // Sync logs
                    path == "/api/logs" && method == "GET" -> {
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(syncLogs.take(40)))
                    }

                    // Articles CRUD
                    path == "/api/articles" && method == "GET" -> {
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(storage.getAllArticles()))
                    }
                    path == "/api/articles" && method == "POST" -> {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val article = storage.json.decodeFromString<Article>(body)
                        val saved = storage.saveArticle(article)
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(saved))
                    }
                    path.startsWith("/api/articles/") && method == "GET" -> {
                        val id = path.substringAfterLast("/")
                        val article = storage.getArticle(id)
                        if (article != null) {
                            sendJsonResponse(exchange, 200, storage.json.encodeToString(article))
                        } else {
                            sendJsonResponse(exchange, 404, """{"error":"Artikel nicht gefunden"}""")
                        }
                    }
                    path.startsWith("/api/articles/") && method == "DELETE" -> {
                        val id = path.substringAfterLast("/")
                        val success = storage.deleteArticle(id)
                        sendJsonResponse(exchange, if (success) 200 else 404, """{"success":$success}""")
                    }

                    // Contracts CRUD
                    path == "/api/contracts" && method == "GET" -> {
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(storage.getAllContracts()))
                    }
                    path == "/api/contracts" && method == "POST" -> {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val contract = storage.json.decodeFromString<Contract>(body)
                        val saved = storage.saveContract(contract)
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(saved))
                    }
                    path.startsWith("/api/contracts/") && method == "GET" -> {
                        val id = path.substringAfterLast("/")
                        val contract = storage.getContract(id)
                        if (contract != null) {
                            sendJsonResponse(exchange, 200, storage.json.encodeToString(contract))
                        } else {
                            sendJsonResponse(exchange, 404, """{"error":"Vertrag nicht gefunden"}""")
                        }
                    }
                    path.startsWith("/api/contracts/") && method == "DELETE" -> {
                        val id = path.substringAfterLast("/")
                        val success = storage.deleteContract(id)
                        sendJsonResponse(exchange, if (success) 200 else 404, """{"success":$success}""")
                    }

                    // UserConfig
                    path == "/api/config" && method == "GET" -> {
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(storage.getUserConfig()))
                    }
                    path == "/api/config" && method == "POST" -> {
                        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                        val config = storage.json.decodeFromString<UserConfig>(body)
                        val saved = storage.saveUserConfig(config)
                        sendJsonResponse(exchange, 200, storage.json.encodeToString(saved))
                    }

                    // File Import (JSON / ZIP)
                    path == "/api/import" && method == "POST" -> {
                        val overwrite = query.contains("overwrite=true")
                        val bytes = exchange.requestBody.readBytes()

                        val isZip = bytes.size >= 4 &&
                                bytes[0] == 0x50.toByte() &&
                                bytes[1] == 0x4B.toByte() &&
                                bytes[2] == 0x03.toByte() &&
                                bytes[3] == 0x04.toByte()

                        val result = if (isZip) {
                            storage.importZip(ByteArrayInputStream(bytes))
                        } else {
                            val text = bytes.toString(Charsets.UTF_8)
                            storage.importJsonText(text, overwrite = overwrite)
                        }

                        sendJsonResponse(exchange, 200, storage.json.encodeToString(result))
                    }

                    // File Export (JSON bundle)
                    path == "/api/export/bundle" && method == "GET" -> {
                        val jsonBundle = storage.json.encodeToString(storage.createBundle())
                        val bytes = jsonBundle.toByteArray(Charsets.UTF_8)
                        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                        exchange.responseHeaders.set("Content-Disposition", "attachment; filename=\"inventory_bundle.json\"")
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }

                    // File Export (ZIP)
                    path == "/api/export/zip" && method == "GET" -> {
                        val byteStream = ByteArrayOutputStream()
                        storage.exportZip(byteStream)
                        val zipBytes = byteStream.toByteArray()
                        exchange.responseHeaders.set("Content-Type", "application/zip")
                        exchange.responseHeaders.set("Content-Disposition", "attachment; filename=\"starwars_inventory.zip\"")
                        exchange.sendResponseHeaders(200, zipBytes.size.toLong())
                        exchange.responseBody.use { it.write(zipBytes) }
                    }

                    else -> {
                        sendJsonResponse(exchange, 404, """{"error":"Endpunkt nicht gefunden"}""")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                sendJsonResponse(exchange, 500, """{"error":"${e.message?.replace("\"", "\\\"")}"}""")
            }
        }

        private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
