package test.solver.ils;

import solver.ils.BinPacking;
import solver.ils.FlatProblem;
import solver.ils.Scorer;
import solver.ils.Solution;
import test.TestSupport;

import static test.TestSupport.check;
import static test.TestSupport.eq;

public final class BinPackingTest {

    private BinPackingTest() {}

    public static void run(TestSupport t) {
        t.module("solver/ils : BinPacking");

        t.test("isDegenerate : vrai quand tout est identique, faux sur l'exemple officiel", () -> {
            check(BinPacking.isDegenerate(new FlatProblem(TestSupport.PACKING_TOKENS)), "instance à latences identiques");
            check(!BinPacking.isDegenerate(TestSupport.example()), "l'exemple officiel n'est pas dégénéré");
            check(!BinPacking.isDegenerate(TestSupport.toy()), "l'instance jouet n'est pas dégénérée");
        });

        t.test("remplit exactement les caches : toutes les vidéos sont placées, score 500 000", () -> {
            FlatProblem p = new FlatProblem(TestSupport.PACKING_TOKENS);
            Solution s = BinPacking.solve(p, 2000, false);
            s.validateOrThrow();
            eq(500_000, Scorer.score(p, s), "score avec toutes les vidéos en cache");
            int placed = 0;
            for (int v = 0; v < p.videoCount; v++) {
                boolean found = false;
                for (int c = 0; c < p.cacheCount; c++) found |= s.contains(c, v);
                if (found) placed++;
            }
            eq(4, placed, "vidéos placées");
            for (int c = 0; c < p.cacheCount; c++) eq(5, s.usedCapacity[c], "cache " + c + " rempli au Mo près");
        });

        t.test("une vidéo n'est placée que dans un seul cache", () -> {
            FlatProblem p = new FlatProblem(TestSupport.PACKING_TOKENS);
            Solution s = BinPacking.solve(p, 2000, false);
            for (int v = 0; v < p.videoCount; v++) {
                int copies = 0;
                for (int c = 0; c < p.cacheCount; c++) if (s.contains(c, v)) copies++;
                check(copies <= 1, "la vidéo " + v + " est dans " + copies + " caches");
            }
        });
    }
}
