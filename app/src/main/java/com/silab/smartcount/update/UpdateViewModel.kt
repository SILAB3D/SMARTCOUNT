package com.silab.smartcount.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Fase del canal de actualización, tal y como la ve la interfaz. */
enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, FAILED }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: Updater.Release? = null,
    val progress: Int = 0,
    val error: String? = null,
    /** Resultado de la comprobación **manual**, que sí cuenta lo que ocurre. */
    val manualResult: String? = null,
    val canInstall: Boolean = true,
    val apk: File? = null
) {
    val buttonLabel: String
        get() = when (phase) {
            UpdatePhase.DOWNLOADING -> "Descargando… $progress %"
            UpdatePhase.READY -> "Instalar"
            UpdatePhase.FAILED -> "Reintentar"
            else -> "Actualizar"
        }
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Versiones que ya se han ofrecido, para no repetir el aviso en cada arranque. */
    private val prefs =
        app.getSharedPreferences("updates", Application.MODE_PRIVATE)

    val installedVersionName: String get() = Updater.installedVersionName(getApplication())
    val installedVersionCode: Long get() = Updater.installedVersionCode(getApplication())

    /**
     * Comprobación del arranque: se calla sus errores. Sin cobertura, o si
     * GitHub responde 403 por límite de peticiones, la app sigue como si nada.
     */
    fun checkOnLaunch() {
        if (_state.value.phase != UpdatePhase.IDLE) return
        viewModelScope.launch {
            val result = Updater.check(getApplication())
            if (result is Updater.Check.Available && !alreadyOffered(result.release)) {
                _state.value = _state.value.copy(
                    phase = UpdatePhase.AVAILABLE,
                    release = result.release,
                    canInstall = Updater.canInstall(getApplication())
                )
            }
        }
    }

    /**
     * Comprobación manual desde Ajustes: cuenta el resultado exacto. Es la
     * única forma de distinguir «no hay novedades» de «el canal está roto».
     */
    fun checkManually() {
        if (_state.value.phase == UpdatePhase.CHECKING) return
        _state.value = _state.value.copy(phase = UpdatePhase.CHECKING, manualResult = null)
        viewModelScope.launch {
            when (val result = Updater.check(getApplication())) {
                is Updater.Check.Available -> _state.value = _state.value.copy(
                    phase = UpdatePhase.AVAILABLE,
                    release = result.release,
                    canInstall = Updater.canInstall(getApplication()),
                    manualResult = "Versión ${result.release.versionName} disponible"
                )
                Updater.Check.UpToDate -> _state.value = _state.value.copy(
                    phase = UpdatePhase.IDLE,
                    manualResult = "Ya estás en la última versión"
                )
                is Updater.Check.Failed -> _state.value = _state.value.copy(
                    phase = UpdatePhase.IDLE,
                    manualResult = "No se pudo comprobar · ${result.reason}"
                )
            }
        }
    }

    /** Botón principal del aviso: descarga y, cuando ya está, instala. */
    fun primaryAction() {
        val state = _state.value
        if (state.phase == UpdatePhase.DOWNLOADING) return
        if (state.phase == UpdatePhase.READY) {
            state.apk?.let { Updater.install(getApplication(), it) }
            return
        }
        val release = state.release ?: return
        _state.value = state.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, error = null)
        viewModelScope.launch {
            runCatching {
                Updater.download(getApplication(), release) { pct ->
                    _state.value = _state.value.copy(progress = pct)
                }
            }.onSuccess { file ->
                _state.value = _state.value.copy(phase = UpdatePhase.READY, apk = file)
                // Con el permiso ya concedido, se encadena sin pedir otro toque.
                if (Updater.canInstall(getApplication())) {
                    Updater.install(getApplication(), file)
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    phase = UpdatePhase.FAILED,
                    error = e.message ?: "La descarga falló"
                )
            }
        }
    }

    fun openPermissionSettings() = Updater.openUnknownSourcesSettings(getApplication())

    /**
     * El permiso se concede fuera de la app, así que hay que releerlo al volver
     * al primer plano; si no, el aviso pediría un permiso ya dado para siempre.
     * La APK ya descargada se conserva: al volver, el botón dice «Instalar».
     */
    fun recheckInstallPermission() {
        val can = Updater.canInstall(getApplication())
        if (can != _state.value.canInstall) {
            _state.value = _state.value.copy(canInstall = can)
        }
    }

    /** Cerrar el aviso no vuelve a preguntar por esta versión en el arranque. */
    fun dismiss() {
        _state.value.release?.let { markOffered(it) }
        _state.value = _state.value.copy(phase = UpdatePhase.IDLE)
    }

    fun clearManualResult() {
        _state.value = _state.value.copy(manualResult = null)
    }

    private fun alreadyOffered(release: Updater.Release) =
        prefs.getLong(KEY_OFFERED, 0L) >= release.versionCode

    private fun markOffered(release: Updater.Release) =
        prefs.edit().putLong(KEY_OFFERED, release.versionCode).apply()

    private companion object {
        const val KEY_OFFERED = "offered_version_code"
    }
}
