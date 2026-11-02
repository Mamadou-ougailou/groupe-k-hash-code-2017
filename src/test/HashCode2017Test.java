package test;

import test.io.OutputWriterTest;
import test.io.ParserTest;
import test.model.ModelTest;
import test.solver.ils.BestLatencyTrackerTest;
import test.solver.ils.BinPackingTest;
import test.solver.ils.BlockKnapsackTest;
import test.solver.ils.FlatProblemTest;
import test.solver.ils.LazyGreedyPlacementTest;
import test.solver.ils.ScorerTest;
import test.solver.ils.SolutionTest;
import test.solver.impl.SolversTest;

/**
 * Point d'entrée unique des tests. Un dossier de test par module testé : model, io, solver/ils, solver/impl.
 * Lancement : make test  (ou java -ea -cp out test.HashCode2017Test après compilation).
 */
public final class HashCode2017Test {

    private HashCode2017Test() {}

    public static void main(String[] args) {
        TestSupport t = new TestSupport();
        long start = System.nanoTime();

        // model et io
        ModelTest.run(t);
        ParserTest.run(t);
        OutputWriterTest.run(t);

        // solver/ils : le solveur final, du plus bas niveau au plus haut
        FlatProblemTest.run(t);
        SolutionTest.run(t);
        ScorerTest.run(t);
        BestLatencyTrackerTest.run(t);
        BlockKnapsackTest.run(t);
        BinPackingTest.run(t);
        LazyGreedyPlacementTest.run(t);

        // solver/impl : les solveurs de bout en bout
        SolversTest.run(t);

        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.println();
        System.out.printf("%d tests réussis, %d échecs, %.1f s%n", t.passed(), t.failed(), seconds);
        if (t.failed() > 0) {
            System.out.println("Échecs :");
            t.failures().forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }
}
