package org.zeith.multipart.blocks;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.*;
import net.minecraft.util.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;
import org.zeith.hammerlib.HammerLib;
import org.zeith.hammerlib.api.blocks.INoItemBlock;
import org.zeith.hammerlib.api.forge.BlockAPI;
import org.zeith.hammerlib.net.Network;
import org.zeith.hammerlib.net.packets.PacketRequestTileSync;
import org.zeith.multipart.api.*;
import org.zeith.multipart.client.MultipartEffects;
import org.zeith.multipart.api.item.IMultipartPlacerItem;
import org.zeith.multipart.init.PartRegistries;
import org.zeith.multipart.mixins.UseOnContextAccessor;

import java.util.*;
import java.util.function.Consumer;

public class BlockMultipartContainer
		extends BaseEntityBlock
		implements INoItemBlock, SimpleWaterloggedBlock
{
	public static final BooleanProperty ALT = BooleanProperty.create("alt");
	public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
	public static final BooleanProperty REDSTONE_SOURCE = BooleanProperty.create("redstone_source");
	public static final IntegerProperty LIGHT_LEVEL = IntegerProperty.create("light", 0, 15);
	
	public BlockMultipartContainer()
	{
		super(Block.Properties.of()
				.forceSolidOff()
				.dynamicShape()
				.noOcclusion()
				.lightLevel(s -> s.getValue(LIGHT_LEVEL))
		);
		
		var defState = defaultBlockState()
				.setValue(REDSTONE_SOURCE, false)
				.setValue(WATERLOGGED, false)
				.setValue(LIGHT_LEVEL, 0);
		
		registerDefaultState(defState);
		MinecraftForge.EVENT_BUS.addListener(this::clickWithItem);
	}
	
	public BlockState defaultBlockState(Level level, BlockPos pos)
	{
		var state = level.getBlockState(pos);
		FluidState fs = level.getFluidState(pos);
		if(state.is(this))
			return state;
		return defaultBlockState().setValue(WATERLOGGED, fs.getType() == Fluids.WATER);
	}
	
	@Override
	public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fs)
	{
		boolean ok = SimpleWaterloggedBlock.super.placeLiquid(level, pos, state, fs);
		var pc = pc(level, pos);
		if(pc != null)
			pc.neighborChanged(Direction.UP, pos, state, level.getBlockState(pos).getOptionalValue(WATERLOGGED)
					.orElse(false));
		return ok;
	}
	
	@Override
	public FluidState getFluidState(BlockState state)
	{
		return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
	}
	
	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b)
	{
		b.add(LIGHT_LEVEL, REDSTONE_SOURCE, ALT, WATERLOGGED);
	}
	
	@Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block blk, BlockPos from, boolean p_60514_)
	{
		super.neighborChanged(state, level, pos, blk, from, p_60514_);
		if(blk != this)
		{
			var pc = pc(level, pos);
			if(pc == null) return;
			pc.neighborChanged(null, from, level.getBlockState(from), state.getValue(WATERLOGGED));
		}
	}
	
	@Override
	public BlockState updateShape(BlockState ourState, Direction direction, BlockState neigborState, LevelAccessor accessor, BlockPos pos, BlockPos neigborPos)
	{
		if(ourState.getValue(WATERLOGGED))
		{
			accessor.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(accessor));
		}
		
		var pc = pc(accessor, pos);
		if(pc == null) return ourState;
		pc.neighborChanged(direction, neigborPos, neigborState, ourState.getValue(WATERLOGGED));
		
		return ourState;
	}
	
	@Override
	public float getDestroyProgress(BlockState state, Player player, BlockGetter get, BlockPos pos)
	{
		var pc = pc(get, pos);
		if(pc == null) return 10F;
		var hit = getPlayerPOVHitResult(get, player, ClipContext.Fluid.NONE);
		if(hit.getType() != HitResult.Type.BLOCK) return 10F;
		var part = pc.selectPart(hit.getLocation());
		if(part.isEmpty()) return 10F;
		var e = part.orElseThrow();
		
		var rs = e.getValue().getHardnessState();
		if(rs != null)
			state = rs;
		
		float f = e.getValue().getDestroySpeed(player);
		if(f == -1.0F) return 0.0F;
		else
		{
			if(f == 0.0F) return 1.0F;
			int i = e.getValue().isCorrectToolForDrops(player) ? 30 : 100;
			return player.getDigSpeed(state, pos) / f / (float) i;
		}
	}
	
	@Override
	public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity)
	{
		var pc = pc(level, pos);
		if(pc == null || !(entity instanceof Player player)) return SoundType.STONE;
		var hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
		if(hit.getType() != HitResult.Type.BLOCK) return SoundType.STONE;
		var pe = pc.selectPart(hit.getLocation()).orElse(null);
		if(pe == null) return SoundType.STONE;
		return pe.getValue().definition().getSoundType(pe.getValue());
	}
	
	@Override
	public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid)
	{
		var pc = pc(level, pos);
		if(pc == null)
		{
			if(level.isClientSide())
				Network.sendToServer(new PacketRequestTileSync(pos));
			return false;
		}
		var hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
		if(hit.getType() != HitResult.Type.BLOCK)
		{
			if(level.isClientSide())
				Network.sendToServer(new PacketRequestTileSync(pos));
			return false;
		}
		if(!hit.getBlockPos().equals(pos))
		{
			if(level.isClientSide())
				Network.sendToServer(new PacketRequestTileSync(pos));
			return false;
		}
		var pe = pc.selectPart(hit.getLocation()).orElse(null);
		if(pe == null)
		{
			if(level.isClientSide())
				Network.sendToServer(new PacketRequestTileSync(pos));
			return false;
		}
		pc.breakPart(player, willHarvest, pe.getKey());
		
		// This is a rare issue, but still, sync to prevent ghost parts.
		if(level.isClientSide())
		{
			HammerLib.PROXY.queueTask(level, 2, () ->
					Network.sendToServer(new PacketRequestTileSync(pos))
			);
		}
		
		return false;
	}
	
	public static PartContainer pc(BlockGetter get, BlockPos pos)
	{
		if(get != null && get.getBlockEntity(pos) instanceof TileMultipartContainer ctr)
		{
			ctr.container.level = get instanceof Level l ? l : ctr.getLevel();
			return ctr.container;
		}
		return null;
	}
	
	public VoxelShape renderShape;
	
	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random)
	{
		var pc = pc(level, pos);
		if(pc == null) return;
		for(PartEntity p : pc.parts())
			p.animateTick(random);
	}
	
	@Override
	public VoxelShape getVisualShape(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext ctx)
	{
		var pc = pc(getter, pos);
		if(pc != null && ctx instanceof EntityCollisionContext ec && ec.getEntity() instanceof Player player)
		{
			var res = getPlayerPOVHitResult(getter, player, ClipContext.Fluid.NONE);
			if(res.getType() != HitResult.Type.MISS)
				return pc.selectPart(res.getLocation())
						.map(e -> e.getValue().getShape())
						.orElseGet(Shapes::empty);
		}
		return Shapes.empty();
	}
	
	@Override
	public VoxelShape getShape(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext ctx)
	{
		if(renderShape != null)
		{
			var s = renderShape;
			renderShape = null;
			return s;
		}
		
		var pc = pc(getter, pos);
		if(pc != null)
			return pc.getShape();
		return Shapes.empty();
	}
	
	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext ctx)
	{
		var pc = pc(getter, pos);
		if(pc != null)
			return pc.getCollisionShape();
		return Shapes.empty();
	}
	
	@Override
	public RenderShape getRenderShape(BlockState p_49232_)
	{
		return RenderShape.MODEL;
	}
	
	@Override
	public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit)
	{
		var pc = pc(level, pos);
		if(pc == null) return InteractionResult.PASS;
		return pc.selectPart(hit.getLocation())
				.map(Map.Entry::getValue)
				.map(ent -> ent.use(player, hand, hit, ent.getSelectionShape(player, hit)))
				.orElse(InteractionResult.PASS);
	}
	
	@Override
	public void attack(BlockState state, Level level, BlockPos pos, Player player)
	{
		var pc = pc(level, pos);
		if(pc != null)
		{
			var res = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
			if(res.getType() != HitResult.Type.MISS)
				pc.selectPart(res.getLocation())
						.map(Map.Entry::getValue)
						.ifPresent(e -> e.attack(player, res, e.getSelectionShape(player, res)));
		}
	}
	
	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity)
	{
		var pc = pc(level, pos);
		if(pc == null) return;
		for(PartEntity part : pc.parts())
			part.stepOn(entity);
	}
	
	@Override
	public boolean makesOpenTrapdoorAboveClimbable(BlockState state, LevelReader level, BlockPos pos, BlockState trapdoorState)
	{
		var pc = pc(level, pos);
		if(pc == null) return false;
		return pc.parts().stream().anyMatch(part -> part.makesOpenTrapdoorAboveClimbable(trapdoorState));
	}
	
	@Override
	public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity)
	{
		var pc = pc(level, pos);
		if(pc == null) return false;
		return pc.parts().stream().anyMatch(part -> part.isLadder(entity));
	}
	
	@Override
	public boolean canHarvestBlock(BlockState state, BlockGetter level, BlockPos pos, Player player)
	{
		var pc = pc(level, pos);
		if(pc == null) return false;
		return pc.parts().stream().anyMatch(part -> part.canHarvestPart(player));
	}
	
	@Override
	public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, @Nullable Direction direction)
	{
		var pc = pc(level, pos);
		if(pc == null) return false;
		return pc.parts().stream().anyMatch(part -> part.canConnectRedstone(direction))
				|| (isSignalSource(state) && direction != null);
	}
	
	@Override
	public BlockState getAppearance(BlockState state, BlockAndTintGetter level, BlockPos pos, Direction side, @Nullable BlockState queryState, @Nullable BlockPos queryPos)
	{
		var pc = pc(level, pos);
		if(pc == null) return state;
		
		for(PartEntity part : pc.parts())
		{
			var st = part.getAppearance(state, side, queryState, queryPos);
			if(st != null) return st;
		}
		
		return state;
	}
	
	@Override
	public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos, Player player)
	{
		var pc = pc(level, pos);
		if(pc != null && target instanceof BlockHitResult res)
			return pc.selectPart(res.getLocation())
					.map(Map.Entry::getValue)
					.map(e -> e.getCloneItemStack(res, player, e.getSelectionShape(player, res)))
					.orElse(ItemStack.EMPTY);
		return ItemStack.EMPTY;
	}
	
	@Nullable
	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state)
	{
		return WorldPartComponents.TILE_TYPE.create(pos, state);
	}
	
	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type)
	{
		return BlockAPI.ticker(level);
	}
	
	@Override
	public boolean isSignalSource(BlockState state)
	{
		return state.getValue(REDSTONE_SOURCE);
	}
	
	@Override
	public int getSignal(BlockState state, BlockGetter get, BlockPos pos, Direction dir)
	{
		var pc = pc(get, pos);
		if(pc == null) return 0;
		return pc.weakRedstoneSignals[dir.ordinal()];
	}
	
	@Override
	public int getDirectSignal(BlockState state, BlockGetter get, BlockPos pos, Direction dir)
	{
		var pc = pc(get, pos);
		if(pc == null) return 0;
		return pc.strongRedstoneSignals[dir.ordinal()];
	}
	
	@Override
	public void initializeClient(Consumer<IClientBlockExtensions> consumer)
	{
		consumer.accept(new IClientBlockExtensions()
		{
			final Random random = new Random();
			
			@Override
			public boolean addHitEffects(BlockState state, Level level, HitResult target, ParticleEngine manager)
			{
				if(target instanceof BlockHitResult hit)
				{
					var pc = pc(level, hit.getBlockPos());
					if(pc == null) return true;
					var part = pc.selectPart(hit.getLocation());
					if(part.isEmpty()) return true;
					var pe = part.orElseThrow();
					MultipartEffects.spawnHitFX(pe.getValue(), hit);
				}
				return true;
			}
			
			@Override
			public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine manager)
			{
				var pc = pc(level, pos);
				if(pc != null)
					for(PartEntity part : pc.parts())
						MultipartEffects.spawnBreakFX(part);
				return true;
			}
		});
	}
	
	public Optional<InteractionResult> useItem(UseOnContext context)
	{
		return performPlaceAction(
				((UseOnContextAccessor) context).getHitResult(),
				context.getLevel(),
				context.getPlayer(),
				context.getHand()
		);
	}
	
	protected void clickWithItem(PlayerInteractEvent.RightClickItem e)
	{
		var hit = getPlayerPOVHitResult(e.getLevel(), e.getEntity(), ClipContext.Fluid.NONE);
		if(hit.getType() != HitResult.Type.BLOCK) return;
		performPlaceAction(hit, e.getLevel(), e.getEntity(), e.getHand());
	}
	
	protected Optional<InteractionResult> performPlaceAction(BlockHitResult hit, Level level, Player player, InteractionHand hand)
	{
		if(player == null) return Optional.empty();
		
		var placePos = hit.getBlockPos();
		var air = level.getBlockState(placePos)
				.canBeReplaced(new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit));
		if(!air)
		{
			placePos = hit.getBlockPos().relative(hit.getDirection());
			air = level.getBlockState(placePos)
					.canBeReplaced(new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit))
					|| level.getBlockState(placePos).is(Blocks.WATER);
		}
		var pc = pc(level, placePos);
		
		IMultipartPlacerItem it = null;
		boolean isFallback = false;
		
		var held = player.getItemInHand(hand);
		if(!held.isEmpty() && held.getItem() instanceof IMultipartPlacerItem it0) it = it0;
		else if(!held.isEmpty())
		{
			it = PartRegistries.getFallbackPlacer(held.getItem());
			isFallback = true;
		}
		
		if(it != null)
		{
			var feature = it.getPlacement(level, hit.getBlockPos(), player, held, hit).orElse(null);
			if(feature == null) return Optional.empty();
			
			var hasWater = WorldPartComponents.BLOCK.defaultBlockState(level, placePos)
					.getValue(BlockMultipartContainer.WATERLOGGED);
			if(hasWater && !feature.base().canSurviveInWater(null))
				return Optional.empty();
			
			boolean justTurned = false;
			
			if(air) // perform empty placement
			{
				if(isFallback) return Optional.empty();
				level.setBlockAndUpdate(placePos, WorldPartComponents.BLOCK.defaultBlockState(level, placePos));
				pc = pc(level, placePos);
			} else if(pc == null)
			{
				// try to convert a block into multipart!
				pc = PartContainer.turnIntoMultipart(level, placePos).orElse(null);
				justTurned = true;
			}
			
			if(pc == null) return Optional.empty();
			
			if(pc.tryPlacePart(feature.base(), feature.placer(), feature.placement()))
			{
				var part = pc.getPartAt(feature.placement());
				level.setBlockAndUpdate(placePos, WorldPartComponents.BLOCK.defaultBlockState(level, placePos)
						.setValue(LIGHT_LEVEL, pc.parts().stream().mapToInt(PartEntity::getLightEmission).max()
								.orElse(0))
				);
				if(part != null)
					it.onPartPlacedBy(part, player, held, hand);
				
				return Optional.of(InteractionResult.sidedSuccess(level.isClientSide));
			} else if(justTurned && level.getBlockEntity(placePos) instanceof TileMultipartContainer ctr)
			{
				// disassemble part immediately!
				// Nobody saw anything.
				ctr.tryDisassemble();
			}
		}
		
		return Optional.empty();
	}
	
	public static BlockHitResult getPlayerPOVHitResult(BlockGetter level, Player player, ClipContext.Fluid fluid)
	{
		float f = player.getXRot();
		float f1 = player.getYRot();
		Vec3 vec3 = player.getEyePosition();
		float f2 = Mth.cos(-f1 * ((float) Math.PI / 180F) - (float) Math.PI);
		float f3 = Mth.sin(-f1 * ((float) Math.PI / 180F) - (float) Math.PI);
		float f4 = -Mth.cos(-f * ((float) Math.PI / 180F));
		float f5 = Mth.sin(-f * ((float) Math.PI / 180F));
		float f6 = f3 * f4;
		float f7 = f2 * f4;
		double d0 = player.getBlockReach();
		Vec3 vec31 = vec3.add((double) f6 * d0, (double) f5 * d0, (double) f7 * d0);
		return level.clip(new ClipContext(vec3, vec31, ClipContext.Block.OUTLINE, fluid, player));
	}
}