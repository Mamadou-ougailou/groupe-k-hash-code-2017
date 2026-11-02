package test.solver.ils;

import solver.ils.FlatProblem;
import solver.ils.Scorer;
import solver.ils.Solution;
import test.TestSupport;

import java.util.Random;

import static test.TestSupport.eq;

public final class ScorerTest {

    private ScorerTest() {}

    public static void run(TestSupport t) {
        t.module("solver/ils : Scorer");

        t.test("la solution d'exemple du sujet vaut 462 500", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            // cache 0 = {2}, cache 1 = {3, 1}, cache 2 = {0, 1}
            s.add(0, 2); s.add(1, 3); s.add(1, 1); s.add(2, 0); s.add(2, 1);
            eq(462_500, Scorer.score(p, s), "score de l'exemple du sujet");
            eq(462_500, TestSupport.referenceScore(TestSupport.EXAMPLE_TEXT, TestSupport.EXAMPLE_STATEMENT_SOLUTION), "score de référence indépendant");
        });

        t.test("une solution vide vaut 0", () -> {
            FlatProblem p = TestSupport.example();
            eq(0, Scorer.score(p, new Solution(p)), "score sans aucune vidéo en cache");
        });

        t.test("la meilleure solution de l'exemple vaut 562 500", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            // l'endpoint 0 est le seul relié à un cache : le cache 0 (100 ms) prend les vidéos 3 (30 Mo) et 1 (50 Mo)
            s.add(0, 3); s.add(0, 1);
            eq(562_500, Scorer.score(p, s), "score du meilleur placement");
            eq(562_500, TestSupport.referenceScore(TestSupport.EXAMPLE_TEXT, "1\n0 3 1\n"), "score de référence indépendant");
        });

        t.test("la borne supérieure de l'exemple vaut 675 000", () -> {
            eq(675_000, Scorer.upperBound(TestSupport.example()), "borne supérieure");
        });

        t.test("le score reste entre 0 et la borne, sur 200 instances aléatoires", () -> {
            Random rng = new Random(2017);
            for (int i = 0; i < 200; i++) {
                FlatProblem p = new FlatProblem(TestSupport.randomInstance(rng));
                Solution s = TestSupport.randomSolution(p, rng);
                long score = Scorer.score(p, s);
                TestSupport.check(score >= 0 && score <= Scorer.upperBound(p), "score entre 0 et la borne (instance " + i + ")");
            }
        });

        t.test("le score est invariant par la réduction des tailles (PGCD)", () -> {
            // l'exemple est réduit par 10 : on le compare au calcul de référence qui travaille sur les tailles brutes
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            s.add(0, 3); s.add(0, 1); s.add(2, 0); s.add(1, 2);
            long reference = TestSupport.referenceScore(TestSupport.EXAMPLE_TEXT, "3\n0 3 1\n1 2\n2 0\n");
            eq(reference, Scorer.score(p, s), "score sur tailles réduites = score de référence");
        });
    }
}
