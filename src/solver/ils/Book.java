package solver.ils;

/**
 * "Le carnet des deux meilleurs" — la structure centrale de tout le solveur.
 *
 * Pour chaque requete r = (video v, endpoint e, nombre Rn), on memorise :
 *   best1[r] : la meilleure latence actuellement disponible
 *   arg1[r]  : QUI la fournit (id de cache, ou -1 = le data center)
 *   best2[r] : la DEUXIEME meilleure latence
 *
 * best2 est le secret : il repond a l'avance a la question
 * "et si je perdais mon meilleur fournisseur ?", ce qui rend les
 * evaluations d'ajout ET de retrait O(1).
 *
 * C'est aussi ce qui encode la difference entre les trois types d'endpoints :
 *   - orphelin   : best1 = best2 = L_D, immobile, rien ne l'affecte jamais
 *   - mono-cache : best2 = L_D toujours -> retirer la video le renvoie au
 *                  data center, chute de plusieurs centaines de ms (fragile)
 *   - multi-cache: best2 = un autre cache proche -> retirer la video du
 *                  meilleur ne coute souvent que ~1 ms (sacrifice quasi gratuit)
 */
public final class Book {

    public final Instance in;
    public final int[] best1;
    public final int[] best2;
    public final int[] arg1;

    public Book(Instance in) {
        this.in = in;
        best1 = new int[in.R];
        best2 = new int[in.R];
        arg1  = new int[in.R];
        reset();
    }

    /** Etat initial : tout est servi par le data center. */
    public void reset() {
        for (int r = 0; r < in.R; r++) {
            int ld = in.dcLatency[in.reqEndpoint[r]];
            best1[r] = ld;
            best2[r] = ld;
            arg1[r]  = -1;
        }
    }

    /** Recopie l'etat d'un autre carnet (meme instance) sans reconstruction. */
    public void copyFrom(Book o) {
        System.arraycopy(o.best1, 0, best1, 0, best1.length);
        System.arraycopy(o.best2, 0, best2, 0, best2.length);
        System.arraycopy(o.arg1,  0, arg1,  0, arg1.length);
    }

    /** Propose une source (cache c, latence l) pour la requete r. */
    private void offer(int r, int l, int c) {
        if (l < best1[r]) { best2[r] = best1[r]; best1[r] = l; arg1[r] = c; }
        else if (l < best2[r]) { best2[r] = l; }
    }

    /** Reconstruction complete depuis une solution. Couteux : a n'utiliser qu'aux jalons. */
    public void rebuild(Solution s) {
        reset();
        for (int r = 0; r < in.R; r++) {
            int e = in.reqEndpoint[r], v = in.reqVideo[r];
            for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++) {
                int c = in.epCache[i];
                if (s.has(c, v)) offer(r, in.epLat[i], c);
            }
        }
    }

    /** Mise a jour incrementale apres ajout de la video v dans le cache c. O(requetes de v). */
    public void onAdd(int c, int v) {
        final int C = in.C;
        for (int i = in.vidStart[v]; i < in.vidStart[v + 1]; i++) {
            int r = in.vidReq[i];
            int l = in.lat[in.reqEndpoint[r] * C + c];
            if (l >= 0) offer(r, l, c);   // l < 0 : endpoint non connecte a c
        }
    }

    /** Recalcule best1/best2/arg1 d'une seule requete (necessaire apres un retrait). */
    public void recompute(int r, Solution s) {
        int e = in.reqEndpoint[r], v = in.reqVideo[r];
        int ld = in.dcLatency[e];
        best1[r] = ld; best2[r] = ld; arg1[r] = -1;
        for (int i = in.epStart[e]; i < in.epStart[e + 1]; i++) {
            int c = in.epCache[i];
            if (s.has(c, v)) offer(r, in.epLat[i], c);
        }
    }

    /**
     * Meilleure latence disponible pour r en IGNORANT le cache c.
     * C'est exactement le "Lbest sans c" dont le sac a dos du niveau 3 a besoin.
     */
    public int bestExcluding(int r, int c) {
        return (arg1[r] == c) ? best2[r] : best1[r];
    }

    /** Score courant deduit du carnet (doit toujours egaler Scorer.score). */
    public long score() {
        long num = 0;
        for (int r = 0; r < in.R; r++)
            num += (long) in.reqCount[r] * (in.dcLatency[in.reqEndpoint[r]] - best1[r]);
        return num * 1000L / in.totalRequests;
    }
}
