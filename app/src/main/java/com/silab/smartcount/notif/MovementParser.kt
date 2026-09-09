package com.silab.smartcount.notif

import com.silab.smartcount.data.db.Confidence
import com.silab.smartcount.data.db.DetectedKind
import java.text.Normalizer

data class ParsedMovement(
    val amount: Double,
    val currency: String,
    val kind: DetectedKind,
    val counterparty: String?,
    val merchant: String?,
    val concept: String?,
    val confidence: Confidence
)

/**
 * Lee las notificaciones de las apps de banco y las convierte en movimientos.
 *
 * Escrito contra notificaciones reales de Revolut, Trade Republic y BBVA, que
 * traen cuatro trampas nada obvias:
 *
 *  1. Android duplica el título ("AmazonAmazon"), hay que plegarlo.
 *  2. Revolut añade el saldo en la segunda línea; si no se descarta, el importe
 *     que se lee es el saldo de la cuenta y no el del movimiento.
 *  3. Muchos avisos llevan importe pero NO son un movimiento: recordatorios de
 *     pagos futuros, pagos denegados, retenciones, ofertas de fraccionamiento,
 *     intereses y planes de ahorro. Todos ésos se descartan explícitamente.
 *  4. El reparto entre título y texto no es fijo, así que las reglas se prueban
 *     sobre ambos campos y sobre su unión en los dos sentidos.
 *
 * Y trabaja en dos niveles: primero las reglas concretas (confianza alta) y,
 * si ninguna encaja, una capa genérica que al menos deduce la dirección del
 * dinero (confianza baja, se marca "revisar" en la bandeja). Es preferible un
 * movimiento a revisar que un movimiento perdido.
 *
 * Referencia ejecutable y test: sim/parser_ref.py y sim/parser_check.py.
 */
object MovementParser {

    // -- normalización ----------------------------------------------------

    /** "AmazonAmazon" -> "Amazon"; también con espacios de cola. */
    fun collapseDoubled(raw: String?): String {
        val s = (raw ?: "").trim()
        if (s.length >= 2 && s.length % 2 == 0 && s.substring(0, s.length / 2) == s.substring(s.length / 2)) {
            return s.substring(0, s.length / 2).trim()
        }
        val c = s.replace(Regex("\\s+"), " ").trim()
        if (c.length >= 2 && c.length % 2 == 0 && c.substring(0, c.length / 2) == c.substring(c.length / 2)) {
            return c.substring(0, c.length / 2).trim()
        }
        return s
    }

    private val EMOJI = Regex("[\\p{So}\\p{Cn}\\uFE0F]")
    private fun stripEmoji(s: String?): String =
        EMOJI.replace(s ?: "", "").trim(' ', '·', '-', '—', ',')

    // -- importe ----------------------------------------------------------

    private val BALANCE = Regex("""saldo(?:\s+de\s+\w+)?\s*:\s*[\d.,]+\s*€?""", RegexOption.IGNORE_CASE)
    private val AMOUNT_AFTER = Regex("""(?<![\d.,])(\d[\d.,]*)\s*(?:€|EUR\b|eur\b|euros\b)""")
    private val AMOUNT_BEFORE = Regex("""(?:€|EUR\s*)(\d[\d.,]*)""")

    /** Acepta 1.234,56 · 1,234.56 · 9,99 · 13.89 · 1.000 · 5 */
    fun parseNumber(raw: String): Double? {
        var s = raw.trim().trimEnd('.', ',')
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        if (lastComma >= 0 && lastDot >= 0) {
            s = if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
        } else if (lastComma >= 0 || lastDot >= 0) {
            val i = maxOf(lastComma, lastDot)
            val tail = s.length - i - 1
            s = if (tail == 3) s.replace(",", "").replace(".", "")
                else if (lastComma >= 0) s.replace(',', '.') else s
        }
        return s.toDoubleOrNull()
    }

