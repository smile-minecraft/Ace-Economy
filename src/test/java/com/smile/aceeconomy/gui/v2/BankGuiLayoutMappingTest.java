package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.BankGuiConfigParser;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The configured layout must resolve to the legacy slot behaviour by default:
 * slot 4 deposits, 11/13 withdraw fixed amounts, 15 closes, others do nothing.
 */
class BankGuiLayoutMappingTest {

    @Test
    void defaultLayoutResolvesLegacySlots() {
        BankGuiLayout layout = BankGuiConfigParser.parse(null, Set.of("dollar", "token"));
        Function<Integer, BankGuiAction> resolver = BankGuiActions.resolver(layout);

        assertEquals(BankGuiAction.Type.DEPOSIT, resolver.apply(4).type());
        assertEquals(BankGuiAction.Type.WITHDRAW, resolver.apply(11).type());
        assertEquals(100L, resolver.apply(11).amount());
        assertEquals(BankGuiAction.Type.WITHDRAW, resolver.apply(13).type());
        assertEquals(500L, resolver.apply(13).amount());
        assertEquals(BankGuiAction.Type.CLOSE, resolver.apply(15).type());
        assertEquals(BankGuiAction.Type.NONE, resolver.apply(0).type());
        assertEquals(BankGuiAction.Type.NONE, resolver.apply(26).type());
    }
}
