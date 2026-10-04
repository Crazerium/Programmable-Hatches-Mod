package reobf.proghatches.util;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraftforge.common.util.FakePlayer;

import reobf.proghatches.main.MyMod;
import reobf.proghatches.net.SubItemRequestMessage;

/**
 * Server-side knowledge of which damage values an item with subtypes really comes in.
 * <p>
 * Needed to turn a recipe input with the wildcard damage (32767, "any damage") into concrete items. The
 * only source of that list is {@code Item.getSubItems}, and that method is client-only: on a dedicated
 * server it does not exist. So the server asks a client. It asks for exactly the items it needs, the
 * client answers from its own cache (see ClientSubItemDamages), and the answer is kept here for as long
 * as the server runs.
 * <p>
 * Items are keyed by the {@link Item} object. Numeric ids differ from world to world and from server to
 * server, so they appear only inside the packets of one session and are never stored.
 * <p>
 * Threading: answers arrive on the network thread, everything else runs on the server thread. The maps
 * are concurrent and the arrays in them are never modified after they are published.
 */
public final class SubItemDamages {

    /** at most this many damage values are recorded per item */
    public static final int MAX_PER_ITEM = 32;

    private static final short[] ZERO = { 0 };
    private static final short[] NONE = {};

    /** what a client has reported; an empty array means "asked, nothing usable came back" */
    private static final Map<Item, short[]> KNOWN = new ConcurrentHashMap<>();
    /** per player, what has been asked of them and is not answered yet */
    private static final Map<UUID, Set<Item>> ASKED = new ConcurrentHashMap<>();

    private SubItemDamages() {}

    public static boolean isKnown(Item item) {
        return KNOWN.containsKey(item);
    }

    /**
     * The damage values a wildcard input of this item stands for. Falls back to damage 0 alone, which is
     * what a wildcard was pinned to before this table existed, whenever the item has no subtypes, nobody
     * has been asked yet, or the client had nothing usable to report. The returned array is shared, do
     * not modify it.
     */
    public static short[] candidates(Item item) {
        if (item != null && item.getHasSubtypes()) {
            short[] known = KNOWN.get(item);
            if (known != null && known.length > 0) return known;
        }
        return ZERO;
    }

    /**
     * Asks the player's client about every given item that has subtypes and is not known yet.
     *
     * @return the items now awaited, a fresh set the caller may modify; empty when there is nothing to
     *         ask or nobody who could answer
     */
    public static Set<Item> request(EntityPlayer player, Collection<Item> items) {
        Set<Item> missing = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Item item : items) {
            if (item != null && item.getHasSubtypes() && !KNOWN.containsKey(item)) missing.add(item);
        }
        if (missing.isEmpty()) return missing;
        if (!(player instanceof EntityPlayerMP) || player instanceof FakePlayer
            || ((EntityPlayerMP) player).playerNetServerHandler == null) {
            missing.clear();
            return missing;
        }

        ASKED.computeIfAbsent(player.getUniqueID(), k -> ConcurrentHashMap.newKeySet())
            .addAll(missing);
        int[] ids = new int[missing.size()];
        int i = 0;
        for (Item item : missing) ids[i++] = Item.getIdFromItem(item);
        MyMod.net.sendTo(new SubItemRequestMessage(ids), (EntityPlayerMP) player);
        return missing;
    }

    /**
     * Takes a client's answer. Only items that were asked of this very player are accepted, the first
     * answer for an item stays, and what is accepted is cleaned up again rather than trusted.
     */
    public static void accept(EntityPlayerMP player, int[] ids, short[][] damages) {
        if (player == null || ids == null || damages == null) return;
        Set<Item> asked = ASKED.get(player.getUniqueID());
        if (asked == null) return;
        for (int i = 0; i < ids.length && i < damages.length; i++) {
            Item item = Item.getItemById(ids[i]);
            if (item == null || !asked.remove(item)) continue;
            KNOWN.putIfAbsent(item, item.getHasSubtypes() ? sanitize(damages[i]) : NONE);
        }
    }

    /** distinct values in the order given, real damage values only, at most {@link #MAX_PER_ITEM} */
    public static short[] sanitize(short[] reported) {
        if (reported == null || reported.length == 0) return NONE;
        short[] kept = new short[Math.min(reported.length, MAX_PER_ITEM)];
        int n = 0;
        outer: for (short damage : reported) {
            if (n == MAX_PER_ITEM) break;
            if (damage < 0 || damage == Short.MAX_VALUE) continue; // 32767 is the wildcard itself
            for (int k = 0; k < n; k++) if (kept[k] == damage) continue outer;
            kept[n++] = damage;
        }
        if (n == 0) return NONE;
        if (n == kept.length) return kept;
        short[] exact = new short[n];
        System.arraycopy(kept, 0, exact, 0, n);
        return exact;
    }
}
