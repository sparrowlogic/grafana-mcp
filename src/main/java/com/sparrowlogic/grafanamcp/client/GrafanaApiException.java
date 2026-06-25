package com.sparrowlogic.grafanamcp.client;

/**
 * Thrown when the Grafana HTTP API returns a non-2xx response.
 */
public class GrafanaApiException extends RuntimeException {

    private final int status;

    public GrafanaApiException(int status, String body) {
        super("Grafana API request failed with HTTP " + status
                + (body == null || body.isBlank() ? "" : ": " + body));
        this.status = status;
    }

    public int getStatus() {
        return this.status;
    }
}
