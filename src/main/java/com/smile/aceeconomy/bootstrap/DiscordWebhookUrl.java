package com.smile.aceeconomy.bootstrap;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Strict validator for a Discord webhook URL used by the bootstrap wiring.
 *
 * <p>Rejects everything that {@code URI.create} alone accepts but that is unsafe or meaningless
 * for an outbound HTTP webhook:</p>
 * <ul>
 *   <li>{@code null} / empty input.</li>
 *   <li>Relative paths (e.g. {@code /hooks/123}, {@code ./local}).</li>
 *   <li>Schemes other than {@code http} / {@code https} (rejects {@code ftp:}, {@code mailto:},
 *       {@code file:}, {@code javascript:}).</li>
 *   <li>Missing host.</li>
 *   <li>Embedded {@code userinfo} ({@code https://user:pass@host/...}).</li>
 *   <li>Out-of-range port (must be {@code 1..65535} when present).</li>
 *   <li>Malformed strings that fail {@link URI} parsing.</li>
 * </ul>
 *
 * <p>Failure messages are fixed strings and never echo the supplied URL, so a malformed secret
 * URL cannot leak through an exception message into logs or test failure reports.</p>
 */
final class DiscordWebhookUrl {

    private static final String MSG_EMPTY = "webhook url is empty";
    private static final String MSG_MALFORMED = "webhook url is malformed";
    private static final String MSG_NOT_ABSOLUTE = "webhook url must be absolute";
    private static final String MSG_SCHEME = "webhook url scheme must be http or https";
    private static final String MSG_HOST = "webhook url host is required";
    private static final String MSG_USERINFO = "webhook url must not contain userinfo";
    private static final String MSG_PORT = "webhook url port out of range";

    private DiscordWebhookUrl() {
    }

    /** Validate {@code raw} as a Discord webhook URL. Returns the parsed {@link URI} on success. */
    static URI validate(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException(MSG_EMPTY);
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(MSG_MALFORMED);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException(MSG_NOT_ABSOLUTE);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(MSG_SCHEME);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException(MSG_HOST);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(MSG_USERINFO);
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException(MSG_PORT);
        }
        return uri;
    }
}
