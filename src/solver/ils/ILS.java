package solver.ils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Niveau 4 — Recherche locale iteree (ILS / "ruin & recreate").
 *
 * La descente par blocs converge vers un optimum ou aucun cache pris
 * isolement ne peut etre ameliore. On est au sommet d'une grande montagne...
 * mais peut-etre pas de la plus haute.
 *
 * On fait alors quelque chose de contre-intuitif : on VIDE volontairement
 * plusieurs caches d'un coup. Le score chute. Puis on relance la descente.
 *
 * Pourquoi ca marche : en re-optimisant le cache A alors que B est encore
 * vide, les gains marginaux sont plus eleves qu'au point fixe — la descente
 * emprunte un chemin qu'elle n'aurait jamais pris. Parfois elle remonte
 * PLUS HAUT qu'avant.
 *
 * Une descente pure ne fait que monter : elle reste prisonniere de sa colline.
 * Pour atteindre une montagne plus haute il faut accepter de descendre dans
 * la vallee d'abord. La perturbation est ce saut ; la descente est la remontee.
 */
public final class ILS {

    public static final class Result {
        public final Solution solution;
        public final long score;
        public final int sweeps, kicks, improvements;
        Result(Solution s, long sc, int sw, int k, int imp) {
            solution = s; score = sc; sweeps = sw; kicks = k; improvements = imp;
        }
    }

    /** Descente par blocs seule, jusqu'au point fixe ou expiration du budget. */
    public static long descend(Instance in, Solution sol, Book book, BlockKnapsack bk,
                               Random rng, long deadlineNs, int maxSweeps, boolean verbose) {
        int[] order = new int[in.C];
        for (int i = 0; i < in.C; i++) order[i] = i;

        long prev = book.score();
        int stale = 0;
        for (int s = 0; s < maxSweeps; s++) {
            shuffle(order, rng);
            bk.sweep(order, deadlineNs);
            long now = book.score();
            if (verbose) System.out.printf("    passe %d : %,d%n", s + 1, now);
            if (now <= prev) { if (++stale >= 2) return now; }   // ordre aleatoire : on retente une fois
            else stale = 0;
            prev = now;
            if (System.nanoTime() > deadlineNs) return now;
        }
        return prev;
    }

    /**
     * Marche aleatoire iteree (multi-depart) : perturbation de 1 a 4 caches, on
     * ACCEPTE le resultat meme s'il est moins bon, et on ne revient a la meilleure
     * solution qu'apres 40 kicks sans progres. Peu efficace sur de grandes
     * instances, mais tres bon pour explorer quand les caches sont peu nombreux
     * et interagissent tous (me_at_the_zoo).
     */
    public static Result runWalk(Instance in, Solution start, long budgetMs, long seed, boolean verbose) {
        Random rng = new Random(seed);
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;

        Solution sol = start.copy();
        Book book = new Book(in);
        book.rebuild(sol);
        BlockKnapsack bk = new BlockKnapsack(in, sol, book);

        int sweeps = 0, kicks = 0, improvements = 0;

        // L'appelant fournit deja une solution descendue : quelques passes suffisent.
        long cur = descend(in, sol, book, bk, rng, deadline, 1, verbose);
        sweeps += 1;
        Solution best = sol.copy();
        long bestScore = cur;

        // Voisinage re-optimise apres chaque kick : les caches touches + un
        // echantillon. Balayer les 500 caches a chaque fois couterait 5 s le kick.
        int local = Math.min(in.C, 8 + in.C / 50);   // voisinage serre = beaucoup plus de kicks
        int[] nb = new int[local];
        int sinceImprovement = 0;

        while (System.nanoTime() < deadline) {
            /* --- Perturbation : vider K caches au hasard -------------------- */
            int k = 1 + rng.nextInt(4);
            int n = 0;
            for (int i = 0; i < k && n < local; i++) {
                int c = rng.nextInt(in.C);
                bk.clearCacheAndUpdate(c);      // carnet mis a jour de facon ciblee
                nb[n++] = c;
            }
            while (n < local) nb[n++] = rng.nextInt(in.C);
            kicks++;

            /* --- Reconstruction sur le voisinage ---------------------------- */
            shuffle(nb, rng);
            bk.sweep(nb, deadline);
            sweeps++;
            long now = book.score();

            if (now > bestScore) {
                bestScore = now;
                best.copyFrom(sol);
                improvements++;
                sinceImprovement = 0;
                if (verbose) System.out.printf("    kick %d -> %,d%n", kicks, now);
            } else if (++sinceImprovement >= 40) {
                // Trop longtemps sans progres : retour a la meilleure solution connue.
                sol.copyFrom(best);
                book.rebuild(sol);
                sinceImprovement = 0;
            }
        }
        return new Result(best, bestScore, sweeps, kicks, improvements);
    }

