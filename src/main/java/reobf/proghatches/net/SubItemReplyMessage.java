package reobf.proghatches.net;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import reobf.proghatches.util.SubItemDamages;

/**
 * Client to server: the damage values of the items a {@link SubItemRequestMessage} asked about. One
 * entry per asked item, empty when the client has nothing to report for it, so the server stops waiting
 * either way.
 * <p>
 * A packet from the client must stay under 32767 bytes, so the sender splits long answers, see
 * {@link #MAX_ENTRIES}.
 */
public class SubItemReplyMessage implements IMessage {

    /** 256 entries of at most 4 + 1 + 32 * 2 bytes are about 18 KB */
    public static final int MAX_ENTRIES = 256;

    int[] ids = new int[0];
    short[][] damages = new short[0][];

    public SubItemReplyMessage() {}

    public SubItemReplyMessage(int[] ids, short[][] damages) {
        this.ids = ids;
        this.damages = damages;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            int n = buf.readUnsignedShort();
            if (n > MAX_ENTRIES) n = 0;
            int[] readIds = new int[n];
            short[][] readDamages = new short[n][];
            for (int i = 0; i < n; i++) {
                readIds[i] = buf.readInt();
                int count = buf.readUnsignedByte();
                short[] values = new short[Math.min(count, SubItemDamages.MAX_PER_ITEM)];
                for (int k = 0; k < count; k++) {
                    short value = buf.readShort();
                    if (k < values.length) values[k] = value;
                }
                readDamages[i] = values;
            }
            ids = readIds;
            damages = readDamages;
        } catch (IndexOutOfBoundsException malformed) {
            ids = new int[0];
            damages = new short[0][];
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int n = Math.min(ids.length, MAX_ENTRIES);
        buf.writeShort(n);
        for (int i = 0; i < n; i++) {
            buf.writeInt(ids[i]);
            short[] values = damages[i];
            int count = Math.min(values.length, SubItemDamages.MAX_PER_ITEM);
            buf.writeByte(count);
            for (int k = 0; k < count; k++) buf.writeShort(values[k]);
        }
    }

    public static class Handler implements IMessageHandler<SubItemReplyMessage, IMessage> {

        @Override
        public IMessage onMessage(SubItemReplyMessage message, MessageContext ctx) {
            SubItemDamages.accept(ctx.getServerHandler().playerEntity, message.ids, message.damages);
            return null;
        }
    }
}
