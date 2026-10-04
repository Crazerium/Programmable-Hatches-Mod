package reobf.proghatches.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import reobf.proghatches.main.MyMod;
import reobf.proghatches.net.SubItemReplyMessage;
import reobf.proghatches.util.SubItemDamages;

/**
 * The client's half of {@link SubItemDamages}: answers the server's question "which damage values does
 * this item come in?" from {@code Item.getSubItems}, which exists on the client only.
 * <p>
 * Rules for one item:
 * <ul>
 * <li>only an item whose getHasSubtypes() is true is looked at. That flag says nothing about how many
 * stacks getSubItems returns, it only decides whether the item is looked at at all;</li>
 * <li>getSubItems may list stacks of other items, those are dropped;</li>
 * <li>distinct damage values are kept in the order listed, at most
 * {@link SubItemDamages#MAX_PER_ITEM}.</li>
 * </ul>
 * Results are cached per {@link Item} object for the lifetime of the client. Item objects stay the same
 * from world to world, numeric ids do not, so ids are looked up only while a packet is read or written.
 * <p>
 * The server's question arrives on the network thread, getSubItems belongs on the client thread, so
 * questions are queued and answered on the next client tick. Only the asked items are computed, which
 * is a handful per recipe map.
 * <p>
 * Top-level and client-only on purpose, see the note on WailaFractionRenderer.
 */
@SideOnly(Side.CLIENT)
public final class ClientSubItemDamages {

    public static final ClientSubItemDamages INSTANCE = new ClientSubItemDamages();

    private static final short[] NONE = {};

    /** client thread only */
    private final Map<Item, short[]> cache = new IdentityHashMap<>();
    private final Queue<int[]> questions = new ConcurrentLinkedQueue<>();

    private ClientSubItemDamages() {}

    /** any thread */
    public void ask(int[] ids) {
        if (ids != null && ids.length > 0) questions.add(ids);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int[] ids;
        while ((ids = questions.poll()) != null) answer(ids);
    }

    private void answer(int[] ids) {
        if (Minecraft.getMinecraft()
            .getNetHandler() == null) return; // left the server in the meantime
        for (int from = 0; from < ids.length; from += SubItemReplyMessage.MAX_ENTRIES) {
            int n = Math.min(SubItemReplyMessage.MAX_ENTRIES, ids.length - from);
            int[] chunkIds = new int[n];
            short[][] chunkDamages = new short[n][];
            for (int i = 0; i < n; i++) {
                chunkIds[i] = ids[from + i];
                Item item = Item.getItemById(chunkIds[i]);
                chunkDamages[i] = item == null ? NONE : damagesOf(item);
            }
            MyMod.net.sendToServer(new SubItemReplyMessage(chunkIds, chunkDamages));
        }
    }

    /** client thread only */
    public short[] damagesOf(Item item) {
        short[] known = cache.get(item);
        if (known == null) {
            known = compute(item);
            cache.put(item, known);
        }
        return known;
    }

    private static short[] compute(Item item) {
        if (!item.getHasSubtypes()) return NONE;

        List<ItemStack> listed = new ArrayList<>();
        list(item, null, listed); // null is what the creative search tab and NEI pass: "everything"
        if (!listsItself(item, listed)) {
            // some items only answer for the tabs they are on
            CreativeTabs[] tabs = null;
            try {
                tabs = item.getCreativeTabs();
            } catch (Throwable ignored) {}
            if (tabs != null) {
                for (CreativeTabs tab : tabs) if (tab != null) list(item, tab, listed);
            }
        }

        short[] raw = new short[listed.size()];
        int n = 0;
        for (ItemStack stack : listed) {
            if (stack == null || stack.getItem() != item) continue; // not this item's own stack
            int damage = stack.getItemDamage();
            if (damage < 0 || damage >= Short.MAX_VALUE) continue;
            raw[n++] = (short) damage;
        }
        short[] own = new short[n];
        System.arraycopy(raw, 0, own, 0, n);
        return SubItemDamages.sanitize(own);
    }

    private static boolean listsItself(Item item, List<ItemStack> listed) {
        for (ItemStack stack : listed) if (stack != null && stack.getItem() == item) return true;
        return false;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void list(Item item, CreativeTabs tab, List<ItemStack> into) {
        List raw = new ArrayList();
        try {
            item.getSubItems(item, tab, raw);
        } catch (Throwable broken) {
            // an item that cannot list itself is simply an item with nothing to report
            MyMod.LOG.warn("getSubItems of {} failed: {}", Item.itemRegistry.getNameForObject(item), broken.toString());
            return;
        }
        for (Object o : raw) if (o instanceof ItemStack) into.add((ItemStack) o);
    }
}
