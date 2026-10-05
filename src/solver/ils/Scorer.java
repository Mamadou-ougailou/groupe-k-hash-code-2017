package solver.ils;

/**
 * Scorer INDEPENDANT : recalcule le score integralement, sans jamais faire
 * confiance a un compteur incremental.
 *
 * C'est l'arbitre. Aux niveaux 2, 3 et 4 le score sera maintenu en delta
 * (+g, -g, des millions de fois) ; une seule erreur de signe et le programme
 * annonce un score qu'il ne produit pas. La regle : apres chaque phase,
 * score_incremental == Scorer.score(...), sinon il y a un bug.
 */
public final class Scorer {

    /**
     * Score officiel = floor(1000 * somme(Rn * (L_D - L)) / total_requetes).
     *
     * Pour chaque requete on prend la meilleure source disponible parmi :
     *   - le data center (toujours disponible, latence L_D)
     *   - les caches connectes a l'endpoint QUI CONTIENNENT la video
     *
     * Un endpoint orphelin (0 cache) garde donc L = L_D et rapporte 0,
     * tout en comptant au denominateur : c'est correct et voulu.
     */
    public static long score(Instance in, Solution s) {
        long num = 0;
        for (int r = 0; r < in.R; r++) {
            int e = in.reqEndpoint[r];
            int v = in.reqVideo[r];
            int best = in.dcLatency[e];
            for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++) {
                int l = in.epLat[i];
                if (l < best && s.has(in.epCache[i], v)) best = l;
            }
            num += (long) in.reqCount[r] * (in.dcLatency[e] - best);
        }
        return num * 1000L / in.totalRequests;
    }

    /** Borne superieure (lache) : chaque requete servie par son meilleur cache connecte. */
    public static long upperBound(Instance in) {
        long num = 0;
        for (int r = 0; r < in.R; r++) {
            int e = in.reqEndpoint[r];
            int best = in.dcLatency[e];
            for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++)
                if (in.epLat[i] < best) best = in.epLat[i];
            num += (long) in.reqCount[r] * (in.dcLatency[e] - best);
        }
        return num * 1000L / in.totalRequests;
    }

    /** Valide capacite + format, puis retourne le score. Leve si la solution est invalide. */
    public static long validateAndScore(Instance in, Solution s) {
        s.validateOrThrow();
        return score(in, s);
    }
}
