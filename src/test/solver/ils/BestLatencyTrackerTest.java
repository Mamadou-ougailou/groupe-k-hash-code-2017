package test.solver.ils;

import solver.ils.BestLatencyTracker;
import solver.ils.FlatProblem;
import solver.ils.Scorer;
import solver.ils.Solution;
import test.TestSupport;

import java.util.Random;

import static test.TestSupport.check;
import static test.TestSupport.eq;

public final class BestLatencyTrackerTest {

    private BestLatencyTrackerTest() {}

    /** Retire une vidéo d'un cache et met à jour le suivi, comme le fait le solveur. */
    private static void removeVideo(FlatProblem p, Solution s, BestLatencyTracker tracker, int cache, int video) {
        s.remove(cache, video);
        for (int i = p.videoRequestStart[video]; i < p.videoRequestStart[video + 1]; i++) {
            int request = p.videoRequestIds[i];
            if (p.latency(p.requestEndpoint[request], cache) >= 0) tracker.recomputeRequest(request, s);
        }
    }

    public static void run(TestSupport t) {
        t.module("solver/ils : BestLatencyTracker");

        t.test("sans aucune vidéo en cache, le score est 0", () -> {
            FlatProblem p = TestSupport.example();
            eq(0, new BestLatencyTracker(p).score(), "score initial");
        });

        t.test("ajouter des vidéos : 562 500 sur l'exemple", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            BestLatencyTracker tracker = new BestLatencyTracker(p);
            s.add(0, 3); tracker.onVideoAdded(0, 3);
            s.add(0, 1); tracker.onVideoAdded(0, 1);
            eq(562_500, tracker.score(), "score incrémental");
            eq(Scorer.score(p, s), tracker.score(), "score recalculé");
        });

        t.test("score incrémental = score recalculé après chaque ajout ou retrait (150 instances aléatoires)", () -> {
            Random rng = new Random(11);
            for (int i = 0; i < 150; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                Solution s = new Solution(p);
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                for (int step = 0; step < 30; step++) {
                    int c = rng.nextInt(p.cacheCount), v = rng.nextInt(p.videoCount);
                    if (s.contains(c, v)) removeVideo(p, s, tracker, c, v);
                    else if (s.add(c, v)) tracker.onVideoAdded(c, v);
                    eq(Scorer.score(p, s), tracker.score(), "instance " + i + ", étape " + step);
                }
                BestLatencyTracker fresh = new BestLatencyTracker(p);
                fresh.rebuildFrom(s);
                eq(fresh.bestLatency, tracker.bestLatency, "meilleures latences (instance " + i + ")");
                eq(fresh.secondBestLatency, tracker.secondBestLatency, "deuxièmes meilleures latences (instance " + i + ")");
            }
        });

        t.test("rebuildFrom reconstruit le suivi depuis une solution", () -> {
            Random rng = new Random(12);
            for (int i = 0; i < 100; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                Solution s = TestSupport.randomSolution(p, rng);
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                tracker.rebuildFrom(s);
                eq(Scorer.score(p, s), tracker.score(), "score après rebuildFrom (instance " + i + ")");
            }
        });

        t.test("reset remet tout au data center", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            BestLatencyTracker tracker = new BestLatencyTracker(p);
            s.add(0, 3); tracker.onVideoAdded(0, 3);
            tracker.reset();
            eq(0, tracker.score(), "score après reset");
            for (int r = 0; r < p.requestCount; r++) {
                eq(-1, tracker.bestCache[r], "aucun cache n'est le meilleur (requête " + r + ")");
                eq(p.dataCenterLatency[p.requestEndpoint[r]], tracker.bestLatency[r], "latence data center (requête " + r + ")");
            }
        });

        t.test("copyFrom recopie l'état d'un autre suivi", () -> {
            Random rng = new Random(13);
            FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
            Solution s = TestSupport.randomSolution(p, rng);
            BestLatencyTracker a = new BestLatencyTracker(p), b = new BestLatencyTracker(p);
            a.rebuildFrom(s);
            b.copyFrom(a);
            eq(a.score(), b.score(), "score après copyFrom");
            eq(a.bestLatency, b.bestLatency, "meilleures latences");
        });

        t.test("bestLatencyWithoutCache = meilleure latence si on retirait ce cache", () -> {
            Random rng = new Random(14);
            for (int i = 0; i < 100; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                Solution s = TestSupport.randomSolution(p, rng);
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                tracker.rebuildFrom(s);
                for (int r = 0; r < p.requestCount; r++) {
                    int e = p.requestEndpoint[r], v = p.requestVideo[r];
                    for (int c = 0; c < p.cacheCount; c++) {
                        int expected = p.dataCenterLatency[e];
                        for (int other = 0; other < p.cacheCount; other++) {
                            int lat = p.latency(e, other);
                            if (other != c && lat >= 0 && s.contains(other, v)) expected = Math.min(expected, lat);
                        }
                        eq(expected, tracker.bestLatencyWithoutCache(r, c), "instance " + i + ", requête " + r + ", cache " + c);
                    }
                }
            }
        });

        t.test("une vidéo absente des caches ne change aucune latence", () -> {
            FlatProblem p = TestSupport.example();
            BestLatencyTracker tracker = new BestLatencyTracker(p);
            tracker.onVideoAdded(0, 2); // la vidéo 2 n'est demandée par personne
            check(tracker.score() == 0, "ajouter une vidéo que personne ne demande ne rapporte rien");
        });
    }
}
