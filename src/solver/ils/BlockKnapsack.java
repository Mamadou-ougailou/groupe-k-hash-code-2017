package solver.ils;

import java.util.Arrays;

/**
 * Niveau 3 — Descente par blocs a sous-probleme EXACT.
 *
 * THEOREME DE DECOMPOSITION
 * Si l'on fige le contenu de tous les caches sauf c, le gain marginal de
 * placer la video v dans c vaut :
 *
 *     g_c(v) = somme  Rn * max(0, Lbest_sans_c(e,v) - L(e,c))
 *          (e,v) requetees, e connecte a c
 *
 * Cette valeur NE DEPEND D'AUCUNE autre video presente dans c : une requete
 * ne porte que sur une seule video, donc deux videos d'un meme cache ne se
 * genent ni ne s'aident — elles se disputent uniquement la PLACE.
 *
 * Des objets a valeur fixe, poids fixe, et un sac de capacite limitee :
 * c'est la definition litterale du SAC A DOS 0/1.
 *
 * RESOLUTION EXACTE PAR LE "COEUR" (reduced-cost fixing)
 * Une DP complete coute nItems * X par cache — c'etait le poste dominant.
 * Or l'optimum du sac a dos est presque entierement determine par la
 * relaxation lineaire (glouton fractionnaire, borne U) :
 *
 *   - les objets de tres bon ratio sont dans TOUTE solution meilleure que la
 *     borne inferieure LB connue (le contenu precedent du cache, ou un
 *     glouton) : les retirer fait chuter la borne sous LB ;
 *   - les objets de tres mauvais ratio n'y sont dans AUCUNE.
 *
 * Avec le cout reduit d_i = p_i - w_i * r_b (r_b = ratio de l'objet critique),
 * l'objet i est fixe "dedans" si d_i > U - LB, fixe "dehors" si -d_i > U - LB.
 * La DP ne porte plus que sur les quelques dizaines d'objets restants, sur la
 * capacite restante. Le resultat reste EXACT : c'est le meme optimum.
 *
 * GARANTIE : LB est realisable, donc l'optimum trouve vaut >= LB. Le score
 * est mathematiquement NON-DECROISSANT.
 */
public final class BlockKnapsack {

    private final Instance in;
    private final Solution sol;
    private final Book book;

    /* Buffers reutilises : zero allocation dans la boucle chaude. */
    private final long[] gain;      // [V] gain marginal de chaque video pour le cache courant
    private final long[] sortKey;   // [V] (ratio tronque) | id video
    private final int[]  ord;       // [V] videos par ratio decroissant
    private final int[]  core;      // [V] objets restant apres fixation
    private final int[]  chosen;    // [V] videos retenues
    private final int[]  gsel;      // [V] solution gloutonne (borne inferieure)
    private final int[]  prev;      // [V] contenu precedent du cache
    private final long[] dp;        // [X+1]
    private long[] take;            // bitset des decisions du DP
    private final int vBits;        // bits reserves a l'id video dans sortKey
    private final long vMask;

    /** Nombre de fois ou la DP a rendu moins que la borne inferieure (doit rester 0). */
    public long fallbacks;

    public BlockKnapsack(Instance in, Solution sol, Book book) {
        this.in = in; this.sol = sol; this.book = book;
        gain    = new long[in.V];
        sortKey = new long[in.V];
        ord     = new int[in.V];
        core    = new int[in.V];
        chosen  = new int[in.V];
        gsel    = new int[in.V];
        prev    = new int[in.V];
        dp      = new long[in.X + 1];
        take    = new long[1024];
        vBits   = 32 - Integer.numberOfLeadingZeros(Math.max(1, in.V - 1));
        vMask   = (1L << vBits) - 1;
    }

    /** Une passe complete sur tous les caches, dans l'ordre fourni. */
    public void sweep(int[] order) { sweep(order, Long.MAX_VALUE); }

