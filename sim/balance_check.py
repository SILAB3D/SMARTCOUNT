"""
Comprueba el balance neto y el plan de liquidación de Stats.kt. Transliterado
del Kotlin.

Lo que se vigila aquí es el **signo de cada tipo de movimiento**, que es donde
la API no documentada engaña: guarda los gastos en negativo y los ingresos en
positivo, pero el reembolso (BALANCE) también en positivo aunque cuente como un
gasto. Trabajando con valores absolutos hay que reponer ese signo a mano, y si
se repone mal el balance queda invertido justo en los ingresos.
"""
def r2(v):
    return round(v * 100.0) / 100.0

def sign_of(tx):
    # Gasto y transferencia: quien pone el dinero queda a favor.
    # Ingreso: quien cobra en nombre del grupo pasa a deberlo.
    return -1.0 if tx["type"] == "INCOME" else 1.0

def balances_by_uuid(t):
    b = {m: 0.0 for m in t["members"]}
    for tx in t["transactions"]:
        s = sign_of(tx)
        b[tx["owner"]] = b.get(tx["owner"], 0.0) + s * abs(tx["amount"])
        for uuid, share in tx["allocations"]:
            b[uuid] = b.get(uuid, 0.0) - s * abs(share)
    return {k: r2(v) for k, v in b.items()}

def settlement_plan(t):
    b = balances_by_uuid(t)
    cred = sorted([(k, v) for k, v in b.items() if v > 0.005],
                  key=lambda x: -x[1])
    deb = sorted([(k, -v) for k, v in b.items() if v < -0.005],
                 key=lambda x: -x[1])
    legs = []
    i = j = 0
    cl = cred[0][1] if cred else 0.0
    dl = deb[0][1] if deb else 0.0
    while i < len(cred) and j < len(deb):
        pay = min(cl, dl)
        if pay > 0.005:
            legs.append((deb[j][0], cred[i][0], r2(pay)))
        cl -= pay
        dl -= pay
        if cl <= 0.005:
            i += 1
            cl = cred[i][1] if i < len(cred) else 0.0
        if dl <= 0.005:
            j += 1
            dl = deb[j][1] if j < len(deb) else 0.0
    return legs

def split_evenly(total, n):
    cents = round(abs(total) * 100.0)
    base, extra = divmod(int(cents), n)
    return [(base + (1 if i < extra else 0)) / 100.0 for i in range(n)]

def expense(desc, amount, payer, split):
    parts = split_evenly(amount, len(split))
    return {"type": "NORMAL", "desc": desc, "amount": -abs(amount), "owner": payer,
            "allocations": list(zip(split, [-p for p in parts]))}

def income(desc, amount, receiver, split):
    parts = split_evenly(amount, len(split))
    return {"type": "INCOME", "desc": desc, "amount": abs(amount), "owner": receiver,
            "allocations": list(zip(split, parts))}

def transfer(desc, amount, sender, receiver):
    total = abs(amount)
    return {"type": "BALANCE", "desc": desc, "amount": total, "owner": sender,
            "allocations": [(receiver, total), (sender, 0.0)]}

MEMBERS = ["ana", "ben", "cid"]

def group(*txs):
    return {"members": MEMBERS, "transactions": list(txs)}

results = []

def check(name, got, expected):
    ok = got == expected
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        print(f"     esperado {expected}\n     obtenido {got}")

# --- Salidas: quien paga queda a favor, los demás a deber -------------------
check("gasto 30 entre 3",
      balances_by_uuid(group(expense("Cena", 30.0, "ana", MEMBERS))),
      {"ana": 20.0, "ben": -10.0, "cid": -10.0})

check("gasto no divisible reparte el céntimo suelto",
      balances_by_uuid(group(expense("Taxi", 10.0, "ana", MEMBERS))),
      {"ana": 6.66, "ben": -3.33, "cid": -3.33})

# --- Entradas: quien cobra adquiere la deuda -------------------------------
check("ingreso 90 entre 3 invierte el gasto",
      balances_by_uuid(group(income("Fianza", 90.0, "ana", MEMBERS))),
      {"ana": -60.0, "ben": 30.0, "cid": 30.0})

check("gasto e ingreso iguales se anulan",
      balances_by_uuid(group(expense("Fianza", 90.0, "ana", MEMBERS),
                             income("Devolución", 90.0, "ana", MEMBERS))),
      {"ana": 0.0, "ben": 0.0, "cid": 0.0})

# --- Adelantos: ajustan solo a las dos personas implicadas ------------------
check("transferencia mueve saldo entre dos",
      balances_by_uuid(group(transfer("Bote", 25.0, "ben", "ana"))),
      {"ana": -25.0, "ben": 25.0, "cid": 0.0})

check("la transferencia salda el gasto que la motiva",
      balances_by_uuid(group(expense("Cena", 30.0, "ana", MEMBERS),
                             transfer("Le pago", 10.0, "ben", "ana"))),
      {"ana": 10.0, "ben": 0.0, "cid": -10.0})

# --- Los saldos siempre suman cero -----------------------------------------
mixed = group(expense("Cena", 30.0, "ana", MEMBERS),
              expense("Taxi", 10.0, "ben", MEMBERS),
              income("Fianza", 45.0, "cid", MEMBERS),
              transfer("Adelanto", 12.5, "cid", "ana"))
check("los saldos suman cero",
      abs(sum(balances_by_uuid(mixed).values())) < 0.005, True)

# --- Liquidación ------------------------------------------------------------
check("plan de un solo pago",
      settlement_plan(group(expense("Cena", 30.0, "ana", ["ana", "ben"]))),
      [("ben", "ana", 15.0)])

check("sin deudas no hay plan",
      settlement_plan(group(transfer("Bote", 10.0, "ana", "ben"),
                            transfer("Vuelta", 10.0, "ben", "ana"))),
      [])

# Dos deudores contra un acreedor: un pago por deudor, no más.
plan = settlement_plan(group(expense("Cena", 30.0, "ana", MEMBERS)))
check("un pago por deudor", plan, [("ben", "ana", 10.0), ("cid", "ana", 10.0)])

# El plan liquida de verdad: aplicarlo deja todos los saldos a cero.
applied = group(*(mixed["transactions"] +
                  [transfer("Liquidación", amt, frm, to)
                   for frm, to, amt in settlement_plan(mixed)]))
check("aplicar el plan deja el grupo en paz",
      all(abs(v) < 0.005 for v in balances_by_uuid(applied).values()), True)

check("aplicado el plan, no queda nada que saldar", settlement_plan(applied), [])

print("\n%d/%d comprobaciones OK" % (sum(results), len(results)))
raise SystemExit(0 if all(results) else 1)
