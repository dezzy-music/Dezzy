/**
 * Dezzy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.dezzy.music.lossless

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Claves DataStore del subsistema lossless. Se mantienen aquí (y no en el
 * PreferenceKeys global) para que el feature sea autocontenido y fácil de
 * revertir.
 */

/**
 * URL base del *resolver propio* que resuelve las descargas lossless. Prellenada
 * con el resolver oficial de Dezzy para que funcione de fábrica (el usuario no
 * pega nada); se puede cambiar en Ajustes. No guardamos credenciales de
 * streaming en la app: viven en el servidor del resolver.
 */
val LosslessResolverUrlKey = stringPreferencesKey("losslessResolverUrl")

/** Resolver por defecto (oficial de Dezzy). Cero configuración para el usuario. */
const val DefaultLosslessResolverUrl = "https://resolver-rust.vercel.app"

/** Proveedor preseleccionado en el diálogo de descarga (id de [LosslessProvider]). */
val LosslessDefaultProviderKey = stringPreferencesKey("losslessDefaultProvider")

/** Calidad preseleccionada (id de [LosslessQuality]). */
val LosslessDefaultQualityKey = stringPreferencesKey("losslessDefaultQuality")

/** Ids de extensiones instaladas/activas, separados por coma. */
val LosslessInstalledExtensionsKey = stringPreferencesKey("losslessInstalledExtensions")

/** Caché del último registry.json descargado (JSON crudo), para mostrar offline. */
val LosslessRegistryCacheKey = stringPreferencesKey("losslessRegistryCache")

/** Interruptor maestro del feature. */
val LosslessEnabledKey = booleanPreferencesKey("losslessEnabled")

/** Solo descargar por Wi-Fi. */
val LosslessWifiOnlyKey = booleanPreferencesKey("losslessWifiOnly")

/**
 * Copiar también el archivo a la carpeta pública Música/Dezzy (visible en
 * gestores de archivos y otras apps de música), además del almacenamiento
 * interno que usa la reproducción local. Ver [LosslessPublicExporter].
 */
val LosslessExportToPublicKey = booleanPreferencesKey("losslessExportToPublic")
