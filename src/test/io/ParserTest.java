package test.io;

import io.Parser;
import model.Cache;
import model.Endpoint;
import model.Problem;
import model.RequestDescription;
import test.TestSupport;

import java.nio.file.Path;

import static test.TestSupport.check;
import static test.TestSupport.eq;

public final class ParserTest {

    private ParserTest() {}

    public static void run(TestSupport t) {
        t.module("io : Parser");

        t.test("lit l'exemple officiel : vidéos, caches, capacité", () -> {
            Problem p = parseExample();
            eq(5, p.getVideos().size(), "vidéos");
            eq(2, p.getEndpoints().size(), "endpoints");
            eq(3, p.getCaches().size(), "caches");
            eq(4, p.getRequests().size(), "requêtes");
            eq(100, p.getCacheCapacity(), "capacité des caches");
            eq(80, p.getVideos().get(2).getSize(), "taille de la vidéo 2");
            eq(100, p.getCaches().get(1).getCapacity(), "capacité du cache 1");
        });

        t.test("lit les latences des endpoints", () -> {
            Problem p = parseExample();
            Endpoint e0 = p.getEndpoints().get(0), e1 = p.getEndpoints().get(1);
            eq(1000, e0.getDataCenterLatency(), "latence data center de l'endpoint 0");
            eq(3, e0.getCacheLatencies().size(), "caches reliés à l'endpoint 0");
            Cache c0 = p.getCaches().get(0), c1 = p.getCaches().get(1), c2 = p.getCaches().get(2);
            eq(100, e0.getCacheLatencies().get(c0), "latence vers le cache 0");
            eq(300, e0.getCacheLatencies().get(c1), "latence vers le cache 1");
            eq(200, e0.getCacheLatencies().get(c2), "latence vers le cache 2");
            eq(500, e1.getDataCenterLatency(), "latence data center de l'endpoint 1");
            eq(0, e1.getCacheLatencies().size(), "l'endpoint 1 n'est relié à aucun cache");
        });

        t.test("lit les requêtes dans l'ordre du fichier", () -> {
            Problem p = parseExample();
            RequestDescription r0 = p.getRequests().get(0), r3 = p.getRequests().get(3);
            eq(3, r0.getVideo().getId(), "vidéo de la première requête");
            eq(0, r0.getEndpoint().getId(), "endpoint de la première requête");
            eq(1500, r0.getNumberOfRequests(), "nombre de requêtes de la première ligne");
            eq(1, r3.getVideo().getId(), "vidéo de la dernière requête");
            eq(1000, r3.getNumberOfRequests(), "nombre de requêtes de la dernière ligne");
            eq(1500, p.getEndpoints().get(0).getRequestedVideos().get(p.getVideos().get(3)), "requêtes mémorisées par l'endpoint");
        });

        t.test("remplit aussi les liens inverses des caches", () -> {
            Problem p = parseExample();
            Cache c0 = p.getCaches().get(0);
            eq(1, c0.getEndpointLatencies().size(), "un seul endpoint relié au cache 0");
            eq(100, c0.getEndpointLatencies().get(p.getEndpoints().get(0)), "latence depuis le cache 0");
        });

        t.test("un fichier absent donne null sans exception", () -> {
            check(new Parser().parse("fichier/qui/n/existe/pas.in") == null, "parse doit renvoyer null");
        });
    }

    private static Problem parseExample() {
        Path file = TestSupport.tempFile(TestSupport.EXAMPLE_TEXT);
        Problem problem = new Parser().parse(file.toString());
        check(problem != null, "l'exemple doit se lire");
        return problem;
    }
}
