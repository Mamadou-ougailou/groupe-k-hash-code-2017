package test.solver.ils;

import solver.ils.FlatProblem;
import test.TestSupport;

import static test.TestSupport.eq;

public final class FlatProblemTest {

    private FlatProblemTest() {}

    public static void run(TestSupport t) {
        t.module("solver/ils : FlatProblem");

        t.test("comptes de l'exemple officiel", () -> {
            FlatProblem p = TestSupport.example();
            eq(5, p.videoCount, "vidéos");
            eq(2, p.endpointCount, "endpoints");
            eq(4, p.requestCount, "requêtes");
            eq(3, p.cacheCount, "caches");
            eq(4000, p.totalRequests, "total des requêtes");
            eq(1, p.endpointsWithoutCache, "endpoints sans cache");
        });

        t.test("les tailles et la capacité sont divisées par leur PGCD (10 sur l'exemple)", () -> {
            FlatProblem p = TestSupport.example();
            eq(new int[]{5, 5, 8, 3, 11}, p.videoSize, "tailles réduites");
            eq(10, p.cacheCapacity, "capacité réduite");
        });

        t.test("pas de réduction quand le PGCD vaut 1 (instance jouet)", () -> {
            FlatProblem p = TestSupport.toy();
            eq(new int[]{4, 3, 4, 7, 7}, p.videoSize, "tailles intactes");
            eq(10, p.cacheCapacity, "capacité intacte");
        });

        t.test("tables des liens endpoint -> cache", () -> {
            FlatProblem p = TestSupport.example();
            eq(new int[]{1000, 500}, p.dataCenterLatency, "latences data center");
            eq(new int[]{0, 3, 3}, p.endpointLinkStart, "début des liens par endpoint");
            eq(new int[]{0, 2, 1}, p.linkedCache, "caches reliés (ordre du fichier)");
            eq(new int[]{100, 200, 300}, p.linkLatency, "latences des liens");
        });

        t.test("matrice des latences : -1 quand il n'y a pas de lien", () -> {
            FlatProblem p = TestSupport.example();
            eq(100, p.latency(0, 0), "endpoint 0 - cache 0");
            eq(300, p.latency(0, 1), "endpoint 0 - cache 1");
            eq(200, p.latency(0, 2), "endpoint 0 - cache 2");
            for (int c = 0; c < 3; c++) eq(-1, p.latency(1, c), "endpoint 1 n'a aucun lien (cache " + c + ")");
        });

        t.test("requêtes triées par endpoint", () -> {
            FlatProblem p = TestSupport.example();
            eq(new int[]{0, 3, 4}, p.endpointRequestStart, "début des requêtes par endpoint");
            eq(new int[]{3, 4, 1, 0}, p.requestVideo, "vidéos demandées");
            eq(new int[]{0, 0, 0, 1}, p.requestEndpoint, "endpoints demandeurs");
            eq(new int[]{1500, 500, 1000, 1000}, p.requestViewCount, "nombres de vues");
        });

        t.test("tables inverses : cache -> endpoints, vidéo -> requêtes", () -> {
            FlatProblem p = TestSupport.example();
            eq(new int[]{0, 1, 2, 3}, p.cacheLinkStart, "début des liens par cache");
            eq(new int[]{0, 0, 0}, p.cacheLinkedEndpoint, "endpoint relié à chaque cache");
            eq(new int[]{100, 300, 200}, p.cacheLinkLatency, "latences vues depuis les caches");
            eq(new int[]{0, 1, 2, 2, 3, 4}, p.videoRequestStart, "début des requêtes par vidéo");
            eq(new int[]{3, 2, 0, 1}, p.videoRequestIds, "requêtes de chaque vidéo");
        });

        t.test("lecture depuis un fichier", () -> {
            try {
                FlatProblem p = new FlatProblem(TestSupport.tempFile(TestSupport.EXAMPLE_TEXT));
                eq(5, p.videoCount, "vidéos lues depuis le fichier");
                eq(4000, p.totalRequests, "total des requêtes lu depuis le fichier");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
