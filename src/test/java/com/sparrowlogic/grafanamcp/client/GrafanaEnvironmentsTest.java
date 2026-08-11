package com.sparrowlogic.grafanamcp.client;

import com.sparrowlogic.grafanamcp.config.EnvironmentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrafanaEnvironmentsTest {

    @TempDir
    Path configDir;

    /** A store pointing at a file that does not exist, i.e. nothing configured at all. */
    private EnvironmentStore noFile() {
        return new EnvironmentStore(this.configDir.resolve("absent.json"));
    }

    private EnvironmentStore fileWith(String json) throws IOException {
        Path file = this.configDir.resolve("config.json");
        Files.writeString(file, json);
        return new EnvironmentStore(file);
    }

    private GrafanaEnvironments environmentsFrom(String json) throws IOException {
        return new GrafanaEnvironments(this.fileWith(json));
    }

    @Test
    void environmentsResolveIndependentlyAndCaseInsensitively() throws IOException {
        GrafanaEnvironments environments = this.environmentsFrom("""
                {"environments": {
                   "production":   {"baseUrl": "https://prod.test",         "token": "prod-token"},
                   "local":        {"baseUrl": "http://localhost:3000",     "token": "local-token"},
                   "sparrowlogic": {"baseUrl": "http://grafana.lan:3000",   "token": "lan-token"}}}""");

        assertThat(environments.describe()).extracting(GrafanaEnvironments.Info::name)
                .containsExactlyInAnyOrder("production", "local", "sparrowlogic");
        assertThat(environments.resolve("local").baseUrl()).isEqualTo("http://localhost:3000");
        assertThat(environments.resolve("LOCAL").name()).isEqualTo("local");
        assertThat(environments.resolve(" Sparrowlogic ").baseUrl())
                .isEqualTo("http://grafana.lan:3000");
        // Each environment gets its own client, so a query cannot leak across instances.
        assertThat(environments.resolve("local").rest())
                .isNotSameAs(environments.resolve("sparrowlogic").rest());
    }

    @Test
    void aCallThatNamesNoEnvironmentFailsAndListsTheChoices() throws IOException {
        GrafanaEnvironments environments = this.environmentsFrom("""
                {"environments": {
                   "production": {"baseUrl": "https://prod.test",     "token": "prod-token"},
                   "local":      {"baseUrl": "http://localhost:3000", "token": "local-token"}}}""");

        // There is no default: reading production while believing you are in staging is the mistake
        // this makes impossible. The message carries the names so a bad call self-corrects at once.
        assertThatThrownBy(() -> environments.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs an 'environment' argument")
                .hasMessageContaining("no default")
                .hasMessageContaining("production")
                .hasMessageContaining("local");
        assertThatThrownBy(() -> environments.resolve("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOnlyEnvironmentStillHasToBeNamed() throws IOException {
        GrafanaEnvironments environments = this.environmentsFrom("""
                {"environments": {
                   "production": {"baseUrl": "https://prod.test", "token": "prod-token"}}}""");

        // Deliberately not a convenience case: which Grafana was read is part of the answer, and
        // adding a second instance later must not change how the first one is called.
        assertThatThrownBy(() -> environments.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Configured: production");
        assertThat(environments.resolve("production").baseUrl()).isEqualTo("https://prod.test");
    }

    @Test
    void anEnvironmentMissingAUrlOrTokenIsNotRegistered() throws IOException {
        GrafanaEnvironments environments = this.environmentsFrom("""
                {"environments": {
                   "local":        {"baseUrl": "http://localhost:3000", "token": ""},
                   "sparrowlogic": {"baseUrl": "",                      "token": "lan-token"},
                   "lab":          {"baseUrl": "http://lab:3000",       "token": "lab-token"}}}""");

        // A half-written entry for an instance that does not exist yet must not advertise itself.
        assertThat(environments.describe()).extracting(GrafanaEnvironments.Info::name)
                .containsExactly("lab");
        assertThatThrownBy(() -> environments.resolve("local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Configured: lab");
    }

    @Test
    void anUnknownNameFailsRatherThanPickingSomethingElse() throws IOException {
        GrafanaEnvironments environments = this.environmentsFrom("""
                {"environments": {
                   "production": {"baseUrl": "https://prod.test",     "token": "prod-token"},
                   "local":      {"baseUrl": "http://localhost:3000", "token": "local-token"}}}""");

        assertThatThrownBy(() -> environments.resolve("staging"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Grafana environment 'staging'")
                .hasMessageContaining("Configured: production, local")
                .hasMessageContaining("list_grafana_environments");
    }

    @Test
    void aLeftOverDefaultEnvironmentKeyIsIgnoredRatherThanRejected() throws IOException {
        // Written by an older version of this server; it must not resurrect a default or fail the read.
        GrafanaEnvironments environments = this.environmentsFrom("""
                {"defaultEnvironment": "production",
                 "environments": {
                   "production": {"baseUrl": "https://prod.test", "token": "prod-token"}}}""");

        assertThat(environments.resolve("production").baseUrl()).isEqualTo("https://prod.test");
        assertThatThrownBy(() -> environments.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trailingSlashesAreTrimmedFromBaseUrls() throws IOException {
        GrafanaEnvironments environments = this.environmentsFrom("""
                {"environments": {
                   "prod": {"baseUrl": "https://prod.test/", "token": "token"}}}""");

        assertThat(environments.resolve("prod").baseUrl()).isEqualTo("https://prod.test");
    }

    @Test
    void withNothingConfiguredResolutionSaysWhereToWriteIt() {
        GrafanaEnvironments environments = new GrafanaEnvironments(this.noFile());

        assertThat(environments.describe()).isEmpty();
        assertThatThrownBy(() -> environments.resolve(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Grafana environment is configured")
                .hasMessageContaining("absent.json")
                .hasMessageContaining("list_grafana_environments");
        assertThatThrownBy(() -> environments.resolve("production"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEnvironmentAddedAfterStartupIsPickedUpWithoutARestart() throws IOException {
        Path file = this.configDir.resolve("config.json");
        GrafanaEnvironments environments = new GrafanaEnvironments(new EnvironmentStore(file));
        assertThatThrownBy(() -> environments.resolve("lab"))
                .isInstanceOf(IllegalStateException.class);

        Files.writeString(file, """
                {"environments": {"lab": {"baseUrl": "http://lab:3000", "token": "lab-token"}}}""");

        assertThat(environments.resolve("lab").baseUrl()).isEqualTo("http://lab:3000");
    }

    @Test
    void anEnvironmentRemovedFromTheFileStopsResolving() throws IOException {
        Path file = this.configDir.resolve("config.json");
        Files.writeString(file, """
                {"environments": {
                   "lab":  {"baseUrl": "http://lab:3000",  "token": "lab-token"},
                   "prod": {"baseUrl": "https://prod.test", "token": "prod-token"}}}""");
        GrafanaEnvironments environments = new GrafanaEnvironments(new EnvironmentStore(file));
        assertThat(environments.resolve("lab")).isNotNull();

        Files.writeString(file, """
                {"environments": {
                   "prod": {"baseUrl": "https://prod.test", "token": "prod-token"}}}""");

        // Deleting the credential is deleting the line; the next miss re-reads and it is gone.
        assertThatThrownBy(() -> environments.resolve("lab"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Grafana environment 'lab'");
    }

    @Test
    void aMalformedFileIsReportedRatherThanFatal() throws IOException {
        EnvironmentStore store = this.fileWith("{ this is not json");

        GrafanaEnvironments environments = new GrafanaEnvironments(store);

        // Startup survives it, and list_grafana_environments is what tells the user why.
        assertThat(environments.describe()).isEmpty();
        assertThat(store.read().error()).contains("could not be read");
    }

    @Test
    void anUnknownNamePointsAtTheFileToEdit() throws IOException {
        GrafanaEnvironments environments = this.environmentsFrom("""
                {"environments": {
                   "production": {"baseUrl": "https://prod.test", "token": "prod-token"}}}""");

        assertThatThrownBy(() -> environments.resolve("lab"))
                .hasMessageContaining("config.json")
                .hasMessageContaining("list_grafana_environments");
    }
}
