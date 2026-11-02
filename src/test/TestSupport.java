package test;

import solver.ils.FlatProblem;
import solver.ils.Scorer;
import solver.ils.Solution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

/**
 * Outils communs des tests : assertions, jeux de données, score de référence indépendant du solveur.
 * Aucune dépendance externe (pas de JUnit).
 */
public final class TestSupport {

    // ------------------------------------------------------------------ jeux de données

    /** Exemple officiel du sujet Hash Code 2017 (5 vidéos, 2 endpoints, 4 requêtes, 3 caches de 100 Mo). */
    public static final String EXAMPLE_TEXT = String.join("\n",
            "5 2 4 3 100",
            "50 50 80 30 110",
            "1000 3",
            "0 100",
            "2 200",
            "1 300",
            "500 0",
            "3 0 1500",
            "0 1 1000",
            "4 0 500",
            "1 0 1000",
            "");

    /** Solution d'exemple donnée dans le sujet : cache 0 = {2}, cache 1 = {3, 1}, cache 2 = {0, 1}. */
    public static final String EXAMPLE_STATEMENT_SOLUTION = "3\n0 2\n1 3 1\n2 0 1\n";

    /** Petite instance (3 caches de 10 Mo, 5 vidéos) où chaque étape de la méthode apporte un gain. */
    public static final int[] TOY_TOKENS = {5, 3, 5, 3, 10, 4, 3, 4, 7, 7,
            100, 2, 2, 20, 0, 20,
            100, 2, 1, 20, 2, 40,
            100, 1, 0, 60,
            0, 0, 60, 2, 1, 30, 1, 2, 40, 3, 1, 50, 2, 2, 50};

    /** Instance où tous les liens et toutes les latences sont identiques et où la somme des tailles remplit exactement les caches. */
    public static final int[] PACKING_TOKENS = {4, 2, 4, 2, 5, 3, 3, 2, 2,
            600, 2, 0, 100, 1, 100,
            600, 2, 0, 100, 1, 100,
            0, 0, 10, 1, 0, 10, 2, 1, 10, 3, 1, 10};

    public static int[] exampleTokens() {
        return tokens(EXAMPLE_TEXT);
    }

