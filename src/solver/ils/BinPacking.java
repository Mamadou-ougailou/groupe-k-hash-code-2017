package solver.ils;

import java.util.Random;

/**
 * Solveur specialise pour les instances a structure DEGENEREE.
 *
 * Sur trending_today : tous les endpoints ont le meme L_D (600 ms), sont
 * connectes aux 100 caches, tous a la meme latence L_c (100 ms). Donc peu
 * importe DANS QUEL cache on met une video — seul compte qu'elle soit cachee
 * quelque part. Chaque requete servie economise exactement L_D - L_c.
 *
 *     score = 1000 * (L_D - L_c) * (requetes couvertes) / (requetes totales)
 *
 * Ce n'est plus un probleme de cache, c'est un BIN-PACKING pur.
 * Et comme somme(tailles) == C * X EXACTEMENT (5 000 000 == 5 000 000),
 * un packing parfait place les 10 000 videos et atteint la BORNE SUPERIEURE :
 * ce n'est plus de l'optimisation, c'est une partition exacte a trouver.
 *
 * Methode : Best-Fit-Decreasing, puis reparation par chaines d'ejection
 * (on force une video dans un bin en ejectant des occupants, qu'on replace
 * ailleurs ; on annule si le resultat empire).
 */
public final class BinPacking {

