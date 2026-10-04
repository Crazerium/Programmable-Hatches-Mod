package reobf.proghatches.item;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.IIcon;
import net.minecraft.util.StatCollector;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.SelectedPart;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.IInterfaceViewable;
import appeng.items.misc.ItemEncodedPattern;
import appeng.me.cache.CraftingGridCache;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.util.Platform;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import reobf.proghatches.item.PatternCircuitEditor.Op;
import reobf.proghatches.item.PatternCircuitEditor.Outcome;
import reobf.proghatches.item.PatternCircuitEditor.Result;
import reobf.proghatches.main.MyMod;

/**
 * Bulk editor for the programming circuits inside the patterns of ME interfaces and CRIBs.
 * <p>
 * This item used to do one thing: clicked on a device of an ME network, it set the amount of every
 * programming circuit in every pattern of that network to 1. That is still what damage 0 does, so a
 * tool that already exists in a world keeps its meaning. PR #280 had added "right click an interface to
 * strip the programming circuits from its patterns" as a fourth mode of the Programming Toolkit; that
 * code was lost from the toolkit again and lives here now, next to the single-target variants of both
 * operations. Four modes, switched with sneak + right click:
 * <ol start="0">
 * <li>set to 1, for every pattern holder on the clicked device's ME network (the original behaviour)</li>
 * <li>set to 1: set the amount of every programming circuit in the clicked holder's patterns to 1
 * (pattern multiplication multiplies the circuit along with the real inputs)</li>
 * <li>clear: remove the programming circuits from every pattern of the clicked pattern holder</li>
 * <li>clear, for every pattern holder on the clicked device's ME network</li>
 * </ol>
 * The network-wide modes are asked for twice: the first click only reports what would change, a second
 * click on the same network within {@link #CONFIRM_TICKS} performs it. Clearing cannot be undone, and one
 * stray click should not be able to rewrite every pattern of a base.
 * <p>
 * A "pattern holder" is anything the Interface Terminal can show, i.e. an {@link IInterfaceViewable}:
 * the ME interface and the AE2FC dual interface as block, cable part and P2P tunnel, GregTech's crafting
 * input buffers, this mod's pattern input hatches, and whatever other add-on follows the same contract.
 * The terminal moves patterns in and out of {@link IInterfaceViewable#getPatterns()} itself, so every
 * holder already has to notice a slot being replaced through that inventory, which is all this tool
 * does.
 */
public class ItemFixer2 extends Item {

    public static final int MODE_SET_ONE_NETWORK = 0;
    public static final int MODE_SET_ONE = 1;
    public static final int MODE_CLEAR = 2;
    public static final int MODE_CLEAR_NETWORK = 3;
    public static final int MODES = 4;

    /** how long a network-wide request waits for its confirming click */
    public static final int CONFIRM_TICKS = 200;

    private static final String LANG = "proghatch.itemfixer2.";

    @SideOnly(Side.CLIENT)
    private IIcon[] icons;

    public ItemFixer2() {
        this.setMaxStackSize(1);
        this.setHasSubtypes(true);
        this.setMaxDamage(0);
    }

    /** anything that is not a mode, a wildcard damage for one, reads as the original behaviour */
    public static int getMode(ItemStack stack) {
        int damage = stack.getItemDamage();
        return damage >= 0 && damage < MODES ? damage : MODE_SET_ONE_NETWORK;
    }

    private static boolean isNetworkMode(int mode) {
        return mode == MODE_SET_ONE_NETWORK || mode == MODE_CLEAR_NETWORK;
    }

    private static Op opOf(int mode) {
        return mode == MODE_CLEAR || mode == MODE_CLEAR_NETWORK ? Op.CLEAR : Op.SET_ONE;
    }

    private static String modeKey(int mode) {
        return LANG + "mode." + mode;
    }

    // ===================== presentation =====================

