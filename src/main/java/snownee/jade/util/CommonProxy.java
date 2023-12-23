package snownee.jade.util;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.jetbrains.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.cache.Cache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.fml.IExtensionPoint;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.IShearable;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.UsernameCache;
import net.neoforged.neoforge.common.capabilities.Capabilities;
import net.neoforged.neoforge.common.capabilities.CapabilityProvider;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.PlayNetworkDirection;
import net.neoforged.neoforge.network.simple.SimpleChannel;
import net.neoforged.neoforgespi.language.ModFileScanData;
import snownee.jade.Jade;
import snownee.jade.addon.universal.ItemCollector;
import snownee.jade.addon.universal.ItemIterator;
import snownee.jade.addon.universal.ItemStorageProvider;
import snownee.jade.api.Accessor;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.ViewGroup;
import snownee.jade.command.JadeServerCommand;
import snownee.jade.impl.WailaClientRegistration;
import snownee.jade.impl.WailaCommonRegistration;
import snownee.jade.impl.config.PluginConfig;
import snownee.jade.network.ReceiveDataPacket;
import snownee.jade.network.RequestEntityPacket;
import snownee.jade.network.RequestTilePacket;
import snownee.jade.network.ServerPingPacket;
import snownee.jade.network.ShowOverlayPacket;

@Mod(Jade.MODID)
public final class CommonProxy {
	public static final SimpleChannel NETWORK = NetworkRegistry.ChannelBuilder.named(new ResourceLocation(Jade.MODID, "networking"))
			.clientAcceptedVersions(s -> true)
			.serverAcceptedVersions(s -> true)
			.networkProtocolVersion(() -> "1")
			.simpleChannel();

