"""Vectorised generator for the Hash Code 2017 "Streaming videos" problem.

Every instance it produces satisfies the official bounds by construction.
All randomness comes from independent streams derived from one seed, so changing
one family of parameters leaves the others bit-for-bit identical.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, asdict

import numpy as np

MAX_VIDEOS = 10_000
MAX_ENDPOINTS = 1_000
MAX_CACHES = 1_000
MAX_REQUESTS = 1_000_000
MAX_CACHE_CAPACITY = 500_000
MAX_VIDEO_SIZE = 1_000
MIN_DC_LATENCY = 2
MAX_DC_LATENCY = 4_000
MAX_CACHE_LATENCY = 500
MAX_REQUEST_COUNT = 10_000

SIZE_DISTS = ("uniform", "lognormal", "bimodal")
TOPOLOGIES = ("random", "clustered")
DEGREE_DISTS = ("constant", "uniform", "poisson")
WEIGHT_DISTS = ("uniform", "zipf")
SIZE_POPULARITY = ("none", "positive", "negative")


class ParamError(ValueError):
    pass


@dataclass
class Params:
    V: int = 5_000
    E: int = 200
    C: int = 50
    X: int = 10_000
    R: int = 50_000
    seed: int = 42

    size_dist: str = "uniform"
    size_min: int = 1
    size_max: int = 300
    size_log_mu: float = 4.0
    size_log_sigma: float = 0.8
    size_small_frac: float = 0.8
    size_large_min: int = 400
    size_large_max: int = 1_000

    topology: str = "clustered"
    topo_jitter: float = 0.02
    degree_dist: str = "uniform"
    degree: int = 4
    degree_min: int = 2
    degree_max: int = 8
    degree_lambda: float = 4.0

    dc_min: int = 200
    dc_max: int = 2_000
    latency_spread: float = 0.6
    latency_noise: float = 0.05

    popularity: str = "zipf"
    zipf_alpha: float = 0.8
    demand: str = "uniform"
    demand_alpha: float = 0.5
    size_popularity: str = "none"
    count_min: int = 1
    count_max: int = 2_000
    duplicate_rate: float = 0.2


def _check_int(name, value, lo, hi):
    try:
        value = int(value)
    except (TypeError, ValueError):
        raise ParamError(f"{name}: entier attendu")
    if not lo <= value <= hi:
        raise ParamError(f"{name}: doit être entre {lo} et {hi} (reçu {value})")
    return value


def _check_float(name, value, lo, hi):
    try:
        value = float(value)
    except (TypeError, ValueError):
        raise ParamError(f"{name}: nombre attendu")
    if not lo <= value <= hi:
        raise ParamError(f"{name}: doit être entre {lo} et {hi} (reçu {value})")
    return value


def _check_choice(name, value, allowed):
    if value not in allowed:
        raise ParamError(f"{name}: valeur inconnue '{value}' (attendu: {', '.join(allowed)})")
    return value


def parse_params(raw: dict) -> Params:
    """Build Params from untrusted input. Raises ParamError on anything invalid."""
    d = {}
    defaults = Params()

    d["V"] = _check_int("V", raw.get("V", defaults.V), 1, MAX_VIDEOS)
    d["E"] = _check_int("E", raw.get("E", defaults.E), 1, MAX_ENDPOINTS)
    d["C"] = _check_int("C", raw.get("C", defaults.C), 1, MAX_CACHES)
    d["X"] = _check_int("X", raw.get("X", defaults.X), 1, MAX_CACHE_CAPACITY)
    d["R"] = _check_int("R", raw.get("R", defaults.R), 1, MAX_REQUESTS)
    d["seed"] = _check_int("seed", raw.get("seed", defaults.seed), 0, 2**31 - 1)

    d["size_dist"] = _check_choice("size_dist", raw.get("size_dist", defaults.size_dist), SIZE_DISTS)
    d["size_min"] = _check_int("size_min", raw.get("size_min", defaults.size_min), 1, MAX_VIDEO_SIZE)
    d["size_max"] = _check_int("size_max", raw.get("size_max", defaults.size_max), 1, MAX_VIDEO_SIZE)
    d["size_log_mu"] = _check_float("size_log_mu", raw.get("size_log_mu", defaults.size_log_mu), 0.0, 8.0)
    d["size_log_sigma"] = _check_float("size_log_sigma", raw.get("size_log_sigma", defaults.size_log_sigma), 0.01, 3.0)
    d["size_small_frac"] = _check_float("size_small_frac", raw.get("size_small_frac", defaults.size_small_frac), 0.0, 1.0)
    d["size_large_min"] = _check_int("size_large_min", raw.get("size_large_min", defaults.size_large_min), 1, MAX_VIDEO_SIZE)
    d["size_large_max"] = _check_int("size_large_max", raw.get("size_large_max", defaults.size_large_max), 1, MAX_VIDEO_SIZE)
    if d["size_min"] > d["size_max"]:
        raise ParamError("size_min doit être ≤ size_max")
    if d["size_large_min"] > d["size_large_max"]:
        raise ParamError("size_large_min doit être ≤ size_large_max")

    d["topology"] = _check_choice("topology", raw.get("topology", defaults.topology), TOPOLOGIES)
    d["topo_jitter"] = _check_float("topo_jitter", raw.get("topo_jitter", defaults.topo_jitter), 0.0, 0.5)
    d["degree_dist"] = _check_choice("degree_dist", raw.get("degree_dist", defaults.degree_dist), DEGREE_DISTS)
    d["degree"] = _check_int("degree", raw.get("degree", defaults.degree), 0, MAX_CACHES)
    d["degree_min"] = _check_int("degree_min", raw.get("degree_min", defaults.degree_min), 0, MAX_CACHES)
    d["degree_max"] = _check_int("degree_max", raw.get("degree_max", defaults.degree_max), 0, MAX_CACHES)
    d["degree_lambda"] = _check_float("degree_lambda", raw.get("degree_lambda", defaults.degree_lambda), 0.0, 1000.0)
    if d["degree_min"] > d["degree_max"]:
        raise ParamError("degree_min doit être ≤ degree_max")

    d["dc_min"] = _check_int("dc_min", raw.get("dc_min", defaults.dc_min), MIN_DC_LATENCY, MAX_DC_LATENCY)
    d["dc_max"] = _check_int("dc_max", raw.get("dc_max", defaults.dc_max), MIN_DC_LATENCY, MAX_DC_LATENCY)
    if d["dc_min"] > d["dc_max"]:
        raise ParamError("dc_min doit être ≤ dc_max")
    d["latency_spread"] = _check_float("latency_spread", raw.get("latency_spread", defaults.latency_spread), 0.0, 1.0)
    d["latency_noise"] = _check_float("latency_noise", raw.get("latency_noise", defaults.latency_noise), 0.0, 1.0)

    d["popularity"] = _check_choice("popularity", raw.get("popularity", defaults.popularity), WEIGHT_DISTS)
    d["zipf_alpha"] = _check_float("zipf_alpha", raw.get("zipf_alpha", defaults.zipf_alpha), 0.0, 3.0)
    d["demand"] = _check_choice("demand", raw.get("demand", defaults.demand), WEIGHT_DISTS)
    d["demand_alpha"] = _check_float("demand_alpha", raw.get("demand_alpha", defaults.demand_alpha), 0.0, 3.0)
    d["size_popularity"] = _check_choice("size_popularity", raw.get("size_popularity", defaults.size_popularity), SIZE_POPULARITY)
    d["count_min"] = _check_int("count_min", raw.get("count_min", defaults.count_min), 1, MAX_REQUEST_COUNT)
    d["count_max"] = _check_int("count_max", raw.get("count_max", defaults.count_max), 1, MAX_REQUEST_COUNT)
    if d["count_min"] > d["count_max"]:
        raise ParamError("count_min doit être ≤ count_max")
    d["duplicate_rate"] = _check_float("duplicate_rate", raw.get("duplicate_rate", defaults.duplicate_rate), 0.0, 0.95)

    p = Params(**d)

    n_unique = p.R - int(round(p.duplicate_rate * p.R))
    if n_unique > p.V * p.E:
        raise ParamError(
            f"Impossible: {n_unique} couples (vidéo, endpoint) distincts demandés "
            f"mais seulement V×E = {p.V * p.E} existent. Augmente V ou E, "
            f"baisse R, ou monte le taux de duplication."
        )
    return p


def _video_sizes(rng, p: Params) -> np.ndarray:
    if p.size_dist == "uniform":
        s = rng.integers(p.size_min, p.size_max + 1, p.V)
    elif p.size_dist == "lognormal":
        s = np.rint(rng.lognormal(p.size_log_mu, p.size_log_sigma, p.V))
    else:
        is_small = rng.random(p.V) < p.size_small_frac
        small = rng.integers(p.size_min, p.size_max + 1, p.V)
        large = rng.integers(p.size_large_min, p.size_large_max + 1, p.V)
        s = np.where(is_small, small, large)
    return np.clip(s, 1, MAX_VIDEO_SIZE).astype(np.int64)


def _degrees(rng, p: Params) -> np.ndarray:
    if p.degree_dist == "constant":
        k = np.full(p.E, p.degree, dtype=np.int64)
    elif p.degree_dist == "uniform":
        k = rng.integers(p.degree_min, p.degree_max + 1, p.E)
    else:
        k = rng.poisson(p.degree_lambda, p.E)
    return np.clip(k, 0, p.C).astype(np.int64)


def _connections(rng, p: Params, degrees: np.ndarray):
    """Pick each endpoint's caches and return them flattened, with a per-endpoint
    normalised closeness in [0, 1] (0 = nearest selected cache, 1 = farthest)."""
    if p.topology == "clustered":
        pos_e = rng.random(p.E)
        pos_c = rng.random(p.C)
        d = np.abs(pos_e[:, None] - pos_c[None, :])
        d = np.minimum(d, 1.0 - d)
        if p.topo_jitter > 0:
            d = np.abs(d + rng.normal(0.0, p.topo_jitter, d.shape))
    else:
        d = rng.random((p.E, p.C))

    order = np.argsort(d, axis=1, kind="stable")
    d_sorted = np.take_along_axis(d, order, axis=1)

    keep = np.arange(p.C)[None, :] < degrees[:, None]
    cache_ids = order[keep].astype(np.int64)
    dist = d_sorted[keep]

    offsets = np.concatenate([[0], np.cumsum(degrees)])
    if dist.size == 0:
        return cache_ids, dist, offsets

    # Distances are sorted within each endpoint, so the group max is its last element.
    group_max = dist[offsets[1:] - 1]
    denom = np.repeat(np.maximum(group_max, 1e-12), degrees)
    closeness = np.clip(dist / denom, 0.0, 1.0)
    return cache_ids, closeness, offsets


def _latencies(rng, p: Params, degrees, closeness):
    dc = rng.integers(p.dc_min, p.dc_max + 1, p.E)
    dc = np.clip(dc, MIN_DC_LATENCY, MAX_DC_LATENCY).astype(np.int64)

    ceiling = np.minimum(MAX_CACHE_LATENCY, dc - 1)
    ceiling_rep = np.repeat(ceiling, degrees).astype(np.float64)

    span = p.latency_spread * (ceiling_rep - 1.0)
    lat = 1.0 + closeness * span
    if p.latency_noise > 0:
        lat = lat + rng.normal(0.0, p.latency_noise * np.maximum(span, 1.0), lat.shape)

    lat = np.rint(lat).astype(np.int64)
    lat = np.clip(lat, 1, np.maximum(ceiling_rep.astype(np.int64), 1))
    return dc, lat


def _weights(rng, n: int, kind: str, alpha: float) -> np.ndarray:
    if kind == "zipf":
        base = 1.0 / np.arange(1, n + 1, dtype=np.float64) ** alpha
    else:
        base = np.ones(n, dtype=np.float64)
    return base / base.sum()


def _sample_from(rng, cdf: np.ndarray, m: int) -> np.ndarray:
    idx = np.searchsorted(cdf, rng.random(m), side="right")
    return np.clip(idx, 0, cdf.size - 1)


def _requests(rng, p: Params, sizes: np.ndarray):
    video_w = _weights(rng, p.V, p.popularity, p.zipf_alpha)
    if p.size_popularity == "positive":
        target = np.argsort(-sizes, kind="stable")
    elif p.size_popularity == "negative":
        target = np.argsort(sizes, kind="stable")
    else:
        target = rng.permutation(p.V)
    vw = np.empty(p.V, dtype=np.float64)
    vw[target] = video_w

    base_e = _weights(rng, p.E, p.demand, p.demand_alpha)
    ew = np.empty(p.E, dtype=np.float64)
    ew[rng.permutation(p.E)] = base_e

    v_cdf = np.cumsum(vw)
    e_cdf = np.cumsum(ew)

    n_dup = int(round(p.duplicate_rate * p.R))
    n_unique = p.R - n_dup

    collected = np.empty(0, dtype=np.int64)
    for _ in range(40):
        if collected.size >= n_unique:
            break
        missing = n_unique - collected.size
        m = int(missing * 1.6) + 4096
        v = _sample_from(rng, v_cdf, m).astype(np.int64)
        e = _sample_from(rng, e_cdf, m).astype(np.int64)
        collected = np.unique(np.concatenate([collected, v * p.E + e]))

    if collected.size < n_unique:
        raise ParamError(
            "Impossible de tirer assez de couples (vidéo, endpoint) distincts avec "
            "ces poids. Réduis R, baisse l'exposant Zipf, ou augmente le taux de duplication."
        )

    # np.unique sorts; shuffle before truncating or we would keep only the low ids.
    rng.shuffle(collected)
    pairs = collected[:n_unique]

    if n_dup > 0:
        pairs = np.concatenate([pairs, rng.choice(pairs, size=n_dup, replace=True)])
    rng.shuffle(pairs)

    videos = (pairs // p.E).astype(np.int64)
    endpoints = (pairs % p.E).astype(np.int64)
    counts = rng.integers(p.count_min, p.count_max + 1, pairs.size).astype(np.int64)
    counts = np.clip(counts, 1, MAX_REQUEST_COUNT)
    return videos, endpoints, counts


def _overlap_stats(p: Params, cache_ids, degrees, rng) -> dict:
    """Jaccard overlap between caches over their endpoint sets.

    The mean alone does not separate a random topology from a geographic one:
    random spreads small overlaps over every pair, geographic concentrates large
    overlaps on neighbours and leaves most pairs disjoint. The spread matters,
    so report the tail too.

    Estimated on a sample of caches when C is large, to keep the matmul cheap.
    """
    empty = {"mean": 0.0, "max": 0.0, "p95": 0.0, "disjoint_share": 0.0}
    if p.C < 2 or cache_ids.size == 0:
        return empty

    member = np.zeros((p.E, p.C), dtype=np.float32)
    rows = np.repeat(np.arange(p.E), degrees)
    member[rows, cache_ids] = 1.0

    sample = np.arange(p.C)
    if p.C > 300:
        sample = rng.choice(p.C, size=300, replace=False)
    sub = member[:, sample]

    inter = sub.T @ sub
    deg_c = sub.sum(axis=0)
    union = deg_c[:, None] + deg_c[None, :] - inter
    with np.errstate(divide="ignore", invalid="ignore"):
        jac = np.where(union > 0, inter / union, 0.0)

    iu = np.triu_indices(sample.size, k=1)
    if iu[0].size == 0:
        return empty
    pairs = jac[iu]
    return {
        "mean": float(pairs.mean()),
        "max": float(pairs.max()),
        "p95": float(np.percentile(pairs, 95)),
        "disjoint_share": float((pairs == 0).mean()),
    }


def _stats(p, sizes, degrees, cache_ids, lat, dc, videos, endpoints, counts, rng):
    overlap = _overlap_stats(p, cache_ids, degrees, rng)
    best = np.full(p.E, np.inf)
    if cache_ids.size:
        np.minimum.at(best, np.repeat(np.arange(p.E), degrees), lat.astype(np.float64))

    reachable = np.minimum(dc.astype(np.float64), best)
    saving_per_endpoint = dc.astype(np.float64) - reachable
    total_requests = int(counts.sum())
    ceiling = float((counts * saving_per_endpoint[endpoints]).sum() * 1000.0 / total_requests)

    pair_ids = videos * p.E + endpoints
    distinct_pairs = int(np.unique(pair_ids).size)

    requested = np.unique(videos)
    useful_volume = int(sizes[requested].sum())

    return {
        "total_requests": total_requests,
        "distinct_pairs": distinct_pairs,
        "duplicate_lines": int(counts.size - distinct_pairs),
        "duplicate_rate_actual": round((counts.size - distinct_pairs) / counts.size, 4),
        "score_ceiling": round(ceiling, 1),
        "videos_per_cache": round(p.X / float(sizes.mean()), 2),
        "capacity_pressure": round(p.C * p.X / max(useful_volume, 1), 4),
        "video_size_mean": round(float(sizes.mean()), 2),
        "video_size_cv": round(float(sizes.std() / max(sizes.mean(), 1e-9)), 4),
        "degree_mean": round(float(degrees.mean()), 3),
        "degree_std": round(float(degrees.std()), 3),
        "degree_min": int(degrees.min()),
        "degree_max": int(degrees.max()),
        "endpoints_without_cache": int((degrees == 0).sum()),
        "cache_overlap_mean": round(overlap["mean"], 4),
        "cache_overlap_max": round(overlap["max"], 4),
        "cache_overlap_p95": round(overlap["p95"], 4),
        "cache_pairs_disjoint": round(overlap["disjoint_share"], 4),
        "dc_latency_mean": round(float(dc.mean()), 1),
        "cache_latency_mean": round(float(lat.mean()), 1) if lat.size else 0.0,
    }


def _write(path, p, sizes, degrees, offsets, cache_ids, lat, dc, videos, endpoints, counts):
    with open(path, "w", buffering=1 << 20) as f:
        f.write(f"{p.V} {p.E} {counts.size} {p.C} {p.X}\n")
        f.write(" ".join(map(str, sizes.tolist())))
        f.write("\n")

        deg_l = degrees.tolist()
        dc_l = dc.tolist()
        cid_l = cache_ids.tolist()
        lat_l = lat.tolist()
        block = []
        for e in range(p.E):
            block.append(f"{dc_l[e]} {deg_l[e]}")
            for i in range(offsets[e], offsets[e + 1]):
                block.append(f"{cid_l[i]} {lat_l[i]}")
        f.write("\n".join(block))
        f.write("\n")

        v_l = videos.tolist()
        e_l = endpoints.tolist()
        c_l = counts.tolist()
        step = 200_000
        for i in range(0, len(v_l), step):
            f.write("\n".join(
                f"{a} {b} {c}" for a, b, c in
                zip(v_l[i:i + step], e_l[i:i + step], c_l[i:i + step])
            ))
            f.write("\n")


def generate(p: Params, out_path: str) -> dict:
    t0 = time.perf_counter()
    s_size, s_deg, s_topo, s_lat, s_req, s_stat = np.random.SeedSequence(p.seed).spawn(6)

    sizes = _video_sizes(np.random.default_rng(s_size), p)
    degrees = _degrees(np.random.default_rng(s_deg), p)
    cache_ids, closeness, offsets = _connections(np.random.default_rng(s_topo), p, degrees)
    dc, lat = _latencies(np.random.default_rng(s_lat), p, degrees, closeness)
    videos, endpoints, counts = _requests(np.random.default_rng(s_req), p, sizes)

    _write(out_path, p, sizes, degrees, offsets, cache_ids, lat, dc, videos, endpoints, counts)

    stats = _stats(p, sizes, degrees, cache_ids, lat, dc,
                   videos, endpoints, counts, np.random.default_rng(s_stat))
    stats["generation_seconds"] = round(time.perf_counter() - t0, 3)
    return stats


def params_to_dict(p: Params) -> dict:
    return asdict(p)
