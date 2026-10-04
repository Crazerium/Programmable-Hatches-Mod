package reobf.proghatches.item;

import java.util.function.Predicate;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Edits the programming circuits inside an encoded pattern's NBT. Pure NBT work: it never touches an
 * inventory and never mutates the tag it is given, so the caller decides what to do with the result.
 * <p>
 * Pattern layouts this has to cope with, all of which keep their inputs in the compound list "in":
 * <ul>
 * <li>AE2 processing pattern (PatternHelper): vanilla ItemStack NBT with "Count" written as an int,
 * falling back to the long "Cnt" when Count is 0.</li>
 * <li>AE2 ultimate / tunnel pattern (UltimatePatternHelper) and the AE2FC fluid pattern
 * (FluidPatternDetails): AE's own stack layout, where "Count" is a byte that is always 0 and the long
 * "Cnt" carries the size, optionally tagged with "StackType". Fluid inputs live in the same list.</li>
 * <li>AE2FC-style patterns additionally carry a mirror of the input list under "Inputs".</li>
 * </ul>
 * Entries are patched field by field instead of being decoded and re-encoded, so whatever layout a
 * pattern uses is the layout it keeps, and entries that are not circuits are copied through verbatim.
 * <p>
 * Workbench patterns ("crafting") are never touched: their input list is positional and a programming
 * circuit cannot be part of a crafting-table recipe anyway.
 */
public final class PatternCircuitEditor {

    private PatternCircuitEditor() {}

    public enum Op {
        /** remove every programming circuit from the inputs */
        CLEAR,
        /** set the amount of every programming circuit in the inputs to 1 */
        SET_ONE
    }

    public enum Outcome {
        /** nothing to do, or not a pattern this editor handles */
        UNCHANGED,
        /** {@link Result#tag} holds the edited copy */
        CHANGED,
        /**
         * Clearing would leave the pattern without any input. AE rejects such a pattern ("No pattern
         * here!"), so it is left alone and reported instead.
         */
        WOULD_BE_EMPTY
    }

    public static final class Result {

        private static final Result UNCHANGED = new Result(Outcome.UNCHANGED, null);
        private static final Result WOULD_BE_EMPTY = new Result(Outcome.WOULD_BE_EMPTY, null);

        public final Outcome outcome;
        /** the edited deep copy of the pattern tag, non-null only for {@link Outcome#CHANGED} */
        public final NBTTagCompound tag;

        private Result(Outcome outcome, NBTTagCompound tag) {
            this.outcome = outcome;
            this.tag = tag;
        }
    }

    private static final int TAG_LONG = 4;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;

    /**
     * @param patternTag the pattern item's tag compound, may be null; never modified
     * @param isCircuit  tells whether one non-empty entry of an input list is a programming circuit
     */
    public static Result edit(NBTTagCompound patternTag, Op op, Predicate<NBTTagCompound> isCircuit) {
        if (patternTag == null || patternTag.getBoolean("crafting") || !patternTag.hasKey("in", TAG_LIST)) {
            return Result.UNCHANGED;
        }

        EditedList in = editList(patternTag.getTagList("in", TAG_COMPOUND), op, isCircuit);
        if (!in.changed) return Result.UNCHANGED;
        if (in.meaningful == 0) return Result.WOULD_BE_EMPTY;

        NBTTagCompound copy = (NBTTagCompound) patternTag.copy();
        copy.setTag("in", in.list);
        if (patternTag.hasKey("Inputs", TAG_LIST)) {
            // edited on its own rather than overwritten with "in", so a mirror that uses another layout keeps it
            EditedList mirror = editList(patternTag.getTagList("Inputs", TAG_COMPOUND), op, isCircuit);
            if (mirror.changed) copy.setTag("Inputs", mirror.list);
        }
        return new Result(Outcome.CHANGED, copy);
    }

    private static final class EditedList {

        final NBTTagList list = new NBTTagList();
        boolean changed;
        /** entries left that actually describe an input, i.e. not the empty placeholders of unused slots */
        int meaningful;
    }

    private static EditedList editList(NBTTagList source, Op op, Predicate<NBTTagCompound> isCircuit) {
        EditedList out = new EditedList();
        for (int i = 0; i < source.tagCount(); i++) {
            NBTTagCompound entry = (NBTTagCompound) source.getCompoundTagAt(i)
                .copy();
            if (entry.hasNoTags()) {
                out.list.appendTag(entry);
                continue;
            }
            if (isCircuit.test(entry)) {
                if (op == Op.CLEAR) {
                    out.changed = true;
                    continue;
                }
                if (setAmountToOne(entry)) out.changed = true;
            }
            out.list.appendTag(entry);
            out.meaningful++;
        }
        return out;
    }

    /**
     * Makes every reader see an amount of 1 while keeping the entry's layout, and reports whether
     * anything had to change. An amount of 0 is fixed up as well.
     */
    static boolean setAmountToOne(NBTTagCompound entry) {
        boolean changed = false;
        final boolean hasCnt = entry.hasKey("Cnt", TAG_LONG);
        final NBTBase count = entry.getTag("Count");
        final boolean hasCount = count instanceof NBTBase.NBTPrimitive;

        if (hasCnt && entry.getLong("Cnt") != 1L) {
            entry.setLong("Cnt", 1L);
            changed = true;
        }
        if (hasCount) {
            final long value = ((NBTBase.NBTPrimitive) count).func_150291_c();
            // Count 0 next to a Cnt is AE's own layout, where Cnt alone carries the amount
            final boolean aeLayout = hasCnt && value == 0L;
            if (!aeLayout && value != 1L) {
                switch (count.getId()) {
                    case 1:
                        entry.setByte("Count", (byte) 1);
                        break;
                    case 2:
                        entry.setShort("Count", (short) 1);
                        break;
                    case 4:
                        entry.setLong("Count", 1L);
                        break;
                    default:
                        entry.setInteger("Count", 1);
                }
                changed = true;
            }
        } else if (!hasCnt) {
            entry.setByte("Count", (byte) 1);
            changed = true;
        }
        return changed;
    }
}
