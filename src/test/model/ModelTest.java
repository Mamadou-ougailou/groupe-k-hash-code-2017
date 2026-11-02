package test.model;

import model.Cache;
import model.Endpoint;
import model.Problem;
import model.RequestDescription;
import model.Video;
import test.TestSupport;

import java.util.List;

import static test.TestSupport.check;
import static test.TestSupport.eq;

public final class ModelTest {

    private ModelTest() {}

    public static void run(TestSupport t) {
        t.module("model");

        t.test("Cache : capacité restante, canFit et addVideo", () -> {
            Cache cache = new Cache(3, 100);
            Video big = new Video(0, 80), small = new Video(1, 30), exact = new Video(2, 20);
            eq(3, cache.getId(), "identifiant");
            eq(100, cache.getRemainingCapacity(), "capacité restante au départ");
            check(cache.canFit(big), "80 Mo rentrent dans 100 Mo");
            cache.addVideo(big);
            eq(20, cache.getRemainingCapacity(), "capacité restante après 80 Mo");
            check(!cache.canFit(small), "30 Mo ne rentrent plus dans 20 Mo");
            check(cache.canFit(exact), "20 Mo rentrent exactement");
            cache.addVideo(exact);
            eq(0, cache.getRemainingCapacity(), "cache plein");
            eq(2, cache.getCachedVideos().size(), "deux vidéos en cache");
            eq(100, cache.getUsedCapacity(), "capacité utilisée");
        });

        t.test("Endpoint : liens vers les caches et requêtes", () -> {
            Endpoint endpoint = new Endpoint(1, 500);
            Cache cache = new Cache(0, 100);
            Video video = new Video(7, 10);
            endpoint.addCacheConnection(cache, 40);
            endpoint.addVideoRequest(video, 250);
            eq(500, endpoint.getDataCenterLatency(), "latence data center");
            eq(40, endpoint.getCacheLatencies().get(cache), "latence vers le cache");
            eq(250, endpoint.getRequestedVideos().get(video), "nombre de requêtes");
        });

        t.test("Cache : liens inverses vers les endpoints", () -> {
            Cache cache = new Cache(0, 100);
            Endpoint endpoint = new Endpoint(0, 500);
            cache.addEndpointConnection(endpoint, 60);
            eq(60, cache.getEndpointLatencies().get(endpoint), "latence depuis le cache");
        });

        t.test("Problem et RequestDescription : accesseurs", () -> {
            Video video = new Video(0, 10);
            Endpoint endpoint = new Endpoint(0, 100);
            Cache cache = new Cache(0, 50);
            RequestDescription request = new RequestDescription(video, endpoint, 12);
            Problem problem = new Problem(List.of(video), List.of(endpoint), List.of(cache), List.of(request), 50);
            eq(1, problem.getVideos().size(), "vidéos");
            eq(1, problem.getEndpoints().size(), "endpoints");
            eq(1, problem.getCaches().size(), "caches");
            eq(1, problem.getRequests().size(), "requêtes");
            eq(50, problem.getCacheCapacity(), "capacité");
            eq(12, request.getNumberOfRequests(), "nombre de requêtes");
            check(request.getVideo() == video && request.getEndpoint() == endpoint, "vidéo et endpoint de la requête");
        });
    }
}
