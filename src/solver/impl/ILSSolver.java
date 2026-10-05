package solver.impl;

import model.Cache;
import model.Endpoint;
import model.Problem;
import model.RequestDescription;
import model.Video;
import solver.Solver;
import solver.ils.BinPacking;
import solver.ils.BlockKnapsack;
import solver.ils.Book;
import solver.ils.Greedy;
import solver.ils.ILS;
import solver.ils.Instance;
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
public class ILSSolver implements Solver {

    private final long budgetS;
    private final int threads;

    public ILSSolver() {
        this(Long.getLong("ils.seconds", 60),
             Integer.getInteger("ils.threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 2)));
    }

    public ILSSolver(long budgetS, int threads) {
        this.budgetS = budgetS;
        this.threads = threads;
    }

    @Override
    public void solve(Problem problem) {
        Instance in = new Instance(toTokens(problem));
        System.out.printf("ILS : budget = %d s, threads = %d%n", budgetS, threads);
        System.out.print("  "); in.printSummary();
        long ub = Scorer.upperBound(in);

        Solution sol;
        if (BinPacking.isDegenerate(in)) {
            System.out.println("  structure degeneree -> bin-packing exact");
            sol = BinPacking.solve(in, budgetS * 1000L, true);
        } else {
            final long end = System.nanoTime() + budgetS * 1_000_000_000L;
            Book book = new Book(in);
            sol = Greedy.run(in, book, false);
            sol.validateOrThrow();
            long greedy = Scorer.score(in, sol);
            check(book.score(), greedy, "glouton");
            System.out.printf("  [1] glouton           = %,d%n", greedy);

            BlockKnapsack bk = new BlockKnapsack(in, sol, book);
            long desc = ILS.descend(in, sol, book, bk, new Random(1),
                    System.nanoTime() + (end - System.nanoTime()) / 3, 30, false);
            sol.validateOrThrow();
            long descScore = Scorer.score(in, sol);
            check(desc, descScore, "descente");
            if (descScore < greedy)
                throw new IllegalStateException("MONOTONIE VIOLEE : " + greedy + " -> " + descScore);
            System.out.printf("  [3] + sac a dos exact = %,d%n", descScore);

            ILS.Result res;
            try {
                // l'ILS recupere tout le temps que la descente n'a pas utilise
                res = ILS.runParallel(in, sol, Math.max(1, (end - System.nanoTime()) / 1_000_000L), threads, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("ILS interrompu", e);
            }
            res.solution.validateOrThrow();
            long ilsScore = Scorer.score(in, res.solution);
            check(res.score, ilsScore, "ILS");
            if (ilsScore >= descScore) sol = res.solution;
            System.out.printf("  [4] + ILS (%d kicks)  = %,d%n", res.kicks, Math.max(descScore, ilsScore));
        }

        sol.validateOrThrow();
        long score = Scorer.score(in, sol);
        System.out.printf("  >>> score = %,d   (borne sup %,d - %.1f%%)%n", score, ub, 100.0 * score / ub);
        writeBack(problem, in, sol);
    }

    /** Re-serialise le modele objet au format du fichier .in (connexions triees par id de cache). */
    private static int[] toTokens(Problem p) {
        List<Video> videos = p.getVideos();
        List<Endpoint> endpoints = p.getEndpoints();
        List<RequestDescription> requests = p.getRequests();

        int conn = 0;
        for (Endpoint e : endpoints) conn += e.getCacheLatencies().size();
        int[] t = new int[5 + videos.size() + 2 * endpoints.size() + 2 * conn + 3 * requests.size()];
        int i = 0;
        t[i++] = videos.size();
        t[i++] = endpoints.size();
        t[i++] = requests.size();
        t[i++] = p.getCaches().size();
        t[i++] = p.getCacheCapacity();
        for (Video v : videos) t[i++] = v.getSize();
        for (Endpoint e : endpoints) {
            t[i++] = e.getDataCenterLatency();
            t[i++] = e.getCacheLatencies().size();
            List<Map.Entry<Cache, Integer>> links = new ArrayList<>(e.getCacheLatencies().entrySet());
            links.sort((a, b) -> Integer.compare(a.getKey().getId(), b.getKey().getId()));
            for (Map.Entry<Cache, Integer> l : links) {
                t[i++] = l.getKey().getId();
                t[i++] = l.getValue();
            }
        }
        for (RequestDescription r : requests) {
            t[i++] = r.getVideo().getId();
            t[i++] = r.getEndpoint().getId();
            t[i++] = r.getNumberOfRequests();
        }
        return t;
    }

    /** Recopie le contenu des caches dans le modele objet, pour OutputWriter. */
    private static void writeBack(Problem p, Instance in, Solution sol) {
        List<Video> videos = p.getVideos();
        for (Cache cache : p.getCaches()) {
            if (!cache.getCachedVideos().isEmpty())
                throw new IllegalStateException("le cache " + cache.getId() + " n'est pas vide");
            for (int v = 0; v < in.V; v++)
                if (sol.has(cache.getId(), v)) cache.addVideo(videos.get(v));
        }
    }

    private static void check(long incremental, long fromScorer, String phase) {
        if (incremental != fromScorer)
            throw new IllegalStateException("INCOHERENCE apres " + phase +
                    " : carnet=" + incremental + " scorer=" + fromScorer);
    }
}
