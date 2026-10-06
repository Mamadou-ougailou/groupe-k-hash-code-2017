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
 *   -Dils.seconds=60        budget total en secondes
 *   -Dils.threads=N         threads pour l'ILS (defaut : coeurs - 2)
 *   -Dils.descentShare=0.33 part du budget laissee a la descente par blocs
 *   -Dils.sweeps=30         passes maximum de la descente
 *   -Dils.trace=chemin.json trace machine de l'execution (scores par phase, kicks)
 *
 * Voir aussi ILS.KICK_MAX, LOCAL, TEMP, NOISE, REPAIR (prefixe ils.).
 *
 * Chaque etape est verifiee : capacite respectee ET score incremental == scorer independant.
 */
public class ILSSolver implements Solver {

    private final long budgetS;
    private final int threads;
    private final double descentShare;
    private final int maxSweeps;
    private final String tracePath;

    public ILSSolver() {
        this(Long.getLong("ils.seconds", 60),
             Integer.getInteger("ils.threads", Math.max(1, Runtime.getRuntime().availableProcessors() - 2)));
    }

    public ILSSolver(long budgetS, int threads) {
        this.budgetS = budgetS;
        this.threads = threads;
        this.descentShare = Double.parseDouble(System.getProperty("ils.descentShare", "0.3333"));
        this.maxSweeps = Integer.getInteger("ils.sweeps", 30);
        this.tracePath = System.getProperty("ils.trace");
    }

    @Override
    public void solve(Problem problem) {
        Instance in = new Instance(toTokens(problem));
        System.out.printf("ILS : budget = %d s, threads = %d%n", budgetS, threads);
        System.out.print("  "); in.printSummary();
        long ub = Scorer.upperBound(in);

        Trace trace = new Trace(in, ub, budgetS, threads, descentShare, maxSweeps);
        long t0 = System.nanoTime();

        Solution sol;
        if (BinPacking.isDegenerate(in)) {
            System.out.println("  structure degeneree -> bin-packing exact");
            trace.degenerate = true;
            sol = BinPacking.solve(in, budgetS * 1000L, true);
            trace.phase("bin-packing", Scorer.score(in, sol), t0);
        } else {
            final long end = System.nanoTime() + budgetS * 1_000_000_000L;
            Book book = new Book(in);
            sol = Greedy.run(in, book, false);
            sol.validateOrThrow();
            long greedy = Scorer.score(in, sol);
            check(book.score(), greedy, "glouton");
            System.out.printf("  [1] glouton           = %,d%n", greedy);
            long tGreedy = trace.phase("glouton", greedy, t0);

            BlockKnapsack bk = new BlockKnapsack(in, sol, book);
            long descentDeadline = System.nanoTime()
                    + (long) ((end - System.nanoTime()) * descentShare);
            long desc = ILS.descend(in, sol, book, bk, new Random(1),
                    descentDeadline, maxSweeps, false);
            sol.validateOrThrow();
            long descScore = Scorer.score(in, sol);
            check(desc, descScore, "descente");
            if (descScore < greedy)
                throw new IllegalStateException("MONOTONIE VIOLEE : " + greedy + " -> " + descScore);
            System.out.printf("  [3] + sac a dos exact = %,d%n", descScore);
            long tDescent = trace.phase("descente", descScore, tGreedy);

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
            trace.phase("ils", Math.max(descScore, ilsScore), tDescent);
            trace.kicks = res.kicks;
            trace.sweeps = res.sweeps;
            trace.improvements = res.improvements;
        }

        sol.validateOrThrow();
        long score = Scorer.score(in, sol);
        System.out.printf("  >>> score = %,d   (borne sup %,d - %.1f%%)%n", score, ub, 100.0 * score / ub);
        trace.finalScore = score;
        trace.write(tracePath);
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

    /**
     * Trace machine d'une execution : ce que les statistiques de pilotage
     * consomment pour decider des reglages de la run suivante.
     */
    private static final class Trace {
        private final Instance in;
        private final long upperBound, budgetS;
        private final int threads, maxSweeps;
        private final double descentShare;
        private final StringBuilder phases = new StringBuilder();
        boolean degenerate;
        int kicks, sweeps, improvements;
        long finalScore;

        Trace(Instance in, long upperBound, long budgetS, int threads,
              double descentShare, int maxSweeps) {
            this.in = in;
            this.upperBound = upperBound;
            this.budgetS = budgetS;
            this.threads = threads;
            this.descentShare = descentShare;
            this.maxSweeps = maxSweeps;
        }

        /** Enregistre une phase et rend l'instant courant, a passer a la suivante. */
        long phase(String name, long score, long sinceNs) {
            long now = System.nanoTime();
            if (phases.length() > 0) phases.append(",\n");
            phases.append(String.format(java.util.Locale.ROOT,
                    "    {\"nom\": \"%s\", \"score\": %d, \"secondes\": %.3f}",
                    name, score, (now - sinceNs) / 1e9));
            return now;
        }

        void write(String path) {
            if (path == null) return;
            String json = String.format(java.util.Locale.ROOT,
                    "{%n" +
                    "  \"dimensions\": {\"V\": %d, \"E\": %d, \"R\": %d, \"C\": %d, \"X\": %d},%n" +
                    "  \"borne_superieure\": %d,%n" +
                    "  \"degeneree\": %b,%n" +
                    "  \"parametres\": {\"seconds\": %d, \"threads\": %d, \"descentShare\": %.4f, " +
                    "\"sweeps\": %d, \"kick\": %d, \"local\": %d, \"temp\": %.4f, \"noise\": %.4f, \"repair\": %d},%n" +
                    "  \"phases\": [%n%s%n  ],%n" +
                    "  \"ils\": {\"kicks\": %d, \"sweeps\": %d, \"improvements\": %d},%n" +
                    "  \"score_final\": %d,%n" +
                    "  \"part_borne_superieure\": %.4f%n" +
                    "}%n",
                    in.V, in.E, in.R, in.C, in.X,
                    upperBound, degenerate,
                    budgetS, threads, descentShare, maxSweeps,
                    ILS.KICK_MAX, ILS.LOCAL, ILS.TEMP, ILS.NOISE, ILS.REPAIR,
                    phases, kicks, sweeps, improvements,
                    finalScore, upperBound > 0 ? (double) finalScore / upperBound : 0.0);
            try {
                java.nio.file.Path dest = java.nio.file.Paths.get(path);
                java.nio.file.Path parent = dest.getParent();
                if (parent != null) java.nio.file.Files.createDirectories(parent);
                java.nio.file.Files.writeString(dest, json);
                System.out.println("  trace : " + path);
            } catch (java.io.IOException e) {
                System.err.println("  trace non ecrite (" + e.getMessage() + ")");
            }
        }
    }
}
