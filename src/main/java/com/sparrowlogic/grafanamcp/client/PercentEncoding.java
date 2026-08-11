package com.sparrowlogic.grafanamcp.client;

import java.nio.charset.StandardCharsets;

/**
 * Percent-encoding for values placed in a query string, strict enough for Go HTTP servers.
 *
 * <p>Spring's {@code UriUtils.encodeQueryParam} follows RFC 3986, which permits {@code +} in a
 * query component and so leaves it untouched. Go's {@code net/http} — what Loki and Grafana are
 * built on — parses query strings with form semantics, where a literal {@code +} decodes to a
 * <em>space</em>. A LogQL regex like <code>{env=~".+"}</code> therefore arrives as {@code ". "} and
 * silently matches nothing: no error, just an empty result.
 *
 * <p>So everything outside the RFC 3986 unreserved set is encoded, which is always safe to
 * over-do and, unlike {@code URLEncoder}, never emits {@code +} for a space.
 */
public final class PercentEncoding {

    /** RFC 3986 unreserved punctuation; alphanumerics are handled separately. */
    private static final String UNRESERVED_MARKS = "-_.~";

    private PercentEncoding() {
    }

    /** Percent-encodes every byte of {@code value} that is not RFC 3986 unreserved. */
    public static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (unreserved(c)) {
                encoded.append(c);
            } else {
                encoded.append(String.format("%%%02X", b));
            }
        }
        return encoded.toString();
    }

    private static boolean unreserved(char c) {
        boolean upper = c >= 'A' && c <= 'Z';
        boolean lower = c >= 'a' && c <= 'z';
        boolean digit = c >= '0' && c <= '9';
        return upper || lower || digit || UNRESERVED_MARKS.indexOf(c) >= 0;
    }
}
