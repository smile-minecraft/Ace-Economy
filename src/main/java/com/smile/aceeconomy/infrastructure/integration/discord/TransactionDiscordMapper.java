package com.smile.aceeconomy.infrastructure.integration.discord;

import java.util.Collection;
import java.util.Objects;

/**
 * Maps a committed {@link DiscordNotificationRequest} onto a {@link DiscordPayload}.
 *
 * <p>Every field value is sanitized through {@link DiscordPayloadFilter} (length-bounded and secret
 * redacted) before being placed in the payload, so the produced JSON can never contain an
 * over-long or credential-leaking value.</p>
 */
public final class TransactionDiscordMapper {

    private final DiscordPayloadFilter filter;
    private final Collection<String> secrets;

    public TransactionDiscordMapper(DiscordPayloadFilter filter, Collection<String> secrets) {
        this.filter = Objects.requireNonNull(filter, "filter");
        this.secrets = secrets; // nullable is allowed; the filter tolerates it
    }

    public DiscordPayload map(DiscordNotificationRequest request) {
        Objects.requireNonNull(request, "request");
        String type = filter.sanitize(request.type(), DiscordPayloadFilter.MAX_TITLE_LENGTH, secrets);
        String sender = filter.sanitize(request.senderName(), DiscordPayloadFilter.MAX_FIELD_VALUE_LENGTH, secrets);
        String receiver = filter.sanitize(request.receiverName(), DiscordPayloadFilter.MAX_FIELD_VALUE_LENGTH, secrets);
        String amount = filter.sanitize(request.amountText(), DiscordPayloadFilter.MAX_FIELD_VALUE_LENGTH, secrets);
        int color = request.adminAction() ? 0xFF5555 : 0x55FF55;
        return new DiscordPayload.Builder()
                .title(type)
                .color(color)
                .addField("Sender", sender, true)
                .addField("Receiver", receiver, true)
                .addField("Amount", amount, true)
                .build();
    }
}
