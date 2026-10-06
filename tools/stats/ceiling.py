"""Plafond de score : score maximal si chaque requete etait servie par le
meilleur cache connecte a son endpoint, capacite ignoree.

Repond a la seule question qui dit s'il vaut la peine d'optimiser une instance :
combien de marge reste-t-il ? Formule alignee sur Scorer.upperBound (Java), y
compris la division entiere finale, pour que les deux valeurs soient comparables.

    python -m tools.stats.ceiling instances/kittens.in
"""

from __future__ import annotations

import numpy as np

from .common import Instance, cli


def compute(inst: Instance) -> dict:
    huge = np.iinfo(np.int64).max
    best_cache = np.full(inst.E, huge, dtype=np.int64)
    if inst.ep_lat.size:
        rows = np.repeat(np.arange(inst.E), inst.degrees)
        np.minimum.at(best_cache, rows, inst.ep_lat)

    reachable = np.minimum(inst.dc, best_cache)
    saving = inst.dc - reachable

    total = inst.total_requests
    numerator = int((inst.req_count * saving[inst.req_endpoint]).sum())
    ceiling = numerator * 1000 // total

    served = saving[inst.req_endpoint] > 0
    servable_volume = int(inst.req_count[served].sum())

    return {
        "plafond_score": int(ceiling),
        "requetes_totales": total,
        "requetes_servables": servable_volume,
        "part_requetes_servables": round(servable_volume / total, 4),
        "requetes_perdues_structurellement": total - servable_volume,
        "gain_moyen_max_ms": round(numerator / total, 2),
    }


if __name__ == "__main__":
    cli("ceiling", compute)
