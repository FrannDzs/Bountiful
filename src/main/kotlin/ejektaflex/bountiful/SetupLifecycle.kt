package ejektaflex.bountiful

import com.google.common.collect.ImmutableList
import com.mojang.datafixers.util.Pair
import ejektaflex.bountiful.block.BoardTileEntity
import ejektaflex.bountiful.data.bounty.BountyData
import ejektaflex.bountiful.data.bounty.BountyEntry
import ejektaflex.bountiful.data.bounty.BountyEntryEntity
import ejektaflex.bountiful.data.bounty.enums.BountifulResourceType
import ejektaflex.bountiful.data.json.JsonSerializers
import ejektaflex.bountiful.data.structure.EntryPool
import ejektaflex.bountiful.ext.sendErrorMsg
import ejektaflex.bountiful.ext.sendMessage
import ejektaflex.bountiful.gui.BoardContainer
import ejektaflex.bountiful.gui.BoardScreen
import ejektaflex.bountiful.item.ItemBounty
import ejektaflex.bountiful.worldgen.JigsawJank
import net.minecraft.block.Block
import net.minecraft.client.gui.ScreenManager
import net.minecraft.command.CommandSource
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.inventory.container.ContainerType
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.resources.IResourceManager
import net.minecraft.resources.IResourceManagerReloadListener
import net.minecraft.tileentity.TileEntityType
import net.minecraft.util.ResourceLocation
import net.minecraft.world.gen.feature.ConfiguredFeature
import net.minecraft.world.gen.feature.Feature
import net.minecraft.world.gen.feature.IFeatureConfig
import net.minecraft.world.gen.feature.jigsaw.*
import net.minecraft.world.gen.feature.structure.VillagePieces
import net.minecraftforge.common.BasicTrade
import net.minecraftforge.common.extensions.IForgeContainerType
import net.minecraftforge.event.RegistryEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.village.VillagerTradesEvent
import net.minecraftforge.event.village.WandererTradesEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
import net.minecraftforge.fml.event.server.FMLServerAboutToStartEvent
import net.minecraftforge.fml.event.server.FMLServerStartingEvent
import java.util.function.Supplier
import kotlin.math.min

@Mod.EventBusSubscriber
object SetupLifecycle {

    init {
        BountifulMod.logger.info("Loading Bountiful listeners..")
    }

    @SubscribeEvent
    fun gameSetup(event: FMLCommonSetupEvent) {
        println("Registering data type adapters for JSON/Data conversion...")
        JsonSerializers.register()
        println("Adding bounty board to world gen")
        JigsawJank.create().append(
                ResourceLocation("minecraft","village/plains/houses")
        ) {
            listOf(
                    Pair.of(SingleJigsawPiece("bountiful:village/common/bounty_gazebo"), 2)
            )
        }

    }

    fun validatePool(pool: EntryPool, sender: CommandSource? = null, log: Boolean = false): MutableList<BountyEntry> {

        BountifulMod.logger.info("Validating pool on side? isRemote?: ${sender?.world?.isRemote}")

        sender?.sendMessage("Validating pool '${pool.id}'")

        val validEntries = mutableListOf<BountyEntry>()

        if (log) {
            BountifulMod.logger.info("Pool: ${pool.id}")
        }
        for (entry in pool.content) {
            if (log) {
                BountifulMod.logger.info("* $entry")
            }
            try {
                entry.validate()
                validEntries.add(entry)
            } catch (e: Exception) {
                sender?.sendErrorMsg(e.message!!)
            }
        }

        return validEntries
    }


    // Update mob bounties
    @SubscribeEvent
    fun entityLivingDeath(e: LivingDeathEvent) {
        val deadEntity = e.entityLiving
        if (e.source.trueSource is PlayerEntity) {
            val player = e.source.trueSource as PlayerEntity
            val bountyStacks = player.inventory.mainInventory.filter { it.item is ItemBounty && it.hasTag() }
            if (bountyStacks.isNotEmpty()) {
                bountyStacks.forEach { stack ->
                    val data = BountyData.from(stack)
                    val eObjs = data.objectives.content.filterIsInstance<BountyEntryEntity>()
                    for (obj in eObjs) {

                        if (obj.isSameEntity(deadEntity)) {
                            obj.killedAmount = min(obj.killedAmount + 1, obj.amount)
                            stack.tag = data.serializeNBT()
                        }

                    }
                }
            }
        }
    }


    @SubscribeEvent
    fun onServerAboutToStart(event: FMLServerAboutToStartEvent) {
        BountifulMod.logger.info("Bountiful listening for resource reloads..")
        event.server.resourceManager.addReloadListener(object : IResourceManagerReloadListener {
            override fun onResourceManagerReload(resourceManager: IResourceManager) {
                BountifulMod.logger.info("Bountiful reloading resources! :D")
                BountifulResourceType.values().forEach { type ->
                    BountifulMod.reloadBountyData(event.server, resourceManager, type)
                }
            }
        })
    }

    @SubscribeEvent
    fun onServerStarting(event: FMLServerStartingEvent) {
        BountifulCommand.generateCommand(event.commandDispatcher)
    }

    @SubscribeEvent
    fun registerItems(event: RegistryEvent.Register<Item>) {
        println("Registering to: ${event.registry.registryName}, ${event.registry.registrySuperType}")
        event.registry.registerAll(
                BountifulContent.Items.BOUNTY,
                BountifulContent.Items.BOUNTYBOARD,
                BountifulContent.Items.DECREE
        )
    }

    @SubscribeEvent
    fun registerBlocks(event: RegistryEvent.Register<Block>) {
        println("Registering to: ${event.registry.registryName}, ${event.registry.registrySuperType}")
        event.registry.registerAll(
                BountifulContent.Blocks.BOUNTYBOARD
        )
    }

    @SubscribeEvent
    fun onTileEntityRegistry(event: RegistryEvent.Register<TileEntityType<*>>) {
        event.registry.register(
                TileEntityType.Builder.create<BoardTileEntity>(Supplier {
                    BoardTileEntity()
                }, BountifulContent.Blocks.BOUNTYBOARD)
                        .build(null)
                        .setRegistryName("${BountifulMod.MODID}:bounty-te")
        )
    }

    @SubscribeEvent
    fun onContainerRegistry(event: RegistryEvent.Register<ContainerType<*>>) {
        event.registry.register(IForgeContainerType.create { windowId, inv, data ->
            val pos = data.readBlockPos()
            BoardContainer(windowId, inv.player.world, pos, inv)
        }.setRegistryName("bountyboard"))
    }

    @SubscribeEvent
    fun onClientInit(event: FMLClientSetupEvent) {
        ScreenManager.registerFactory(BountifulContent.Guis.BOARDCONTAINER) {
            container, inv, textComponent ->  BoardScreen(container, inv, textComponent)
        }
    }

    private val decreeTrade = BasicTrade(3, ItemStack(BountifulContent.Items.DECREE), 5, 5, 0.5f)

    @SubscribeEvent
    fun doWandererTrades(event: WandererTradesEvent) {
        event.genericTrades.add(decreeTrade)
    }

    @SubscribeEvent
    fun doVillagerTrades(event: VillagerTradesEvent) {
        event.trades[2].add(decreeTrade)
    }

}


