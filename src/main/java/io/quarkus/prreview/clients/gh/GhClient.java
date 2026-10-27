package io.quarkus.prreview.clients.gh;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.prreview.config.ReviewConfig;
import io.quarkus.prreview.pr.model.PullRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only access to GitHub through the {@code gh} CLI. Only {@code gh pr view} and {@code gh pr diff} can run;
 * any other subcommand is refused before a process starts.
 */
@ApplicationScoped
public class GhClient {

    private static final Set<List<String>> ALLOWED_SUBCOMMANDS = Set.of(List.of("pr", "view"), List.of("pr", "diff"));

    private final ReviewConfig config;
    private final JsonMapper mapper;

    public GhClient(ReviewConfig config, JsonMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    /** Fetches the PR metadata and its unified diff. */
    public PullRequest fetch(int pr) {
        String json = run(List.of("pr", "view", Integer.toString(pr), "--json", "number,title,url,state"));
        String diff = run(List.of("pr", "diff", Integer.toString(pr)));
        try {
            View view = mapper.readValue(json, View.class);
            return new PullRequest(view.number(), view.title(), view.url(), view.state(), diff);
        } catch (JacksonException e) {
            throw new GhException("unexpected output from gh pr view " + pr + ": " + e.getOriginalMessage(), e);
        }
    }

    private record View(int number, String title, String url, String state) {
    }

    /** Builds the full command line; refuses anything but the allowed read-only subcommands. */
    List<String> command(List<String> args) {
        if (args.size() < 2 || !ALLOWED_SUBCOMMANDS.contains(args.subList(0, 2))) {
            throw new IllegalArgumentException("gh subcommand not allowed: " + args);
        }
        List<String> command = new ArrayList<>();
        command.add(config.gh().binary());
        command.addAll(args);
        command.add("-R");
        command.add(config.gh().repo());
        return command;
    }

    private String run(List<String> args) {
        List<String> command = command(args);
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new GhException("cannot run " + command.getFirst() + ": " + e.getMessage(), e);
        }
        // Read both streams concurrently so a large diff can't block the process on a full pipe.
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        try {
            if (!process.waitFor(config.gh().timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GhException("gh " + String.join(" ", args) + " timed out after " + config.gh().timeout());
            }
            if (process.exitValue() != 0) {
                String message = stderr.join().strip();
                throw new GhException(message.isEmpty() ? "gh exited with " + process.exitValue() : message);
            }
            return stdout.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GhException("interrupted while running gh", e);
        }
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
