package solver.ils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public final class ILS {

    public static final class Result {
        public final Solution solution;
        public final long score;
        public final int sweeps, kicks, improvements;
        Result(Solution s, long sc, int sw, int k, int imp) {
            solution = s; score = sc; sweeps = sw; kicks = k; improvements = imp;
        }
    }

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
            if (now <= prev) { if (++stale >= 2) return now; }
            else stale = 0;
            prev = now;
            if (System.nanoTime() > deadlineNs) return now;
        }
        return prev;
    }

    public static Result runWalk(Instance in, Solution start, long budgetMs, long seed, boolean verbose) {
        Random rng = new Random(seed);
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;

        Solution sol = start.copy();
        Book book = new Book(in);
        book.rebuild(sol);
        BlockKnapsack bk = new BlockKnapsack(in, sol, book);

        int sweeps = 0, kicks = 0, improvements = 0;

        long cur = descend(in, sol, book, bk, rng, deadline, 1, verbose);
        sweeps += 1;
        Solution best = sol.copy();
        long bestScore = cur;

        int local = Math.min(in.C, 8 + in.C / 50);
        int[] nb = new int[local];
        int sinceImprovement = 0;

        while (System.nanoTime() < deadline) {

            int k = 1 + rng.nextInt(4);
            int n = 0;
            for (int i = 0; i < k && n < local; i++) {
                int c = rng.nextInt(in.C);
                bk.clearCacheAndUpdate(c);
                nb[n++] = c;
            }
            while (n < local) nb[n++] = rng.nextInt(in.C);
            kicks++;

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

                sol.copyFrom(best);
                book.rebuild(sol);
                sinceImprovement = 0;
            }
        }
        return new Result(best, bestScore, sweeps, kicks, improvements);
    }

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

    public static int KICK_MAX = Integer.getInteger("ils.kick", 3);
    public static int LOCAL    = Integer.getInteger("ils.local", 3);

    public static double TEMP  = Double.parseDouble(System.getProperty("ils.temp", "0"));

    public static double NOISE = Double.parseDouble(System.getProperty("ils.noise", "0.33"));
    public static int REPAIR   = Integer.getInteger("ils.repair", 2);

    public static Result run(Instance in, Solution start, long budgetMs, long seed,
                             boolean verbose, int[][] nbr) {
        return run(in, start, budgetMs, seed, verbose, nbr, null);
    }

    public static int COOP_MIN_CACHES = 32;

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

    public static long ROUND_MS = 300;

    static final class Change {
        final int[] caches; final int[][] contents;
        Change(int[] c, int[][] k) { caches = c; contents = k; }
    }

    public static final class Coop {
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

        long cur = coop != null ? book.score() : descend(in, sol, book, bk, rng, deadline, 1, verbose);
        sweeps += 1;
        Solution best = sol.copy();
        long bestScore = cur;

        final ArrayList<Change> log = coop != null ? new ArrayList<>() : null;
        long logBase = cur;
        boolean[] isDirty = new boolean[in.C];
        int[] dirty = new int[in.C];
        int nDirty = 0;
        long nextSync = System.nanoTime() + ROUND_MS * 1_000_000L;

        final int C = in.C;
        final int local = Math.min(C, LOCAL);
        int[] set = new int[local], ord = new int[local];
        int[][] snap = new int[local][in.V];
        int[] snapN = new int[local];

        while (System.nanoTime() < deadline) {

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

            for (int i = 0; i < n; i++) snapN[i] = bk.snapshot(set[i], snap[i]);
            for (int i = 0; i < nKick; i++) bk.clearCacheAndUpdate(set[i]);
            if (NOISE > 0) {

                System.arraycopy(set, 0, ord, 0, nKick);
                shuffle(ord, nKick, rng);
                for (int i = 0; i < nKick; i++) bk.optimizeCache(ord[i], NOISE, rng);
            }
            System.arraycopy(set, 0, ord, 0, n);
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

    public static Result runParallel(Instance in, Solution start, long budgetMs,
                                     int threads, boolean verbose) throws InterruptedException {
        if (in.C <= COOP_MIN_CACHES) return runWalkParallel(in, start, budgetMs, threads, verbose);
        final int[][] nbr = neighbors(in);
        final Coop coop = new Coop(in, start);
        final Solution[] starts = new Solution[threads];
        for (int t = 0; t < threads; t++) starts[t] = coop.master.copy();
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

