package solver.ils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

public final class IteratedLocalSearch {

    public static final class Result {
        public final Solution solution;
        public final long score;
        public final int sweeps, kicks, improvements;
        Result(Solution solution, long score, int sweeps, int kicks, int improvements) {
            this.solution = solution; this.score = score; this.sweeps = sweeps; this.kicks = kicks; this.improvements = improvements;
        }
    }

    public static long descendBySweeps(FlatProblem problem, Solution solution, BestLatencyTracker tracker, BlockKnapsack knapsack,
                                       Random rng, long deadlineNs, int maxSweeps, boolean verbose) {
        int[] cacheOrder = new int[problem.cacheCount];
        for (int i = 0; i < problem.cacheCount; i++) cacheOrder[i] = i;

        long previousScore = tracker.score();
        int staleSweeps = 0;
        for (int sweep = 0; sweep < maxSweeps; sweep++) {
            shuffle(cacheOrder, rng);
            knapsack.sweep(cacheOrder, deadlineNs);
            long currentScore = tracker.score();
            if (verbose) System.out.printf("    passe %d : %,d%n", sweep + 1, currentScore);
            if (currentScore <= previousScore) { if (++staleSweeps >= 2) return currentScore; }
            else staleSweeps = 0;
            previousScore = currentScore;
            if (System.nanoTime() > deadlineNs) return currentScore;
        }
        return previousScore;
    }

    public static Result runSingleWalk(FlatProblem problem, Solution startSolution, long budgetMs, long seed, boolean verbose) {
        Random rng = new Random(seed);
        long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;

        Solution solution = startSolution.copy();
        BestLatencyTracker tracker = new BestLatencyTracker(problem);
        tracker.rebuildFrom(solution);
        BlockKnapsack knapsack = new BlockKnapsack(problem, solution, tracker);

        int sweeps = 0, kicks = 0, improvements = 0;

        long currentScore = descendBySweeps(problem, solution, tracker, knapsack, rng, deadlineNanos, 1, verbose);
        sweeps += 1;
        Solution bestSolution = solution.copy();
        long bestScore = currentScore;

        int cachesPerRound = Math.min(problem.cacheCount, 8 + problem.cacheCount / 50);
        int[] roundCaches = new int[cachesPerRound];
        int roundsSinceImprovement = 0;

        while (System.nanoTime() < deadlineNanos) {

            int kickSize = 1 + rng.nextInt(4);
            int roundCount = 0;
            for (int i = 0; i < kickSize && roundCount < cachesPerRound; i++) {
                int cache = rng.nextInt(problem.cacheCount);
                knapsack.clearCacheAndUpdate(cache);
                roundCaches[roundCount++] = cache;
            }
            while (roundCount < cachesPerRound) roundCaches[roundCount++] = rng.nextInt(problem.cacheCount);
            kicks++;

            shuffle(roundCaches, rng);
            knapsack.sweep(roundCaches, deadlineNanos);
            sweeps++;
            long newScore = tracker.score();

            if (newScore > bestScore) {
                bestScore = newScore;
                bestSolution.copyFrom(solution);
                improvements++;
                roundsSinceImprovement = 0;
                if (verbose) System.out.printf("    kick %d -> %,d%n", kicks, newScore);
            } else if (++roundsSinceImprovement >= 40) {

                solution.copyFrom(bestSolution);
                tracker.rebuildFrom(solution);
                roundsSinceImprovement = 0;
            }
        }
        return new Result(bestSolution, bestScore, sweeps, kicks, improvements);
    }

    public static int[][] neighboringCaches(FlatProblem problem) {
        int cacheCount = problem.cacheCount;
        int[][] neighborsOfCache = new int[cacheCount][];
        int[] lastVisitedFrom = new int[cacheCount];
        Arrays.fill(lastVisitedFrom, -1);
        int[] neighborBuffer = new int[cacheCount];
        for (int cache = 0; cache < cacheCount; cache++) {
            int neighborCount = 0;
            lastVisitedFrom[cache] = cache;
            for (int i = problem.cacheLinkStart[cache]; i < problem.cacheLinkStart[cache + 1]; i++) {
                int endpoint = problem.cacheLinkedEndpoint[i];
                for (int link = problem.endpointLinkStart[endpoint]; link < problem.endpointLinkStart[endpoint + 1]; link++) {
                    int otherCache = problem.linkedCache[link];
                    if (lastVisitedFrom[otherCache] != cache) { lastVisitedFrom[otherCache] = cache; neighborBuffer[neighborCount++] = otherCache; }
                }
            }
            neighborsOfCache[cache] = Arrays.copyOf(neighborBuffer, neighborCount);
        }
        return neighborsOfCache;
    }

