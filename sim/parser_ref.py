# -*- coding: utf-8 -*-
"""
Referencia del parser de notificaciones bancarias de SmartCount.
Se escribe primero aquí para poder probarlo contra notificaciones reales,
y luego se transcribe a Kotlin línea por línea.
"""
import re, unicodedata

KINDS = ["BIZUM_RECEIVED","BIZUM_SENT","TRANSFER_RECEIVED","TRANSFER_SENT",
         "CARD_SPEND","CARD_ADJUSTMENT","DIRECT_DEBIT","REFUND",
         "JOINT_SPEND","JOINT_WITHDRAWAL","JOINT_INCOME","SELF_TRANSFER",
         "INCOME_OTHER","SPEND_OTHER","UNKNOWN"]

# --- normalización -------------------------------------------------------
def collapse_doubled(t):
    """Android repite el título: "AmazonAmazon", "Conjunta · X  Conjunta · X  "."""
    s = (t or "").strip()
    n = len(s)
    if n >= 2 and n % 2 == 0 and s[:n//2] == s[n//2:]:
        return s[:n//2].strip()
    # con espacios de cola: probar sobre el texto ya recortado
    c = re.sub(r"\s+", " ", s).strip()
    n = len(c)
    if n >= 2 and n % 2 == 0 and c[:n//2] == c[n//2:]:
        return c[:n//2].strip()
    return s

EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿️⬀-⯿←-⇿]")
def strip_emoji(s): return EMOJI.sub("", s or "").strip(" ·-—,")

# --- importe -------------------------------------------------------------
BALANCE = re.compile(r"saldo(?:\s+de\s+\w+)?\s*:\s*[\d.,]+\s*€?", re.I)
AMT_AFTER  = re.compile(r"(?<![\d.,])(\d[\d.,]*)\s*(?:€|EUR\b|eur\b|euros\b)")
AMT_BEFORE = re.compile(r"(?:€|EUR\s*)(\d[\d.,]*)")

def parse_number(raw):
    s = raw.strip().rstrip(".,")
    last_c, last_d = s.rfind(","), s.rfind(".")
    if last_c >= 0 and last_d >= 0:
        # el separador más a la derecha es el decimal
        if last_c > last_d: s = s.replace(".", "").replace(",", ".")
        else: s = s.replace(",", "")
    elif last_c >= 0 or last_d >= 0:
        i = max(last_c, last_d)
        tail = len(s) - i - 1
        s = s.replace(",", "").replace(".", "") if tail == 3 else \
            (s.replace(",", ".") if last_c >= 0 else s)
    try: return float(s)
    except ValueError: return None

def find_amount(text):
    """Primer importe del texto, ignorando la línea de saldo."""
    cleaned = BALANCE.sub(" ", text)
    best = None
    for rx in (AMT_AFTER, AMT_BEFORE):
        m = rx.search(cleaned)
        if m and (best is None or m.start() < best[0]):
            best = (m.start(), m.group(1))
    return parse_number(best[1]) if best else None

# --- reglas --------------------------------------------------------------
IGNORE = [
    r"recordatorio de pago", r"est[áa] programado para",
    r"podr[íi]as fraccionar", r"denegado el pago", r"pago denegado",
    r"^retenido importe",
    r"savings plan", r"plan de ahorro", r"capital at risk",
    r"you earned .* interest", r"update your app", r"set limit orders",
    r"monthly payout",
]

# Nivel 2: si ninguna regla concreta encaja, al menos sabemos la dirección.
MONEY_IN_HINTS = [
    r"has recibido", r"te ha enviado", r"te han enviado", r"recibido un ingreso",
    r"ingreso (?:de|por)", r"abono", r"devoluci[óo]n", r"reembolso",
    r"you (?:have )?received", r"received", r"refund", r"credited",
]
MONEY_OUT_HINTS = [
    r"has gastado", r"has pagado", r"has enviado", r"gasto de", r"pago de",
    r"cargo", r"adeudo", r"compra", r"retirada", r"has retirado",
    r"you (?:have )?paid", r"spent", r"payment of", r"debited", r"withdraw",
    r"renovad", r"suscripci[óo]n", r"cuota", r"cobro", r"recibo", r"subscription",
]

# El orden importa: lo específico antes que lo genérico. "Has enviado un Bizum
# de 8,50 € a X" tiene que ganar a la regla de "Has enviado ... a X".
RULES = [
  # Bizum, en cualquiera de las redacciones de los tres bancos
  (r"^(?P<name>.+?)\s+sent you a Bizum of",             "BIZUM_RECEIVED"),
  (r"You sent a Bizum of .*? to\s+(?P<name>.+?)\.?$",   "BIZUM_SENT"),
  (r"Bizum sent to\s+(?P<name>.+?)\.?$",                "BIZUM_SENT"),
  (r"Bizum recibido de\s+(?P<name>.+?)\s+por",          "BIZUM_RECEIVED"),
  (r"Has recibido un Bizum de\s+(?P<name>.+?)\s+(?:por|de)", "BIZUM_RECEIVED"),
  (r"Has enviado un Bizum de .* a\s+(?P<name>.+?)(?:\.|$)", "BIZUM_SENT"),
  (r"Bizum enviado a\s+(?P<name>.+?)(?:[.:]|$)",        "BIZUM_SENT"),
  # Trade Republic (inglés)
  (r"You have received .*? from\s+(?P<name>.+?)\.?$",  "TRANSFER_RECEIVED"),
  (r"Received\s+[€\d][\d.,]*\s*€?\s+from\s+(?P<name>[^.]+)", "REFUND"),
  (r"Spent\s+[€\d][\d.,]*\s*€?\s+at\s+(?P<name>.+?)\.?$",    "CARD_SPEND"),
  (r"payment was adjusted to",                          "CARD_ADJUSTMENT"),
  (r"You (?:have )?sent .*? to\s+(?P<name>.+?)\.?$",    "TRANSFER_SENT"),
  (r"Refund of .*? from\s+(?P<name>.+?)\.?$",           "REFUND"),
  (r"You paid\s+[€\d][\d.,]*\s*€?\s+(?:to|at)\s+(?P<name>.+?)\.?$", "CARD_SPEND"),
  # Revolut (español)
  (r"(?P<name>.+?)\s+ha retirado .* Cuenta Conjunta",   "JOINT_WITHDRAWAL"),
  (r"(?P<name>.+?)\s+ha gastado",                       "JOINT_SPEND"),
  (r"Has recibido .* de\s+(?P<name>.+?)\s+en tu Cuenta Conjunta", "JOINT_INCOME"),
  (r"Has enviado .* a\s+(?P<name>.+?)(?:\.|$)",         "TRANSFER_SENT"),
  (r"^Te ha enviado",                                   "BIZUM_RECEIVED"),   # el nombre está en el título
  (r"(?P<name>.+?)\s+te ha enviado dinero",             "TRANSFER_RECEIVED"),
  (r"Has gastado",                                      "CARD_SPEND"),       # comercio en el título
  (r"^Gasto de",                                        "CARD_SPEND"),
  (r"Has pagado .* a\s+(?P<name>.+?)(?:\.|$)",          "TRANSFER_SENT"),
  (r"Has retirado",                                     "CARD_SPEND"),
  (r"Retirada de efectivo",                             "CARD_SPEND"),
  # BBVA (español)
  (r"un adeudo de\s+(?P<name>.+?)\s+de\s+[\d.,]+\s*EUR", "DIRECT_DEBIT"),
  (r"Devoluci[óo]n aceptada de .* en\s+(?P<name>.+?)\s+con tu tarjeta", "REFUND"),
  (r"(?:Pago aceptado de|Aceptado pago de) .* en\s+(?P<name>.+?)\s+con tu tarjeta", "CARD_SPEND"),
  (r"[Tt]ransferencia recibida de\s+(?P<name>.+?)(?:\.|$)", "TRANSFER_RECEIVED"),
  (r"[Ii]ngreso por transferencia de\s+(?P<name>.+?)(?:\.|$)", "TRANSFER_RECEIVED"),
  (r"[Tt]ransferencia (?:emitida|enviada|realizada) .* a\s+(?P<name>.+?)(?:\.|$)", "TRANSFER_SENT"),
  (r"Compra .* en\s+(?P<name>.+?)\s+con tu tarjeta",    "CARD_SPEND"),
]

TITLE_IS_COUNTERPARTY = {"BIZUM_RECEIVED", "CARD_SPEND", "CARD_ADJUSTMENT",
                         "INCOME_OTHER", "SPEND_OTHER"}
# En estos el título trae el comercio, pero la contraparte es una persona
TITLE_IS_MERCHANT = {"JOINT_SPEND", "JOINT_WITHDRAWAL", "CARD_SPEND",
                     "CARD_ADJUSTMENT", "SPEND_OTHER"}
CONCEPT = [re.compile(r"concepto[:\s]+[\"“]?(?P<c>[^\"”.\n]{1,60})", re.I)]

def clean_name(s):
    s = strip_emoji(re.sub(r"\s+", " ", s or "")).strip(" ,:;-")
    # "Conjunta · Amazon" / "Cuenta Conjunta · Tripo AI" -> lo que va tras el ·
    if "·" in s: s = s.split("·")[-1].strip()
    # códigos de comercio: "WWW.AMAZON* 404-451942" -> "AMAZON"
    if "*" in s: s = s.split("*")[0].strip()
    s = re.sub(r"^(?:www\.)", "", s, flags=re.I).strip()
    # un punto final se conserva solo si cierra una inicial: "Ivan C.S." pero "Amazon"
    while s.endswith(".") and not re.search(r"(?:^|[\s.])[A-Za-zÁÉÍÓÚÑ]\.$", s):
        s = s[:-1].strip()
    if not s: return None
    if s.isupper() and len(s) > 3:
        s = " ".join(w.capitalize() for w in s.lower().split())
    return s

def _norm(s):
    s = unicodedata.normalize("NFD", (s or "").lower())
    return re.sub(r"[^a-z ]", " ", "".join(c for c in s if unicodedata.category(c) != "Mn"))

def is_self(name, own_name):
    """¿La contraparte eres tú? Compara por tokens, sin tildes ni mayúsculas."""
    if not name or not own_name: return False
    a, b = set(_norm(name).split()), set(_norm(own_name).split())
    a.discard(""); b.discard("")
    if not a or not b: return False
    return a == b or len(a & b) >= 2

GENERIC_TITLES = re.compile(
    r"^(transfer received|recordatorio|pago aceptado|aceptado pago|pago denegado|"
    r"recibo cargado|importe retenido|devoluci[óo]n|ingreso|abono|has recibido|"
    r"dinero recibido|transferencia (completada|realizada)|conjunta|cuenta conjunta|"
    r"retirada|savings plan|update your app|set limit orders|you earned)", re.I)

def parse(bank, raw_title, raw_text, own_name=None):
    title = collapse_doubled(raw_title)
    text  = collapse_doubled(raw_text).replace("\\n", "\n")
    full  = f"{title}. {text}" if title else text

    for rx in IGNORE:
        if re.search(rx, full, re.I):
            return None

    amount = find_amount(text) or find_amount(title)
    if amount is None or amount <= 0:
        return None

    # El orden de los campos no siempre es el mismo, así que se prueban las
    # reglas sobre el texto, sobre el título y sobre la unión en ambos sentidos.
    candidates = [text, title, f"{title}. {text}", f"{text}. {title}"]
    kind, name, confidence = "UNKNOWN", None, "LOW"
    for cand in candidates:
        if not cand.strip():
            continue
        hit = None
        for rx, k in RULES:
            m = re.search(rx, cand, re.I | re.M)
            if m:
                hit = (k, clean_name(m.group("name")) if "name" in (m.groupdict() or {}) else None)
                break
        if hit:
            kind, name, confidence = hit[0], hit[1], "HIGH"
            break

    # Nivel 2: ninguna regla encaja, pero hay importe y una pista de dirección.
    # Preferimos un movimiento a revisar antes que perderlo.
    if kind == "UNKNOWN":
        low = full.lower()
        if any(re.search(h, low) for h in MONEY_IN_HINTS):
            kind = "INCOME_OTHER"
        elif any(re.search(h, low) for h in MONEY_OUT_HINTS):
            kind = "SPEND_OTHER"
        else:
            return None

    # cuando la regla no da nombre, el título suele ser la contraparte
    cleaned_title = clean_name(strip_emoji(title))
    if cleaned_title and GENERIC_TITLES.match(cleaned_title):
        cleaned_title = None
    merchant = cleaned_title if kind in TITLE_IS_MERCHANT else None
    if not name and kind in TITLE_IS_COUNTERPARTY:
        name = cleaned_title

    # movimientos entre tus propias cuentas: no son un gasto compartido
    if is_self(name, own_name):
        kind = "SELF_TRANSFER"

    concept = None
    for rx in CONCEPT:
        m = rx.search(full)
        if m: concept = m.group("c").strip(); break

    return dict(kind=kind, amount=round(amount, 2), counterparty=name,
                merchant=merchant, concept=concept, confidence=confidence)
