package io.wispforest.affinity.block.impl;

import io.wispforest.affinity.block.template.AethumNetworkMemberBlock;
import io.wispforest.affinity.blockentity.impl.AethumFluxCacheBlockEntity;
import io.wispforest.affinity.blockentity.template.TickedBlockEntity;
import io.wispforest.affinity.registries.AffinityBlocks;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Stream;

import static io.wispforest.affinity.registries.AffinityBlocks.AETHUM_FLUX_CACHE;

@SuppressWarnings("deprecation")
public class AethumFluxCacheBlock extends AethumNetworkMemberBlock {

    public static final EnumProperty<Part> PART = EnumProperty.of("part", Part.class);

    private static final VoxelShape MIDDLE_SHAPE = Stream.of(
            Block.createCuboidShape(2, 0, 2, 14, 16, 14),
            Block.createCuboidShape(4, 0, 0, 6, 16, 2),
            Block.createCuboidShape(10, 0, 0, 12, 16, 2),
            Block.createCuboidShape(10, 0, 14, 12, 16, 16),
            Block.createCuboidShape(14, 0, 4, 16, 16, 6),
            Block.createCuboidShape(14, 0, 10, 16, 16, 12),
            Block.createCuboidShape(0, 0, 4, 2, 16, 6),
            Block.createCuboidShape(0, 0, 10, 2, 16, 12),
            Block.createCuboidShape(4, 0, 14, 6, 16, 16)
    ).reduce((v1, v2) -> VoxelShapes.combineAndSimplify(v1, v2, BooleanBiFunction.OR)).get();

    private static final VoxelShape BOTTOM_SHAPE = VoxelShapes.combineAndSimplify(
            Block.createCuboidShape(0, 0, 0, 16, 2, 16),
            MIDDLE_SHAPE, BooleanBiFunction.OR
    );

    private static final VoxelShape TOP_SHAPE = VoxelShapes.combineAndSimplify(
            Block.createCuboidShape(0, 14, 0, 16, 16, 16),
            MIDDLE_SHAPE, BooleanBiFunction.OR
    );

    private static final VoxelShape STANDALONE_SHAPE = VoxelShapes.union(
            Block.createCuboidShape(0, 14, 0, 16, 16, 16),
            Block.createCuboidShape(0, 0, 0, 16, 2, 16),
            MIDDLE_SHAPE
    );

    public AethumFluxCacheBlock() {
        super(FabricBlockSettings.copyOf(Blocks.STONE_BRICKS).nonOpaque().luminance(10));
        this.setDefaultState(this.getDefaultState().with(PART, Part.STANDALONE));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return switch (state.get(PART)) {
            case BOTTOM -> BOTTOM_SHAPE;
            case MIDDLE -> MIDDLE_SHAPE;
            case TOP -> TOP_SHAPE;
            case STANDALONE -> STANDALONE_SHAPE;
        };
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return checkType(type, AffinityBlocks.Entities.AETHUM_FLUX_CACHE, TickedBlockEntity.ticker());
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        var above = ctx.getWorld().getBlockState(ctx.getBlockPos().up());
        var below = ctx.getWorld().getBlockState(ctx.getBlockPos().down());

        return getUpdateState(above, below);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        var above = world.getBlockState(pos.up());
        var below = world.getBlockState(pos.down());

        return getUpdateState(above, below);
    }

    private BlockState getUpdateState(BlockState above, BlockState below) {
        if (!above.isOf(AETHUM_FLUX_CACHE) && !below.isOf(AETHUM_FLUX_CACHE)) {
            return this.getDefaultState();
        } else if (above.isOf(AETHUM_FLUX_CACHE) && !below.isOf(AETHUM_FLUX_CACHE)) {
            return this.getDefaultState().with(PART, Part.BOTTOM);
        } else if (!above.isOf(AETHUM_FLUX_CACHE) && below.isOf(AETHUM_FLUX_CACHE)) {
            return this.getDefaultState().with(PART, Part.TOP);
        } else {
            return this.getDefaultState().with(PART, Part.MIDDLE);
        }
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return super.canPlaceAt(state, world, pos);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new AethumFluxCacheBlockEntity(pos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    public enum Part implements StringIdentifiable {
        BOTTOM(true, true),
        MIDDLE(false, true),
        TOP(false, false),
        STANDALONE(true, false);

        public final boolean isBase;
        public final boolean extendsUp;

        Part(boolean isBase, boolean extendsUp) {
            this.isBase = isBase;
            this.extendsUp = extendsUp;
        }

        @Override
        public String asString() {
            return this.name().toLowerCase();
        }
    }
}