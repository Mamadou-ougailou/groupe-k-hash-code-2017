"""Borne superieure serree, tenant compte de la capacite.

Le plafond de tools/stats/ceiling.py (et Scorer.upperBound en Java) suppose que
chaque video est presente dans chaque cache : il ignore completement la
capacite. Sur une instance ou la capacite est le facteur limitant, il est
tellement lache qu'il fait croire a une marge qui n'existe pas.

Borne par relaxation fractionnaire, par cache :

  pour un cache c, chaque video v a une valeur = somme sur les endpoints relies
  a c des requetes pour v multipliees par (L_D - L_c), et un poids = sa taille.
  Remplir c au mieux, en autorisant les fractions, majore ce que c peut
  rapporter. La somme sur les caches majore le total, car l'economie reelle
  d'une requete est un minimum sur ses sources, donc au plus la somme de ses
  economies possibles cache par cache.

La borne retenue est le minimum des deux : relaxation fractionnaire et plafond
sans capacite.

LIMITE, mesuree : sur les quatre instances officielles cette relaxation ne
resserre RIEN. En sommant cache par cache, elle compte l'economie d'une requete
une fois par cache connecte a son endpoint, donc elle surestime d'un facteur de
l'ordre du degre moyen (4 a 12 ici). Le minimum retombe toujours sur le plafond
sans capacite. Le champ facteur_surcomptage rend ce diagnostic explicite.

Ce resultat negatif est informatif : la capacite agregee n'est pas le facteur
limitant de ces instances, c'est la structure de connexion. Une borne vraiment
serree demanderait une relaxation lineaire du probleme complet, avec des
variables d'affectation, ce que ce module ne fait pas.

    python -m tools.stats.bound instances/kittens.in
"""

from __future__ import annotations

import numpy as np

from . import ceiling
from .common import Instance, cli


def compute(inst: Instance) -> dict:
    order = np.argsort(inst.req_endpoint, kind="stable")
    sorted_ep = inst.req_endpoint[order]
    bounds = np.searchsorted(sorted_ep, np.arange(inst.E + 1))

    # Endpoints relies a chaque cache, avec la latence correspondante.
    per_cache: list[list[tuple[int, int]]] = [[] for _ in range(inst.C)]
    for e in range(inst.E):
        for i in range(inst.ep_start[e], inst.ep_start[e + 1]):
            per_cache[int(inst.ep_cache[i])].append((e, int(inst.ep_lat[i])))

    sizes = inst.sizes.astype(np.float64)
    total_fractional = 0.0

    for c in range(inst.C):
        links = per_cache[c]
        if not links:
            continue
        value = np.zeros(inst.V, dtype=np.float64)
        for e, lat in links:
            gain = int(inst.dc[e]) - lat
            if gain <= 0:
                continue
            lo, hi = bounds[e], bounds[e + 1]
            if lo == hi:
                continue
            rows = order[lo:hi]
            np.add.at(value, inst.req_video[rows], inst.req_count[rows] * gain)

        useful = value > 0
        if not useful.any():
            continue
        vals = value[useful]
        wts = sizes[useful]
        ratio = vals / wts
        rank = np.argsort(-ratio, kind="stable")
        vals, wts = vals[rank], wts[rank]

        cumulative = np.cumsum(wts)
        fits = cumulative <= inst.X
        taken = float(vals[fits].sum())
        n_full = int(fits.sum())
        if n_full < vals.size:          # part fractionnaire du premier objet qui deborde
            used = float(cumulative[n_full - 1]) if n_full else 0.0
            remaining = inst.X - used
            if remaining > 0:
                taken += float(vals[n_full]) * remaining / float(wts[n_full])
        total_fractional += taken

    total = inst.total_requests
    loose = ceiling.compute(inst)["plafond_score"]
    fractional = int(total_fractional * 1000 // total)
    supercache = supercache_bound(inst)
    tight = min(loose, fractional, supercache)

    return {
        "borne_serree": tight,
        "borne_lache": loose,
        "borne_fractionnaire": fractional,
        "borne_cache_unique": supercache,
        "resserrement": round(1 - tight / loose, 4) if loose else 0.0,
        "capacite_limitante": bool(tight < loose),
        "facteur_surcomptage": round(fractional / loose, 2) if loose else 0.0,
    }


def supercache_bound(inst: Instance) -> int:
    """Relaxation "cache unique" : tous les caches fusionnes en un seul de
    capacite C*X, chaque endpoint servi a sa meilleure latence connectee.

    C'est strictement plus permissif que le probleme reel — une video posee une
    seule fois y sert tous les endpoints au mieux, la ou il faudrait la
    dupliquer — donc l'optimum de cette relaxation majore l'optimum reel.

    Contrairement a la somme par cache, elle ne surcompte pas, et contrairement
    au plafond de ceiling.py elle tient compte de la capacite totale. C'est la
    seule des trois qui reste calculable sur les instances tres connectees.
    """
    huge = np.iinfo(np.int64).max
    best = np.full(inst.E, huge, dtype=np.int64)
    if inst.ep_lat.size:
        rows = np.repeat(np.arange(inst.E), inst.degrees)
        np.minimum.at(best, rows, inst.ep_lat)
    saving = inst.dc - np.minimum(inst.dc, best)

    value = np.zeros(inst.V, dtype=np.float64)
    np.add.at(value, inst.req_video, inst.req_count * saving[inst.req_endpoint])

    useful = value > 0
    if not useful.any():
        return 0
    vals = value[useful]
    wts = inst.sizes[useful].astype(np.float64)
    rank = np.argsort(-(vals / wts), kind="stable")
    vals, wts = vals[rank], wts[rank]

    capacity = float(inst.C) * float(inst.X)
    cumulative = np.cumsum(wts)
    fits = cumulative <= capacity
    taken = float(vals[fits].sum())
    n_full = int(fits.sum())
    if n_full < vals.size:
        used = float(cumulative[n_full - 1]) if n_full else 0.0
        remaining = capacity - used
        if remaining > 0:
            taken += float(vals[n_full]) * remaining / float(wts[n_full])

    return int(taken * 1000 // inst.total_requests)


if __name__ == "__main__":
    cli("bound", compute)
