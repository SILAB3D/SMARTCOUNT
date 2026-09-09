"""
Comprueba la decisión de QuickAssignReceiver.planFor: qué movimiento se crea
al pulsar el botón de un grupo en la notificación. Transliterado del Kotlin.
"""
def member_by_name(members, name):
    if not name: return None
    needle = name.strip().lower()
    for m in members:
        if m.strip().lower() == needle: return m
    for m in members:
        a = m.strip().lower()
        if a and (needle.startswith(a + " ") or a.startswith(needle.split(" ")[0] + " ")):
            return m
    return None

def plan_for(kind, counterparty, members, me):
    other = member_by_name(members, counterparty)
    is_bizum = kind in ("BIZUM_SENT", "BIZUM_RECEIVED")
    if is_bizum and me and other and other != me:
        if kind == "BIZUM_SENT":
            return ("REIMBURSEMENT", me, other)
        return ("REIMBURSEMENT", other, me)
    return ("EXPENSE", me, tuple(members))

MEMBERS = ["Ana", "Ben", "Cid", "Dee"]
ME = "Ana"
cases = [
    # (kind, contraparte, esperado)
    ("BIZUM_SENT", "Ben", ("REIMBURSEMENT", "Ana", "Ben")),
    ("BIZUM_RECEIVED", "Ben", ("REIMBURSEMENT", "Ben", "Ana")),
    # el banco da nombre completo, el grupo solo el nombre de pila
    ("BIZUM_RECEIVED", "Ben Torres", ("REIMBURSEMENT", "Ben", "Ana")),
    ("BIZUM_SENT", "BEN TORRES", ("REIMBURSEMENT", "Ana", "Ben")),
    # contraparte que no está en el grupo -> gasto repartido
    ("BIZUM_SENT", "Farmacia Central", ("EXPENSE", "Ana", tuple(MEMBERS))),
    ("BIZUM_SENT", None, ("EXPENSE", "Ana", tuple(MEMBERS))),
    # transferencias: nunca reembolso automático
    ("TRANSFER_RECEIVED", "Ben", ("EXPENSE", "Ana", tuple(MEMBERS))),
    ("TRANSFER_SENT", "Dee", ("EXPENSE", "Ana", tuple(MEMBERS))),
    # un Bizum a ti mismo no puede ser reembolso
    ("BIZUM_SENT", "Ana", ("EXPENSE", "Ana", tuple(MEMBERS))),
    ("UNKNOWN", "Ben", ("EXPENSE", "Ana", tuple(MEMBERS))),
]
ok = 0
for kind, cp, expected in cases:
    got = plan_for(kind, cp, MEMBERS, ME)
    good = got == expected
    ok += good
    print(("PASS " if good else "FAIL ") + f"{kind:18} contraparte={str(cp):18} -> {got[0]}"
          + ("" if good else f"  esperado {expected}"))
print(f"\n{ok}/{len(cases)} OK")
raise SystemExit(0 if ok == len(cases) else 1)
