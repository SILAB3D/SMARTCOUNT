package com.silab.smartcount.notif

import android.content.Context

/**
 * Hasta dónde llega la vigilancia de notificaciones.
 *
 * Son tres escalones, del más estrecho al más ancho, y no dos como antes: el
 * interruptor de «modo aprendizaje» mezclaba dos preguntas distintas —qué apps
 * mirar y si descubrir apps nuevas— en un solo sí o no.
 */
enum class LearnMode(val label: String, val description: String) {
    OFF("Apagado", "Solo los bancos que ya trae la app"),
    SELECTIVE("Selectivo", "Solo las apps vigiladas que tengas activadas"),
    FULL("Completo", "Todas las apps del dispositivo, para descubrir la tuya");

    fun next(): LearnMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Qué apps se vigilan.
 *
 * La lista semilla cubre los bancos españoles más habituales, pero los IDs de
 * paquete cambian y hay muchas entidades: para eso está el modo **Completo**,
 * que mira todas las apps y anota cuáles notifican, de forma que la tuya se
 * pueda activar de un toque desde Ajustes.
 *
 * Las apps no se quitan, se **desactivan**. Quitar una de la lista la hacía
 * desaparecer de la pantalla, y con ella la posibilidad de volver a activarla
 * sin recordar su nombre de paquete. Las que trae la app no se pueden borrar
 * —siempre se pueden desactivar—; las que has añadido tú, sí.
 */
class BankRegistry(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("bank_registry", Context.MODE_PRIVATE)

    init {
        migrateLegacy()
    }

    // -- qué apps hay -------------------------------------------------------

    /** Los bancos que trae la app de fábrica. */
    fun defaults(): Set<String> = DEFAULT_PACKAGES.keys

    /** Las que has añadido tú, desde la bandeja o desde Ajustes. */
    fun added(): Set<String> = prefs.getStringSet(KEY_ADDED, emptySet()).orEmpty()

    /** Las apagadas a mano, sean de fábrica o añadidas. */
    fun disabled(): Set<String> = prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty()

    /** Las que han notificado alguna vez sin estar vigiladas: candidatas. */
    fun seen(): Set<String> = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()

    /** Todas las que aparecen en la lista de Ajustes. */
    fun knownPackages(): Set<String> = defaults() + added()

    /** Vigilada y encendida. */
    fun isActive(pkg: String): Boolean = pkg in knownPackages() && pkg !in disabled()

    fun watchedPackages(): Set<String> = knownPackages().filterNot { it in disabled() }.toSet()

    fun setActive(pkg: String, active: Boolean) {
        val updated = if (active) disabled() - pkg else disabled() + pkg
        prefs.edit().putStringSet(KEY_DISABLED, updated).apply()
    }

    /** Pasa una app a vigilada (y encendida). Deja de ser una simple candidata. */
    fun add(pkg: String) {
        prefs.edit()
            .putStringSet(KEY_ADDED, added() + pkg)
            .putStringSet(KEY_DISABLED, disabled() - pkg)
            .putStringSet(KEY_SEEN, seen() - pkg)
            .apply()
    }

    /** Solo vale para las añadidas a mano: las de fábrica se desactivan, no se borran. */
    fun forget(pkg: String) {
        if (pkg in defaults()) { setActive(pkg, false); return }
        prefs.edit()
            .putStringSet(KEY_ADDED, added() - pkg)
            .putStringSet(KEY_DISABLED, disabled() - pkg)
            .apply()
    }

    /**
     * Deja de seguir la app de origen. Es lo que ocurre al marcar una
     * notificación como «no bancaria»: si su app no vuelve a mirarse, no
     * vuelve a aparecer. Se puede reactivar en Ajustes.
     */
    fun stopWatching(pkg: String) {
        prefs.edit()
            .putStringSet(KEY_DISABLED, disabled() + pkg)
            .putStringSet(KEY_SEEN, seen() - pkg)
            .apply()
    }

    /** Anota que una app ha notificado, para poder ofrecerla en Ajustes. */
    fun noteSeen(pkg: String) {
        if (pkg in knownPackages() || pkg in seen()) return
        prefs.edit().putStringSet(KEY_SEEN, seen() + pkg).apply()
    }

    // -- alcance ------------------------------------------------------------

    var learnMode: LearnMode
        get() = runCatching {
            LearnMode.valueOf(prefs.getString(KEY_MODE, null) ?: LearnMode.SELECTIVE.name)
        }.getOrDefault(LearnMode.SELECTIVE)
        set(v) = prefs.edit().putString(KEY_MODE, v.name).apply()

    /**
     * ¿Se mira esta app? Depende del modo, y en los dos primeros se respeta
     * siempre lo apagado a mano: desactivar un banco significa desactivarlo,
     * no «desactivarlo salvo en modo apagado».
     */
    fun isWatched(pkg: String): Boolean = when (learnMode) {
        LearnMode.OFF -> pkg in defaults() && pkg !in disabled()
        LearnMode.SELECTIVE -> isActive(pkg)
        LearnMode.FULL -> true
    }

    // -- nombres ------------------------------------------------------------

    /** El nombre de la app tal y como lo enseña el sistema; si no está, el paquete. */
    fun label(pkg: String): String {
        DEFAULT_PACKAGES[pkg]?.let { return it }
        return runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /**
     * Tu nombre tal y como lo escriben los bancos. Sirve para reconocer los
     * movimientos entre tus propias cuentas (te llegan como si alguien te
     * hubiera enviado dinero) y no llevarlos a la bandeja.
     */
    var ownName: String?
        get() = prefs.getString(KEY_OWN_NAME, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(KEY_OWN_NAME, v.orEmpty()).apply()

    // -- migración ----------------------------------------------------------

    /**
     * Antes había un conjunto de «vigiladas» y un booleano de aprendizaje. Lo
     * que faltaba de la lista se había quitado a mano, así que se traduce a
     * desactivado, y lo que sobraba, a añadido: nadie pierde su configuración
     * al actualizar.
     */
    private fun migrateLegacy() {
        if (prefs.contains(KEY_MODE)) return

        val legacyWatched = prefs.getStringSet(KEY_LEGACY_WATCHED, null)
        val editor = prefs.edit()
        if (legacyWatched != null) {
            editor.putStringSet(KEY_ADDED, legacyWatched - DEFAULT_PACKAGES.keys)
            editor.putStringSet(KEY_DISABLED, DEFAULT_PACKAGES.keys - legacyWatched)
            editor.remove(KEY_LEGACY_WATCHED)
        }
        val legacyLearn = prefs.getBoolean(KEY_LEGACY_LEARN, false)
        editor.putString(KEY_MODE, if (legacyLearn) LearnMode.FULL.name else LearnMode.SELECTIVE.name)
        editor.remove(KEY_LEGACY_LEARN)
        editor.apply()
    }

    companion object {
        private const val KEY_ADDED = "added_packages"
        private const val KEY_DISABLED = "disabled_packages"
        private const val KEY_SEEN = "seen_packages"
        private const val KEY_MODE = "learn_mode_v2"
        private const val KEY_OWN_NAME = "own_name"
        private const val KEY_LEGACY_WATCHED = "watched_packages"
        private const val KEY_LEGACY_LEARN = "learn_mode"

        /**
         * Semilla. Verifica los que uses: si tu banco no aparece o cambió de
         * paquete, pon el modo en Completo y actívalo desde Ajustes.
         */
        val DEFAULT_PACKAGES = mapOf(
            "com.bbva.bbvacontigo" to "BBVA",
            "de.traderepublic.app" to "Trade Republic",
            "es.bancosantander.apps" to "Santander",
            "es.lacaixa.mobile.android.newwapicon" to "CaixaBank",
            "es.openbank.mobile" to "Openbank",
            "es.evobanco.bancamovil" to "EVO Banco",
            "com.ing.mobile" to "ING",
            "es.univia.unicajamovil" to "Unicaja",
            "com.rsi" to "Ruralvía",
            "es.cajamar.cajamar" to "Cajamar",
            "com.abanca.bancadigital" to "Abanca",
            "es.bancsabadell.wallet" to "Sabadell",
            "com.revolut.revolut" to "Revolut",
            "de.number26.android" to "N26"
        )
    }
}
