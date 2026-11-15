package com.gurru.music.data

/* Una mezcla: un grupo de canciones locales para escuchar de corrido. */
data class Mix(
    val name: String,
    val desc: String,
    val tracks: List<Track>
)

/* Un acceso directo: un artista que escuchas mucho, con sus canciones
   ordenadas por reproducciones y su primera portada. */
data class Shortcut(
    val name: String,
    val artUri: String?,
    val tracks: List<Track>
)

/* Grupo interno para armar las mezclas (no se usa fuera de este archivo). */
private data class Group(
    val name: String,
    val tracks: MutableList<Track>,
    var score: Int = 0
)

/* Arma "Mezclas para ti" desde la biblioteca local y el contador de
   reproducciones (misma lógica que la app actual):
   - top 3 artistas por reproducciones -> "Lo más de <artista>"
   - top 2 álbumes (con 2 o más canciones) -> "Álbum: <álbum>"
   - "Mezcla sorpresa": toda la música local revuelta, primeras 30.
   Si hay menos de 2 canciones, no hay mezclas. */
fun buildMixes(tracks: List<Track>, counts: Map<String, Int>): List<Mix> {
    if (tracks.size < 2) return emptyList()
    val byArtist = mutableMapOf<String, Group>()
    val byAlbum = mutableMapOf<String, Group>()
    for (t in tracks) {
        val artist = t.artist.ifBlank { "Artista desconocido" }.trim()
        val aKey = artist.lowercase()
        val aGroup = byArtist.getOrPut(aKey) { Group(artist, mutableListOf()) }
        aGroup.tracks.add(t)
        aGroup.score += counts[t.id] ?: 0
        val album = t.album.trim()
        if (album.isNotEmpty()) {
            val alKey = album.lowercase()
            val alGroup = byAlbum.getOrPut(alKey) { Group(album, mutableListOf()) }
            alGroup.tracks.add(t)
            alGroup.score += counts[t.id] ?: 0
        }
    }
    fun byPlays(list: List<Track>): List<Track> =
        list.sortedByDescending { counts[it.id] ?: 0 }
    fun topGroups(map: Map<String, Group>, max: Int): List<Group> =
        map.values.sortedWith(
            compareByDescending<Group> { it.score }.thenByDescending { it.tracks.size }
        ).take(max)
    val mixes = mutableListOf<Mix>()
    for (g in topGroups(byArtist, 3)) {
        if (g.tracks.isEmpty()) continue
        val n = g.tracks.size
        mixes.add(
            Mix(
                name = "Lo más de ${g.name}",
                desc = "$n ${if (n == 1) "canción" else "canciones"}",
                tracks = byPlays(g.tracks)
            )
        )
    }
    for (g in topGroups(byAlbum, 2)) {
        if (g.tracks.size < 2) continue
        mixes.add(
            Mix(
                name = "Álbum: ${g.name}",
                desc = "${g.tracks.size} canciones",
                tracks = byPlays(g.tracks)
            )
        )
    }
    val all = tracks.shuffled().take(30)
    if (all.isNotEmpty()) {
        mixes.add(Mix(name = "Mezcla sorpresa", desc = "Toda tu música, revuelta", tracks = all))
    }
    return mixes
}

/* Top 12 artistas por reproducciones (Accesos directos). Cada uno trae
   sus canciones ordenadas por reproducciones y su primera portada. */
fun buildShortcuts(tracks: List<Track>, counts: Map<String, Int>): List<Shortcut> {
    if (tracks.size < 2) return emptyList()
    val byArtist = mutableMapOf<String, Group>()
    for (t in tracks) {
        val artist = t.artist.ifBlank { "Artista desconocido" }.trim()
        val key = artist.lowercase()
        val group = byArtist.getOrPut(key) { Group(artist, mutableListOf()) }
        group.tracks.add(t)
        group.score += counts[t.id] ?: 0
    }
    fun byPlays(list: List<Track>): List<Track> =
        list.sortedByDescending { counts[it.id] ?: 0 }
    return byArtist.values
        .sortedWith(
            compareByDescending<Group> { it.score }.thenByDescending { it.tracks.size }
        )
        .take(12)
        .map { g ->
            val ordered = byPlays(g.tracks)
            Shortcut(
                name = g.name,
                artUri = ordered.firstOrNull { it.artUri != null }?.artUri,
                tracks = ordered
            )
        }
}
