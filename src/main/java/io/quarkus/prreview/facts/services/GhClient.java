package io.quarkus.prreview.facts.services;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs read-only {@code gh} commands (design §3.1, §8). This is the <em>only</em> place the agent
 * shells out, and it never passes a write/merge/secret subcommand — least-privilege read-only access
 * is the primary prompt-injection mitigation, not a filter.
 *
 * <p>A failed command surfaces as {@link GhException} so the caller can degrade the corresponding fact
 * to <em>unknown → advisory</em> (design §9), never a silent pass.
 */
@ApplicationScoped
public class GhClient {

    /** Read-only {@code gh} subcommands this agent is permitted to invoke. */
    private static final java.util.Set<String> ALLOWED = java.util.Set.of("pr", "api", "search");

    @ConfigProperty(name = "gh.binary", defaultValue = "gh")
    String ghBinary;

    @ConfigProperty(name = "gh.repo")
    java.util.Optional<String> repo;

    @ConfigProperty(name = "gh.timeout-seconds", defaultValue = "60")
    int timeoutSeconds;

    public static final class GhException extends RuntimeException {
        public GhException(String message) {
            super(message);
        }
    }

    /** Run {@code gh <args...>} and return stdout, enforcing the read-only subcommand allow-list. */
    public String run(String... args) {
        if (args.length == 0 || !ALLOWED.contains(args[0])) {
            throw new GhException("Refusing non-read-only gh subcommand: "
                    + (args.length == 0 ? "<none>" : args[0]));
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(ghBinary);
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new GhException("gh timed out after " + timeoutSeconds + "s: " + String.join(" ", cmd));
            }
            if (p.exitValue() != 0) {
                throw new GhException("gh failed (" + p.exitValue() + "): " + stderr.strip());
            }
            return stdout;
        } catch (IOException e) {
            throw new GhException("gh could not be executed (" + ghBinary + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GhException("gh interrupted");
        }
    }

    /** Append {@code -R <owner/repo>} if configured, so a range sweep targets the right repo. */
    public String[] withRepo(String... args) {
        if (repo.isEmpty() || repo.get().isBlank()) {
            return args;
        }
        List<String> out = new ArrayList<>(List.of(args));
        out.add("-R");
        out.add(repo.get());
        return out.toArray(String[]::new);
    }
}