    /** Passe interruptible. */
    public void sweep(int[] order, long deadlineNs) { sweep(order, order.length, deadlineNs); }

    /** Passe sur les len premiers caches de order. */
    public void sweep(int[] order, int len, long deadlineNs) {
        for (int i = 0; i < len; i++) {
            optimizeCache(order[i]);
            if ((i & 15) == 15 && System.nanoTime() > deadlineNs) return;
        }
    }

    /** Range dans out[] le contenu du cache c (liste de videos) et renvoie sa taille. */
    public int snapshot(int c, int[] out) {
        int n = 0;
        for (int v = 0; v < in.V; v++) if (sol.has(c, v)) out[n++] = v;
        return n;
    }

    /** Remet le cache c dans l'etat decrit par snap[0..n), carnet compris. */
    public void restore(int c, int[] snap, int n) {
        clearCacheAndUpdate(c);
        for (int i = 0; i < n; i++) place(c, snap[i]);
    }

    /**
     * Vide le cache c en tenant le carnet a jour, sans reconstruction globale.
     * Renvoie le nombre de videos retirees (laissees dans le buffer prev).
     */
    public int clearCacheAndUpdate(int c) {
        final int V = in.V, C = in.C;
        int nPrev = 0;
        for (int v = 0; v < V; v++) if (sol.has(c, v)) prev[nPrev++] = v;
        if (nPrev == 0) return 0;
        for (int i = 0; i < nPrev; i++) sol.remove(c, prev[i]);
        // Le carnet doit oublier c : on recalcule uniquement les requetes impactees.
        for (int i = 0; i < nPrev; i++) {
            int v = prev[i];
            for (int j = in.vidStart[v]; j < in.vidStart[v + 1]; j++) {
                int r = in.vidReq[j];
                if (in.lat[in.reqEndpoint[r] * C + c] >= 0) book.recompute(r, sol);
            }
        }
        return nPrev;
    }

