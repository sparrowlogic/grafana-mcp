package com.sparrowlogic.grafanamcp.tools;

import com.sparrowlogic.grafanamcp.client.GrafanaEnvironments;
import com.sparrowlogic.grafanamcp.config.EnvironmentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentToolsTest {

    @TempDir
    Path configDir;

    private EnvironmentTools tools(Path configFile) {
        return new EnvironmentTools(new GrafanaEnvironments(new EnvironmentStore(configFile)));
    }

    private EnvironmentTools toolsWith(String json) throws IOException {
        Path file = this.configDir.resolve("config.json");
        Files.writeString(file, json);
        return this.tools(file);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configuration(Map<String, Object> response) {
        return (Map<String, Object>) response.get("configuration");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> environments(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("environments");
    }

    @Test
    void reportsTheConfiguredEnvironments() throws IOException {
        Map<String, Object> response = this.toolsWith("""
                {"environments": {
                   "production": {"baseUrl": "https://prod.test",     "token": "prod-token"},
                   "local":      {"baseUrl": "http://localhost:3000", "token": "local-token"}}}""")
                .listGrafanaEnvironments();

        assertThat(environments(response)).extracting(entry -> entry.get("name"))
                .containsExactlyInAnyOrder("production", "local");
        assertThat(environments(response)).allSatisfy(entry ->
                assertThat(entry).containsOnlyKeys("name", "baseUrl"));
    }

    @Test
    void advertisesNoDefaultEvenWithASingleEnvironment() throws IOException {
        Map<String, Object> response = this.toolsWith("""
                {"environments": {
                   "production": {"baseUrl": "https://prod.test", "token": "prod-token"}}}""")
                .listGrafanaEnvironments();

        // The old response told the caller the argument could be omitted; saying so now would be
        // an instruction to make a call that always fails.
        assertThat(response).doesNotContainKey("defaultEnvironment");
        assertThat(response.get("note").toString())
                .contains("there is no default")
                .contains("required");
    }

    @Test
    void saysSoWhenNothingIsConfiguredAtAll() {
        Map<String, Object> response = this.tools(this.configDir.resolve("absent.json"))
                .listGrafanaEnvironments();

        assertThat(environments(response)).isEmpty();
        assertThat(response.get("note").toString()).contains("No environment is configured");
    }

    @Test
    void neverReturnsATokenInAnyForm() throws IOException {
        Map<String, Object> response = this.toolsWith("""
                {"environments": {
                   "local": {"baseUrl": "http://localhost:3000", "token": "glsa_supersecret"}}}""")
                .listGrafanaEnvironments();

        // The whole reason there is no add/update tool is to keep tokens out of the conversation;
        // this response must not put one back in.
        assertThat(response.toString()).doesNotContain("glsa_supersecret");
    }

    @Test
    void tellsTheCallerWhichFileToEditAndWhatToWrite() {
        Path file = this.configDir.resolve("absent.json");

        Map<String, Object> configuration = configuration(this.tools(file).listGrafanaEnvironments());

        assertThat(configuration.get("editableFile")).isEqualTo(file.toString());
        assertThat(configuration.get("fileExists")).isEqualTo(false);
        assertThat(configuration.get("fileTemplate").toString())
                .contains("baseUrl").contains("token")
                .doesNotContain("defaultEnvironment");
        assertThat(configuration.get("howToAdd").toString())
                .contains("chmod 600").contains("Service accounts");
        // The env-var route is gone; pointing at it would send someone somewhere that does nothing.
        assertThat(configuration).doesNotContainKey("alternativeViaEnvironment");
        assertThat(configuration.toString()).doesNotContain("GRAFANA_URL");
    }

    @Test
    void reportsWhetherTheFileExists() throws IOException {
        Map<String, Object> response = this.toolsWith("""
                {"environments": {"lab": {"baseUrl": "http://lab:3000", "token": "lab-token"}}}""")
                .listGrafanaEnvironments();

        assertThat(configuration(response).get("fileExists")).isEqualTo(true);
        assertThat(environments(response)).extracting(entry -> entry.get("name"))
                .containsExactly("lab");
    }

    @Test
    void aMalformedFileIsReportedAsAProblemToFix() throws IOException {
        Map<String, Object> configuration = configuration(
                this.toolsWith("{ oops").listGrafanaEnvironments());

        assertThat(configuration.get("problem").toString())
                .contains("could not be read").contains("fileTemplate");
    }

    @Test
    void looseFilePermissionsAreCalledOut() throws IOException {
        Path file = this.configDir.resolve("config.json");
        Files.writeString(file, "{\"environments\": {}}");
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions
                .fromString("rw-r--r--"));

        Map<String, Object> configuration = configuration(this.tools(file).listGrafanaEnvironments());

        assertThat(configuration.get("warning").toString()).contains("chmod 600");
    }
}
