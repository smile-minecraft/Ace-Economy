package com.smile.aceeconomy.gui.v2;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Locks the result mapping and the player-visible wording together: a
 * swapped hand is a completed credit whose item was kept, and an uncertain
 * withdraw must never promise delivery, balance or refund state it cannot
 * prove. Fails on wording that over-promises.
 */
class BanknoteMessageWordingTest {

    private static String text(String locale, String key) {
        String resource = "lang/" + locale + ".yml";
        try (InputStream in = BanknoteMessageWordingTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource: " + resource);
            Map<String, Object> root = new Yaml().load(in);
            assertNotNull(root, "empty resource: " + resource);
            Object banknote = root.get("banknote");
            assertTrue(banknote instanceof Map, "no banknote section in " + resource);
            Object value = ((Map<?, ?>) banknote).get(key);
            assertNotNull(value, locale + " is missing banknote." + key);
            return String.valueOf(value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("cannot read " + resource, e);
        }
    }

    @Test
    void mismatchMapsToMismatchKey() {
        V2BankGuiSession.Reply reply = V2BankGuiSession.replyFor(
                V2BankGuiSession.ClickOutcome.itemMismatch(), null);
        assertNotNull(reply, "a swapped hand must reply, never stay silent");
        assertTrue(reply.key().equals("banknote.redeem-mismatch"),
                "mismatch must map to the mismatch key, got: " + reply.key());
    }

    @Test
    void mismatchWordingStatesTheCreditCompleted() {
        assertTrue(text("en_US", "redeem-mismatch").toLowerCase().contains("credit"),
                "en mismatch must state the credit completed");
        assertTrue(text("zh_TW", "redeem-mismatch").contains("入帳"),
                "zh_TW mismatch must state the credit completed");
        assertTrue(text("zh_CN", "redeem-mismatch").contains("入账"),
                "zh_CN mismatch must state the credit completed");
    }

    @Test
    void mismatchWordingNeverClaimsACancellation() {
        assertFalse(text("en_US", "redeem-mismatch").toLowerCase().contains("cancel"),
                "en mismatch must not claim the redemption was cancelled");
        assertFalse(text("zh_TW", "redeem-mismatch").contains("取消"),
                "zh_TW mismatch must not claim the redemption was cancelled");
        assertFalse(text("zh_CN", "redeem-mismatch").contains("取消"),
                "zh_CN mismatch must not claim the redemption was cancelled");
    }

    @Test
    void uncertainWithdrawMapsToWithdrawPendingKey() {
        for (String reason : new String[]{
                com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow.PENDING_WITHDRAW_INDETERMINATE,
                com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow.PENDING_DELIVERY_UNCERTAIN}) {
            V2BankGuiSession.Reply reply = V2BankGuiSession.replyFor(
                    V2BankGuiSession.ClickOutcome.pending(reason), null);
            assertNotNull(reply, "pending " + reason + " must reply");
            assertTrue(reply.key().equals("banknote.withdraw-pending"),
                    "uncertain " + reason + " must map to withdraw-pending, got: " + reply.key());
        }
    }

    @Test
    void withdrawPendingWordingWarnsAgainstRetrying() {
        assertTrue(text("en_US", "withdraw-pending").toLowerCase().contains("do not retry"),
                "en pending must warn against retrying");
        assertTrue(text("zh_TW", "withdraw-pending").contains("請勿重試"),
                "zh_TW pending must warn against retrying");
        assertTrue(text("zh_CN", "withdraw-pending").contains("请勿重复"),
                "zh_CN pending must warn against retrying");
    }

    @Test
    void withdrawPendingWordingPromisesNoOutcome() {
        String en = text("en_US", "withdraw-pending").toLowerCase();
        assertFalse(en.contains("refund"), "en pending must not claim a refund happened: " + en);
        assertFalse(en.contains("unchanged"), "en pending must not claim the balance is unchanged: " + en);
        assertFalse(en.contains("nothing was delivered"),
                "en pending must not claim nothing was delivered: " + en);
        String tw = text("zh_TW", "withdraw-pending");
        assertFalse(tw.contains("未交付"), "zh_TW pending must not claim nothing was delivered: " + tw);
        assertFalse(tw.contains("已退回"), "zh_TW pending must not claim a refund happened: " + tw);
        String cn = text("zh_CN", "withdraw-pending");
        assertFalse(cn.contains("未交付"), "zh_CN pending must not claim nothing was delivered: " + cn);
        assertFalse(cn.contains("已退回"), "zh_CN pending must not claim a refund happened: " + cn);
    }
}
