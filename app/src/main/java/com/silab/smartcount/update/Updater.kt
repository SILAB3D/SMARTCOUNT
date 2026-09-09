package com.silab.smartcount.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Canal de actualización por releases de GitHub.
 *
 * Un `git push` a main dispara el workflow, que compila y firma la APK y la
 * publica como release. La app la busca al arrancar, la descarga y lanza el
 * instalador del sistema.
 *
 * **Android no deja instalar en silencio** a una app normal: hace falta ser
 * *device owner* o app de sistema. Comprobar, descargar y preparar sí es
 * automático; el último paso es siempre un diálogo que confirma la persona.
 *
 * El `versionCode` sale de la **etiqueta** de la release (`v0.1.0-b23` → 23),
 * que ya viene en la respuesta de la API: una petición y ningún metadato
 * suelto que mantener sincronizado.
 */
object Updater {

    /** Debe ser un repositorio **público**: la app consulta la API sin credenciales. */
    const val REPO = "SILAB3D/SMARTCOUNT"

    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** `v<versionName>-b<versionCode>`, tal y como la escribe el workflow. */
    private val TAG_FORMAT = Regex("""^v(.+)-b(\d+)$""")

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- modelo

    data class Release(
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val apkSize: Long
    )

    sealed interface Check {
        /** Hay una versión más nueva publicada. */
        data class Available(val release: Release) : Check
        /** La instalada es igual o más nueva que la última publicada. */
        data object UpToDate : Check
        /**
         * No se ha podido comprobar. La comprobación automática se lo calla;
         * la manual de Ajustes lo cuenta, que es la única forma de distinguir
         * «no hay nada» de «está roto».
         */
        data class Failed(val reason: String) : Check
    }

    // ------------------------------------------------------------- instalada

    fun installedVersionCode(context: Context): Long =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)

    fun installedVersionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

    // --------------------------------------------------------------- consulta

    suspend fun check(context: Context): Check = withContext(Dispatchers.IO) {
        val latest = runCatching { fetchLatest() }.getOrElse { e ->
            return@withContext Check.Failed(describe(e))
        }
        when {
            // La fila de Ajustes es de una línea: el motivo va corto y la
            // explicación entera vive en el README.
            latest == null -> Check.Failed("repositorio privado o sin releases")
            latest.versionCode > installedVersionCode(context) -> Check.Available(latest)
            else -> Check.UpToDate
        }
    }

    /** Devuelve null si no hay release, si la etiqueta no tiene el formato o si no trae APK. */
    private fun fetchLatest(): Release? {
        val request = Request.Builder()
            .url(API_LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                error("GitHub respondió ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val dto = json.decodeFromString<ReleaseDto>(body)

            val match = TAG_FORMAT.find(dto.tagName) ?: return null
            val asset = dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return null

            return Release(
                versionName = match.groupValues[1],
                versionCode = match.groupValues[2].toLong(),
                apkUrl = asset.downloadUrl,
                apkSize = asset.size
            )
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Sin conexión"
        is java.net.SocketTimeoutException -> "GitHub no responde"
        else -> e.message ?: e::class.java.simpleName
    }

    // -------------------------------------------------------------- descarga

    /**
     * Descarga la APK a la caché con progreso real. Va por HTTP nativo y no por
     * ningún puente: son varios megas y hay que escribirlos directos a disco.
     */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Una sola APK guardada: la anterior ya no sirve para nada.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "smartcount-${release.versionCode}.apk")

        val request = Request.Builder().url(release.apkUrl).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("La descarga falló (${response.code})")
            val body = response.body ?: error("Respuesta vacía")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        }
        target
    }

    // ----------------------------------------------------------- instalación

    /**
     * En Android 8+ el permiso de «instalar apps desconocidas» se concede por
     * app, en una pantalla del sistema. Nada dentro de la app avisa de que ha
     * cambiado: hay que releerlo al volver al primer plano.
     */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Lanza el instalador del sistema. El toque de confirmación es de la persona. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ------------------------------------------------------------------ DTOs

    @Serializable
    private data class ReleaseDto(
        @kotlinx.serialization.SerialName("tag_name") val tagName: String,
        val assets: List<AssetDto> = emptyList()
    )

    @Serializable
    private data class AssetDto(
        val name: String,
        @kotlinx.serialization.SerialName("browser_download_url") val downloadUrl: String,
        val size: Long = 0
    )
}
