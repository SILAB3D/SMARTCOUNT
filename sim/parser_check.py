# -*- coding: utf-8 -*-
"""
Regresión del parser de notificaciones contra un corpus real de Revolut,
Trade Republic y BBVA (nombres de personas anonimizados).

Este archivo es la REFERENCIA del parser: MovementParser.kt es su
transcripción línea por línea. Si tocas uno, toca el otro y pasa esto.

    python3 sim/parser_check.py
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from parser_ref import parse

OWN = "Alex Ruiz Moreno"
HERE = os.path.dirname(os.path.abspath(__file__))

def main():
    rows = [l.rstrip("\n").split("\t")
            for l in open(os.path.join(HERE, "corpus_notificaciones.tsv"), encoding="utf-8")]
    ok = fail = 0
    for bank, title, text, exp_kind, exp_amount, exp_cp in rows:
        r = parse(bank, title, text, OWN)
        kind = r["kind"] if r else "IGNORED"
        amount = f"{r['amount']:.2f}" if r else ""
        cp = (r["counterparty"] or "") if r else ""
        good = kind == exp_kind and amount == exp_amount and cp == exp_cp
        if good:
            ok += 1
        else:
            fail += 1
            print(f"FALLO  {text[:56]}")
            print(f"       esperado {exp_kind} {exp_amount} {exp_cp}")
            print(f"       obtenido {kind} {amount} {cp}")
    print(f"\n{ok}/{ok + fail} notificaciones clasificadas como se espera")
    return 0 if fail == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
