"""Statistiques sur une solution (.in + .out) : c'est ici que se diagnostique un
local search.

Mesure notamment le cout de retrait de chaque placement (cache, video). Un cout
nul signifie qu'une autre source sert deja aussi bien les memes requetes : le
placement est redondant et son retrait est gratuit. La part de ces placements
est la taille reelle du plateau sur lequel un hill-climbing va s'arreter.

    python -m tools.stats.solution instances/kittens.in output/kittens.out
"""

from __future__ import annotations

import numpy as np

from .common import Instance, cli


def _books(inst: Instance, has: np.ndarray):
    """best1, best2 et le cache fournisseur, par requete.

    best2 est la meilleure source de repli si best1 disparait : deuxieme cache
    contenant la video, ou le datacenter a defaut.
    """
    best1 = inst.dc[inst.req_endpoint].astype(np.int64).copy()
    best2 = best1.copy()
    arg1 = np.full(inst.R, -1, dtype=np.int64)
    found = np.zeros(inst.R, dtype=np.int8)

    order = np.argsort(inst.req_endpoint, kind="stable")
    sorted_ep = inst.req_endpoint[order]
    bounds = np.searchsorted(sorted_ep, np.arange(inst.E + 1))

    for e in range(inst.E):
        lo, hi = bounds[e], bounds[e + 1]
        if lo == hi:
            continue
        rows = order[lo:hi]
        vids = inst.req_video[rows]

        a, b = inst.ep_start[e], inst.ep_start[e + 1]
        if a == b:
            continue
        link_order = np.argsort(inst.ep_lat[a:b], kind="stable")
        for idx in link_order:
            c = int(inst.ep_cache[a + idx])
            lat = int(inst.ep_lat[a + idx])
            contains = has[c, vids]
            if not contains.any():
                continue
            first = contains & (found[rows] == 0)
            second = contains & (found[rows] == 1)
            if first.any():
                target = rows[first]
                best1[target] = lat
                arg1[target] = c
            if second.any():
                best2[rows[second]] = lat
            found[rows] += contains.astype(np.int8)

    return best1, best2, arg1


def compute(inst: Instance, has: np.ndarray) -> dict:
    best1, best2, arg1 = _books(inst, has)

    total = inst.total_requests
    numerator = int((inst.req_count * (inst.dc[inst.req_endpoint] - best1)).sum())
    score = numerator * 1000 // total

    # Cout de retrait, accumule par placement (cache, video).
    loss = inst.req_count * (best2 - best1)
    served = arg1 >= 0
    cost = np.zeros(inst.C * inst.V, dtype=np.int64)
    if served.any():
        np.add.at(cost, arg1[served] * inst.V + inst.req_video[served], loss[served])

    placements = np.argwhere(has)
    n_placements = int(placements.shape[0])
    if n_placements:
        flat = placements[:, 0] * inst.V + placements[:, 1]
        placement_cost = cost[flat]
        free_removals = int((placement_cost == 0).sum())
    else:
        placement_cost = np.zeros(0, dtype=np.int64)
        free_removals = 0

    used = (has * inst.sizes[None, :]).sum(axis=1)
    free = inst.X - used

    volume_per_endpoint = np.zeros(inst.E, dtype=np.int64)
    np.add.at(volume_per_endpoint, inst.req_endpoint, inst.req_count)

    potential = np.zeros(inst.C, dtype=np.int64)
    rows = np.repeat(np.arange(inst.E), inst.degrees)
    contrib = volume_per_endpoint[rows] * (inst.dc[rows] - inst.ep_lat)
    np.add.at(potential, inst.ep_cache, contrib)

    realized = np.zeros(inst.C, dtype=np.int64)
    if served.any():
        np.add.at(realized, arg1[served],
                  (inst.req_count * (inst.dc[inst.req_endpoint] - best1))[served])

    with np.errstate(divide="ignore", invalid="ignore"):
        efficiency = np.where(potential > 0, realized / potential, 0.0)

    replication = has.sum(axis=0)
    cached = replication > 0

    ranked = np.sort(realized)[::-1]
    top10_share = float(ranked[:10].sum() / numerator) if numerator > 0 else 0.0

    return {
        "score": int(score),
        "placements": n_placements,
        "retraits_gratuits": free_removals,
        "part_retraits_gratuits": round(free_removals / n_placements, 4) if n_placements else 0.0,
        "cout_retrait_median": int(np.median(placement_cost)) if n_placements else 0,
        "cout_retrait_p90": int(np.percentile(placement_cost, 90)) if n_placements else 0,
        "capacite_utilisee_Mo": int(used.sum()),
        "capacite_libre_Mo": int(free.sum()),
        "part_capacite_gaspillee": round(float(free.sum() / (inst.C * inst.X)), 4),
        "caches_pleins": int((free == 0).sum()),
        "caches_vides": int((used == 0).sum()),
        "efficacite_cache_moyenne": round(float(efficiency.mean()), 4),
        "efficacite_cache_min": round(float(efficiency.min()), 4) if inst.C else 0.0,
        "caches_sous_exploites": int(((efficiency < 0.5) & (potential > 0)).sum()),
        "part_score_top10_caches": round(top10_share, 4),
        "videos_placees": int(cached.sum()),
        "replication_moyenne": round(float(replication[cached].mean()), 3) if cached.any() else 0.0,
        "replication_max": int(replication.max()) if inst.V else 0,
    }


if __name__ == "__main__":
    cli("solution", compute)