    /** Primer importe del texto, ignorando la línea de saldo. */
    fun findAmount(text: String): Double? {
        val cleaned = BALANCE.replace(text, " ")
        val candidates = listOfNotNull(
            AMOUNT_AFTER.find(cleaned)?.let { it.range.first to it.groupValues[1] },
            AMOUNT_BEFORE.find(cleaned)?.let { it.range.first to it.groupValues[1] }
        )
        val best = candidates.minByOrNull { it.first } ?: return null
        return parseNumber(best.second)
    }

    // -- reglas -----------------------------------------------------------

    /** Avisos que llevan importe pero no son un movimiento realizado. */
    private val IGNORE = listOf(
        "recordatorio de pago", "est[áa] programado para",
        "podr[íi]as fraccionar", "denegado el pago", "pago denegado",
        "^retenido importe",
        "savings plan", "plan de ahorro", "capital at risk",
        "you earned .* interest", "update your app", "set limit orders",
        "monthly payout"
    ).map { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }

    private data class Rule(val regex: Regex, val kind: DetectedKind, val group: String? = null)

    private fun rule(pattern: String, kind: DetectedKind, group: String? = null) =
        Rule(Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)), kind, group)

    /**
     * El orden importa: lo específico antes que lo genérico. "Has enviado un
     * Bizum de 8,50 € a X" tiene que ganar a la regla de "Has enviado ... a X".
     */
    private val RULES = listOf(
        // Bizum, en cualquiera de las redacciones de los tres bancos
        rule("""^(?<name>.+?)\s+sent you a Bizum of""", DetectedKind.BIZUM_RECEIVED, "name"),
        rule("""You sent a Bizum of .*? to\s+(?<name>.+?)\.?$""", DetectedKind.BIZUM_SENT, "name"),
        rule("""Bizum sent to\s+(?<name>.+?)\.?$""", DetectedKind.BIZUM_SENT, "name"),
        rule("""Bizum recibido de\s+(?<name>.+?)\s+por""", DetectedKind.BIZUM_RECEIVED, "name"),
        rule("""Has recibido un Bizum de\s+(?<name>.+?)\s+(?:por|de)""", DetectedKind.BIZUM_RECEIVED, "name"),
        rule("""Has enviado un Bizum de .* a\s+(?<name>.+?)(?:\.|$)""", DetectedKind.BIZUM_SENT, "name"),
        rule("""Bizum enviado a\s+(?<name>.+?)(?:[.:]|$)""", DetectedKind.BIZUM_SENT, "name"),
        // Trade Republic (notifica en inglés)
        rule("""You have received .*? from\s+(?<name>.+?)\.?$""", DetectedKind.TRANSFER_RECEIVED, "name"),
        rule("""Received\s+[€\d][\d.,]*\s*€?\s+from\s+(?<name>[^.]+)""", DetectedKind.REFUND, "name"),
        rule("""Spent\s+[€\d][\d.,]*\s*€?\s+at\s+(?<name>.+?)\.?$""", DetectedKind.CARD_SPEND, "name"),
        rule("""payment was adjusted to""", DetectedKind.CARD_ADJUSTMENT),
        rule("""You (?:have )?sent .*? to\s+(?<name>.+?)\.?$""", DetectedKind.TRANSFER_SENT, "name"),
        rule("""Refund of .*? from\s+(?<name>.+?)\.?$""", DetectedKind.REFUND, "name"),
        rule("""You paid\s+[€\d][\d.,]*\s*€?\s+(?:to|at)\s+(?<name>.+?)\.?$""", DetectedKind.CARD_SPEND, "name"),
        // Revolut
        rule("""(?<name>.+?)\s+ha retirado .* Cuenta Conjunta""", DetectedKind.JOINT_WITHDRAWAL, "name"),
        rule("""(?<name>.+?)\s+ha gastado""", DetectedKind.JOINT_SPEND, "name"),
        rule("""Has recibido .* de\s+(?<name>.+?)\s+en tu Cuenta Conjunta""", DetectedKind.JOINT_INCOME, "name"),
        rule("""Has enviado .* a\s+(?<name>.+?)(?:\.|$)""", DetectedKind.TRANSFER_SENT, "name"),
        rule("""^Te ha enviado""", DetectedKind.BIZUM_RECEIVED),
        rule("""(?<name>.+?)\s+te ha enviado dinero""", DetectedKind.TRANSFER_RECEIVED, "name"),
        rule("""Has gastado""", DetectedKind.CARD_SPEND),
        rule("""^Gasto de""", DetectedKind.CARD_SPEND),
        rule("""Has pagado .* a\s+(?<name>.+?)(?:\.|$)""", DetectedKind.TRANSFER_SENT, "name"),
        rule("""Has retirado""", DetectedKind.CARD_SPEND),
        rule("""Retirada de efectivo""", DetectedKind.CARD_SPEND),
        // BBVA
        rule("""un adeudo de\s+(?<name>.+?)\s+de\s+[\d.,]+\s*EUR""", DetectedKind.DIRECT_DEBIT, "name"),
        rule("""Devoluci[óo]n aceptada de .* en\s+(?<name>.+?)\s+con tu tarjeta""", DetectedKind.REFUND, "name"),
        rule("""(?:Pago aceptado de|Aceptado pago de) .* en\s+(?<name>.+?)\s+con tu tarjeta""", DetectedKind.CARD_SPEND, "name"),
        rule("""[Tt]ransferencia recibida de\s+(?<name>.+?)(?:\.|$)""", DetectedKind.TRANSFER_RECEIVED, "name"),
        rule("""[Ii]ngreso por transferencia de\s+(?<name>.+?)(?:\.|$)""", DetectedKind.TRANSFER_RECEIVED, "name"),
        rule("""[Tt]ransferencia (?:emitida|enviada|realizada) .* a\s+(?<name>.+?)(?:\.|$)""", DetectedKind.TRANSFER_SENT, "name"),
        rule("""Compra .* en\s+(?<name>.+?)\s+con tu tarjeta""", DetectedKind.CARD_SPEND, "name")
    )

    /** Nivel 2: si ninguna regla concreta encaja, al menos sabemos la dirección. */
    private val MONEY_IN_HINTS = listOf(
        "has recibido", "te ha enviado", "te han enviado", "recibido un ingreso",
        "ingreso (?:de|por)", "abono", "devoluci[óo]n", "reembolso",
        "you (?:have )?received", "received", "refund", "credited"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val MONEY_OUT_HINTS = listOf(
        "has gastado", "has pagado", "has enviado", "gasto de", "pago de",
        "cargo", "adeudo", "compra", "retirada", "has retirado",
        "you (?:have )?paid", "spent", "payment of", "debited", "withdraw",
        "renovad", "suscripci[óo]n", "cuota", "cobro", "recibo", "subscription"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    /** Tipos en los que el título trae el comercio. */
    private val TITLE_IS_MERCHANT = setOf(
        DetectedKind.JOINT_SPEND, DetectedKind.JOINT_WITHDRAWAL,
        DetectedKind.CARD_SPEND, DetectedKind.CARD_ADJUSTMENT, DetectedKind.SPEND_OTHER
    )

    /** Tipos en los que, a falta de nombre en el texto, el título es la contraparte. */
    private val TITLE_IS_COUNTERPARTY = setOf(
        DetectedKind.BIZUM_RECEIVED, DetectedKind.CARD_SPEND, DetectedKind.CARD_ADJUSTMENT,
        DetectedKind.INCOME_OTHER, DetectedKind.SPEND_OTHER
    )

    private val GENERIC_TITLE = Regex(
        "^(transfer received|recordatorio|pago aceptado|aceptado pago|pago denegado|" +
            "recibo cargado|importe retenido|devoluci[óo]n|ingreso|abono|has recibido|" +
            "dinero recibido|transferencia (completada|realizada)|conjunta|cuenta conjunta|" +
            "retirada|savings plan|update your app|set limit orders|you earned)",
        RegexOption.IGNORE_CASE
    )

    private val CONCEPT = Regex("""concepto[:\s]+["“]?(?<c>[^"”.\n]{1,60})""", RegexOption.IGNORE_CASE)

    fun cleanName(raw: String?): String? {
        var s = stripEmoji((raw ?: "").replace(Regex("\\s+"), " ")).trim(' ', ',', ':', ';', '-')
        if (s.contains("·")) s = s.substringAfterLast("·").trim()
        if (s.contains("*")) s = s.substringBefore("*").trim()       // "WWW.AMAZON* 404-4519"
        s = s.replace(Regex("^www\\.", RegexOption.IGNORE_CASE), "").trim()
        // el punto final se conserva solo si cierra una inicial: "Ivan C.S." pero "Amazon"
        while (s.endsWith(".") && !Regex("""(?:^|[\s.])\p{L}\.$""").containsMatchIn(s)) {
            s = s.dropLast(1).trim()
        }
        if (s.isBlank()) return null
        if (s == s.uppercase() && s.length > 3) {
            s = s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
        return s
    }

    private fun normalizeName(s: String?): Set<String> =
        Normalizer.normalize(s ?: "", Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .split(" ").filter { it.isNotBlank() }.toSet()

    /** ¿La contraparte eres tú? Movimientos entre tus cuentas no son gasto compartido. */
    fun isSelf(name: String?, ownName: String?): Boolean {
        val a = normalizeName(name)
        val b = normalizeName(ownName)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.intersect(b).size >= 2
    }

    // -- parseo -----------------------------------------------------------

    fun parse(rawTitle: String?, rawText: String?, ownName: String? = null): ParsedMovement? {
        val title = collapseDoubled(rawTitle)
        val text = collapseDoubled(rawText)
        if (title.isBlank() && text.isBlank()) return null
        val full = if (title.isNotBlank()) "$title. $text" else text

        if (IGNORE.any { it.containsMatchIn(full) }) return null

        val amount = findAmount(text) ?: findAmount(title) ?: return null
        if (amount <= 0.0) return null

        // El orden de los campos no siempre es el mismo: se prueban las reglas
        // sobre el texto, sobre el título y sobre la unión en ambos sentidos.
        var kind = DetectedKind.UNKNOWN
        var name: String? = null
        var confidence = Confidence.LOW
        outer@ for (candidate in listOf(text, title, "$title. $text", "$text. $title")) {
            if (candidate.isBlank()) continue
            for (r in RULES) {
                val m = r.regex.find(candidate) ?: continue
                kind = r.kind
                name = if (r.group != null) cleanName(m.groups[r.group]?.value) else null
                confidence = Confidence.HIGH
                break@outer
            }
        }

        // Nivel 2: ninguna regla encaja, pero hay importe y una pista de dirección.
        if (kind == DetectedKind.UNKNOWN) {
            kind = when {
                MONEY_IN_HINTS.any { it.containsMatchIn(full) } -> DetectedKind.INCOME_OTHER
                MONEY_OUT_HINTS.any { it.containsMatchIn(full) } -> DetectedKind.SPEND_OTHER
                else -> return null
            }
        }

        var cleanedTitle = cleanName(stripEmoji(title))
        if (cleanedTitle != null && GENERIC_TITLE.containsMatchIn(cleanedTitle)) cleanedTitle = null
        val merchant = if (kind in TITLE_IS_MERCHANT) cleanedTitle else null
        if (name == null && kind in TITLE_IS_COUNTERPARTY) name = cleanedTitle

        if (isSelf(name, ownName)) kind = DetectedKind.SELF_TRANSFER

        val concept = CONCEPT.find(full)?.groups?.get("c")?.value?.trim()

        return ParsedMovement(
            amount = amount,
            currency = "EUR",
            kind = kind,
            counterparty = name,
            merchant = merchant,
            concept = concept,
            confidence = confidence
        )
    }
}
