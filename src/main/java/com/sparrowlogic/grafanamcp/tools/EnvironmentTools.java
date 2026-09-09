package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.config.EnvironmentStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Tells a caller which Grafana instances this server can reach, and where to edit that list.
 *
 * <p>There are deliberately no tools for adding, changing or deleting an environment. Those would
 * take a service-account token as an argument, which puts a live credential into the conversation
 * and the model's context for the sake of writing one line to a file. Reporting the file's path and
 * shape gets the same job done with the secret staying between the user and their disk.
 */
@Component
public class EnvironmentTools {

    /** The shape of the editable file, shown verbatim so it can be copied. */
    private static final String FILE_TEMPLATE = """
            {
              "environments": {
                "production": { "baseUrl": "https://grafana.example.com", "token": "glsa_…" },
                "local":      { "baseUrl": "http://localhost:3000",       "token": "glsa_…" }
              }
            }""";

    private final GrafanaEnvironments environments;

    public EnvironmentTools(GrafanaEnvironments environments) {
        this.environments = environments;
    }

    @Tool(name = "list_grafana_environments",
            description = "List the Grafana instances this server can query — names and base URLs. "
                    + "Every tool that reaches Grafana needs an 'environment' naming one of them "
                    + "and there is no default, so call this first, and again whenever a call "
                    + "reports an unknown environment. fetch_loki_page and get_loki_entry are the "
                    + "exceptions: their searchId already belongs to one environment, so they "
                    + "reject the argument.\n\nAlso answers 'how do I add an environment?': the "
                    + "response gives the editable JSON config file's path and shape. Adding one "
                    + "means writing that file — no tool takes a token, deliberately, since a "
                    + "token passed as an argument would be recorded in the conversation. New "
                    + "entries are picked up on the next call. Tokens are never returned.")
    public Map<String, Object> listGrafanaEnvironments() {
        List<Map<String, Object>> configured = new ArrayList<>();
        for (GrafanaEnvironments.Info info : this.environments.describe()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", info.name());
            entry.put("baseUrl", info.baseUrl());
            configured.add(entry);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("environments", configured);
        response.put("configuration", this.configuration());
        response.put("note", configured.isEmpty()
                ? "No environment is configured, so no Grafana tool can run yet. Create the file "
                        + "described in 'configuration'."
                : "Pass 'environment' to every tool that reaches Grafana — there is no default, and "
                        + "one is required even though "
                        + (configured.size() == 1 ? "only one is" : "several are")
                        + " configured, so which Grafana was read is always explicit. fetch_loki_page "
                        + "and get_loki_entry are the exceptions: they read a cached snapshot and "
                        + "reject an 'environment' argument. Cached log searches and traces are "
                        + "scoped per environment, so a searchId or trace id from one is not visible "
                        + "from another.");
        return response;
    }

    /** Where configuration lives and what to write there. */
    private Map<String, Object> configuration() {
        EnvironmentStore store = this.environments.store();
        EnvironmentStore.Contents contents = store.read();
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("editableFile", store.file().toString());
        configuration.put("fileExists", contents.present());
        configuration.put("fileTemplate", FILE_TEMPLATE);
        configuration.put("howToAdd", "Create or edit " + store.file() + " with the shape in "
                + "'fileTemplate', then chmod 600 it — it holds Grafana service-account tokens. "
                + "Create the token in Grafana under Administration > Service accounts. This file is "
                + "the only place environments are defined; an entry missing a baseUrl or token is "
                + "ignored rather than half-registered. The next tool call picks up the change — no "
                + "restart, and nothing to re-register.");
        if (contents.error() != null) {
            configuration.put("problem", contents.error()
                    + " — the file is being ignored until it parses; check it against 'fileTemplate'.");
        }
        if (contents.worldReadable()) {
            configuration.put("warning", store.file() + " is readable by more than its owner and it "
                    + "contains tokens. Run: chmod 600 " + store.file());
        }
        return configuration;
    }
}