    public static int[] tokens(String text) {
        List<Integer> out = new ArrayList<>();
        try (Scanner sc = new Scanner(text)) {
            while (sc.hasNextInt()) out.add(sc.nextInt());
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public static FlatProblem example() {
        return new FlatProblem(exampleTokens());
    }

    public static FlatProblem toy() {
        return new FlatProblem(TOY_TOKENS);
    }

    public static Path tempFile(String content) {
        try {
            Path file = Files.createTempFile("hashcode2017-test-", ".txt");
            Files.writeString(file, content);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Petite instance aléatoire valide : latences vers les caches strictement plus petites que vers le data center,
     * pas de doublon (vidéo, endpoint), au moins une requête.
     */
    public static int[] randomInstance(Random rng) {
        int videos = 1 + rng.nextInt(5), endpoints = 1 + rng.nextInt(3), caches = 1 + rng.nextInt(3);
        int capacity = 4 + rng.nextInt(9);
        List<Integer> t = new ArrayList<>();
        int pairs = videos * endpoints;
        int requests = 1 + rng.nextInt(Math.min(6, pairs));
        t.add(videos); t.add(endpoints); t.add(requests); t.add(caches); t.add(capacity);
        for (int v = 0; v < videos; v++) t.add(1 + rng.nextInt(6));
        for (int e = 0; e < endpoints; e++) {
            int dc = 60 + rng.nextInt(140);
            t.add(dc);
            List<Integer> ids = new ArrayList<>();
            for (int c = 0; c < caches; c++) ids.add(c);
            java.util.Collections.shuffle(ids, rng);
            int links = rng.nextInt(caches + 1);
            t.add(links);
            for (int i = 0; i < links; i++) { t.add(ids.get(i)); t.add(5 + rng.nextInt(dc - 10)); }
        }
        List<Integer> all = new ArrayList<>();
        for (int p = 0; p < pairs; p++) all.add(p);
        java.util.Collections.shuffle(all, rng);
        for (int r = 0; r < requests; r++) {
            int p = all.get(r);
            t.add(p / endpoints); t.add(p % endpoints); t.add(1 + rng.nextInt(100));
        }
        return t.stream().mapToInt(Integer::intValue).toArray();
    }

    // ------------------------------------------------------------------ score de référence indépendant

    /**
     * Recalcule le score officiel à partir du texte de l'instance et du texte de la solution, sans utiliser le solveur
     * (formule du juge). Renvoie -1 si la solution est invalide (capacité dépassée, cache ou vidéo inconnus).
     */
    public static long referenceScore(String instanceText, String solutionText) {
        Scanner in = new Scanner(instanceText);
        int videos = in.nextInt(), endpoints = in.nextInt(), requests = in.nextInt(), caches = in.nextInt(), capacity = in.nextInt();
        int[] size = new int[videos];
        for (int v = 0; v < videos; v++) size[v] = in.nextInt();
        int[] dc = new int[endpoints];
        int[][] latency = new int[endpoints][caches];
        for (int e = 0; e < endpoints; e++) {
            dc[e] = in.nextInt();
            int k = in.nextInt();
            java.util.Arrays.fill(latency[e], -1);
            for (int i = 0; i < k; i++) { int c = in.nextInt(); latency[e][c] = in.nextInt(); }
        }
        int[][] req = new int[requests][3];
        for (int r = 0; r < requests; r++) { req[r][0] = in.nextInt(); req[r][1] = in.nextInt(); req[r][2] = in.nextInt(); }

        boolean[][] has = new boolean[caches][videos];
        String[] lines = solutionText.trim().split("\\R");
        int used = Integer.parseInt(lines[0].trim());
        if (lines.length != used + 1) return -1;
        for (int i = 1; i <= used; i++) {
            String[] parts = lines[i].trim().split("\\s+");
            int c = Integer.parseInt(parts[0]);
            if (c < 0 || c >= caches) return -1;
            long total = 0;
            for (int j = 1; j < parts.length; j++) {
                int v = Integer.parseInt(parts[j]);
                if (v < 0 || v >= videos) return -1;
                if (!has[c][v]) { has[c][v] = true; total += size[v]; }
            }
            if (total > capacity) return -1;
        }
        long saved = 0, count = 0;
        for (int[] r : req) {
            int best = dc[r[1]];
            for (int c = 0; c < caches; c++)
                if (latency[r[1]][c] >= 0 && has[c][r[0]]) best = Math.min(best, latency[r[1]][c]);
            saved += (long) r[2] * (dc[r[1]] - best);
            count += r[2];
        }
        return saved * 1000L / count;
    }

    // ------------------------------------------------------------------ force brute

    /** Meilleur score possible en ne changeant que le contenu du cache {@code cache} (les autres restent fixes). */
    public static long bestScoreChangingOnlyCache(FlatProblem p, Solution base, int cache) {
        long best = -1;
        for (int mask = 0; mask < (1 << p.videoCount); mask++) {
            int size = 0;
            for (int v = 0; v < p.videoCount; v++) if ((mask >> v & 1) != 0) size += p.videoSize[v];
            if (size > p.cacheCapacity) continue;
            Solution s = base.copy();
            s.clearCache(cache);
            for (int v = 0; v < p.videoCount; v++) if ((mask >> v & 1) != 0) s.add(cache, v);
            best = Math.max(best, Scorer.score(p, s));
        }
        return best;
    }

    /** Optimum global par énumération de tous les contenus de tous les caches (petites instances seulement). */
    public static long bruteForceOptimum(FlatProblem p) {
        return enumerate(p, 0, new Solution(p));
    }

    private static long enumerate(FlatProblem p, int cache, Solution partial) {
        if (cache == p.cacheCount) return Scorer.score(p, partial);
        long best = -1;
        for (int mask = 0; mask < (1 << p.videoCount); mask++) {
            int size = 0;
            for (int v = 0; v < p.videoCount; v++) if ((mask >> v & 1) != 0) size += p.videoSize[v];
            if (size > p.cacheCapacity) continue;
            Solution s = partial.copy();
            for (int v = 0; v < p.videoCount; v++) if ((mask >> v & 1) != 0) s.add(cache, v);
            best = Math.max(best, enumerate(p, cache + 1, s));
        }
        return best;
    }

    /** Solution aléatoire valide (chaque vidéo tentée dans chaque cache avec une probabilité 1/2). */
    public static Solution randomSolution(FlatProblem p, Random rng) {
        Solution s = new Solution(p);
        for (int c = 0; c < p.cacheCount; c++)
            for (int v = 0; v < p.videoCount; v++)
                if (rng.nextBoolean()) s.add(c, v);
        return s;
    }

    // ------------------------------------------------------------------ assertions et comptage

    private int passed, failed;
    private String module = "";
    private final List<String> failures = new ArrayList<>();

    public void module(String name) {
        module = name;
        System.out.println();
        System.out.println("== " + name);
    }

    public void test(String name, Runnable body) {
        try {
            body.run();
            passed++;
            System.out.println("  OK    " + name);
        } catch (Throwable e) {
            failed++;
            String message = module + " : " + name + " : " + e;
            failures.add(message);
            System.out.println("  ECHEC " + name + " : " + e);
        }
    }

    public int passed() { return passed; }

    public int failed() { return failed; }

    public List<String> failures() { return failures; }

    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void eq(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + " : attendu " + expected + ", obtenu " + actual);
    }

    public static void same(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + " : attendu " + expected + ", obtenu " + actual);
    }

    public static void eq(int[] expected, int[] actual, String message) {
        if (!java.util.Arrays.equals(expected, actual))
            throw new AssertionError(message + " : attendu " + java.util.Arrays.toString(expected) + ", obtenu " + java.util.Arrays.toString(actual));
    }

    public static void throwsIllegalState(Runnable body, String message) {
        try {
            body.run();
        } catch (IllegalStateException e) {
            return;
        }
        throw new AssertionError(message + " : IllegalStateException attendue");
    }
}