    @SideOnly(Side.CLIENT)
    @Override
    public void registerIcons(IIconRegister register) {
        icons = new IIcon[MODES];
        for (int i = 0; i < MODES; i++) {
            icons[i] = register.registerIcon("proghatches:fixer2_" + i);
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIconFromDamage(int damage) {
        // the damage of a stack is not guaranteed to be a mode, see ItemProgrammingToolkit (issue #335)
        return icons[damage >= 0 && damage < MODES ? damage : MODE_SET_ONE_NETWORK];
    }

    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        return super.getItemStackDisplayName(stack) + " ("
            + StatCollector.translateToLocal(modeKey(getMode(stack)))
            + ")";
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        int mode = getMode(stack);
        lines.add(
            StatCollector.translateToLocalFormatted(
                LANG + "tooltip.current",
                EnumChatFormatting.AQUA + StatCollector.translateToLocal(modeKey(mode))));
        lines.add(StatCollector.translateToLocal(LANG + "tooltip.mode." + mode));
        if (isNetworkMode(mode)) {
            lines.add(StatCollector.translateToLocalFormatted(LANG + "tooltip.confirm", CONFIRM_TICKS / 20));
        }
        if (opOf(mode) == Op.CLEAR) {
            lines.add(EnumChatFormatting.RED + StatCollector.translateToLocal(LANG + "tooltip.irreversible"));
        }
        lines.add(StatCollector.translateToLocal(LANG + "tooltip.holders"));
        lines.add(StatCollector.translateToLocal(LANG + "tooltip.switch"));
        super.addInformation(stack, player, lines, advanced);
    }

    // ===================== interaction =====================

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (player.isSneaking()) {
            int next = (getMode(stack) + 1) % MODES;
            stack.setItemDamage(next);
            if (!world.isRemote) {
                PENDING.remove(player);
                player.addChatMessage(
                    new ChatComponentTranslation(LANG + "chat.mode", new ChatComponentTranslation(modeKey(next))));
            }
        }
        return stack;
    }

    /**
     * onItemUseFirst rather than onItemUse: an interface opens its GUI on a plain right click, and that
     * is decided before onItemUse would ever run. Returning true here, on the server, is what keeps the
     * GUI closed. On the client this has to return false, because a true there makes the client swallow
     * the click without telling the server about it.
     */
    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (world.isRemote || player.isSneaking()) return false; // sneaking always means "switch mode"
        TileEntity te = world.getTileEntity(x, y, z);
        if (te == null) return false;

        int mode = getMode(stack);
        Target target = Target.resolve(te, hitX, hitY, hitZ);
        if (isNetworkMode(mode) ? target.node == null : target.holder == null) {
            return false; // nothing this mode works on: let the block have the click
        }

        if (!mayEdit(player, te, target.node)) {
            say(player, EnumChatFormatting.RED, "chat.denied");
            return true;
        }