    /** Detecte la structure : L_D uniforme, tous les caches connectes, L_c uniforme. */
    public static boolean isDegenerate(Instance in) {
        if (in.E == 0 || in.C == 0) return false;
        int ld = in.dcLatency[0];
        if (in.epStart[1] - in.epStart[0] != in.C) return false;
        int lc = in.epLat[0];
        for (int e = 0; e < in.E; e++) {
            if (in.dcLatency[e] != ld) return false;
            if (in.epStart[e + 1] - in.epStart[e] != in.C) return false;
            for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++)
                if (in.epLat[i] != lc) return false;
        }
        return true;
    }

    private final Instance in;
    private final int V, C, X;
    private final long[] weight;      // requetes totales par video
    private final int[] binOf, posIn, rem, binCount;
    private int[][] binItems;
    private final int[] unplaced;
    private int nUnplaced;
    private long unplacedWeight;

    private BinPacking(Instance in) {
        this.in = in; V = in.V; C = in.C; X = in.X;
        weight = new long[V];
        for (int r = 0; r < in.R; r++) weight[in.reqVideo[r]] += in.reqCount[r];
        binOf = new int[V]; posIn = new int[V];
        rem = new int[C]; binCount = new int[C];
        binItems = new int[C][16];
        unplaced = new int[V];
        java.util.Arrays.fill(binOf, -1);
        java.util.Arrays.fill(rem, X);
    }

    private void addTo(int b, int v) {
        if (binCount[b] == binItems[b].length) {
            int[] a = new int[binItems[b].length * 2];
            System.arraycopy(binItems[b], 0, a, 0, binCount[b]);
            binItems[b] = a;
        }
        binItems[b][binCount[b]] = v;
        posIn[v] = binCount[b]++;
        binOf[v] = b;
        rem[b] -= in.videoSize[v];
    }

    private void removeFrom(int v) {
        int b = binOf[v], p = posIn[v], last = --binCount[b];
        int mv = binItems[b][last];
        binItems[b][p] = mv; posIn[mv] = p;
        binOf[v] = -1;
        rem[b] += in.videoSize[v];
    }

    /** Meilleur bin au sens best-fit (celui qui laisse le moins de reste). */
    private int bestFit(int v) {
        int s = in.videoSize[v], best = -1, bestRem = Integer.MAX_VALUE;
        for (int b = 0; b < C; b++)
            if (rem[b] >= s && rem[b] - s < bestRem) { bestRem = rem[b] - s; best = b; }
        return best;
    }

    public static Solution solve(Instance in, long budgetMs, boolean verbose) {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;

        /* Tentative 1 : remplissage EXACT bin par bin (subset-sum).
           Comme somme(tailles) == C*X, si chaque bin est rempli exactement a X
           alors TOUTES les videos sont placees : optimum atteint et prouve. */
        for (int attempt = 0; attempt < 40 && System.nanoTime() < deadline; attempt++) {
            BinPacking bp = new BinPacking(in);
            if (bp.exactPack(new Random(attempt * 7919L + 1))) {
                if (verbose) System.out.printf("  remplissage EXACT reussi (essai %d) : 0 video non placee%n",
                        attempt + 1);
                return bp.toSolution(in);
            }
        }

        /* Tentative 2 : BFD + reparations (repli si l'exact echoue). */
        if (verbose) System.out.println("  remplissage exact non trouve -> BFD + reparations");
        BinPacking bp = new BinPacking(in);
        bp.pack(Math.max(1, (deadline - System.nanoTime()) / 1_000_000L), verbose);
        return bp.toSolution(in);
    }

    private Solution toSolution(Instance in) {
        Solution sol = new Solution(in);
        for (int v = 0; v < in.V; v++) if (binOf[v] >= 0) sol.add(binOf[v], v);
        return sol;
    }

    /**
     * Remplit chaque bin a EXACTEMENT X par programmation dynamique
     * d'atteignabilite (subset-sum), sur un echantillon du pool restant.
     *
     * On ne cherche plus "un bon rangement" mais LA partition exacte :
     * reach[w] = peut-on atteindre la somme w ? On reconstruit ensuite le
     * sous-ensemble de somme X exactement, on le retire du pool, bin suivant.
     */
    private boolean exactPack(Random rng) {
        int[] pool = new int[V];
        for (int i = 0; i < V; i++) pool[i] = i;
        for (int i = V - 1; i > 0; i--) {                 // melange : diversifie les essais
            int j = rng.nextInt(i + 1);
            int t = pool[i]; pool[i] = pool[j]; pool[j] = t;
        }
        int nPool = V;

        final int SAMPLE = 420;                           // 420 * X operations par bin
        boolean[] reach = new boolean[X + 1];
        int[] byItem = new int[X + 1];
        int[] sample = new int[SAMPLE];

        for (int b = 0; b < C; b++) {
            int ns = Math.min(SAMPLE, nPool);
            for (int i = 0; i < ns; i++) sample[i] = pool[nPool - ns + i];

            java.util.Arrays.fill(reach, false);
            java.util.Arrays.fill(byItem, -1);
            reach[0] = true;
            for (int i = 0; i < ns; i++) {
                int s = in.videoSize[sample[i]];
                for (int w = X; w >= s; w--)
                    if (!reach[w] && reach[w - s]) { reach[w] = true; byItem[w] = i; }
                if (reach[X]) break;                      // inutile de continuer
            }
            if (!reach[X]) return false;                  // echec : on retentera avec un autre melange

            // Reconstruction du sous-ensemble de somme exactement X.
            boolean[] used = new boolean[ns];
            int w = X;
            while (w > 0) {
                int i = byItem[w];
                if (i < 0) return false;
                used[i] = true;
                addTo(b, sample[i]);
                w -= in.videoSize[sample[i]];
            }
            // Retirer du pool les items consommes.
            int write = nPool - ns;
            for (int i = 0; i < ns; i++) if (!used[i]) pool[write++] = sample[i];
            nPool = write;
        }
        return nPool == 0;
    }

    private void pack(long budgetMs, boolean verbose) {
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        Random rng = new Random(20170223L);

        /* --- Best-Fit-Decreasing par taille ------------------------------- */
        Integer[] boxed = new Integer[V];           // tri unique, hors boucle chaude
        for (int i = 0; i < V; i++) boxed[i] = i;
        java.util.Arrays.sort(boxed, (a, b) -> in.videoSize[b] - in.videoSize[a]);
        int[] bySize = new int[V];
        for (int i = 0; i < V; i++) bySize[i] = boxed[i];

        for (int i = 0; i < V; i++) {
            int v = bySize[i], b = bestFit(v);
            if (b >= 0) addTo(b, v);
            else { unplaced[nUnplaced++] = v; unplacedWeight += weight[v]; }
        }
        long totalSize = 0;
        for (int v = 0; v < V; v++) totalSize += in.videoSize[v];
        if (verbose)
            System.out.printf("  BFD : %d videos non placees, gaspillage %d Mo (capacite=%d, somme=%d)%n",
                    nUnplaced, sumRem(), (long) C * X, totalSize);

        /* --- Reparation 1 : TRANSFERT deterministe -------------------------
           Les trous sont disperses sur plusieurs bins. Plutot que d'ejecter
           au hasard, on choisit un bin cible et on DEMENAGE ses occupants
           vers les trous des autres bins, jusqu'a liberer la place voulue.

           Les deux phases (deterministe puis aleatoire) ALTERNENT : le hasard
           debloque des configurations que le deterministe ne voit pas, et
           reciproquement. Une seule passe de chaque ne suffit pas.          */
        int iters = 0;
        while (nUnplaced > 0 && System.nanoTime() < deadline) {
        boolean progress = true;
        while (nUnplaced > 0 && progress && System.nanoTime() < deadline) {
            progress = false;
            for (int ui = 0; ui < nUnplaced && !progress; ui++) {
                int u = unplaced[ui], su = in.videoSize[u];
                for (int b = 0; b < C && !progress; b++) {
                    if (rem[b] >= su) continue;                 // bestFit s'en serait charge
                    if (X - su < 0) continue;
                    // Deplacer des occupants de b vers d'autres bins.
                    // (compteur de demenagements retire : non significatif)
                    for (int k = binCount[b] - 1; k >= 0 && rem[b] < su; k--) {
                        int x = binItems[b][k];
                        int sx = in.videoSize[x], dest = -1, bestRem = Integer.MAX_VALUE;
                        for (int b2 = 0; b2 < C; b2++)
                            if (b2 != b && rem[b2] >= sx && rem[b2] - sx < bestRem) {
                                bestRem = rem[b2] - sx; dest = b2;
                            }
                        if (dest >= 0) { removeFrom(x); addTo(dest, x); k = binCount[b]; }
                    }
                    if (rem[b] >= su) {
                        addTo(b, u);
                        unplaced[ui] = unplaced[--nUnplaced];
                        unplacedWeight -= weight[u];
                        progress = true;
                    }
                    // NB : un simple demenagement ne compte PAS comme un progres.
                    // Sinon la phase 1 tourne indefiniment en brassant les bins
                    // et les phases 1b/1c (les seules capables de concentrer la
                    // marge) ne sont jamais atteintes.
                }
            }

            /* --- Reparation 1b : ECHANGE 1-1 dirige -----------------------
               Les trous sont tous plus petits que la plus petite video : aucun
               demenagement simple n'est possible. Il faut CONCENTRER la place
               libre. On echange x (bin b) contre y (bin b2) plus petit, ce qui
               transfere size[x]-size[y] de marge vers b. Condition :
                   need <= size[x] - size[y] <= rem[b2]                      */
            /* --- Reparation 1c : ACCUMULATION de marge ---------------------
               Exiger qu'un seul echange comble tout le manque est trop rigide.
               On accepte tout echange qui AUGMENTE la marge du bin cible sans
               casser l'autre (0 < size[x]-size[y] <= rem[b2]), et on repete :
               la place libre se concentre progressivement dans b.           */
            if (!progress) {
                for (int ui = 0; ui < nUnplaced && !progress; ui++) {
                    int u = unplaced[ui], su = in.videoSize[u];
                    for (int b = 0; b < C && !progress; b++) {
                        boolean moved = true;
                        while (rem[b] < su && moved && System.nanoTime() < deadline) {
                            moved = false;
                            for (int ix = 0; ix < binCount[b] && !moved; ix++) {
                                int x = binItems[b][ix], sx = in.videoSize[x];
                                for (int b2 = 0; b2 < C && !moved; b2++) {
                                    if (b2 == b) continue;
                                    for (int iy = 0; iy < binCount[b2]; iy++) {
                                        int y = binItems[b2][iy], d = sx - in.videoSize[y];
                                        if (d <= 0 || d > rem[b2]) continue;
                                        removeFrom(x); removeFrom(y);
                                        addTo(b2, x); addTo(b, y);
                                        moved = true; break;
                                    }
                                }
                            }
                        }
                        if (rem[b] >= su) {
                            addTo(b, u);
                            unplaced[ui] = unplaced[--nUnplaced];
                            unplacedWeight -= weight[u];
                            progress = true;
                        }
                    }
                }
            }

            if (!progress) {
                for (int ui = 0; ui < nUnplaced && !progress; ui++) {
                    int u = unplaced[ui], su = in.videoSize[u];
                    for (int b = 0; b < C && !progress; b++) {
                        int need = su - rem[b];
                        if (need <= 0) continue;
                        for (int ix = 0; ix < binCount[b] && !progress; ix++) {
                            int x = binItems[b][ix], sx = in.videoSize[x];
                            if (sx < need) continue;            // retirer x ne suffit pas
                            for (int b2 = 0; b2 < C && !progress; b2++) {
                                if (b2 == b) continue;
                                for (int iy = 0; iy < binCount[b2]; iy++) {
                                    int y = binItems[b2][iy], d = sx - in.videoSize[y];
                                    if (d < need || d > rem[b2]) continue;
                                    removeFrom(x); removeFrom(y);
                                    addTo(b2, x); addTo(b, y);
                                    addTo(b, u);
                                    unplaced[ui] = unplaced[--nUnplaced];
                                    unplacedWeight -= weight[u];
                                    progress = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (nUnplaced == 0) break;

        /* --- Reparation 2 : chaines d'ejection aleatoires ------------------ */
        int[] undoV = new int[64]; int[] undoB = new int[64];
        long phaseEnd = Math.min(deadline, System.nanoTime() + 3_000_000_000L);
        while (nUnplaced > 0 && System.nanoTime() < phaseEnd) {
            iters++;
            int ui = rng.nextInt(nUnplaced);
            int u = unplaced[ui];
            int su = in.videoSize[u];
            int b = rng.nextInt(C);

            int nUndo = 0;
            long before = unplacedWeight;

            // Ejecter au hasard jusqu'a liberer la place pour u.
            int guard = 0;
            while (rem[b] < su && binCount[b] > 0 && guard++ < 32) {
                int x = binItems[b][rng.nextInt(binCount[b])];
                if (nUndo == undoV.length) {
                    undoV = java.util.Arrays.copyOf(undoV, nUndo * 2);
                    undoB = java.util.Arrays.copyOf(undoB, nUndo * 2);
                }
                undoV[nUndo] = x; undoB[nUndo] = b; nUndo++;
                removeFrom(x);
            }
            if (rem[b] < su) { restore(undoV, undoB, nUndo); continue; }

            // Placer u, retirer de la liste des non places.
            addTo(b, u);
            unplaced[ui] = unplaced[--nUnplaced];
            unplacedWeight -= weight[u];

            // Replacer les ejectes ; ceux qui ne rentrent nulle part deviennent non places.
            int firstNew = nUnplaced;
            for (int i = 0; i < nUndo; i++) {
                int x = undoV[i], nb = bestFit(x);
                if (nb >= 0) addTo(nb, x);
                else { unplaced[nUnplaced++] = x; unplacedWeight += weight[x]; }
            }

            if (unplacedWeight > before) {
                // Degradation : on annule tout et on remet l'etat precedent.
                for (int i = nUnplaced - 1; i >= firstNew; i--) unplacedWeight -= weight[unplaced[i]];
                nUnplaced = firstNew;
                for (int i = 0; i < nUndo; i++) if (binOf[undoV[i]] >= 0) removeFrom(undoV[i]);
                removeFrom(u);
                unplaced[nUnplaced++] = u; unplacedWeight += weight[u];
                restore(undoV, undoB, nUndo);
            }
        }
        }
        if (verbose)
            System.out.printf("  reparation : %d iterations -> %d non placees, gaspillage %d Mo%n",
                    iters, nUnplaced, sumRem());
    }

    private void restore(int[] v, int[] b, int n) {
        for (int i = 0; i < n; i++)
            if (binOf[v[i]] < 0 && rem[b[i]] >= in.videoSize[v[i]]) addTo(b[i], v[i]);
    }

    private int sumRem() { int s = 0; for (int b = 0; b < C; b++) s += rem[b]; return s; }
}
