package reobf.proghatches.main;

import net.minecraftforge.client.MinecraftForgeClient;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import reobf.proghatches.ae.TileMolecularAssemblerInterface;
import reobf.proghatches.ae.render.TESRMAInterface;
import reobf.proghatches.client.CircuitSpecialRenderer;
import reobf.proghatches.client.ClientSubItemDamages;

public class ClientProxy extends CommonProxy {

    @SuppressWarnings("unchecked")
    @Override
    public void preInit(FMLPreInitializationEvent event) {

        super.preInit(event);
        MinecraftForgeClient.registerItemRenderer(MyMod.progcircuit, new CircuitSpecialRenderer());

        ClientRegistry.bindTileEntitySpecialRenderer/*
                                                     * (
                                                     * TileEntityRendererDispatcher.instance.mapSpecialRenderers.put
                                                     */(TileMolecularAssemblerInterface.class, new TESRMAInterface());
        // answers the server's sub item questions on the client thread
        FMLCommonHandler.instance()
            .bus()
            .register(ClientSubItemDamages.INSTANCE);
    }

    @Override
    public void answerSubItemRequest(int[] ids) {
        ClientSubItemDamages.INSTANCE.ask(ids);
    }

}
