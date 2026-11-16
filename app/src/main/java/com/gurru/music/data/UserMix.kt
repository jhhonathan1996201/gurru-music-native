package com.gurru.music.data

/* Una mezcla creada por el usuario en la pestaña Mezclas (auto-DJ
   Fase 1): una lista de canciones locales en un orden, que suenan de
   corrido con crossfade y control de tempo. Se guarda solo la lista
   de IDs; las canciones se resuelven contra la biblioteca al abrir
   la mezcla, así si una canción ya no está, simplemente no suena. */
data class UserMix(
    val id: String,
    val name: String,
    val trackIds: List<String>,
    val crossfadeSec: Int = 8
)
