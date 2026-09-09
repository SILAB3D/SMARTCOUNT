# -*- coding: utf-8 -*-
"""
Evalúa la sensibilidad del parser por app: qué reconoce, qué ignora a
propósito y qué se le escapa. Comprueba además que el resultado no dependa
del reparto entre título y texto, que no siempre viene igual.

    python3 sim/eval_parser.py
"""
import sys, os, collections
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import parser_ref as P

OWN = "Alex Ruiz Moreno"

def load(name):
    path = os.path.join(HERE, name)
    return [l.rstrip("\n").split("\t") for l in open(path, encoding="utf-8")]

def evaluate(rows, swap, label):
    per = collections.defaultdict(collections.Counter)
    misses = []
    for row in rows:
        bank, title, text, exp_kind = row[0], row[1], row[2], row[3]
        t, x = (text, title) if swap else (title, text)
        r = P.parse(bank, t, x, OWN)
        kind = r["kind"] if r else "IGNORED"
        per[bank][kind] += 1
        if exp_kind != "IGNORED" and kind == "IGNORED":
            misses.append((bank, text[:60], exp_kind))
    print(f"\n=== {label} ===")
    for bank in sorted(per):
        c = per[bank]
        total = sum(c.values())
        detected = total - c["IGNORED"]
        detail = " ".join(f"{k}:{v}" for k, v in c.most_common() if k != "IGNORED")
        print(f"{bank:<16}{detected}/{total} reconocidos   {detail}")
    for b, t, e in misses:
        print(f"  SE ESCAPA [{b}] {t}  (esperado {e})")
    return len(misses)

def main():
    real = load("corpus_notificaciones.tsv")
    a = evaluate(real, False, "corpus real · orden habitual")
    b = evaluate(real, True, "corpus real · título y texto intercambiados")

    hyp = load("variantes_hipoteticas.tsv")
    c = evaluate(hyp, False, "variantes hipotéticas (redacciones no vistas aún)")

    print(f"\nse escapan: {a} con orden normal, {b} con campos intercambiados, "
          f"{c} en las variantes hipotéticas")
    return 0 if a == b == c == 0 else 1

if __name__ == "__main__":
    sys.exit(main())
