package test.solver.ils;

import solver.ils.BestLatencyTracker;
import solver.ils.BlockKnapsack;
import solver.ils.FlatProblem;
import solver.ils.IteratedLocalSearch;
import solver.ils.LazyGreedyPlacement;
import solver.ils.Scorer;
import solver.ils.Solution;
import test.TestSupport;

import java.util.Random;

import static test.TestSupport.check;
import static test.TestSupport.eq;

/** Les trois étapes de la méthode : placement glouton, descente par sacs à dos, recherche par perturbations. */
public final class LazyGreedyPlacementTest {

    private LazyGreedyPlacementTest() {}

    public static void run(TestSupport t) {
        t.module("solver/ils : LazyGreedyPlacement et pipeline de la méthode");

        t.test("exemple officiel : le placement glouton vaut 562 500", () -> {
            FlatProblem p = TestSupport.example();
            BestLatencyTracker tracker = new BestLatencyTracker(p);
            Solution s = LazyGreedyPlacement.run(p, tracker, false);
            s.validateOrThrow();
            eq(562_500, Scorer.score(p, s), "score du placement glouton");
            eq(Scorer.score(p, s), tracker.score(), "suivi cohérent");
        });

        t.test("instance jouet : glouton 51 304, descente 53 043, optimum 61 739, borne 64 347", () -> {
            FlatProblem p = TestSupport.toy();
            BestLatencyTracker tracker = new BestLatencyTracker(p);
            Solution s = LazyGreedyPlacement.run(p, tracker, false);
            s.validateOrThrow();
            eq(51_304, Scorer.score(p, s), "placement glouton");
            BlockKnapsack knapsack = new BlockKnapsack(p, s, tracker);
            long reported = IteratedLocalSearch.descendBySweeps(p, s, tracker, knapsack, new Random(1), Long.MAX_VALUE / 2, 30, false);
            s.validateOrThrow();
            eq(53_043, Scorer.score(p, s), "après la descente");
            eq(reported, Scorer.score(p, s), "score annoncé par la descente");
            eq(61_739, TestSupport.bruteForceOptimum(p), "optimum par force brute");
            eq(64_347, Scorer.upperBound(p), "borne supérieure");
        });

        t.test("instance jouet : la recherche par perturbations reste valide et améliore la descente", () -> {
            FlatProblem p = TestSupport.toy();
            BestLatencyTracker tracker = new BestLatencyTracker(p);
            Solution s = LazyGreedyPlacement.run(p, tracker, false);
            BlockKnapsack knapsack = new BlockKnapsack(p, s, tracker);
            IteratedLocalSearch.descendBySweeps(p, s, tracker, knapsack, new Random(1), Long.MAX_VALUE / 2, 30, false);
            long descent = Scorer.score(p, s);
            IteratedLocalSearch.Result result;
            try {
                result = IteratedLocalSearch.runParallel(p, s, 1000, 1, false);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            result.solution.validateOrThrow();
            long found = Scorer.score(p, result.solution);
            eq(found, result.score, "score annoncé par la recherche");
            check(found >= descent, "la recherche ne fait pas pire que la descente (" + found + " < " + descent + ")");
            check(found <= TestSupport.bruteForceOptimum(p), "jamais au-dessus de l'optimum");
        });

        t.test("placement glouton valide et suivi cohérent (150 instances aléatoires)", () -> {
            Random rng = new Random(21);
            for (int i = 0; i < 150; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                Solution s = LazyGreedyPlacement.run(p, tracker, false);
                s.validateOrThrow();
                eq(Scorer.score(p, s), tracker.score(), "suivi cohérent (instance " + i + ")");
            }
        });

        t.test("la descente ne fait jamais baisser le score (150 instances aléatoires)", () -> {
            Random rng = new Random(22);
            for (int i = 0; i < 150; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                Solution s = LazyGreedyPlacement.run(p, tracker, false);
                long before = Scorer.score(p, s);
                BlockKnapsack knapsack = new BlockKnapsack(p, s, tracker);
                IteratedLocalSearch.descendBySweeps(p, s, tracker, knapsack, new Random(i), Long.MAX_VALUE / 2, 30, false);
                s.validateOrThrow();
                long after = Scorer.score(p, s);
                check(after >= before, "descente monotone (instance " + i + ") : " + before + " -> " + after);
                eq(after, tracker.score(), "suivi cohérent après descente (instance " + i + ")");
            }
        });

        t.test("jamais au-dessus de l'optimum par force brute (100 instances aléatoires)", () -> {
            Random rng = new Random(23);
            for (int i = 0; i < 100; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                BestLatencyTracker tracker = new BestLatencyTracker(p);
                Solution s = LazyGreedyPlacement.run(p, tracker, false);
                long opt = TestSupport.bruteForceOptimum(p);
                check(Scorer.score(p, s) <= opt, "le glouton ne dépasse pas l'optimum (instance " + i + ")");
            }
        });
    }
}
