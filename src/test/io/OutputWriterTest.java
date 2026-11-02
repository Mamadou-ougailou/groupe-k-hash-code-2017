package test.io;

import io.OutputWriter;
import model.Cache;
import model.Endpoint;
import model.Problem;
import model.Video;
import test.TestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static test.TestSupport.check;
import static test.TestSupport.eq;
import static test.TestSupport.same;

public final class OutputWriterTest {

    private OutputWriterTest() {}

    public static void run(TestSupport t) {
        t.module("io : OutputWriter");

        t.test("format : nombre de caches utilisés, puis une ligne par cache non vide", () -> {
            Video v0 = new Video(0, 10), v1 = new Video(1, 10), v2 = new Video(2, 10);
            Cache c0 = new Cache(0, 100), c1 = new Cache(1, 100), c2 = new Cache(2, 100);
            c0.addVideo(v0);
            c2.addVideo(v1);
            c2.addVideo(v2);
            Problem problem = new Problem(List.of(v0, v1, v2), List.<Endpoint>of(), List.of(c0, c1, c2), List.of(), 100);
            Path out = tempDir().resolve("sous-dossier").resolve("solution.out");
            try {
                new OutputWriter().writeToFile(problem, out.toString());
                List<String> lines = Files.readAllLines(out);
                eq(3, lines.size(), "nombre de lignes");
                same("2", lines.get(0), "première ligne = caches utilisés");
                same("0 0", lines.get(1), "cache 0");
                String[] cache2 = lines.get(2).split(" ");
                same("2", cache2[0], "identifiant du cache 2");
                eq(3, cache2.length, "le cache 2 contient deux vidéos");
                check(lines.get(2).contains(" 1") && lines.get(2).contains(" 2"), "vidéos 1 et 2 listées");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        t.test("aucun cache utilisé : fichier réduit à « 0 »", () -> {
            Cache c0 = new Cache(0, 100);
            Problem problem = new Problem(List.<Video>of(), List.<Endpoint>of(), List.of(c0), List.of(), 100);
            Path out = tempDir().resolve("vide.out");
            try {
                new OutputWriter().writeToFile(problem, out.toString());
                same(List.of("0"), Files.readAllLines(out), "contenu du fichier");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        t.test("chemin de sortie : argument explicite, sinon output/<nom>.out", () -> {
            same("mon/fichier.out", OutputWriter.getOutputFilePath(new String[]{"in.txt", "mon/fichier.out"}, "in.txt"), "argument explicite");
            same("output/kittens.out", OutputWriter.getOutputFilePath(new String[]{"instances/kittens.in"}, "instances/kittens.in"), "nom par défaut");
            same("output/sans_extension.out", OutputWriter.getOutputFilePath(new String[]{"sans_extension"}, "sans_extension"), "nom sans extension");
        });
    }

    private static Path tempDir() {
        try {
            Path dir = Files.createTempDirectory("hashcode2017-out-");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