    /** Voisinage : pour chaque cache, les caches qui partagent au moins un endpoint avec lui. */
    public static int[][] neighbors(Instance in) {
        int C = in.C;
        int[][] res = new int[C][];
        int[] mark = new int[C];
        Arrays.fill(mark, -1);
        int[] tmp = new int[C];
        for (int c = 0; c < C; c++) {
            int n = 0;
            mark[c] = c;
            for (int i = in.cEpStart[c]; i < in.cEpStart[c + 1]; i++) {
                int e = in.cEp[i];
                for (int j = in.epStart[e]; j < in.epStart[e + 1]; j++) {
                    int c2 = in.epCache[j];
                    if (mark[c2] != c) { mark[c2] = c; tmp[n++] = c2; }
                }
            }
            res[c] = Arrays.copyOf(tmp, n);
        }
        return res;
    }

    /** Parametres du voisinage de perturbation. */
    public static int KICK_MAX = Integer.getInteger("ils.kick", 3);      // nombre max de caches vides a chaque kick
    public static int LOCAL    = Integer.getInteger("ils.local", 3);      // nombre de caches re-optimises a chaque kick
    /** Temperature relative (Metropolis) : un kick moins bon de d est accepte avec proba exp(-d / (TEMP*score)). */
    public static double TEMP  = Double.parseDouble(System.getProperty("ils.temp", "0"));
    /**
     * Bruit relatif de la reconstruction diversifiante : apres avoir vide les caches
     * du kick, on les remplit avec des gains perturbes de +-NOISE, puis on repare
     * exactement (REPAIR passes). 0 = reconstruction exacte directe (ancien comportement).
     */
    public static double NOISE = Double.parseDouble(System.getProperty("ils.noise", "0.2"));
    public static int REPAIR   = Integer.getInteger("ils.repair", 2);

    /**
     * Descente + perturbations iterees, dans un budget de temps.
     *
     * Chaque kick vide 1..KICK_MAX caches VOISINS (qui partagent des endpoints :
     * eux seuls interagissent), puis re-optimise ces caches et quelques voisins.
     * Si le score ne baisse pas on garde ; sinon on ANNULE le kick (journal du
     * contenu des caches touches). Les egalites sont acceptees : elles laissent
     * traverser les plateaux.
     */
    public static Result run(Instance in, Solution start, long budgetMs, long seed,
                             boolean verbose, int[][] nbr) {
        return run(in, start, budgetMs, seed, verbose, nbr, null);
    }

    /** En dessous de ce nombre de caches, tous les kicks interagissent : on n'utilise pas la fusion. */
    public static int COOP_MIN_CACHES = 32;

