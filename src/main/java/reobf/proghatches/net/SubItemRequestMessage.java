package reobf.proghatches.net;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import reobf.proghatches.main.MyMod;

/**
 * Server to client: "which damage values do these items come in?" Carries numeric item ids, which are
 * only meaningful for the current connection. Answered with {@link SubItemReplyMessage}.
 */
public class SubItemRequestMessage implements IMessage {

    private static final int MAX_IDS = 4096;

    int[] ids = new int[0];

    public SubItemRequestMessage() {}

    public SubItemRequestMessage(int[] ids) {
        this.ids = ids;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        if (n < 0 || n > MAX_IDS || buf.readableBytes() < n * 4) {
            ids = new int[0];
            return;
        }
        ids = new int[n];
        for (int i = 0; i < n; i++) ids[i] = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int n = Math.min(ids.length, MAX_IDS);
        buf.writeInt(n);
        for (int i = 0; i < n; i++) buf.writeInt(ids[i]);
    }

    public static class Handler implements IMessageHandler<SubItemRequestMessage, IMessage> {

        @Override
        public IMessage onMessage(SubItemRequestMessage message, MessageContext ctx) {
            // through the proxy: the answer needs client-only code, and this class loads on a server too
            MyMod.proxy.answerSubItemRequest(message.ids);
            return null;
        }
    }
}
