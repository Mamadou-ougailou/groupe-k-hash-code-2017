package io;

import model.Cache;
import model.Problem;
import model.Video;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class OutputWriter {

    public static void extracteIntoFile(Problem problem, String outputFilePath) {
        // 4. Output results to file
        OutputWriter writer = new OutputWriter();
        try {
            writer.writeToFile(problem, outputFilePath);
            System.out.println("Saved solution to: " + outputFilePath);
        } catch (IOException e) {
            System.err.println("Failed to write output file: " + e.getMessage());
        }
    }

    public static String getOutputFilePath(String[] args, String inputFilePath) {
        String outputFilePath;
        if (args.length >= 2) {
            outputFilePath = args[1];
        } else {
            File inputFile = new File(inputFilePath);
            String name = inputFile.getName();
            int dotIdx = name.lastIndexOf('.');
            String baseName = (dotIdx > 0) ? name.substring(0, dotIdx) : name;
            outputFilePath = "output/" + baseName + ".out";
        }
        return outputFilePath;
    }

    /**
     * Prints the solution to the console in the exact format required by the Google Hash Code submission.
     */
    public void writeToConsole(Problem problem) {
        List<Cache> usedCaches = problem.getCaches().stream()
                .filter(c -> !c.getCachedVideos().isEmpty())
                .toList();

        System.out.println(usedCaches.size());

        for (Cache cache : usedCaches) {
            StringBuilder sb = new StringBuilder();
            sb.append(cache.getId());
            for (Video video : cache.getCachedVideos()) {
                sb.append(" ").append(video.getId());
            }
            System.out.println(sb);
        }
    }

    /**
     * Writes the solution to a file in the Hash Code submission format.
     * Creates parent directories if they don't exist.
     */
    public void writeToFile(Problem problem, String filePath) throws IOException {
        List<Cache> usedCaches = problem.getCaches().stream()
                .filter(c -> !c.getCachedVideos().isEmpty())
                .toList();

        File outputFile = new File(filePath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(String.valueOf(usedCaches.size()));
            writer.newLine();

            for (Cache cache : usedCaches) {
                StringBuilder sb = new StringBuilder();
                sb.append(cache.getId());
                for (Video video : cache.getCachedVideos()) {
                    sb.append(" ").append(video.getId());
                }
                writer.write(sb.toString());
                writer.newLine();
            }
        }
    }
}
