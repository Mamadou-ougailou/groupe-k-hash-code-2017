package test.solver.ils;

import solver.ils.FlatProblem;
import solver.ils.Solution;
import test.TestSupport;

import static test.TestSupport.check;
import static test.TestSupport.eq;

public final class SolutionTest {

    private SolutionTest() {}

    public static void run(TestSupport t) {
        t.module("solver/ils : Solution");

        // exemple : tailles réduites 5, 5, 8, 3, 11 et capacité 10
        t.test("un ajout hors capacité est refusé et ne change rien", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            check(s.add(0, 2), "la vidéo 2 (8) rentre dans un cache vide");
            check(!s.add(0, 0), "la vidéo 0 (5) ne rentre plus (8 + 5 > 10)");
            check(!s.contains(0, 0), "la vidéo refusée n'est pas dans le cache");
            eq(8, s.usedCapacity[0], "capacité utilisée inchangée");
            eq(2, s.remainingCapacity(0), "capacité restante");
        });

        t.test("une vidéo plus grande que le cache est refusée", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            check(!s.add(1, 4), "la vidéo 4 (11) dépasse la capacité 10");
            eq(0, s.usedCapacity[1], "rien n'est consommé");
        });

        t.test("un doublon est refusé", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            check(s.add(0, 3), "premier ajout");
            check(!s.add(0, 3), "même vidéo, même cache");
            eq(3, s.usedCapacity[0], "la capacité n'est comptée qu'une fois");
        });

        t.test("remove et clearCache mettent la capacité à jour", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            s.add(0, 0); s.add(0, 1);
            eq(10, s.usedCapacity[0], "deux vidéos de 5");
            check(s.remove(0, 0), "retrait d'une vidéo présente");
            check(!s.remove(0, 0), "retrait d'une vidéo absente");
            eq(5, s.usedCapacity[0], "capacité après retrait");
            s.clearCache(0);
            eq(0, s.usedCapacity[0], "capacité après vidage");
            check(!s.contains(0, 1), "le cache est vide");
        });

        t.test("copy est indépendante de l'original, copyFrom recopie", () -> {
            FlatProblem p = TestSupport.example();
            Solution a = new Solution(p);
            a.add(0, 0);
            Solution b = a.copy();
            b.add(1, 1);
            check(!a.contains(1, 1), "modifier la copie ne touche pas l'original");
            check(b.contains(0, 0), "la copie contient le contenu de départ");
            a.copyFrom(b);
            check(a.contains(1, 1), "copyFrom recopie le contenu");
            eq(5, a.usedCapacity[1], "copyFrom recopie aussi les capacités");
        });

        t.test("validateOrThrow accepte une solution saine", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            s.add(0, 0); s.add(1, 3);
            s.validateOrThrow();
        });

        t.test("validateOrThrow détecte un compteur désynchronisé", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            s.add(0, 0);
            s.usedCapacity[0] += 1;
            TestSupport.throwsIllegalState(s::validateOrThrow, "compteur faux");
        });

        t.test("validateOrThrow détecte une capacité dépassée", () -> {
            FlatProblem p = TestSupport.example();
            Solution s = new Solution(p);
            s.add(0, 0); s.add(0, 1);
            s.usedCapacity[0] = 99;
            TestSupport.throwsIllegalState(s::validateOrThrow, "capacité dépassée");
        });
    }
}
