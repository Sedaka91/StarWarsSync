package com.starwars.sync.web

object WebInterface {

    private val cachedHtml: String by lazy {
        val stream = javaClass.getResourceAsStream("/web/index.html")
        if (stream != null) {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            "<html><body><h1>Star Wars Datapad System</h1><p>Web-Ressource nicht gefunden.</p></body></html>"
        }
    }

    fun getHtml(): String = cachedHtml
}
