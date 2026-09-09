"""
Compara los payloads que construye TricountClient.kt (transliterados aquí,
'shadow') con los de la librería de referencia de Python, capturando las
peticiones con una sesión falsa. Detecta errores de signo, redondeo y nombres
de campo, que es donde una API no documentada te rompe en silencio.
"""
import sys, json, uuid, os
# Librería de referencia: `pip install tricount-api`.
# SMARTCOUNT_REF_LIB permite apuntar a una copia local desempaquetada.
_ref = os.environ.get('SMARTCOUNT_REF_LIB')
if _ref:
    sys.path.insert(0, _ref)
from datetime import datetime
import tricount.client as tc

captured = []

class FakeResp:
    status_code = 200
    def raise_for_status(self): pass
    def json(self): return {"Response": [{"Id": {"id": 999}}]}

class FakeSession:
    def __init__(self): self.headers = {}
    def post(self, url, json=None, **kw):
        captured.append(("POST", url, json)); return FakeResp()
    def put(self, url, json=None, **kw):
        captured.append(("PUT", url, json)); return FakeResp()
    def delete(self, url, **kw):
        captured.append(("DELETE", url, None)); return FakeResp()
    def get(self, url, **kw):
        captured.append(("GET", url, None)); return FakeResp()

api = tc.TricountAPI(tc.Credentials(app_id="app-1", public_key_pem="pk"))
api.session = FakeSession(); api.user_id = 42; api._authenticated = True

A = tc.Member(id=1, uuid="u-ana", display_name="Ana")
B = tc.Member(id=2, uuid="u-ben", display_name="Ben")
C = tc.Member(id=3, uuid="u-cid", display_name="Cid")
T = tc.Tricount(id=7, uuid="t-uuid", title="Piso", description="", currency="EUR",
                public_identifier_token="tok", members=[A,B,C],
                membership_uuid_active="u-ana")
D = datetime(2026, 9, 6, 12, 0, 0)

# ---------- shadow: transliteración de mi Kotlin ----------
def money(v): return "%.2f" % v
def r2(v): return round(v*100.0)/100.0
def api_date(d): return d.strftime("%Y-%m-%d %H:%M:%S.%f")

def sh_expense(t, desc, amount, payer, split, category=None, date=D):
    total = abs(amount); per = r2(total/len(split))
    return {"description": desc,
            "amount": {"value": money(-total), "currency": t.currency},
            "membership_uuid_owner": payer.uuid,
            "allocations": [{"membership_uuid": m.uuid,
                             "amount": {"value": money(-per), "currency": t.currency},
                             "type": "AMOUNT"} for m in split],
            "type_transaction": "NORMAL", "status": "ACTIVE", "date": api_date(date),
            **({"category": category} if category else {})}

def sh_income(t, desc, amount, receiver, split, date=D):
    total = abs(amount); per = r2(total/len(split))
    return {"description": desc,
            "amount": {"value": money(total), "currency": t.currency},
            "membership_uuid_owner": receiver.uuid,
            "allocations": [{"membership_uuid": m.uuid,
                             "amount": {"value": money(per), "currency": t.currency},
                             "type": "AMOUNT"} for m in split],
            "type_transaction": "INCOME", "status": "ACTIVE", "date": api_date(date)}

def sh_reimb(t, payer, receiver, amount, desc, date=D):
    total = abs(amount)
    return {"description": desc,
            "amount": {"value": money(total), "currency": t.currency},
            "membership_uuid_owner": payer.uuid,
            "allocations": [
                {"membership_uuid": receiver.uuid,
                 "amount": {"value": money(total), "currency": t.currency}, "type": "AMOUNT"},
                {"membership_uuid": payer.uuid,
                 "amount": {"value": "0", "currency": t.currency}, "type": "AMOUNT"}],
            "type_transaction": "BALANCE", "status": "ACTIVE", "date": api_date(date)}

def norm(p):
    """El uuid del cliente es aleatorio y el formato numérico de la referencia
    usa str(float); normalizamos ambos para comparar lo que importa."""
    p = {k: v for k, v in p.items() if k != "uuid"}
    def fix(a):
        a = dict(a); a["amount"] = {"value": "%.2f" % float(a["amount"]["value"]),
                                    "currency": a["amount"]["currency"]}
        return a
    p["amount"] = {"value": "%.2f" % float(p["amount"]["value"]), "currency": p["amount"]["currency"]}
    p["allocations"] = [fix(a) for a in p["allocations"]]
    return p

def check(name, ref_payload, mine):
    a, b = norm(ref_payload), norm(mine)
    ok = a == b
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        for k in sorted(set(a) | set(b)):
            if a.get(k) != b.get(k):
                print(f"   {k}:\n     ref={a.get(k)}\n     mio={b.get(k)}")
    return ok

results = []

# 1. gasto simple, reparto exacto
captured.clear()
api.create_transaction(T, "Cena", 30.0, A, [A,B,C], date=D)
results.append(check("gasto 30 entre 3", captured[-1][2], sh_expense(T,"Cena",30.0,A,[A,B,C])))

# 2. gasto con reparto no exacto (10/3)
captured.clear()
api.create_transaction(T, "Taxi", 10.0, B, [A,B,C], date=D)
results.append(check("gasto 10 entre 3 (redondeo)", captured[-1][2], sh_expense(T,"Taxi",10.0,B,[A,B,C])))

# 3. gasto con categoria
captured.clear()
api.create_transaction(T, "Compra", 47.35, A, [A,B], category=tc.Category.GROCERIES, date=D)
results.append(check("gasto con categoria", captured[-1][2],
                     sh_expense(T,"Compra",47.35,A,[A,B],category="GROCERIES")))

# 4. ingreso
captured.clear()
api.create_income(T, "Fianza", 100.0, A, [A,B], date=D)
results.append(check("ingreso", captured[-1][2], sh_income(T,"Fianza",100.0,A,[A,B])))

# 5. reembolso (Bizum entre miembros)
captured.clear()
api.create_reimbursement(T, B, A, 25.0, "Bizum", date=D)
results.append(check("reembolso/Bizum", captured[-1][2], sh_reimb(T,B,A,25.0,"Bizum")))

# 6. URLs y verbos
captured.clear()
api.create_transaction(T, "x", 5.0, A, [A], date=D)
api.delete_transaction(T, 123)
urls = [(m,u) for m,u,_ in captured]
exp = [("POST","https://api.tricount.bunq.com/v1/user/42/registry/7/registry-entry"),
       ("DELETE","https://api.tricount.bunq.com/v1/user/42/registry/7/registry-entry/123")]
ok = urls == exp
print(("PASS " if ok else "FAIL ")+"rutas y verbos"); results.append(ok)
if not ok: print("  ", urls)

print("\n%d/%d comprobaciones OK" % (sum(results), len(results)))
sys.exit(0 if all(results) else 1)