        if (isNetworkMode(mode)) {
            useOnNetwork(player, target.node, mode);
        } else {
            useOnHolder(player, target.holder, mode);
        }
        return true;
    }

    private static void useOnHolder(EntityPlayer player, IInterfaceViewable holder, int mode) {
        Tally tally = new Tally();
        apply(Collections.singletonList(holder), opOf(mode), false, tally);
        report(player, mode, tally);
    }

    private static void useOnNetwork(EntityPlayer player, IGridNode node, int mode) {
        IGrid grid = node.getGrid();
        if (grid == null) {
            say(player, EnumChatFormatting.RED, "chat.nonetwork");
            return;
        }
        List<IInterfaceViewable> holders = new ArrayList<>();
        for (IGridNode n : grid.getNodes()) {
            IGridHost machine = n.getMachine();
            if (machine instanceof IInterfaceViewable) holders.add((IInterfaceViewable) machine);
        }

        long now = player.worldObj.getTotalWorldTime();
        Pending pending = PENDING.remove(player);
        boolean confirmed = pending != null && pending.mode == mode
            && pending.grid.get() == grid
            && now <= pending.expires;

        Tally tally = new Tally();
        if (!confirmed) {
            apply(holders, opOf(mode), true, tally);
            if (tally.patterns == 0) {
                report(player, mode, tally);
                return;
            }
            PENDING.put(player, new Pending(mode, grid, now + CONFIRM_TICKS));
            player.addChatMessage(
                colored(
                    new ChatComponentTranslation(
                        LANG + "chat.confirm." + (opOf(mode) == Op.CLEAR ? "clear" : "setone"),
                        tally.patterns,
                        tally.holders,
                        CONFIRM_TICKS / 20),
                    EnumChatFormatting.YELLOW));
            reportSkipped(player, tally);
            return;
        }

        apply(holders, opOf(mode), false, tally);
        MyMod.LOG.info(
            "{} used the pattern circuit fixer on a whole ME network (dim {}): mode {}, {} pattern(s) in {} of {} pattern holder(s) changed",
            player.getCommandSenderName(),
            player.worldObj.provider.dimensionId,
            mode,
            tally.patterns,
            tally.holders,
            holders.size());
        report(player, mode, tally);
    }

    // ===================== the work =====================

    private static final class Tally {

        int patterns;
        int holders;
        /** patterns that clearing would have left without any input */
        int skipped;
    }

    private static boolean isProgrammingCircuit(NBTTagCompound entry) {
        try {
            // item entries carry the vanilla id/Damage/tag fields in every pattern layout, fluid entries
            // carry no item id at all and come back as null
            ItemStack stack = ItemStack.loadItemStackFromNBT(entry);
            return stack != null && stack.getItem() instanceof ItemProgrammingCircuit;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Edits (or, when simulating, only counts) the patterns of the given holders.
     * <p>
     * A changed pattern goes back into its slot as a new stack. That is required, not a nicety: an
     * interface only rebuilds its crafting list when the slot's content is a different stack, and it
     * tells old from new patterns by stack identity. AE's own pattern multiplier works the same way.
     * Rebuilds of the network's pattern table are paused meanwhile, so the grid rebuilds once at the end
     * instead of once per changed pattern.
     * <p>
     * Patterns that need no change are not touched at all. Stacks are remembered by identity because
     * one pattern can be visible through several holders, a mapping slave shows the patterns of its
     * master, and must be counted once.
     */
    private static void apply(List<IInterfaceViewable> holders, Op op, boolean simulate, Tally tally) {
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!simulate) CraftingGridCache.pauseRebuilds();
        try {
            for (IInterfaceViewable holder : holders) {
                IInventory patterns = holder.getPatterns();
                if (patterns == null) continue;
                boolean touched = false;
                for (int slot = 0; slot < patterns.getSizeInventory(); slot++) {
                    ItemStack pattern = patterns.getStackInSlot(slot);
                    if (pattern == null || !(pattern.getItem() instanceof ItemEncodedPattern)) continue;
                    if (!seen.add(pattern)) continue;
                    Result result = PatternCircuitEditor
                        .edit(pattern.getTagCompound(), op, ItemFixer2::isProgrammingCircuit);
                    if (result.outcome == Outcome.WOULD_BE_EMPTY) {
                        tally.skipped++;
                    } else if (result.outcome == Outcome.CHANGED) {
                        tally.patterns++;
                        touched = true;
                        if (!simulate) {
                            ItemStack edited = pattern.copy();
                            edited.setTagCompound(result.tag);
                            seen.add(edited);
                            patterns.setInventorySlotContents(slot, edited);
                        }
                    }
                }
                if (touched) {
                    tally.holders++;
                    if (!simulate) {
                        // not every holder's pattern inventory marks its tile dirty on its own
                        TileEntity tile = holder.getTileEntity();
                        if (tile != null) tile.markDirty();
                    }
                }
            }
        } finally {
            if (!simulate) CraftingGridCache.unpauseRebuilds();
        }
    }

    /** Same two checks AE makes before it opens an interface's GUI: block access, then BUILD on the network. */
    private static boolean mayEdit(EntityPlayer player, TileEntity te, IGridNode node) {
        if (!Platform.hasPermissions(new DimensionalCoord(te), player)) return false;
        if (node == null) return true;
        IGrid grid = node.getGrid();
        if (grid == null) return true;
        ISecurityGrid security = grid.getCache(ISecurityGrid.class);
        return security == null || security.hasPermission(player, SecurityPermissions.BUILD);
    }

    // ===================== what was clicked =====================

    private static final class Target {

        /** the clicked pattern holder, null if the click did not hit one */
        final IInterfaceViewable holder;
        /** a grid node of the clicked device, null if it has none */
        final IGridNode node;

        private Target(IInterfaceViewable holder, IGridNode node) {
            this.holder = holder;
            this.node = node;
        }

        static Target resolve(TileEntity te, float hitX, float hitY, float hitZ) {
            Object clicked = te;
            if (te instanceof IPartHost) {
                // same lookup the cable bus itself does to find the part under the cursor
                SelectedPart selected = ((IPartHost) te).selectPart(Vec3.createVectorHelper(hitX, hitY, hitZ));
                if (selected != null && selected.part != null) clicked = selected.part;
            } else if (te instanceof IGregTechTileEntity) {
                // a GregTech machine is its meta tile entity, the tile is only the shell
                Object meta = ((IGregTechTileEntity) te).getMetaTileEntity();
                if (meta != null) clicked = meta;
            }
            IInterfaceViewable holder = clicked instanceof IInterfaceViewable ? (IInterfaceViewable) clicked : null;
            IGridNode node = nodeOf(clicked);
            if (node == null && clicked != te) node = nodeOf(te);
            return new Target(holder, node);
        }

        private static IGridNode nodeOf(Object device) {
            try {
                if (device instanceof IPart) {
                    IGridNode node = ((IPart) device).getGridNode();
                    if (node != null) return node;
                }
                if (device instanceof IGridProxyable) {
                    AENetworkProxy proxy = ((IGridProxyable) device).getProxy();
                    IGridNode node = proxy == null ? null : proxy.getNode();
                    if (node != null) return node;
                }
                if (device instanceof IGridHost) {
                    // values() includes UNKNOWN, which is how a cable answers for itself
                    for (ForgeDirection direction : ForgeDirection.values()) {
                        IGridNode node = ((IGridHost) device).getGridNode(direction);
                        if (node != null) return node;
                    }
                }
            } catch (RuntimeException e) {
                // a machine that is not on the network yet may not be able to answer; treat as "no node"
            }
            return null;
        }
    }

    // ===================== confirmation of network-wide requests =====================

    private static final class Pending {

        final int mode;
        final WeakReference<IGrid> grid;
        final long expires;

        Pending(int mode, IGrid grid, long expires) {
            this.mode = mode;
            this.grid = new WeakReference<>(grid);
            this.expires = expires;
        }
    }

    /** server thread only */
    private static final Map<EntityPlayer, Pending> PENDING = new WeakHashMap<>();

    // ===================== chat =====================

    private static IChatComponent colored(IChatComponent component, EnumChatFormatting color) {
        component.getChatStyle()
            .setColor(color);
        return component;
    }

    private static void say(EntityPlayer player, EnumChatFormatting color, String key, Object... args) {
        player.addChatMessage(colored(new ChatComponentTranslation(LANG + key, args), color));
    }

    private static void report(EntityPlayer player, int mode, Tally tally) {
        if (tally.patterns == 0) {
            say(player, EnumChatFormatting.GRAY, "chat.nothing");
        } else {
            String what = opOf(mode) == Op.CLEAR ? "clear" : "setone";
            if (isNetworkMode(mode)) {
                say(player, EnumChatFormatting.GREEN, "chat.done.network." + what, tally.patterns, tally.holders);
            } else {
                say(player, EnumChatFormatting.GREEN, "chat.done." + what, tally.patterns);
            }
        }
        reportSkipped(player, tally);
    }

    private static void reportSkipped(EntityPlayer player, Tally tally) {
        if (tally.skipped > 0) say(player, EnumChatFormatting.GOLD, "chat.skipped", tally.skipped);
    }
}
