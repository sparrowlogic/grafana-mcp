package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.config.EnvironmentStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The Grafana instances this server can talk to, each with its own base URL, token and HTTP client.
 *
 * <p>Every tool takes a required {@code environment} argument that resolves through here, so one
 * server process can answer for a laptop instance, a production one and a lab one without being
 * restarted or reconfigured. Resolution is per call rather than a mode that gets switched: an
 * MCP client holds no reliable memory of "which environment am I in", and the failure mode of
 * getting that wrong — reading production while believing you are in staging — is the kind of
 * mistake that should be impossible rather than unlikely.
 *
 * <p>There is deliberately no default environment. A call that does not name one fails with the
 * list of configured names rather than picking an instance on the caller's behalf, even when only
 * one is configured: which Grafana was read is part of the answer, and a caller that never had to
 * say cannot be said to have known. Adding a second instance therefore changes nothing about how
 * the first is called.
 *
 * <p>Environments come from one place: the user-editable JSON file described by
 * {@link EnvironmentStore}. Nothing is defined in {@code application.yaml} or read from the launch
 * environment, so adding an instance is an edit to a file rather than a re-registration of the MCP
 * server. A name that misses is looked for in the file once more before failing, so that edit takes
 * effect on the next call rather than the next restart.
 *
 * <p>Names are matched case-insensitively.
 */
@Component
public class GrafanaEnvironments {

    private final Map<String, Target> byName = new ConcurrentHashMap<>();
    private final EnvironmentStore store;
    private volatile long loadedFingerprint;

    public GrafanaEnvironments(EnvironmentStore store) {
        this.store = store;
        this.load();
    }

    /** (Re)builds the registry from the editable file. */
    private synchronized void load() {
        Map<String, Target> rebuilt = new LinkedHashMap<>();
        // Read the fingerprint before the contents, so a write that lands between the two is caught
        // by the next refresh rather than being mistaken for what was loaded.
        final long fingerprint = this.store.fingerprint();
        EnvironmentStore.Contents contents = this.store.read();
        contents.environments().forEach((name, environment) ->
                this.register(rebuilt, name, environment.baseUrl(), environment.token()));
        this.byName.keySet().retainAll(rebuilt.keySet());
        this.byName.putAll(rebuilt);
        this.loadedFingerprint = fingerprint;
    }

    /** Re-reads the file only when it has changed since the last load. */
    private void refreshIfChanged() {
        if (this.store.fingerprint() != this.loadedFingerprint) {
            this.load();
        }
    }

    /**
     * The environment a call must name.
     *
     * @param name environment name; there is no default, so a blank one is an error
     * @throws IllegalStateException    if nothing is configured at all
     * @throws IllegalArgumentException if the name is blank, or is not one of the configured ones
     */
    public Target resolve(String name) {
        // An edit to the file — adding a credential or removing one — takes effect here, on the next
        // call, rather than at the next restart.
        this.refreshIfChanged();
        if (name == null || name.isBlank()) {
            return this.unnamed();
        }
        Target target = this.byName.get(name.trim().toLowerCase(Locale.ROOT));
        return target == null ? this.unknown(name) : target;
    }

    /** Always throws: the call named no environment and this server does not guess one. */
    private Target unnamed() {
        this.requireConfigured();
        throw new IllegalArgumentException("This tool needs an 'environment' argument saying which "
                + "Grafana to use. There is no default. Configured: "
                + String.join(", ", this.byName.keySet())
                + ". Call list_grafana_environments for what each one is.");
    }

    /** Always throws: the name given is not one of the configured ones. */
    private Target unknown(String name) {
        this.requireConfigured();
        throw new IllegalArgumentException("Unknown Grafana environment '" + name + "'. Configured: "
                + String.join(", ", this.byName.keySet()) + ". To add one, edit " + this.store.file()
                + " — list_grafana_environments describes the file.");
    }

    /** Nothing is configured at all, which is a different problem from naming the wrong instance. */
    private void requireConfigured() {
        if (this.byName.isEmpty()) {
            throw new IllegalStateException("No Grafana environment is configured. Add one to "
                    + this.store.file() + " — call list_grafana_environments for the exact shape "
                    + "to write.");
        }
    }

    /** The editable configuration file, present or not. */
    public EnvironmentStore store() {
        return this.store;
    }

    /** Every configured environment, for {@code list_grafana_environments}. */
    public List<Info> describe() {
        this.refreshIfChanged();
        List<Info> environments = new ArrayList<>();
        this.byName.forEach((name, target) -> environments.add(new Info(name, target.baseUrl())));
        return List.copyOf(environments);
    }

    /**
     * Registers an environment, ignoring one that is not fully configured — a half-written entry for
     * an instance that does not exist yet should not advertise itself as available.
     */
    private void register(Map<String, Target> into, String name, String baseUrl, String token) {
        boolean named = name != null && !name.isBlank();
        boolean addressed = baseUrl != null && !baseUrl.isBlank();
        boolean authenticated = token != null && !token.isBlank();
        if (named && addressed && authenticated) {
            String key = name.trim().toLowerCase(Locale.ROOT);
            into.put(key, new Target(key, trimTrailingSlash(baseUrl.trim()),
                    restClient(baseUrl.trim(), token.trim())));
        }
    }

    private static RestClient restClient(String baseUrl, String token) {
        // RestClient.builder() rather than the autoconfigured builder: this app runs with
        // web-application-type=none, so web client autoconfiguration is not active. The default
        // builder still registers the standard message converters (including Jackson).
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static String trimTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * One resolved Grafana instance: its name, its base URL (no trailing slash) and the client that
     * carries its token.
     *
     * @param name    the environment's name, lower-cased
     * @param baseUrl base URL with any trailing slash removed
     * @param rest    HTTP client pre-configured with this instance's base URL and token
     */
    public record Target(String name, String baseUrl, RestClient rest) {
    }

    /**
     * A configured environment, as reported to a caller.
     *
     * @param name    the environment's name
     * @param baseUrl the Grafana it points at
     */
    public record Info(String name, String baseUrl) {
    }
}
