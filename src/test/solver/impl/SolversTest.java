package test.solver.impl;

import io.OutputWriter;
import io.Parser;
import model.Problem;
import solver.Solver;
import solver.impl.CacheFirstSolver;
import solver.impl.GreedyCELFSolver;
import solver.impl.GreedyKnapsackIlsSolver;
import solver.impl.GreedySolver;
import solver.impl.VideoFirstSolver;
import test.TestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static test.TestSupport.check;
import static test.TestSupport.eq;

/** Bout en bout : fichier d'instance -> Parser -> solveur -> OutputWriter -> score recalculé indépendamment. */
public final class SolversTest {

    private SolversTest() {}

    private static final List<String> NAMES = List.of("VideoFirst", "CacheFirst", "Greedy", "GreedyCELF", "Final");

    private static Solver solver(String name, long finalSeconds) {
        Supplier<Solver> s = switch (name) {
            case "VideoFirst" -> VideoFirstSolver::new;
            case "CacheFirst" -> CacheFirstSolver::new;
            case "Greedy" -> GreedySolver::new;
            case "GreedyCELF" -> GreedyCELFSolver::new;
            default -> () -> new GreedyKnapsackIlsSolver(finalSeconds, 1);
        };
        return s.get();
    }

    /** Résout puis relit le fichier écrit : renvoie le score de référence (-1 si la solution est invalide). */
    private static long solveAndScore(String instanceText, Solver solver) {
        Path in = TestSupport.tempFile(instanceText);
        Problem problem = new Parser().parse(in.toString());
        check(problem != null, "l'instance doit se lire");
        java.io.PrintStream console = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); // les solveurs affichent leur progression
        try {
            solver.solve(problem);
        } finally {
            System.setOut(console);
        }
        try {
            Path out = Files.createTempFile("hashcode2017-solution-", ".out");
            out.toFile().deleteOnExit();
            new OutputWriter().writeToFile(problem, out.toString());
            return TestSupport.referenceScore(instanceText, Files.readString(out));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String asText(int[] tokens) {
        return Arrays.stream(tokens).mapToObj(Integer::toString).collect(Collectors.joining(" ")) + "\n";
    }

    public static void run(TestSupport t) {
        t.module("solver/impl : solveurs complets");

        t.test("solveur final sur l'exemple officiel : fichier valide et score 562 500", () -> {
            eq(562_500, solveAndScore(TestSupport.EXAMPLE_TEXT, solver("Final", 1)), "score de la solution écrite");
        });

        for (String name : NAMES) {
            if (name.equals("Final")) continue;
            t.test(name + " produit une solution valide sur l'exemple officiel", () -> {
                long score = solveAndScore(TestSupport.EXAMPLE_TEXT, solver(name, 0));
                check(score >= 0, "solution valide (capacité et format)");
                check(score <= 675_000, "score sous la borne supérieure");
            });
        }

        t.test("les quatre premiers solveurs restent valides sur 40 instances aléatoires", () -> {
            Random rng = new Random(41);
            for (int i = 0; i < 40; i++) {
                String text = asText(TestSupport.randomInstance(rng));
                for (String name : NAMES) {
                    if (name.equals("Final")) continue;
                    long score = solveAndScore(text, solver(name, 0));
                    check(score >= 0, name + " : solution invalide sur l'instance " + i);
                }
            }
        });

        t.test("le solveur final reste valide sur 15 instances aléatoires (budget minimal)", () -> {
            Random rng = new Random(42);
            for (int i = 0; i < 15; i++) {
                String text = asText(TestSupport.randomInstance(rng));
                long score = solveAndScore(text, solver("Final", 0));
                check(score >= 0, "solution invalide sur l'instance " + i);
            }
        });

        t.test("le solveur final fait au moins aussi bien que les autres sur l'exemple jouet", () -> {
            String text = asText(TestSupport.TOY_TOKENS);
            long best = 0;
            for (String name : NAMES) {
                if (name.equals("Final")) continue;
                best = Math.max(best, solveAndScore(text, solver(name, 0)));
            }
            long finalScore = solveAndScore(text, solver("Final", 1));
            check(finalScore >= best, "final " + finalScore + " < meilleur des autres " + best);
        });

        t.test("le solveur final traite une instance où tous les caches sont identiques (rangement exact)", () -> {
            eq(500_000, solveAndScore(asText(TestSupport.PACKING_TOKENS), solver("Final", 1)), "score avec toutes les vidéos en cache");
        });
    }
}