    /**
     * Vide le cache c, puis le remplit de maniere OPTIMALE etant donne
     * le contenu fige de tous les autres caches.
     */
    public void optimizeCache(int c) {
        final int V = in.V, X = in.X;
        final int[] size = in.videoSize;

        /* --- 1. Relever puis vider le contenu actuel ---------------------- */
        int nPrev = clearCacheAndUpdate(c);

        /* --- 2. Gains marginaux : c est vide, donc best1 exclut deja c -----
               On ne parcourt que les requetes des endpoints relies a c.    */
        Arrays.fill(gain, 0L);
        final int[] best1 = book.best1;
        final int[] rv = in.reqVideo, rn = in.reqCount;
        for (int i = in.cEpStart[c], iEnd = in.cEpStart[c + 1]; i < iEnd; i++) {
            int e = in.cEp[i], l = in.cEpLat[i];
            for (int r = in.epReqStart[e], rEnd = in.epReqStart[e + 1]; r < rEnd; r++) {
                int d = best1[r] - l;
                if (d > 0) gain[rv[r]] += (long) rn[r] * d;
            }
        }

        /* --- 3. Objets, tries par ratio gain/taille decroissant ------------
               Cle triable : les bits du ratio (double positif => ordre
               preserve), dont on ecrase les bits de poids faible par l'id.
               Erreur relative < 2^-38 : absorbee par la marge `tol` plus bas. */
        int n = 0;
        for (int v = 0; v < V; v++) {
            long g = gain[v];
            if (g <= 0 || size[v] > X) continue;
            long bits = Double.doubleToRawLongBits((double) g / size[v]);
            sortKey[n++] = ((bits >>> vBits) << vBits) | v;
        }
        if (n == 0) return;                            // rien a mettre : cache laisse vide
        Arrays.sort(sortKey, 0, n);
        for (int k = 0; k < n; k++) ord[k] = (int) (sortKey[n - 1 - k] & vMask);

        /* --- 4. Relaxation lineaire : objet critique b -------------------- */
        long cumW = 0, cumP = 0;
        int b = n;
        for (int k = 0; k < n; k++) {
            int v = ord[k];
            if (cumW + size[v] > X) { b = k; break; }
            cumW += size[v];
            cumP += gain[v];
        }
        if (b == n) {                                  // tout tient : optimum evident
            for (int k = 0; k < n; k++) place(c, ord[k]);
            return;
        }

        /* --- 5. Borne inferieure LB : meilleur de (glouton, ancien contenu) */
        int ng = 0;
        for (int k = 0; k < b; k++) gsel[ng++] = ord[k];
        long gV = cumP, left = X - cumW;
        for (int k = b; k < n && left > 0; k++) {
            int v = ord[k];
            if (size[v] <= left) { gsel[ng++] = v; left -= size[v]; gV += gain[v]; }
        }
        long pV = 0;
        for (int i = 0; i < nPrev; i++) if (gain[prev[i]] > 0) pV += gain[prev[i]];
        final long lb = Math.max(gV, pV);

        int vb = ord[b];
        final double rb = (double) gain[vb] / size[vb];
        final double ub = cumP + (double) (X - cumW) * rb;
        final double lim = Math.max(0.0, ub - lb) + 1e-10 * ub + 1.0;

        /* --- 6. Fixation par couts reduits -------------------------------- */
        int nFix = 0, nCore = 0;
        long fixW = 0, fixP = 0, coreW = 0;
        for (int k = 0; k < n; k++) {
            int v = ord[k];
            double d = gain[v] - size[v] * rb;
            if (d > lim)       { chosen[nFix++] = v; fixW += size[v]; fixP += gain[v]; }
            else if (-d > lim) { /* fixe dehors */ }
            else               { core[nCore++] = v; coreW += size[v]; }
        }
        final long capL = X - fixW;

        boolean ok = capL >= 0;
        int nCh = nFix;
        if (ok) {
            int cap = (int) capL;
            if (coreW <= cap) {                        // tout le coeur tient
                for (int i = 0; i < nCore; i++) chosen[nCh++] = core[i];
                ok = fixP + sumGain(core, nCore) >= lb;
            } else {
                /* --- 7. DP exacte sur le coeur seulement ------------------- */
                final int words = (cap + 64) >>> 6;
                final int need = nCore * words;
                if (take.length < need) take = new long[need];
                Arrays.fill(take, 0, need, 0L);
                Arrays.fill(dp, 0, cap + 1, 0L);
                for (int i = 0; i < nCore; i++) {
                    int v = core[i];
                    int wi = size[v];
                    long vi = gain[v];
                    int base = i * words;
                    for (int w = cap; w >= wi; w--) {
                        long cand = dp[w - wi] + vi;
                        if (cand > dp[w]) {
                            dp[w] = cand;
                            take[base + (w >>> 6)] |= 1L << w;   // Java : decalage mod 64
                        }
                    }
                }
                ok = fixP + dp[cap] >= lb;
                if (ok) {
                    int w = cap;
                    for (int i = nCore - 1; i >= 0; i--) {
                        if ((take[i * words + (w >>> 6)] & (1L << w)) != 0) {
                            int v = core[i];
                            chosen[nCh++] = v;
                            w -= size[v];
                        }
                    }
                }
            }
        }

        /* --- 8. Application (repli sur LB si l'invariant est viole) -------- */
        if (ok) {
            for (int i = 0; i < nCh; i++) place(c, chosen[i]);
        } else {
            fallbacks++;
            if (gV >= pV) for (int i = 0; i < ng; i++) place(c, gsel[i]);
            else for (int i = 0; i < nPrev; i++) if (gain[prev[i]] > 0) place(c, prev[i]);
        }
    }

    private long sumGain(int[] a, int n) {
        long s = 0;
        for (int i = 0; i < n; i++) s += gain[a[i]];
        return s;
    }

    private void place(int c, int v) {
        sol.add(c, v);
        book.onAdd(c, v);
    }
}
