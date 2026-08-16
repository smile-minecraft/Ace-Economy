package com.smile.aceeconomy.infrastructure.integration.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * [TEST:P3] 測試用假物件，實作 {@link DiscordTransport}，可控制投遞結果。
 *
 * <p>模式：</p>
 * <ul>
 *   <li>{@link Mode#SUCCESS} — 立即以 204 完成 future。</li>
 *   <li>{@link Mode#FAIL} — 以例外完成 future（模擬逾時/錯誤）。</li>
 *   <li>{@link Mode#HANG} — 回傳永不完成的 future（模擬停滯的投遞）。</li>
 * </ul>
 */
public final class FakeDiscordTransport implements DiscordTransport {

    public enum Mode { SUCCESS, FAIL, HANG }

    private final List<DiscordPayload> sent = new ArrayList<>();
    private volatile Mode mode = Mode.SUCCESS;

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    @Override
    public CompletableFuture<DiscordSendResult> send(DiscordPayload payload) {
        sent.add(payload);
        return switch (mode) {
            case SUCCESS -> CompletableFuture.completedFuture(DiscordSendResult.ok(204));
            case FAIL -> CompletableFuture.failedFuture(new RuntimeException("simulated delivery failure"));
            case HANG -> new CompletableFuture<>(); // never completes
        };
    }

    public List<DiscordPayload> sent() {
        return sent;
    }
}
