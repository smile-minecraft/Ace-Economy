package com.smile.aceeconomy.infrastructure.acelib;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * MiniMessage renderer for v2 messages.
 *
 * <p>AceLib's {@code MessageService} performs {@code {var}} substitution and prefix
 * concatenation but does NOT parse MiniMessage tags. This renderer is the explicit
 * second stage: it takes the already-substituted, prefix-applied string and
 * deserializes it with Adventure MiniMessage so that {@code <gold>} etc. become
 * styled components instead of being sent verbatim.</p>
 *
 * <p>The typed-placeholder contract (String / Number / BigDecimal / enum) is
 * honoured upstream by {@code LangManager} substitution (via {@code toString()});
 * this renderer only turns the resulting MiniMessage source into a Component or
 * plain text. The contract that {@code <gold>} / {@code <amount>} must never be
 * emitted as raw text is enforced by callers through the plain-text projection.</p>
 */
public final class MessageRenderer {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public Component render(String miniMessageSource) {
        if (miniMessageSource == null || miniMessageSource.isEmpty()) {
            return Component.empty();
        }
        return MINI_MESSAGE.deserialize(miniMessageSource);
    }

    public String plainText(String miniMessageSource) {
        return PLAIN.serialize(render(miniMessageSource));
    }
}
