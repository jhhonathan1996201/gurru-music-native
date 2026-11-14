# Gurru Music nativa

Versión nativa de Gurru Music en Kotlin (Jetpack Compose + Media3 ExoPlayer), construida por fases. El mismo diseño neón de la app actual (fondo `#04163C`, acento cian `#22D3EE`), el mismo `applicationId` (`com.gurru.music`) y el mismo nombre.

## Estado: Fase 0 — esqueleto

El proyecto existe, abre y muestra una sola pantalla con el nombre "Gurru Music" y el texto "Biblioteca vacía". Nada más. Las pantallas y la lógica llegan en las fases siguientes (Fase 1: biblioteca local + reproductor).

## La app de uso diario sigue siendo la actual

La app Capacitor actual (repo `gurru-music`) sigue siendo la que se usa a diario. Esta versión nativa la reemplaza solo cuando una fase esté probada en el teléfono.

## Compilar

Solo por GitHub Actions: cada push a `main` corre `./gradlew assembleDebug` y deja el APK como artefacto `gurru-music-apk` (7 días). La compilación local en este entorno está bloqueada (Gradle no puede hablar con su daemon).

- `compileSdk`/`targetSdk` 35, `minSdk` 24, AGP 8.7.2, Gradle 8.11.1, Kotlin 2.0.21.
- `versionCode` = número de corrida de GitHub Actions (`GITHUB_RUN_NUMBER`); sin corrida, 1 / "1.0".
- APK debug: para instalarlo hay que desinstalar la app vieja primero (hasta que se restaure la firma release en la Fase 6).
