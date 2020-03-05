package ejektaflex.bountiful

import ejektaflex.bountiful.generic.IMerge
import ejektaflex.bountiful.data.json.JsonAdapter
import ejektaflex.bountiful.ext.sendErrorMsg
import ejektaflex.bountiful.data.bounty.enums.BountifulResourceType
import ejektaflex.bountiful.data.structure.EntryPool
import ejektaflex.bountiful.generic.ValueRegistry
import net.alexwells.kottle.FMLKotlinModLoadingContext
import net.minecraft.command.CommandSource
import net.minecraft.resources.IResourceManager
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.lang.Exception
import java.nio.file.Paths


@Mod(BountifulMod.MODID)
object BountifulMod {

    const val MODID = "bountiful"

    val logger: Logger = LogManager.getLogger()

    init {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, BountifulConfig.serverSpec)
    }

    fun reloadBountyData(
            server: MinecraftServer,
            manager: IResourceManager = server.resourceManager,
            fillType: BountifulResourceType,
            msgSender: CommandSource? = null
    ) {

        val folderName = "bounties/" + fillType.folderName
        val extension = ".json"

        fillType.reg.empty()

        //logger.warn("Namespaces: ${manager.resourceNamespaces}")

        fun rlFileName(rl: ResourceLocation) = rl.path.substringAfterLast("/")

        // Get all resource locations, grouped by namespace
        val spaceMap = manager.getAllResourceLocations(folderName) {
            it.endsWith(extension)
        }.groupBy { rl -> rlFileName(rl) }

        // For each group of files with the same name
        fileLoop@ for ((filename, locations) in spaceMap) {

            var obj: IMerge<Any>? = null

            //logger.error("########## FILENAME: $filename ##########")

            // Go through each namespace in order
            nameLoop@ for (namespace in manager.resourceNamespaces - config.namespaceBlacklist) {

                //logger.warn("Inspecting namespace: $namespace")

                // Try get the RL of the namespace for this file
                val location = locations.find { it.namespace == namespace }

                //logger.info("- Location found? $location (${location?.path})")

                if (location != null ) {
                    //logger.info("- - Yes!")

                    val res = manager.getResource(location)
                    val content = res.inputStream.reader().readText()

                    val newObj = try {
                        JsonAdapter.fromJsonExp(content, fillType.klazz)
                    } catch (e: Exception) {
                        msgSender?.sendErrorMsg("Skipping resource $location. Reason: ${e.message}")
                        continue@nameLoop
                    } as IMerge<Any>

                    //logger.info("New obj is: $newObj")

                    if (obj != null) {
                        //logger.warn("MERGING $obj with $newObj")
                        if (newObj.canLoad) {
                            obj.merge(newObj)
                        }
                        //logger.warn("RESULT IS: $obj")
                    } else {
                        obj = newObj
                    }
                }



            }

            // Adding item to pool
            if (obj != null) {

                if (obj is EntryPool) {
                    SetupLifecycle.validatePool(obj, msgSender, true)
                }

                (fillType.reg as ValueRegistry<Any>).add(obj as Any)
                //logger.error("Reg Size Is Now: ${fillType.reg.content.size}")
            }

        }

    }

    init {
        FMLKotlinModLoadingContext.get().modEventBus.register(SetupLifecycle)
    }

    val config = BountifulConfig()

}
