"""Choix des reglages du solveur a partir des statistiques.

Pas de generation de code : une fonction de decision lisible qui lit les
statistiques de l'instance (.in), et si elles sont disponibles celles de la
solution (.out) et la trace d'execution, puis propose les proprietes systeme
a passer au solveur Java.

Les seuils ci-dessous sont des HYPOTHESES calibrees sur les quatre instances
officielles. Avec si peu de points on ne peut pas parler d'apprentissage : ce
sont des regles interpretables, a confronter au reglage aleatoire de
tools/stats/tuner.py avant d'en tirer la moindre conclusion.

    python -m tools.stats.advisor instances/kittens.in
    python -m tools.stats.advisor instances/kittens.in --solution output/kittens.out
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from . import ceiling, granularity, overlap, plateau
from .common import read_instance, read_solution
from .solution import compute as solution_compute

# Seuils de classification
PLATEAU_DEGENERE = 0.99      # part de requetes dont l'endpoint a deux caches equivalents
ECART_PLAT = 5.0             # ms : en dessous, deplacer une video ne change presque rien
ECART_RIGIDE = 50.0          # ms : au dessus, les echanges sont massivement rejetes
RECOUVREMENT_FORT = 0.40     # p95 du Jaccard entre caches
DISJOINT_FORT = 0.50         # part de paires de caches sans endpoint commun
VIDEOS_PAR_CACHE_FAIBLE = 10.0
PRESSION_SERREE = 0.95

# Seuils de retroaction (statistiques de solution / trace)
GASPILLAGE_NOTABLE = 0.02
REDONDANCE_NOTABLE = 0.10
PLAFOND_ATTEINT = 0.90
KICKS_STERILES = 1e-4        # improvements / kicks en dessous duquel l'ILS tourne a vide

BASE_CONFIG = {
    "ils.descentShare": 0.3333,
    "ils.sweeps": 30,
    "ils.kick": 3,
    "ils.local": 3,
    "ils.temp": 0.0,
    "ils.noise": 0.2,
    "ils.repair": 2,
}


def instance_stats(path: str | Path) -> dict:
    inst = read_instance(path)
    return {
        "dimensions": {"V": inst.V, "E": inst.E, "R": inst.R, "C": inst.C, "X": inst.X},
        "ceiling": ceiling.compute(inst),
        "granularity": granularity.compute(inst),
        "overlap": overlap.compute(inst),
        "plateau": plateau.compute(inst),
    }


def classify(stats: dict) -> tuple[str, list[str]]:
    pl = stats["plateau"]
    ov = stats["overlap"]
    gr = stats["granularity"]

    ecart = pl["ecart_best2_best1_moyen"]
    sur_plateau = pl["part_requetes_sur_plateau"]

    if sur_plateau >= PLATEAU_DEGENERE and ecart == 0.0:
        return "degeneree", [
            f"{100 * sur_plateau:.0f}% des requetes sur plateau et ecart best2-best1 nul",
            "tous les caches sont interchangeables : c'est du bin-packing, pas du placement",
        ]

    if ecart < ECART_PLAT and ov["recouvrement_p95"] > RECOUVREMENT_FORT:
        return "plateau_large", [
            f"ecart best2-best1 de {ecart:.1f} ms seulement",
            f"recouvrement p95 des caches eleve ({ov['recouvrement_p95']:.2f})",
            "deplacer une video change tres peu le score : il faut de la diversification",
        ]

    if ecart > ECART_RIGIDE and ov["part_paires_disjointes"] > DISJOINT_FORT:
        return "rigide_fragmentee", [
            f"ecart best2-best1 large ({ecart:.1f} ms)",
            f"{100 * ov['part_paires_disjointes']:.0f}% des paires de caches sont disjointes",
            "les caches interagissent peu : l'optimisation exacte par blocs paie",
        ]

    if (gr["videos_par_cache_moyen"] < VIDEOS_PAR_CACHE_FAIBLE
            and gr["pression_capacite"] >= PRESSION_SERREE):
        return "sac_a_dos_serre", [
            f"{gr['videos_par_cache_moyen']:.1f} videos par cache",
            f"pression de capacite {gr['pression_capacite']:.2f}",
            "la granularite du sac a dos domine le choix du contenu",
        ]

    return "standard", ["aucune signature marquee : configuration de reference"]


def base_for(archetype: str) -> dict:
    config = dict(BASE_CONFIG)
    if archetype == "degeneree":
        # Le chemin bin-packing ignore les parametres d'ILS.
        return config
    if archetype == "plateau_large":
        config.update({"ils.temp": 0.02, "ils.kick": 6, "ils.local": 5,
                       "ils.noise": 0.35, "ils.descentShare": 0.25})
    elif archetype == "rigide_fragmentee":
        config.update({"ils.temp": 0.0, "ils.kick": 2, "ils.local": 3,
                       "ils.noise": 0.15, "ils.descentShare": 0.5})
    elif archetype == "sac_a_dos_serre":
        config.update({"ils.descentShare": 0.6, "ils.sweeps": 60, "ils.kick": 2})
    return config


def feedback(config: dict, sol: dict | None, trace: dict | None) -> tuple[dict, list[str]]:
    """Ajuste la configuration a partir du resultat de la run precedente."""
    config = dict(config)
    notes: list[str] = []

    if sol is not None:
        if sol["part_capacite_gaspillee"] > GASPILLAGE_NOTABLE:
            notes.append(
                f"{100 * sol['part_capacite_gaspillee']:.1f}% de capacite gaspillee : "
                "une passe d'insertion finale au gain brut reste a gagner")
        if sol["part_retraits_gratuits"] > REDONDANCE_NOTABLE:
            config["ils.kick"] = min(12, int(config["ils.kick"]) + 3)
            notes.append(
                f"{100 * sol['part_retraits_gratuits']:.1f}% des placements sont "
                "redondants : kicks agrandis")
        if sol.get("caches_sous_exploites", 0) > 0.5 * sol.get("placements", 1):
            notes.append("beaucoup de caches sous-exploites : cibler le LNS dessus")

    if trace is not None:
        part = trace.get("part_borne_superieure", 0.0)
        kicks = trace.get("ils", {}).get("kicks", 0)
        improvements = trace.get("ils", {}).get("improvements", 0)
        if part > PLAFOND_ATTEINT:
            notes.append(
                f"deja a {100 * part:.1f}% de la borne superieure : "
                "deplacer le budget vers une autre instance rapporte davantage")
        if kicks > 1000 and improvements / max(kicks, 1) < KICKS_STERILES:
            config["ils.temp"] = max(float(config["ils.temp"]), 0.02)
            config["ils.noise"] = min(0.5, float(config["ils.noise"]) + 0.1)
            notes.append(
                f"{improvements} ameliorations pour {kicks} kicks : l'ILS tourne a vide, "
                "diversification augmentee")

    return config, notes


def recommend(instance: str | Path, solution: str | Path | None = None,
              trace_path: str | Path | None = None) -> dict:
    stats = instance_stats(instance)
    archetype, reasons = classify(stats)
    config = base_for(archetype)

    sol = None
    if solution is not None:
        inst = read_instance(instance)
        sol = solution_compute(inst, read_solution(solution, inst))

    trace = None
    if trace_path is not None and Path(trace_path).is_file():
        trace = json.loads(Path(trace_path).read_text())

    config, notes = feedback(config, sol, trace)

    return {
        "instance": Path(instance).name,
        "archetype": archetype,
        "raisons": reasons,
        "configuration": config,
        "retroaction": notes,
        "plafond_score": stats["ceiling"]["plafond_score"],
        "ligne_de_commande": java_flags(config),
        "_stats": stats,
        "_solution": sol,
    }


def java_flags(config: dict) -> str:
    return " ".join(f"-D{k}={v}" for k, v in sorted(config.items()))


def allocate_budget(recommendations: list[dict], total_seconds: int,
                    floor_seconds: int = 5) -> dict[str, int]:
    """Repartit un budget total entre instances selon la marge restante.

    Le score final est la somme sur les instances : une seconde depensee sur une
    instance deja proche de sa borne superieure ne rapporte presque rien.
    """
    margins = {}
    for rec in recommendations:
        sol = rec.get("_solution")
        ceil_ = rec["plafond_score"]
        if sol and ceil_ > 0:
            margins[rec["instance"]] = max(0.0, 1.0 - sol["score"] / ceil_)
        else:
            margins[rec["instance"]] = 1.0

    pool = max(0, total_seconds - floor_seconds * len(margins))
    total_margin = sum(margins.values())
    out = {}
    for name, margin in margins.items():
        share = (margin / total_margin) if total_margin > 0 else 1.0 / len(margins)
        out[name] = floor_seconds + int(pool * share)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description="Reglages conseillés a partir des statistiques")
    parser.add_argument("instances", nargs="+", type=Path)
    parser.add_argument("--solution-dir", type=Path)
    parser.add_argument("--trace-dir", type=Path)
    parser.add_argument("--budget", type=int, help="budget total en secondes a repartir")
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()

    recs = []
    for path in args.instances:
        sol = None
        if args.solution_dir:
            candidate = args.solution_dir / f"{path.stem}.out"
            sol = candidate if candidate.is_file() else None
        trace = None
        if args.trace_dir:
            candidate = args.trace_dir / f"{path.stem}.json"
            trace = candidate if candidate.is_file() else None
        recs.append(recommend(path, sol, trace))

    budget = allocate_budget(recs, args.budget) if args.budget else None

    for rec in recs:
        print(f"=== {rec['instance']} -> {rec['archetype']} ===")
        for reason in rec["raisons"]:
            print(f"    {reason}")
        if rec["retroaction"]:
            print("  retroaction :")
            for note in rec["retroaction"]:
                print(f"    {note}")
        flags = rec["ligne_de_commande"]
        if budget:
            flags = f"-Dils.seconds={budget[rec['instance']]} " + flags
        print(f"  java {flags} -cp out GoogleHash2017 ...")
        print()

    if budget:
        print("--- repartition du budget selon la marge restante ---")
        for name, seconds in budget.items():
            print(f"  {name:32s} {seconds:5d} s")

    if args.json:
        clean = [{k: v for k, v in r.items() if not k.startswith("_")} for r in recs]
        args.json.write_text(json.dumps(clean, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\nJSON : {args.json}")


if __name__ == "__main__":
    main()
