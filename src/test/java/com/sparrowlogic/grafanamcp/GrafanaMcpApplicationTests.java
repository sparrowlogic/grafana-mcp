package com.sparrowlogic.grafanamcp;

import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.tools.AnnotationTools;
import com.sparrowlogic.grafanamcp.tools.DashboardTools;
import com.sparrowlogic.grafanamcp.tools.EnvironmentTools;
import com.sparrowlogic.grafanamcp.tools.LokiTools;
import com.sparrowlogic.grafanamcp.tools.MetricsTools;
import com.sparrowlogic.grafanamcp.tools.NodeMetricsTools;
import com.sparrowlogic.grafanamcp.tools.TempoTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class GrafanaMcpApplicationTests {

    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void everyToolClassIsRegisteredWithTheProvider() {
        // The provider's tool list is a hand-maintained allowlist, so a tool class that exists as a
        // bean but is missing from it registers nothing at all — silently.
        assertThat(context.getBean(DashboardTools.class)).isNotNull();
        assertThat(context.getBean(AnnotationTools.class)).isNotNull();
        assertThat(context.getBean(MetricsTools.class)).isNotNull();
        assertThat(context.getBean(NodeMetricsTools.class)).isNotNull();
        assertThat(context.getBean(LokiTools.class)).isNotNull();
        assertThat(context.getBean(TempoTools.class)).isNotNull();
        assertThat(context.getBean(EnvironmentTools.class)).isNotNull();

        assertThat(context.getBean(ToolCallbackProvider.class).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("search_dashboards", "list_annotations", "query_prometheus", "fetch_cpu",
                        "search_loki_logs", "count_loki_logs", "get_trace", "search_traces",
                        "list_grafana_environments");
    }

    @Test
    void theConfiguredEnvironmentResolvesOnlyWhenNamed() {
        GrafanaEnvironments environments = context.getBean(GrafanaEnvironments.class);

        // Configured entirely by the file surefire points grafana.mcp.config-file at; nothing in
        // application.yaml or the launch environment contributes an instance.
        assertThat(environments.describe()).hasSize(1);
        assertThat(environments.resolve("test").baseUrl()).isEqualTo("https://grafana.test");
        assertThatThrownBy(() -> environments.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs an 'environment' argument");
        assertThatThrownBy(() -> environments.resolve("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Grafana environment");
    }

    /**
     * Tools that reach no Grafana and so name none: the directory itself, which is the bootstrap
     * call, and the two cache readers, whose searchId belongs to one environment already.
     */
    private static final List<String> WITHOUT_AN_ENVIRONMENT =
            List.of("list_grafana_environments", "fetch_loki_page", "get_loki_entry");

    @Test
    void everyToolThatReachesGrafanaDemandsAnEnvironmentInItsSchema() {
        // The runtime guard in GrafanaEnvironments.resolve is the real one, but it only fires after a
        // model has already guessed. This asserts the schema itself says the argument is mandatory —
        // and that making it so did not sweep up the genuinely optional arguments beside it.
        List<ToolCallback> callbacks = List.of(context.getBean(ToolCallbackProvider.class)
                .getToolCallbacks());
        assertThat(callbacks).hasSizeGreaterThan(1);

        for (ToolCallback callback : callbacks) {
            String name = callback.getToolDefinition().name();
            String schema = callback.getToolDefinition().inputSchema();
            if (WITHOUT_AN_ENVIRONMENT.contains(name)) {
                assertThat(schema).as(name).doesNotContain("\"environment\"");
            } else {
                assertThat(requiredOf(schema)).as(name)
                        .contains("environment")
                        .doesNotContain("limit")
                        .doesNotContain("datasourceUid");
            }
        }
    }

    @Test
    void theCacheReadersAreNamedAsExceptionsWhereverTheRuleIsStated() throws IOException {
        // Schemas are additionalProperties:false, so "pass environment to every tool" is not a
        // harmless overstatement — it instructs the model to make a call the server hard-rejects.
        // The shipped instructions are read from the file rather than the context: this test's own
        // application.yaml shadows the main one, so the context never holds the text that ships.
        String instructions = Files.readString(Path.of("src/main/resources/application.yaml"));

        for (String text : List.of(instructions, describeDirectory())) {
            assertThat(text).contains("fetch_loki_page").contains("get_loki_entry");
            // The rule has to carry its qualifier, not just mention the exceptions somewhere later.
            assertThat(text).contains("reaches Grafana").doesNotContain("Every tool REQUIRES");
        }
    }

    /** The directory tool's own description plus the note it returns — both are read by the model. */
    private String describeDirectory() {
        ToolCallback directory = List.of(context.getBean(ToolCallbackProvider.class)
                        .getToolCallbacks()).stream()
                .filter(callback -> "list_grafana_environments"
                        .equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
        return directory.getToolDefinition().description()
                + context.getBean(EnvironmentTools.class).listGrafanaEnvironments().get("note");
    }

    /** The schema's "required" array, as text — enough to assert membership without a JSON parse. */
    private static String requiredOf(String schema) {
        int start = schema.indexOf("\"required\"");
        if (start < 0) {
            return "";
        }
        int open = schema.indexOf('[', start);
        int close = schema.indexOf(']', open);
        return open < 0 || close < 0 ? "" : schema.substring(open, close + 1);
    }
}
