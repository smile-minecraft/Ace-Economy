package com.smile.aceeconomy.infrastructure.item;

import com.smile.acelib.item.ItemIdentity;
import com.smile.acelib.item.ItemSchemaVersion;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;

import net.kyori.adventure.text.Component;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-metadata regression for {@link V2BanknoteFactory}, exercised against the real AceLib
 * {@code AceItemFactory} encode/decode path instead of a fake factory. The AceLib ItemStack is
 * mocked (a real one needs a live server) but the PDC contract it reads and writes is a faithful,
 * map-backed {@link PersistentDataContainer}, so the identity / schema / gameplay keys under
 * {@code aececonomy.v2:} are the same ones the server stores.
 *
 * <p>Locks two defects the live inventory exposed: (1) {@code decode} read {@code _version} from
 * the {@code banknote} key namespace instead of the v2 namespace, so a real note never decoded at
 * all, and (2) AceLib's {@code create} always stamps {@code _version=1.0} regardless of the
 * identity, so even a correctly read note carried a stale schema and the validator rejected it.
 * The mint must therefore stamp the v2 schema itself; a note whose identity is v2 but whose stamp
 * is the legacy {@code 1.0} stays rejected rather than being loosened into acceptance.
 */
class V2BanknoteFactorySchemaTest {

    private static final LocalizedBanknoteFace FACE =
            new LocalizedBanknoteFace((key, vars) -> Component.text(key));
    private static final V2BanknoteFactory FACTORY = new V2BanknoteFactory(FACE);

    private static final UUID ISSUER = UUID.fromString("7bdaa41d-1e4c-4272-babd-98c62d376ce4");
    private static final UUID NONCE = UUID.fromString("fbf98a13-d6d2-4f31-a322-4e3580400f2d");

