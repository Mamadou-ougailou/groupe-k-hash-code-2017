"""Orchestrateur : applique toutes les metriques a une ou plusieurs instances.

    python -m tools.stats.run_all instances/*.in
    python -m tools.stats.run_all instances/kittens.in --solution output/kittens.out
    python -m tools.stats.run_all instances/*.in --json stats.json --csv stats.csv
"""

from __future__ import annotations

import argparse
import csv
import json
import time
from pathlib import Path

from . import ceiling, granularity, overlap, plateau, solution
from .common import read_instance, read_solution

INSTANCE_METRICS = {
    "ceiling": ceiling.compute,
    "granularity": granularity.compute,
    "overlap": overlap.compute,
    "plateau": plateau.compute,
}

# Colonnes du tableau de synthese : celles qui pilotent une decision.
SUMMARY = [
    ("plafond_score", "ceiling"),
    ("part_requetes_servables", "ceiling"),
    ("videos_par_cache_moyen", "granularity"),
    ("cv_tailles", "granularity"),
    ("pression_capacite", "granularity"),
    ("correlation_taille_popularite", "granularity"),
    ("taux_duplication", "granularity"),
    ("recouvrement_p95", "overlap"),
    ("part_paires_disjointes", "overlap"),
    ("part_requetes_sur_plateau", "plateau"),
    ("ecart_best2_best1_moyen", "plateau"),
]


def analyse(path: Path, solution_path: Path | None = None) -> dict:
    inst = read_instance(path)
    out: dict = {
        "instance": path.name,
        "dimensions": {"V": inst.V, "E": inst.E, "R": inst.R, "C": inst.C, "X": inst.X},
    }
    for name, fn in INSTANCE_METRICS.items():
        t0 = time.perf_counter()
        out[name] = fn(inst)
        out[name]["_secondes"] = round(time.perf_counter() - t0, 3)

    if solution_path is not None:
        has = read_solution(solution_path, inst)
        t0 = time.perf_counter()
        out["solution"] = solution.compute(inst, has)
        out["solution"]["_fichier"] = solution_path.name
        out["solution"]["_secondes"] = round(time.perf_counter() - t0, 3)
        if out["ceiling"]["plafond_score"] > 0:
            out["solution"]["part_du_plafond"] = round(
                out["solution"]["score"] / out["ceiling"]["plafond_score"], 4)
    return out


def summary_row(result: dict) -> dict:
    row = {"instance": result["instance"], **result["dimensions"]}
    for key, group in SUMMARY:
        row[key] = result[group][key]
    if "solution" in result:
        row["score"] = result["solution"]["score"]
        row["part_du_plafond"] = result["solution"].get("part_du_plafond", 0.0)
        row["part_retraits_gratuits"] = result["solution"]["part_retraits_gratuits"]
        row["part_capacite_gaspillee"] = result["solution"]["part_capacite_gaspillee"]
    return row


def main() -> None:
    parser = argparse.ArgumentParser(description="Toutes les statistiques sur des instances Hash Code 2017")
    parser.add_argument("instances", nargs="+", type=Path)
    parser.add_argument("--solution", type=Path, help="solution .out (une seule instance)")
    parser.add_argument("--solution-dir", type=Path,
                        help="dossier de solutions ; associe <nom>.out a <nom>.in")
    parser.add_argument("--json", type=Path)
    parser.add_argument("--csv", type=Path)
    args = parser.parse_args()

    results = []
    for path in args.instances:
        sol = args.solution
        if sol is None and args.solution_dir is not None:
            candidate = args.solution_dir / f"{path.stem}.out"
            sol = candidate if candidate.is_file() else None
        print(f"... {path.name}", flush=True)
        results.append(analyse(path, sol))

    rows = [summary_row(r) for r in results]
    headers = list(rows[0].keys())
    widths = {h: max(len(h), max(len(str(r.get(h, ""))) for r in rows)) for h in headers}
    print()
    print("  ".join(h.ljust(widths[h]) for h in headers))
    for row in rows:
        print("  ".join(str(row.get(h, "")).ljust(widths[h]) for h in headers))

    if args.json:
        args.json.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\nJSON : {args.json}")
    if args.csv:
        with args.csv.open("w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=headers)
            writer.writeheader()
            writer.writerows(rows)
        print(f"CSV  : {args.csv}")


if __name__ == "__main__":
    main()
