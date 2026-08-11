package com.sparrowlogic.grafanamcp.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads Grafana environments from a user-owned JSON file, the editable half of this server's
 * configuration.
 *
 * <p>This is the only place environments are defined. They could have come from
 * {@code application.yaml} and environment variables instead, but that file lives inside the shipped
 * jar and those variables are set by whatever launched the process — so adding an instance would
 * mean re-registering the MCP server. This file is somewhere a person (or an agent acting for them)
 * can simply edit:
 *
 * <pre>{@code
 * {
 *   "environments": {
 *     "production": { "baseUrl": "https://grafana.example.com", "token": "glsa_…" },
 *     "local":      { "baseUrl": "http://localhost:3000",       "token": "glsa_…" }
 *   }
 * }
 * }</pre>
 *
 * <p>There is no default-environment key: every tool call names the instance it wants. Unrecognised
 * keys — including one left over from an earlier version — are ignored rather than rejected.
 *
 * <p>It holds service-account tokens, so it is expected to be readable only by its owner; anything
 * looser is reported through {@code list_grafana_environments} rather than silently accepted. A
 * missing file is normal and means "no extra environments". A malformed one is reported the same way
 * instead of failing startup — a typo here should not stop the tools that do not need it.
 */
@Component
public class EnvironmentStore {

    /** Overridable so tests, and anyone with a different layout, are not tied to the home directory. */
    private static final String PATH_PROPERTY = "grafana.mcp.config-file";
    private static final String PATH_ENV = "GRAFANA_MCP_CONFIG";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path file;

    public EnvironmentStore() {
        this(defaultPath());
    }

    /** For a caller that knows where the file is — tests, and anyone embedding this server. */
    public EnvironmentStore(Path file) {
        this.file = file;
    }

    /** Where this server looks for user-editable configuration. */
    public Path file() {
        return this.file;
    }

    /**
     * A cheap stand-in for the file's content: last-modified time and size. A caller that has already
     * read the file can compare this to decide whether to read it again, so an edit — including
     * deleting an environment — takes effect without restarting the server or re-reading on every
     * call.
     *
     * @return 0 when the file does not exist
     */
    public long fingerprint() {
        if (!Files.isRegularFile(this.file)) {
            return 0L;
        }
        return this.modifiedAndSize();
    }

    private long modifiedAndSize() {
        try {
            return Files.getLastModifiedTime(this.file).toMillis() * 31L + Files.size(this.file);
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * Reads the file. Never throws: a missing, unreadable or malformed file yields an empty result
     * carrying the problem, so a caller can report it.
     */
    public Contents read() {
        if (!Files.isRegularFile(this.file)) {
            return new Contents(Map.of(), false, null, false);
        }
        return this.parse();
    }

    private Contents parse() {
        try {
            JsonNode root = MAPPER.readTree(Files.readString(this.file));
            Map<String, Environment> environments = new LinkedHashMap<>();
            root.path("environments").propertyStream().forEach(entry -> environments.put(entry.getKey(),
                    new Environment(
                            entry.getValue().path("baseUrl").asString(""),
                            entry.getValue().path("token").asString(""))));
            return new Contents(Map.copyOf(environments), true, null, this.worldReadable());
        } catch (IOException | JacksonException ex) {
            return new Contents(Map.of(), true,
                    this.file + " could not be read: " + ex.getMessage(), false);
        }
    }

    /** True when the file's permissions let anyone but its owner read the tokens inside it. */
    private boolean worldReadable() {
        PosixFileAttributeView view = Files.getFileAttributeView(this.file, PosixFileAttributeView.class);
        return view != null && readableByOthers(view);
    }

    private static boolean readableByOthers(PosixFileAttributeView view) {
        try {
            Set<PosixFilePermission> permissions = view.readAttributes().permissions();
            return permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ);
        } catch (IOException ex) {
            return false;
        }
    }

    private static Path defaultPath() {
        String override = System.getProperty(PATH_PROPERTY, System.getenv(PATH_ENV));
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".grafana-mcp-server", "config.json");
    }

    /**
     * What the file said.
     *
     * @param environments  environments defined in the file
     * @param present       whether the file exists at all
     * @param error         why it could not be read, or null when it was fine
     * @param worldReadable true when its permissions are looser than owner-only
     */
    public record Contents(Map<String, Environment> environments, boolean present, String error,
            boolean worldReadable) {
    }

    /**
     * One Grafana instance as written in the file.
     *
     * @param baseUrl base URL, e.g. {@code http://localhost:3000}
     * @param token   service account token for that instance
     */
    public record Environment(String baseUrl, String token) {
    }
}
