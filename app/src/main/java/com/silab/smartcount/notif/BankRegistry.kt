package com.silab.smartcount.notif

import android.content.Context

/**
 * Qué apps se vigilan.
 *
 * La lista semilla cubre los bancos españoles más habituales, pero los IDs de
 * paquete cambian y hay muchas entidades: por eso existe el "modo aprendizaje"
 * (Ajustes → Detección), que muestra todas las notificaciones recibidas y deja
 * añadir el paquete de tu banco con un toque. Lo aprendido se guarda aquí.
 */
class BankRegistry(context: Context) {

    private val prefs = context.getSharedPreferences("bank_registry", Context.MODE_PRIVATE)

    fun watchedPackages(): Set<String> =
        prefs.getStringSet(KEY_WATCHED, null) ?: DEFAULT_PACKAGES.keys

    fun isWatched(pkg: String): Boolean = watchedPackages().contains(pkg)

    fun add(pkg: String) {
        prefs.edit().putStringSet(KEY_WATCHED, watchedPackages() + pkg).apply()
    }

    fun remove(pkg: String) {
        prefs.edit().putStringSet(KEY_WATCHED, watchedPackages() - pkg).apply()
    }

    fun label(pkg: String): String = DEFAULT_PACKAGES[pkg] ?: pkg

    /**
     * Tu nombre tal y como lo escriben los bancos. Sirve para reconocer los
     * movimientos entre tus propias cuentas (te llegan como si alguien te
     * hubiera enviado dinero) y no llevarlos a la bandeja.
     */
    var ownName: String?
        get() = prefs.getString(KEY_OWN_NAME, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(KEY_OWN_NAME, v.orEmpty()).apply()

    /** Modo aprendizaje: registra todas las notificaciones, no solo las vigiladas. */
    var learnMode: Boolean
        get() = prefs.getBoolean(KEY_LEARN, false)
        set(v) = prefs.edit().putBoolean(KEY_LEARN, v).apply()

    companion object {
        private const val KEY_WATCHED = "watched_packages"
        private const val KEY_LEARN = "learn_mode"
        private const val KEY_OWN_NAME = "own_name"

        /**
         * Semilla. Verifica los que uses: si tu banco no aparece o cambió de
         * paquete, actívalo desde el modo aprendizaje.
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