    public static int KICK_MAX = Integer.getInteger("ils.kick", 3);
    public static int LOCAL    = Integer.getInteger("ils.local", 3);

    public static double TEMP  = Double.parseDouble(System.getProperty("ils.temp", "0"));

    public static double NOISE = Double.parseDouble(System.getProperty("ils.noise", "0.2"));
    public static int REPAIR   = Integer.getInteger("ils.repair", 2);

    public static Result run(FlatProblem problem, Solution startSolution, long budgetMs, long seed,
                             boolean verbose, int[][] neighborsOfCache) {
        return run(problem, startSolution, budgetMs, seed, verbose, neighborsOfCache, null);
    }

    public static int COOP_MIN_CACHES = 32;

    public static Result runWalkParallel(FlatProblem problem, Solution startSolution, long budgetMs,
                                         int threadCount, boolean verbose) throws InterruptedException {
        final Result[] results = new Result[threadCount];
        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> results[threadId] = runSingleWalk(problem, startSolution, budgetMs, 12345L + threadId * 7919L, false));
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();
        Result best = results[0];
        for (Result result : results) if (result != null && (best == null || result.score > best.score)) best = result;
        if (verbose) {
            StringBuilder builder = new StringBuilder("    graines : ");
            for (Result result : results) builder.append(result == null ? "-" : String.format("%,d ", result.score));
            System.out.println(builder);
        }
        return best;
    }

    public static long ROUND_MS = 300;

    static final class Change {
        final int[] caches; final int[][] contents;
        Change(int[] caches, int[][] contents) { this.caches = caches; this.contents = contents; }
    }

    public static final class CooperativeMaster {
        final Solution masterSolution; final BestLatencyTracker masterTracker; final BlockKnapsack masterKnapsack;
        long masterScore;
        long appliedChanges, refusedChanges;
        final AtomicLong totalKicks = new AtomicLong();

        CooperativeMaster(FlatProblem problem, Solution startSolution) {
            masterSolution = startSolution.copy();
            masterTracker = new BestLatencyTracker(problem);
            masterTracker.rebuildFrom(masterSolution);
            masterKnapsack = new BlockKnapsack(problem, masterSolution, masterTracker);
            masterScore = masterTracker.score();
        }

        synchronized long score() { return masterScore; }

        synchronized long sync(ArrayList<Change> changeLog, Solution walkerSolution, BestLatencyTracker walkerTracker) {
            for (Change change : changeLog) {
                int cacheCount = change.caches.length;
                int[][] previousContents = new int[cacheCount][];
                for (int i = 0; i < cacheCount; i++) {
                    int[] buffer = new int[masterSolution.problem.videoCount];
                    previousContents[i] = Arrays.copyOf(buffer, masterKnapsack.snapshot(change.caches[i], buffer));
                }
                for (int i = 0; i < cacheCount; i++) masterKnapsack.restore(change.caches[i], change.contents[i], change.contents[i].length);
                long scoreAfterChange = masterTracker.score();
                if (scoreAfterChange >= masterScore) { masterScore = scoreAfterChange; appliedChanges++; }
                else {
                    for (int i = 0; i < cacheCount; i++) masterKnapsack.restore(change.caches[i], previousContents[i], previousContents[i].length);
                    refusedChanges++;
                }
            }
            changeLog.clear();
            walkerSolution.copyFrom(masterSolution);
            walkerTracker.copyFrom(masterTracker);
            return masterScore;
        }
    }

    public static Result run(FlatProblem problem, Solution startSolution, long budgetMs, long seed,
                             boolean verbose, int[][] neighborsOfCache, CooperativeMaster master) {
        Random rng = new Random(seed);
        long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;

        Solution solution = startSolution.copy();
        BestLatencyTracker tracker = new BestLatencyTracker(problem);
        tracker.rebuildFrom(solution);
        BlockKnapsack knapsack = new BlockKnapsack(problem, solution, tracker);

        int sweeps = 0, kicks = 0, improvements = 0;

        long currentScore = master != null ? tracker.score() : descendBySweeps(problem, solution, tracker, knapsack, rng, deadlineNanos, 1, verbose);
        sweeps += 1;
        Solution bestSolution = solution.copy();
        long bestScore = currentScore;

        final ArrayList<Change> changeLog = master != null ? new ArrayList<>() : null;
        long lastLoggedScore = currentScore;
        boolean[] isCacheDirty = new boolean[problem.cacheCount];
        int[] dirtyCaches = new int[problem.cacheCount];
        int dirtyCount = 0;
        long nextSyncNanos = System.nanoTime() + ROUND_MS * 1_000_000L;

        final int cacheCount = problem.cacheCount;
        final int neighborhoodLimit = Math.min(cacheCount, LOCAL);
        int[] touchedCaches = new int[neighborhoodLimit], sweepOrder = new int[neighborhoodLimit];
        int[][] cacheSnapshots = new int[neighborhoodLimit][problem.videoCount];
        int[] snapshotSizes = new int[neighborhoodLimit];

        while (System.nanoTime() < deadlineNanos) {

            int seedCache = rng.nextInt(cacheCount);
            int[] neighborsOfSeed = neighborsOfCache[seedCache];
            int kickSize = 1 + rng.nextInt(Math.min(KICK_MAX, neighborhoodLimit));
            int touchedCount = 0;
            touchedCaches[touchedCount++] = seedCache;
            for (int attempt = 0; touchedCount < kickSize && attempt < 4 * neighborhoodLimit && neighborsOfSeed.length > 0; attempt++) {
                int cache = neighborsOfSeed[rng.nextInt(neighborsOfSeed.length)];
                if (!arrayContains(touchedCaches, touchedCount, cache)) touchedCaches[touchedCount++] = cache;
            }
            int kickedCount = touchedCount;
            int targetTouchedCount = Math.min(neighborhoodLimit, neighborsOfSeed.length + 1);
            for (int attempt = 0; touchedCount < targetTouchedCount && attempt < 8 * neighborhoodLimit; attempt++) {
                int cache = neighborsOfSeed[rng.nextInt(neighborsOfSeed.length)];
                if (!arrayContains(touchedCaches, touchedCount, cache)) touchedCaches[touchedCount++] = cache;
            }
            kicks++;

            for (int i = 0; i < touchedCount; i++) snapshotSizes[i] = knapsack.snapshot(touchedCaches[i], cacheSnapshots[i]);
            for (int i = 0; i < kickedCount; i++) knapsack.clearCacheAndUpdate(touchedCaches[i]);
            if (NOISE > 0) {

                System.arraycopy(touchedCaches, 0, sweepOrder, 0, kickedCount);
                shuffle(sweepOrder, kickedCount, rng);
                for (int i = 0; i < kickedCount; i++) knapsack.optimizeCache(sweepOrder[i], NOISE, rng);
            }
            System.arraycopy(touchedCaches, 0, sweepOrder, 0, touchedCount);
            for (int repairPass = 0; repairPass < (NOISE > 0 ? REPAIR : 1); repairPass++) {
                shuffle(sweepOrder, touchedCount, rng);
                knapsack.sweep(sweepOrder, touchedCount, Long.MAX_VALUE);
            }
            sweeps++;
            long newScore = tracker.score();

            boolean accepted = newScore >= currentScore;
            if (!accepted && TEMP > 0) accepted = rng.nextDouble() < Math.exp((newScore - currentScore) / (TEMP * currentScore));

            if (accepted) {
                if (newScore > currentScore) improvements++;
                currentScore = newScore;
                for (int i = 0; i < touchedCount; i++)
                    if (!isCacheDirty[touchedCaches[i]]) { isCacheDirty[touchedCaches[i]] = true; dirtyCaches[dirtyCount++] = touchedCaches[i]; }
                if (newScore > lastLoggedScore) {
                    lastLoggedScore = newScore;
                    if (changeLog != null) {
                        changeLog.add(captureChange(knapsack, dirtyCaches, dirtyCount, problem.videoCount));
                        for (int i = 0; i < dirtyCount; i++) isCacheDirty[dirtyCaches[i]] = false;
                        dirtyCount = 0;
                    }
                }
                if (master == null && newScore > bestScore) {
                    bestScore = newScore;
                    bestSolution.copyFrom(solution);
                    if (verbose) System.out.printf("    kick %d -> %,d%n", kicks, newScore);
                }
            } else {

                for (int i = 0; i < touchedCount; i++) knapsack.restore(touchedCaches[i], cacheSnapshots[i], snapshotSizes[i]);
            }

            if (master != null && System.nanoTime() >= nextSyncNanos) {
                currentScore = master.sync(changeLog, solution, tracker);
                lastLoggedScore = currentScore;
                for (int i = 0; i < dirtyCount; i++) isCacheDirty[dirtyCaches[i]] = false;
                dirtyCount = 0;
                nextSyncNanos = System.nanoTime() + ROUND_MS * 1_000_000L;
            }
        }
        if (master != null) {
            currentScore = master.sync(changeLog, solution, tracker);
            master.totalKicks.addAndGet(kicks);
            return new Result(solution, currentScore, sweeps, kicks, improvements);
        }
        return new Result(bestSolution, bestScore, sweeps, kicks, improvements);
    }

    private static Change captureChange(BlockKnapsack knapsack, int[] dirtyCaches, int dirtyCount, int videoCount) {
        int[] caches = Arrays.copyOf(dirtyCaches, dirtyCount);
        int[][] contents = new int[dirtyCount][];
        int[] buffer = new int[videoCount];
        for (int i = 0; i < dirtyCount; i++) contents[i] = Arrays.copyOf(buffer, knapsack.snapshot(dirtyCaches[i], buffer));
        return new Change(caches, contents);
    }

    private static boolean arrayContains(int[] array, int length, int value) {
        for (int i = 0; i < length; i++) if (array[i] == value) return true;
        return false;
    }

    public static Result runParallel(FlatProblem problem, Solution startSolution, long budgetMs,
                                     int threadCount, boolean verbose) throws InterruptedException {
        if (problem.cacheCount <= COOP_MIN_CACHES) return runWalkParallel(problem, startSolution, budgetMs, threadCount, verbose);
        final int[][] neighborsOfCache = neighboringCaches(problem);
        final CooperativeMaster master = new CooperativeMaster(problem, startSolution);
        final Solution[] startSolutions = new Solution[threadCount];
        for (int t = 0; t < threadCount; t++) startSolutions[t] = master.masterSolution.copy();
        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() ->
                run(problem, startSolutions[threadId], budgetMs, 12345L + threadId * 7919L, false, neighborsOfCache, master));
            threads[t].start();
        }
        if (verbose) {
            long startNanos = System.nanoTime();
            while (System.nanoTime() - startNanos < budgetMs * 1_000_000L) {
                Thread.sleep(Math.min(5000, Math.max(1, budgetMs - (System.nanoTime() - startNanos) / 1_000_000L)));
                System.out.printf("    t=%4.0fs  score=%,d%n", (System.nanoTime() - startNanos) / 1e9, master.score());
            }
        }
        for (Thread thread : threads) thread.join();
        if (verbose)
            System.out.printf("    fusion : %d kicks appliques, %d refuses (conflit)%n", master.appliedChanges, master.refusedChanges);
        return new Result(master.masterSolution, master.masterScore, 0, (int) master.totalKicks.get(), (int) master.appliedChanges);
    }

    private static void shuffle(int[] array, Random rng) { shuffle(array, array.length, rng); }

    private static void shuffle(int[] array, int length, Random rng) {
        for (int i = length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int swapped = array[i]; array[i] = array[j]; array[j] = swapped;
        }
    }
}