	public CommonProxy() {
		ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, () -> new IExtensionPoint.DisplayTest(() -> IExtensionPoint.DisplayTest.IGNORESERVERONLY, (a, b) -> true));
		FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
		FMLJavaModLoadingContext.get().getModEventBus().addListener(this::loadComplete);
		NeoForge.EVENT_BUS.addListener(CommonProxy::playerJoin);
		NeoForge.EVENT_BUS.addListener(CommonProxy::registerServerCommand);
		if (isPhysicallyClient()) {
			ClientProxy.init();
		}
	}

	public static int showOrHideFromServer(Collection<ServerPlayer> players, boolean show) {
		ShowOverlayPacket msg = new ShowOverlayPacket(show);
		for (ServerPlayer player : players) {
			CommonProxy.NETWORK.sendTo(msg, player.connection.connection, PlayNetworkDirection.PLAY_TO_CLIENT);
		}
		return players.size();
	}

	private static void playerJoin(PlayerEvent.PlayerLoggedInEvent event) {
		Jade.LOGGER.info("Syncing config to {} ({})", event.getEntity().getGameProfile().getName(), event.getEntity().getGameProfile().getId());
		NETWORK.sendTo(new ServerPingPacket(PluginConfig.INSTANCE), ((ServerPlayer) event.getEntity()).connection.connection, PlayNetworkDirection.PLAY_TO_CLIENT);
	}

	@Nullable
	public static String getLastKnownUsername(UUID uuid) {
		return UsernameCache.getLastKnownUsername(uuid);
	}

	public static File getConfigDirectory() {
		return FMLPaths.CONFIGDIR.get().toFile();
	}

	public static boolean isShears(ItemStack tool) {
		return tool.is(Tags.Items.SHEARS);
	}

	public static boolean isShearable(BlockState state) {
		return state.getBlock() instanceof IShearable;
	}

	public static boolean isCorrectToolForDrops(BlockState state, Player player) {
		return CommonHooks.isCorrectToolForDrops(state, player);
	}

	public static String getModIdFromItem(ItemStack stack) {
		if (stack.hasTag() && stack.getTag().contains("id")) {
			String s = stack.getTag().getString("id");
			if (s.contains(":")) {
				ResourceLocation id = ResourceLocation.tryParse(s);
				if (id != null) {
					return id.getNamespace();
				}
			}
		}
		return stack.getItem().getCreatorModId(stack);
	}

	public static boolean isPhysicallyClient() {
		return FMLEnvironment.dist.isClient();
	}

	private static void registerServerCommand(RegisterCommandsEvent event) {
		JadeServerCommand.register(event.getDispatcher());
	}

	@SuppressWarnings("UnstableApiUsage")
	public static ItemCollector<?> createItemCollector(Object target, Cache<Object, ItemCollector<?>> containerCache) {
		if (target instanceof CapabilityProvider<?> capProvider) {
			if (!(target instanceof Entity) || target instanceof AbstractChestedHorse) {
				try {
					IItemHandler itemHandler = capProvider.getCapability(Capabilities.ITEM_HANDLER).orElse(null);
					if (itemHandler != null) {
						return containerCache.get(itemHandler, () -> new ItemCollector<>(JadeForgeUtils.fromItemHandler(itemHandler, target instanceof AbstractChestedHorse ? 2 : 0)));
					}
				} catch (Throwable e) {
					WailaExceptionHandler.handleErr(e, null, null);
				}
			}
		}
		if (target instanceof Container) {
			if (target instanceof ChestBlockEntity) {
				return new ItemCollector<>(new ItemIterator.ContainerItemIterator(o -> {
					if (o instanceof ChestBlockEntity be) {
						if (be.getBlockState().getBlock() instanceof ChestBlock chestBlock) {
							Container compound = ChestBlock.getContainer(chestBlock, be.getBlockState(), be.getLevel(), be.getBlockPos(), false);
							if (compound != null) {
								return compound;
							}
						}
						return be;
					}
					return null;
				}, 0));
			}
			return new ItemCollector<>(new ItemIterator.ContainerItemIterator(0));
		}
		return ItemCollector.EMPTY;
	}

	@Nullable
	public static List<ViewGroup<ItemStack>> containerGroup(Container container, Accessor<?> accessor) {
		try {
			return ItemStorageProvider.INSTANCE.containerCache.get(container, () -> new ItemCollector<>(new ItemIterator.ContainerItemIterator(0))).update(container, accessor.getLevel().getGameTime());
		} catch (ExecutionException e) {
			return null;
		}
	}

	@Nullable
	@SuppressWarnings("UnstableApiUsage")
	public static List<ViewGroup<ItemStack>> storageGroup(Object storage, Accessor<?> accessor) {
		try {
			return ItemStorageProvider.INSTANCE.containerCache.get(storage, () -> new ItemCollector<>(JadeForgeUtils.fromItemHandler((IItemHandler) storage, 0))).update(storage, accessor.getLevel().getGameTime());
		} catch (ExecutionException e) {
			return null;
		}
	}

	public static List<ViewGroup<CompoundTag>> wrapFluidStorage(Accessor<?> accessor, Object target) {
		if (target instanceof CapabilityProvider<?> capProvider) {
			IFluidHandler fluidHandler = capProvider.getCapability(Capabilities.FLUID_HANDLER).orElse(null);
			if (fluidHandler != null) {
				return JadeForgeUtils.fromFluidHandler(fluidHandler);
			}
		}
		return null;
	}

	public static List<ViewGroup<CompoundTag>> wrapEnergyStorage(Accessor<?> accessor, Object target) {
		if (target instanceof CapabilityProvider<?> capProvider) {
			IEnergyStorage storage = capProvider.getCapability(Capabilities.ENERGY).orElse(null);
			if (storage != null && storage.getMaxEnergyStored() > 0) {
				var group = new ViewGroup<>(List.of(EnergyView.of(storage.getEnergyStored(), storage.getMaxEnergyStored())));
				group.getExtraData().putString("Unit", "FE");
				return List.of(group);
			}
		}
		return null;
	}

	public static boolean isDevEnv() {
		return !FMLEnvironment.production;
	}

	public static float getEnchantPowerBonus(BlockState state, Level world, BlockPos pos) {
		return state.getEnchantPowerBonus(world, pos);
	}

	@SuppressWarnings("deprecation")
	public static ResourceLocation getId(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block);
	}

	@SuppressWarnings("deprecation")
	public static ResourceLocation getId(EntityType<?> entityType) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
	}

	@SuppressWarnings("deprecation")
	public static ResourceLocation getId(BlockEntityType<?> blockEntityType) {
		return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType);
	}

	@SuppressWarnings("deprecation")
	public static ResourceLocation getId(PaintingVariant motive) {
		return BuiltInRegistries.PAINTING_VARIANT.getKey(motive);
	}

	public static String getPlatformIdentifier() {
		return "neoforge";
	}

	public static MutableComponent getProfressionName(VillagerProfession profession) {
		ResourceLocation profName = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
		return Component.translatable(EntityType.VILLAGER.getDescriptionId() + '.' + (!ResourceLocation.DEFAULT_NAMESPACE.equals(profName.getNamespace()) ? profName.getNamespace() + '.' : "") + profName.getPath());
	}

	public static boolean isBoss(Entity entity) {
		EntityType<?> entityType = entity.getType();
		return entityType.is(Tags.EntityTypes.BOSSES) || entityType == EntityType.ENDER_DRAGON || entityType == EntityType.WITHER;
	}

	public static boolean isModLoaded(String modid) {
		try {
			return ModList.get().isLoaded(modid);
		} catch (Throwable e) {
			return false;
		}
	}

	public static ItemStack getBlockPickedResult(BlockState state, Player player, BlockHitResult hitResult) {
		return state.getCloneItemStack(hitResult, player.level(), hitResult.getBlockPos(), player);
	}

	public static ItemStack getEntityPickedResult(Entity entity, Player player, EntityHitResult hitResult) {
		return MoreObjects.firstNonNull(entity.getPickedResult(hitResult), ItemStack.EMPTY);
	}

	public static Component getFluidName(JadeFluidObject fluid) {
		return toFluidStack(fluid).getDisplayName();
	}

	public static FluidStack toFluidStack(JadeFluidObject fluid) {
		return new FluidStack(fluid.getType(), (int) fluid.getAmount(), fluid.getTag());
	}

	private void setup(FMLCommonSetupEvent event) {
		NETWORK.messageBuilder(ReceiveDataPacket.class, 1, PlayNetworkDirection.PLAY_TO_CLIENT)
				.encoder(ReceiveDataPacket::write)
				.decoder(ReceiveDataPacket::read)
				.consumerNetworkThread(ReceiveDataPacket::handle)
				.add();
		NETWORK.messageBuilder(ServerPingPacket.class, 2, PlayNetworkDirection.PLAY_TO_CLIENT)
				.encoder(ServerPingPacket::write)
				.decoder(ServerPingPacket::read)
				.consumerNetworkThread(ServerPingPacket::handle)
				.add();
		NETWORK.messageBuilder(RequestEntityPacket.class, 3, PlayNetworkDirection.PLAY_TO_SERVER)
				.encoder(RequestEntityPacket::write)
				.decoder(RequestEntityPacket::read)
				.consumerNetworkThread(RequestEntityPacket::handle)
				.add();
		NETWORK.messageBuilder(RequestTilePacket.class, 4, PlayNetworkDirection.PLAY_TO_SERVER)
				.encoder(RequestTilePacket::write)
				.decoder(RequestTilePacket::read)
				.consumerNetworkThread(RequestTilePacket::handle)
				.add();
		NETWORK.messageBuilder(ShowOverlayPacket.class, 5, PlayNetworkDirection.PLAY_TO_CLIENT)
				.encoder(ShowOverlayPacket::write)
				.decoder(ShowOverlayPacket::read)
				.consumerNetworkThread(ShowOverlayPacket::handle)
				.add();
	}

	private void loadComplete(FMLLoadCompleteEvent event) {
		/* off */
		List<String> classNames = ModList.get().getAllScanData()
				.stream()
				.flatMap($ -> $.getAnnotations().stream())
				.filter($ -> {
					if ($.annotationType().getClassName().equals(WailaPlugin.class.getName())) {
						String required = (String) $.annotationData().getOrDefault("value", "");
						return required.isEmpty() || ModList.get().isLoaded(required);
					}
					return false;
				})
				.map(ModFileScanData.AnnotationData::memberName)
				.toList();
		/* on */

		for (String className : classNames) {
			Jade.LOGGER.info("Start loading plugin at {}", className);
			try {
				Class<?> clazz = Class.forName(className);
				if (IWailaPlugin.class.isAssignableFrom(clazz)) {
					IWailaPlugin plugin = (IWailaPlugin) clazz.getDeclaredConstructor().newInstance();
					plugin.register(WailaCommonRegistration.instance());
					if (CommonProxy.isPhysicallyClient()) {
						plugin.registerClient(WailaClientRegistration.instance());
					}
				}
			} catch (Throwable e) {
				Jade.LOGGER.error("Error loading plugin at {}", className, e);
			}
		}
		Jade.loadComplete();
	}
}
