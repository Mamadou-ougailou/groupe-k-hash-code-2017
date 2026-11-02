package solver.impl;

import model.Cache;
import model.Endpoint;
import model.Problem;
import model.RequestDescription;
import model.Video;
import solver.Solver;
import solver.ils.BestLatencyTracker;
import solver.ils.BinPacking;
import solver.ils.BlockKnapsack;
import solver.ils.FlatProblem;
import solver.ils.LazyGreedyPlacement;
import solver.ils.IteratedLocalSearch;
import solver.ils.Scorer;
import solver.ils.Solution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Adaptateur vers le solveur du paquet solver.ils (voir docs/ils-knapsack.md) :
 *   glouton CELF -> descente par blocs (sac a dos exact) -> ILS parallele,
 *   ou bin-packing exact si l'instance est degeneree (trending_today).
 *
 * Reglages (proprietes systeme) :
 *   -Dils.seconds=60   budget total en secondes
 *   -Dils.threads=N    threads pour l'ILS (defaut : coeurs - 2)
 *
 * Chaque etape est verifiee : capacite respectee ET score incremental == scorer independant.
 */
public class GreedyKnapsackIlsSolver implements Solver {

    private final long budgetSeconds;
    private final int threadCount;

    public GreedyKnapsackIlsSolver() {
        this(Long.getLong("ils.seconds", 60),
             Integer.getInteger("ils.threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 2)));
    }

    public GreedyKnapsackIlsSolver(long budgetSeconds, int threadCount) {
        this.budgetSeconds = budgetSeconds;
        this.threadCount = threadCount;
    }

    @Override
    public void solve(Problem problem) {
        FlatProblem flatProblem = new FlatProblem(toTokens(problem));
//        System.out.printf("ILS : budget = %d s, threads = %d%n", budgetSeconds, threadCount);
//        System.out.print("  "); flatProblem.printSummary();
        long upperBound = Scorer.upperBound(flatProblem);

        Solution solution;
        if (BinPacking.isDegenerate(flatProblem)) {
            System.out.println("  structure degeneree -> bin-packing exact");
            solution = BinPacking.solve(flatProblem, budgetSeconds * 1000L, true);
        } else {
            final long endNanos = System.nanoTime() + budgetSeconds * 1_000_000_000L;
            BestLatencyTracker tracker = new BestLatencyTracker(flatProblem);
            solution = LazyGreedyPlacement.run(flatProblem, tracker, false);
            solution.validateOrThrow();
            long greedyScore = Scorer.score(flatProblem, solution);
            check(tracker.score(), greedyScore, "glouton");
//            System.out.printf("  [1] glouton           = %,d%n", greedyScore);

            BlockKnapsack knapsack = new BlockKnapsack(flatProblem, solution, tracker);
            long incrementalDescentScore = IteratedLocalSearch.descendBySweeps(flatProblem, solution, tracker, knapsack, new Random(1),
                    System.nanoTime() + (endNanos - System.nanoTime()) / 3, 30, false);
            solution.validateOrThrow();
            long descentScore = Scorer.score(flatProblem, solution);
            check(incrementalDescentScore, descentScore, "descente");
            if (descentScore < greedyScore)
                throw new IllegalStateException("MONOTONIE VIOLEE : " + greedyScore + " -> " + descentScore);
//            System.out.printf("  [3] + sac a dos exact = %,d%n", descentScore);

            IteratedLocalSearch.Result ilsResult;
            try {
                // l'ILS recupere tout le temps que la descente n'a pas utilise
                ilsResult = IteratedLocalSearch.runParallel(flatProblem, solution, Math.max(1, (endNanos - System.nanoTime()) / 1_000_000L), threadCount, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ILS interrompu", e);
            }
            ilsResult.solution.validateOrThrow();
            long ilsScore = Scorer.score(flatProblem, ilsResult.solution);
            check(ilsResult.score, ilsScore, "ILS");
            if (ilsScore >= descentScore) solution = ilsResult.solution;
//            System.out.printf("  [4] + ILS (%d kicks)  = %,d%n", ilsResult.kicks, Math.max(descentScore, ilsScore));
        }

        solution.validateOrThrow();
        long score = Scorer.score(flatProblem, solution);
//        System.out.printf("  >>> score = %,d   (borne sup %,d - %.1f%%)%n", score, upperBound, 100.0 * score / upperBound);
        writeBack(problem, flatProblem, solution);
    }

    /** Re-serialise le modele objet au format du fichier .in (connexions triees par id de cache). */
    private static int[] toTokens(Problem problem) {
        List<Video> videos = problem.getVideos();
        List<Endpoint> endpoints = problem.getEndpoints();
        List<RequestDescription> requests = problem.getRequests();

        int linkCount = 0;
        for (Endpoint endpoint : endpoints) linkCount += endpoint.getCacheLatencies().size();
        int[] tokens = new int[5 + videos.size() + 2 * endpoints.size() + 2 * linkCount + 3 * requests.size()];
        int writeIndex = 0;
        tokens[writeIndex++] = videos.size();
        tokens[writeIndex++] = endpoints.size();
        tokens[writeIndex++] = requests.size();
        tokens[writeIndex++] = problem.getCaches().size();
        tokens[writeIndex++] = problem.getCacheCapacity();
        for (Video video : videos) tokens[writeIndex++] = video.getSize();
        for (Endpoint endpoint : endpoints) {
            tokens[writeIndex++] = endpoint.getDataCenterLatency();
            tokens[writeIndex++] = endpoint.getCacheLatencies().size();
            List<Map.Entry<Cache, Integer>> links = new ArrayList<>(endpoint.getCacheLatencies().entrySet());
            links.sort((first, second) -> Integer.compare(first.getKey().getId(), second.getKey().getId()));
            for (Map.Entry<Cache, Integer> link : links) {
                tokens[writeIndex++] = link.getKey().getId();
                tokens[writeIndex++] = link.getValue();
            }
        }
        for (RequestDescription request : requests) {
            tokens[writeIndex++] = request.getVideo().getId();
            tokens[writeIndex++] = request.getEndpoint().getId();
            tokens[writeIndex++] = request.getNumberOfRequests();
        }
        return tokens;
    }

    /** Recopie le contenu des caches dans le modele objet, pour OutputWriter. */
    private static void writeBack(Problem problem, FlatProblem flatProblem, Solution solution) {
        List<Video> videos = problem.getVideos();
        for (Cache cache : problem.getCaches()) {
            if (!cache.getCachedVideos().isEmpty())
                throw new IllegalStateException("le cache " + cache.getId() + " n'est pas vide");
            for (int video = 0; video < flatProblem.videoCount; video++)
                if (solution.contains(cache.getId(), video)) cache.addVideo(videos.get(video));
        }
    }

    private static void check(long incrementalScore, long independentScore, String phase) {
        if (incrementalScore != independentScore)
            throw new IllegalStateException("INCOHERENCE apres " + phase +
                    " : carnet=" + incrementalScore + " scorer=" + independentScore);
    }
}
