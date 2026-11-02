package test.solver.ils;

import solver.ils.BestLatencyTracker;
import solver.ils.BlockKnapsack;
import solver.ils.FlatProblem;
import solver.ils.Scorer;
import solver.ils.Solution;
import test.TestSupport;

import java.util.Random;

import static test.TestSupport.check;
import static test.TestSupport.eq;

public final class BlockKnapsackTest {

    private BlockKnapsackTest() {}

    public static void run(TestSupport t) {
        t.module("solver/ils : BlockKnapsack");

        t.test("optimizeCache est exact : égale la force brute sur le contenu du cache (200 instances, tous les caches)", () -> {
            Random rng = new Random(31);
            for (int i = 0; i < 200; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                Solution s = TestSupport.randomSolution(p, rng);
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                tracker.rebuildFrom(s);
                BlockKnapsack knapsack = new BlockKnapsack(p, s, tracker);
                for (int c = 0; c < p.cacheCount; c++) {
                    long before = Scorer.score(p, s);
                    long best = TestSupport.bestScoreChangingOnlyCache(p, s, c);
                    knapsack.optimizeCache(c);
                    s.validateOrThrow();
                    long after = Scorer.score(p, s);
                    eq(best, after, "optimum du cache " + c + " (instance " + i + ")");
                    check(after >= before, "le score ne baisse pas (instance " + i + ", cache " + c + ")");
                    eq(after, tracker.score(), "suivi cohérent (instance " + i + ", cache " + c + ")");
                }
            }
        });

        t.test("le glouton peut se tromper, le sac à dos non (cache 1 de l'exemple jouet)", () -> {
            // gain par Mo : B (3 Mo) > C (4 Mo), mais A + C (8 Mo) vaut plus que A + B (7 Mo)
            FlatProblem p = TestSupport.toy();
            Solution s = new Solution(p);
            s.add(0, 0); s.add(0, 1); s.add(1, 2); s.add(2, 3);
            BestLatencyTracker tracker = new BestLatencyTracker(p);
            tracker.rebuildFrom(s);
            eq(51_304, Scorer.score(p, s), "point de départ = résultat du placement glouton");
            new BlockKnapsack(p, s, tracker).optimizeCache(0);
            check(s.contains(0, 0) && s.contains(0, 2) && !s.contains(0, 1), "le cache 0 passe de {A, B} à {A, C}");
            eq(53_043, Scorer.score(p, s), "score après le sac à dos");
        });

        t.test("clearCacheAndUpdate vide le cache, renvoie le nombre de vidéos retirées et garde le suivi cohérent", () -> {
            Random rng = new Random(32);
            for (int i = 0; i < 100; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                Solution s = TestSupport.randomSolution(p, rng);
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                tracker.rebuildFrom(s);
                BlockKnapsack knapsack = new BlockKnapsack(p, s, tracker);
                int c = rng.nextInt(p.cacheCount), expected = 0;
                for (int v = 0; v < p.videoCount; v++) if (s.contains(c, v)) expected++;
                eq(expected, knapsack.clearCacheAndUpdate(c), "vidéos retirées (instance " + i + ")");
                eq(0, s.usedCapacity[c], "cache vide (instance " + i + ")");
                eq(Scorer.score(p, s), tracker.score(), "suivi cohérent (instance " + i + ")");
            }
        });

        t.test("snapshot puis restore rend exactement le contenu d'origine", () -> {
            Random rng = new Random(33);
            for (int i = 0; i < 100; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                Solution s = TestSupport.randomSolution(p, rng);
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                tracker.rebuildFrom(s);
                BlockKnapsack knapsack = new BlockKnapsack(p, s, tracker);
                long scoreBefore = Scorer.score(p, s);
                int c = rng.nextInt(p.cacheCount);
                int[] buffer = new int[p.videoCount];
                int count = knapsack.snapshot(c, buffer);
                knapsack.optimizeCache(c, 0.5, rng);
                knapsack.restore(c, buffer, count);
                eq(scoreBefore, Scorer.score(p, s), "score restauré (instance " + i + ")");
                eq(scoreBefore, tracker.score(), "suivi restauré (instance " + i + ")");
                s.validateOrThrow();
            }
        });

        t.test("un passage sur tous les caches ne fait pas baisser le score", () -> {
            Random rng = new Random(34);
            for (int i = 0; i < 100; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                Solution s = TestSupport.randomSolution(p, rng);
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                tracker.rebuildFrom(s);
                BlockKnapsack knapsack = new BlockKnapsack(p, s, tracker);
                long before = Scorer.score(p, s);
                int[] order = new int[p.cacheCount];
                for (int c = 0; c < order.length; c++) order[c] = c;
                knapsack.sweep(order);
                s.validateOrThrow();
                check(Scorer.score(p, s) >= before, "passage monotone (instance " + i + ")");
                eq(Scorer.score(p, s), tracker.score(), "suivi cohérent (instance " + i + ")");
            }
        });

        t.test("un cache ne reçoit jamais une vidéo plus grande que lui", () -> {
            // instance jouet : les vidéos 3 et 4 (7 Mo) tiennent dans 10 Mo, on réduit donc la capacité artificiellement
            int[] tokens = TestSupport.TOY_TOKENS.clone();
            tokens[4] = 6; // capacité 6 Mo : les vidéos de 7 Mo ne peuvent plus entrer
            FlatProblem p = new FlatProblem(tokens);
            Solution s = new Solution(p);
            BestLatencyTracker tracker = new BestLatencyTracker(p);
            BlockKnapsack knapsack = new BlockKnapsack(p, s, tracker);
            for (int c = 0; c < p.cacheCount; c++) knapsack.optimizeCache(c);
            s.validateOrThrow();
            for (int c = 0; c < p.cacheCount; c++) {
                check(!s.contains(c, 3) && !s.contains(c, 4), "pas de vidéo de 7 Mo dans un cache de 6 Mo");
            }
        });
    }
}