    private static BanknoteClaim claim() {
        ItemIdentity id = new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY,
                BanknoteClaim.V2_SCHEMA.major(), BanknoteClaim.V2_SCHEMA.minor());
        return new BanknoteClaim(id, BanknoteClaim.V2_SCHEMA, 100L, ISSUER, NONCE, "dollar");
    }

    // ---------------- live mismatch: identity v2 with a legacy 1.0 stamp ----------------

    @Test
    void liveMintedShapeDecodesAndIsRejectedByTheStrictSchemaCheck() {
        // The exact key layout MCC read from the server: aececonomy.v2:_id_major=2, _id_minor=0,
        // aececonomy.v2:_version=1.0, plus the gameplay payload.
        ItemStack note = liveShapedStack("1.0");

        Optional<BanknoteClaim> decoded = FACTORY.decode(note);

        assertTrue(decoded.isPresent(),
                "a v2 identity must decode even when its schema stamp is the legacy 1.0");
        assertEquals(new ItemSchemaVersion(1, 0), decoded.get().schema(),
                "the stale stamp must be read from the v2 namespace, not assumed to be 2.0");
        ValidationResult result = new BanknoteValidator(new InMemoryIdempotencyGuard())
                .validateStructure(decoded.get());
        assertFalse(result.success(), "an identity-2.0 note stamped 1.0 must not pass validation");
        assertEquals("schema.version", result.reasonCode());
    }

    // ---------------- mint must produce a real, decodable, valid v2 note ----------------

    @Test
    void mintStampsV2SchemaOnTheRealAceLibMetadataAndRoundTrips() {
        BanknoteClaim claim = claim();
        try (MockedConstruction<ItemStack> construction = Mockito.mockConstruction(
                ItemStack.class,
                (mock, context) -> bindStatefulMeta(mock))) {
            Optional<ItemStack> minted = FACTORY.mint(claim);

            assertTrue(minted.isPresent(), "a positive claim must mint");
            ItemStack note = minted.get();

            String stamped = note.getItemMeta().getPersistentDataContainer()
                    .get(new NamespacedKey(BanknoteClaim.V2_NAMESPACE, "_version"),
                            PersistentDataType.STRING);
            assertEquals("2.0", stamped,
                    "mint must write the v2 schema, not AceLib's hardcoded 1.0 default");

            Optional<BanknoteClaim> decoded = FACTORY.decode(note);
            assertTrue(decoded.isPresent(), "a freshly minted note must decode");
            assertEquals(claim, decoded.get(),
                    "identity, schema and gameplay payload must survive the mint/decode round trip");
            ValidationResult result = new BanknoteValidator(new InMemoryIdempotencyGuard())
                    .validateStructure(decoded.get());
            assertTrue(result.success(), "a freshly minted note must pass structural validation");
        }
    }

    @Test
    void mintOfNonPositiveValueStillRefuses() {
        BanknoteClaim claim = claim();
        BanknoteClaim negative = new BanknoteClaim(claim.identity(), claim.schema(), -1L,
                claim.issuer(), claim.nonce(), claim.currency());
        assertTrue(FACTORY.mint(negative).isEmpty(), "a non-positive note must never mint");
    }

    // ---------------- no loosening: arbitrary schemas stay rejected ----------------

    @Test
    void arbitrarySchemaVersionsAreStillRejected() {
        BanknoteValidator validator = new BanknoteValidator(new InMemoryIdempotencyGuard());
        for (ItemSchemaVersion other : new ItemSchemaVersion[] {
                new ItemSchemaVersion(1, 0), new ItemSchemaVersion(3, 0),
                new ItemSchemaVersion(2, 1), new ItemSchemaVersion(0, 2) }) {
            BanknoteClaim claim = new BanknoteClaim(claim().identity(), other, 100L,
                    ISSUER, UUID.randomUUID(), "dollar");
            ValidationResult result = validator.validateStructure(claim);
            assertFalse(result.success(), "schema " + other + " must not be accepted");
            assertEquals("schema.version", result.reasonCode());
        }
    }

    // ---------------- test doubles: a minimal, faithful PDC + stateful mock stack ----------------

    private static ItemStack liveShapedStack(String version) {
        MapPdc pdc = new MapPdc();
        setString(pdc, "_id_namespace", BanknoteClaim.V2_NAMESPACE);
        setString(pdc, "_id", BanknoteClaim.V2_KEY);
        setInt(pdc, "_id_major", BanknoteClaim.V2_SCHEMA.major());
        setInt(pdc, "_id_minor", BanknoteClaim.V2_SCHEMA.minor());
        setString(pdc, "_version", version);
        setString(pdc, "_gameplay_value", "100");
        setString(pdc, "_gameplay_issuer", ISSUER.toString());
        setString(pdc, "_gameplay_nonce", NONCE.toString());
        setString(pdc, "_gameplay_currency", "dollar");
        return statefulStack(pdc);
    }

    private static void setString(MapPdc pdc, String key, String value) {
        pdc.set(new NamespacedKey(BanknoteClaim.V2_NAMESPACE, key), PersistentDataType.STRING, value);
    }

    private static void setInt(MapPdc pdc, String key, int value) {
        pdc.set(new NamespacedKey(BanknoteClaim.V2_NAMESPACE, key), PersistentDataType.INTEGER, value);
    }

    /** Route the mocked stack's meta getter/setter through one mutable holder, so PDC writes stick. */
    private static void bindStatefulMeta(ItemStack stack) {
        ItemMeta[] holder = { newMeta(new MapPdc()) };
        Mockito.when(stack.getItemMeta()).thenAnswer(invocation -> holder[0]);
        Mockito.doAnswer(invocation -> {
            holder[0] = invocation.getArgument(0);
            return null;
        }).when(stack).setItemMeta(Mockito.any(ItemMeta.class));
    }

    private static ItemStack statefulStack(MapPdc pdc) {
        ItemStack stack = Mockito.mock(ItemStack.class);
        ItemMeta[] holder = { newMeta(pdc) };
        Mockito.when(stack.getItemMeta()).thenAnswer(invocation -> holder[0]);
        Mockito.doAnswer(invocation -> {
            holder[0] = invocation.getArgument(0);
            return null;
        }).when(stack).setItemMeta(Mockito.any(ItemMeta.class));
        return stack;
    }

    private static ItemMeta newMeta(MapPdc pdc) {
        ItemMeta meta = Mockito.mock(ItemMeta.class);
        Mockito.when(meta.getPersistentDataContainer()).thenReturn(pdc);
        Mockito.when(meta.hasCustomName()).thenReturn(false);
        Mockito.when(meta.clone()).thenAnswer(invocation -> {
            MapPdc copy = new MapPdc();
            pdc.copyTo(copy, true);
            return newMeta(copy);
        });
        return meta;
    }

    /**
     * Minimal map-backed {@link PersistentDataContainer} carrying String/Integer values, matching
     * the storage semantics AceLib's ItemFactory relies on (set/get/has/copyTo). Serialization is
     * intentionally unsupported — the production path never serialises a note.
     */
    private static final class MapPdc implements PersistentDataContainer {

        private final Map<NamespacedKey, Object> values = new LinkedHashMap<>();
        private final Map<NamespacedKey, PersistentDataType<?, ?>> types = new LinkedHashMap<>();

        @Override
        public <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value) {
            values.put(key, value);
            types.put(key, type);
        }

        @Override
        public void remove(NamespacedKey key) {
            values.remove(key);
            types.remove(key);
        }

        @Override
        public void readFromBytes(byte[] bytes, boolean clear) {
            throw new UnsupportedOperationException("banknote metadata is never serialized");
        }

        @Override
        public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
            return values.containsKey(key);
        }

        @Override
        public boolean has(NamespacedKey key) {
            return values.containsKey(key);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type) {
            return (C) values.get(key);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (C) value;
        }

        @Override
        public Set<NamespacedKey> getKeys() {
            return new LinkedHashSet<>(values.keySet());
        }

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void copyTo(PersistentDataContainer other, boolean replace) {
            for (Map.Entry<NamespacedKey, Object> entry : values.entrySet()) {
                if (!replace && other.has(entry.getKey())) {
                    continue;
                }
                other.set(entry.getKey(), (PersistentDataType) types.get(entry.getKey()), entry.getValue());
            }
        }

        @Override
        public PersistentDataAdapterContext getAdapterContext() {
            return null;
        }

        @Override
        public byte[] serializeToBytes() {
            throw new UnsupportedOperationException("banknote metadata is never serialized");
        }

        @Override
        public int getSize() {
            return values.size();
        }
    }
}
