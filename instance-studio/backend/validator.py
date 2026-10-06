"""Validator for Hash Code 2017 "Streaming videos" instance files.

Checks every bound stated in the problem statement, including the per-request
count bound (0 < Rn <= 10000) that the original instanceValidator.py omits.
"""

from __future__ import annotations

from generator import (
    MAX_VIDEOS, MAX_ENDPOINTS, MAX_CACHES, MAX_REQUESTS, MAX_CACHE_CAPACITY,
    MAX_VIDEO_SIZE, MIN_DC_LATENCY, MAX_DC_LATENCY, MAX_CACHE_LATENCY,
    MAX_REQUEST_COUNT,
)

MAX_ERRORS = 50


class _Cursor:
    __slots__ = ("tokens", "i")

    def __init__(self, tokens):
        self.tokens = tokens
        self.i = 0

    def next_int(self, what: str) -> int:
        if self.i >= len(self.tokens):
            raise _Truncated(f"fichier tronqué: {what} manquant")
        tok = self.tokens[self.i]
        self.i += 1
        try:
            return int(tok)
        except ValueError:
            raise _Truncated(f"{what}: entier attendu, trouvé '{tok[:32]}'")

    def remaining(self) -> int:
        return len(self.tokens) - self.i


class _Truncated(Exception):
    pass


def validate_text(text: str) -> dict:
    errors: list[str] = []

    def err(msg: str) -> bool:
        if len(errors) < MAX_ERRORS:
            errors.append(msg)
        return len(errors) >= MAX_ERRORS

    cur = _Cursor(text.split())
    summary: dict = {}

    try:
        V = cur.next_int("V")
        E = cur.next_int("E")
        R = cur.next_int("R")
        C = cur.next_int("C")
        X = cur.next_int("X")

        if not 1 <= V <= MAX_VIDEOS:
            err(f"V = {V} hors bornes [1, {MAX_VIDEOS}]")
        if not 1 <= E <= MAX_ENDPOINTS:
            err(f"E = {E} hors bornes [1, {MAX_ENDPOINTS}]")
        if not 1 <= R <= MAX_REQUESTS:
            err(f"R = {R} hors bornes [1, {MAX_REQUESTS}]")
        if not 1 <= C <= MAX_CACHES:
            err(f"C = {C} hors bornes [1, {MAX_CACHES}]")
        if not 1 <= X <= MAX_CACHE_CAPACITY:
            err(f"X = {X} hors bornes [1, {MAX_CACHE_CAPACITY}]")

        summary.update({"V": V, "E": E, "R": R, "C": C, "X": X})

        if errors:
            return {"valid": False, "errors": errors, "summary": summary}

        total_size = 0
        for v in range(V):
            s = cur.next_int(f"taille de la vidéo {v}")
            total_size += s
            if not 1 <= s <= MAX_VIDEO_SIZE:
                if err(f"vidéo {v}: taille {s} hors bornes [1, {MAX_VIDEO_SIZE}]"):
                    break

        degrees = []
        for e in range(E):
            ld = cur.next_int(f"latence datacenter de l'endpoint {e}")
            k = cur.next_int(f"nombre de caches de l'endpoint {e}")

            if not MIN_DC_LATENCY <= ld <= MAX_DC_LATENCY:
                err(f"endpoint {e}: latence datacenter {ld} hors bornes "
                    f"[{MIN_DC_LATENCY}, {MAX_DC_LATENCY}]")
            if not 0 <= k <= C:
                err(f"endpoint {e}: K = {k} hors bornes [0, {C}]")
                raise _Truncated(
                    f"endpoint {e}: K invalide, impossible de continuer la lecture")

            degrees.append(k)
            seen = set()
            for _ in range(k):
                cid = cur.next_int(f"id de cache de l'endpoint {e}")
                lat = cur.next_int(f"latence de cache de l'endpoint {e}")
                if not 0 <= cid < C:
                    err(f"endpoint {e}: id de cache {cid} hors bornes [0, {C - 1}]")
                elif cid in seen:
                    err(f"endpoint {e}: cache {cid} déclaré deux fois")
                seen.add(cid)
                if not 1 <= lat <= MAX_CACHE_LATENCY:
                    err(f"endpoint {e}, cache {cid}: latence {lat} hors bornes "
                        f"[1, {MAX_CACHE_LATENCY}]")
                if lat >= ld:
                    err(f"endpoint {e}, cache {cid}: latence {lat} >= latence "
                        f"datacenter {ld}")
            if len(errors) >= MAX_ERRORS:
                break

        pairs = set()
        duplicate_lines = 0
        total_requests = 0
        for r in range(R):
            v = cur.next_int(f"vidéo de la requête {r}")
            e = cur.next_int(f"endpoint de la requête {r}")
            n = cur.next_int(f"compte de la requête {r}")

            if not 0 <= v < V:
                err(f"requête {r}: id vidéo {v} hors bornes [0, {V - 1}]")
            if not 0 <= e < E:
                err(f"requête {r}: id endpoint {e} hors bornes [0, {E - 1}]")
            if not 1 <= n <= MAX_REQUEST_COUNT:
                err(f"requête {r}: compte {n} hors bornes [1, {MAX_REQUEST_COUNT}]")

            total_requests += n
            key = v * E + e
            if key in pairs:
                duplicate_lines += 1
            else:
                pairs.add(key)

            if len(errors) >= MAX_ERRORS:
                break

        leftover = cur.remaining()
        if leftover > 0:
            err(f"{leftover} valeur(s) en trop après la dernière requête")

        summary.update({
            "total_requests": total_requests,
            "distinct_pairs": len(pairs),
            "duplicate_lines": duplicate_lines,
            "video_size_total": total_size,
            "degree_mean": round(sum(degrees) / len(degrees), 3) if degrees else 0,
            "degree_min": min(degrees) if degrees else 0,
            "degree_max": max(degrees) if degrees else 0,
            "endpoints_without_cache": sum(1 for d in degrees if d == 0),
        })

    except _Truncated as exc:
        err(str(exc))

    return {"valid": not errors, "errors": errors, "summary": summary}
