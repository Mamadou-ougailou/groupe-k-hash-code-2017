"""Recouvrement des caches : indice de Jaccard entre caches sur leurs ensembles
d'endpoints.

La moyenne seule ne distingue pas une topologie aleatoire d'une topologie
geographique : l'aleatoire etale de petits recouvrements sur toutes les paires,
la geographique concentre de gros recouvrements sur les voisins et laisse la
plupart des paires disjointes. C'est la queue de distribution qui porte
l'information, donc on la remonte aussi.

Deux caches qui se recouvrent fortement se disputent les memes endpoints : y
placer la meme video est redondant, et c'est exactement ce qui fabrique les
plateaux ou un local search s'enlise.

    python -m tools.stats.overlap instances/kittens.in
"""

from __future__ import annotations

import numpy as np

from .common import Instance, cli

SAMPLE_CAP = 300


def compute(inst: Instance, sample_cap: int = SAMPLE_CAP) -> dict:
    empty = {
        "recouvrement_moyen": 0.0, "recouvrement_p95": 0.0, "recouvrement_max": 0.0,
        "part_paires_disjointes": 0.0, "part_paires_sup_0_2": 0.0,
        "caches_echantillonnes": 0, "echantillonne": False,
    }
    if inst.C < 2 or inst.ep_cache.size == 0:
        return empty

    member = np.zeros((inst.E, inst.C), dtype=np.float32)
    rows = np.repeat(np.arange(inst.E), inst.degrees)
    member[rows, inst.ep_cache] = 1.0

    sampled = inst.C > sample_cap
    if sampled:
        rng = np.random.default_rng(0)
        keep = np.sort(rng.choice(inst.C, size=sample_cap, replace=False))
        member = member[:, keep]

    inter = member.T @ member
    degree = member.sum(axis=0)
    union = degree[:, None] + degree[None, :] - inter
    with np.errstate(divide="ignore", invalid="ignore"):
        jaccard = np.where(union > 0, inter / union, 0.0)

    iu = np.triu_indices(member.shape[1], k=1)
    if iu[0].size == 0:
        return empty
    pairs = jaccard[iu]

    return {
        "recouvrement_moyen": round(float(pairs.mean()), 4),
        "recouvrement_p95": round(float(np.percentile(pairs, 95)), 4),
        "recouvrement_max": round(float(pairs.max()), 4),
        "part_paires_disjointes": round(float((pairs == 0).mean()), 4),
        "part_paires_sup_0_2": round(float((pairs > 0.2).mean()), 4),
        "caches_echantillonnes": int(member.shape[1]),
        "echantillonne": bool(sampled),
    }


if __name__ == "__main__":
    cli("overlap", compute)