    /** Multi-depart parallele : N marches independantes, on garde la meilleure. */
    public static Result runWalkParallel(Instance in, Solution start, long budgetMs,
                                         int threads, boolean verbose) throws InterruptedException {
        final Result[] res = new Result[threads];
        Thread[] th = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            th[t] = new Thread(() -> res[id] = runWalk(in, start, budgetMs, 12345L + id * 7919L, false));
            th[t].start();
        }
        for (Thread x : th) x.join();
        Result best = res[0];
        for (Result r : res) if (r != null && (best == null || r.score > best.score)) best = r;
        if (verbose) {
            StringBuilder sb = new StringBuilder("    graines : ");
            for (Result r : res) sb.append(r == null ? "-" : String.format("%,d ", r.score));
            System.out.println(sb);
        }
        return best;
    }

    /** Duree d'une manche entre deux synchronisations des threads (ms). */
    public static long ROUND_MS = 300;

    /**
     * Fusion des threads : chaque thread journalise ses kicks AMELIORANTS
     * (caches touches + nouveau contenu). Le maitre les rejoue un a un et les
     * VALIDE par le score : un kick qui, combine aux autres, ferait baisser le
     * total est annule. Les kicks portant sur des caches sans endpoint commun
     * s'additionnent ; en cas de conflit, le score arbitre.
     */
    static final class Change {
        final int[] caches; final int[][] contents;
        Change(int[] c, int[][] k) { caches = c; contents = k; }
    }

    static final class Coop {
        final Solution master; final Book mbook; final BlockKnapsack mbk;
        long mscore;
        long applied, refused;
        final AtomicLong kicks = new AtomicLong();

        Coop(Instance in, Solution start) {
            master = start.copy();
            mbook = new Book(in);
            mbook.rebuild(master);
            mbk = new BlockKnapsack(in, master, mbook);
            mscore = mbook.score();
        }

        synchronized long score() { return mscore; }

        /** Fusionne le journal du thread dans le maitre, puis lui rend l'etat du maitre. */
        synchronized long sync(ArrayList<Change> log, Solution sol, Book book) {
            for (Change ch : log) {
                int n = ch.caches.length;
                int[][] old = new int[n][];
                for (int i = 0; i < n; i++) {
                    int[] buf = new int[master.in.V];
                    old[i] = Arrays.copyOf(buf, mbk.snapshot(ch.caches[i], buf));
                }
                for (int i = 0; i < n; i++) mbk.restore(ch.caches[i], ch.contents[i], ch.contents[i].length);
                long s = mbook.score();
                if (s >= mscore) { mscore = s; applied++; }
                else {
                    for (int i = 0; i < n; i++) mbk.restore(ch.caches[i], old[i], old[i].length);
                    refused++;
                }
            }
            log.clear();
            sol.copyFrom(master);
            book.copyFrom(mbook);
            return mscore;
        }
    }

    public static Result run(Instance in, Solution start, long budgetMs, long seed,
                             boolean verbose, int[][] nbr, Coop coop) {
        Random rng = new Random(seed);
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;

        Solution sol = start.copy();
        Book book = new Book(in);
        book.rebuild(sol);
        BlockKnapsack bk = new BlockKnapsack(in, sol, book);

        int sweeps = 0, kicks = 0, improvements = 0;

        // L'appelant fournit deja une solution descendue : une passe suffit.
        long cur = coop != null ? book.score() : descend(in, sol, book, bk, rng, deadline, 1, verbose);
        sweeps += 1;
        Solution best = sol.copy();
        long bestScore = cur;

        final ArrayList<Change> log = coop != null ? new ArrayList<>() : null;
        long logBase = cur;                        // meilleur score deja journalise / synchronise
        boolean[] isDirty = new boolean[in.C];     // caches modifies depuis le dernier journal
        int[] dirty = new int[in.C];
        int nDirty = 0;
        long nextSync = System.nanoTime() + ROUND_MS * 1_000_000L;

        final int C = in.C;
        final int local = Math.min(C, LOCAL);
        int[] set = new int[local], ord = new int[local];
        int[][] snap = new int[local][in.V];
        int[] snapN = new int[local];

        while (System.nanoTime() < deadline) {
            /* --- Choix du voisinage : un cache et ses voisins ---------------- */
            int c0 = rng.nextInt(C);
            int[] nb = nbr[c0];
            int k = 1 + rng.nextInt(Math.min(KICK_MAX, local));
            int n = 0;
            set[n++] = c0;
            for (int g = 0; n < k && g < 4 * local && nb.length > 0; g++) {
                int c = nb[rng.nextInt(nb.length)];
                if (!has(set, n, c)) set[n++] = c;
            }
            int nKick = n;
            int target = Math.min(local, nb.length + 1);
            for (int g = 0; n < target && g < 8 * local; g++) {
                int c = nb[rng.nextInt(nb.length)];
                if (!has(set, n, c)) set[n++] = c;
            }
            kicks++;

            /* --- Journal, perturbation, reconstruction ----------------------- */
            for (int i = 0; i < n; i++) snapN[i] = bk.snapshot(set[i], snap[i]);
            for (int i = 0; i < nKick; i++) bk.clearCacheAndUpdate(set[i]);
            if (NOISE > 0) {
                /* Reconstruction bruitee des caches vides, puis reparation exacte. */
                System.arraycopy(set, 0, ord, 0, nKick);
                shuffle(ord, nKick, rng);
                for (int i = 0; i < nKick; i++) bk.optimizeCache(ord[i], NOISE, rng);
            }
            System.arraycopy(set, 0, ord, 0, n);     // set[] reste aligne sur le journal
            for (int rep = 0; rep < (NOISE > 0 ? REPAIR : 1); rep++) {
                shuffle(ord, n, rng);
                bk.sweep(ord, n, Long.MAX_VALUE);
            }
            sweeps++;
            long now = book.score();

            boolean accept = now >= cur;
            if (!accept && TEMP > 0) accept = rng.nextDouble() < Math.exp((now - cur) / (TEMP * cur));

            if (accept) {
                if (now > cur) improvements++;
                cur = now;
                for (int i = 0; i < n; i++)
                    if (!isDirty[set[i]]) { isDirty[set[i]] = true; dirty[nDirty++] = set[i]; }
                if (now > logBase) {
                    logBase = now;
                    if (log != null) {
                        log.add(change(bk, dirty, nDirty, in.V));
                        for (int i = 0; i < nDirty; i++) isDirty[dirty[i]] = false;
                        nDirty = 0;
                    }
                }
                if (coop == null && now > bestScore) {
                    bestScore = now;
                    best.copyFrom(sol);
                    if (verbose) System.out.printf("    kick %d -> %,d%n", kicks, now);
                }
            } else {
                /* Annulation : chaque cache retrouve son contenu d'avant le kick. */
                for (int i = 0; i < n; i++) bk.restore(set[i], snap[i], snapN[i]);
            }

            if (coop != null && System.nanoTime() >= nextSync) {
                cur = coop.sync(log, sol, book);
                logBase = cur;
                for (int i = 0; i < nDirty; i++) isDirty[dirty[i]] = false;
                nDirty = 0;
                nextSync = System.nanoTime() + ROUND_MS * 1_000_000L;
            }
        }
        if (coop != null) {
            cur = coop.sync(log, sol, book);
            coop.kicks.addAndGet(kicks);
            return new Result(sol, cur, sweeps, kicks, improvements);
        }
        return new Result(best, bestScore, sweeps, kicks, improvements);
    }

    private static Change change(BlockKnapsack bk, int[] set, int n, int V) {
        int[] caches = Arrays.copyOf(set, n);
        int[][] contents = new int[n][];
        int[] buf = new int[V];
        for (int i = 0; i < n; i++) contents[i] = Arrays.copyOf(buf, bk.snapshot(set[i], buf));
        return new Change(caches, contents);
    }

    private static boolean has(int[] a, int n, int x) {
        for (int i = 0; i < n; i++) if (a[i] == x) return true;
        return false;
    }

    /**
     * Recherche locale iteree COOPERATIVE : N threads explorent en parallele
     * a partir de la meme solution ; leurs kicks ameliorants sont fusionnes
     * dans un maitre (voir Coop). Les threads ne partagent que l'Instance et
     * la table de voisinage en lecture seule, plus le maitre sous verrou.
     */
    public static Result runParallel(Instance in, Solution start, long budgetMs,
                                     int threads, boolean verbose) throws InterruptedException {
        if (in.C <= COOP_MIN_CACHES) return runWalkParallel(in, start, budgetMs, threads, verbose);
        final int[][] nbr = neighbors(in);
        final Coop coop = new Coop(in, start);
        final Solution[] starts = new Solution[threads];
        for (int t = 0; t < threads; t++) starts[t] = coop.master.copy();   // avant tout thread : le maitre bouge ensuite
        Thread[] th = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            th[t] = new Thread(() ->
                run(in, starts[id], budgetMs, 12345L + id * 7919L, false, nbr, coop));
            th[t].start();
        }
        if (verbose) {
            long t0 = System.nanoTime();
            while (System.nanoTime() - t0 < budgetMs * 1_000_000L) {
                Thread.sleep(Math.min(5000, Math.max(1, budgetMs - (System.nanoTime() - t0) / 1_000_000L)));
                System.out.printf("    t=%4.0fs  score=%,d%n", (System.nanoTime() - t0) / 1e9, coop.score());
            }
        }
        for (Thread x : th) x.join();
        if (verbose)
            System.out.printf("    fusion : %d kicks appliques, %d refuses (conflit)%n", coop.applied, coop.refused);
        return new Result(coop.master, coop.mscore, 0, (int) coop.kicks.get(), (int) coop.applied);
    }

    private static void shuffle(int[] a, Random rng) { shuffle(a, a.length, rng); }

    private static void shuffle(int[] a, int n, Random rng) {
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }
}
