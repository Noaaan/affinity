package io.wispforest.affinity.blockentity.impl;

import io.wispforest.affinity.block.impl.ItemTransferNodeBlock;
import io.wispforest.affinity.blockentity.template.InteractableBlockEntity;
import io.wispforest.affinity.blockentity.template.LinkableBlockEntity;
import io.wispforest.affinity.blockentity.template.SyncedBlockEntity;
import io.wispforest.affinity.blockentity.template.TickedBlockEntity;
import io.wispforest.affinity.client.render.CrosshairStatProvider;
import io.wispforest.affinity.misc.BeforeMangroveBasketCaptureCallback;
import io.wispforest.affinity.misc.screenhandler.ItemTransferNodeScreenHandler;
import io.wispforest.affinity.misc.util.NbtUtil;
import io.wispforest.affinity.object.AffinityBlocks;
import io.wispforest.affinity.object.AffinityParticleSystems;
import io.wispforest.owo.nbt.NbtKey;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Predicate;

@SuppressWarnings("UnstableApiUsage")
public class ItemTransferNodeBlockEntity extends SyncedBlockEntity implements TickedBlockEntity, CrosshairStatProvider, LinkableBlockEntity, InteractableBlockEntity, BeforeMangroveBasketCaptureCallback {

    public static final NbtKey<Mode> MODE_KEY = new NbtKey<>("Mode", NbtKey.Type.STRING.then(Mode::byId, mode -> mode.id));
    public static final NbtKey<Integer> STACK_SIZE_KEY = new NbtKey<>("StackSize", NbtKey.Type.INT);
    public static final NbtKey<ItemStack> FILTER_STACK_KEY = new NbtKey<>("FilterStack", NbtKey.Type.ITEM_STACK);

    public static final NbtKey<Boolean> IGNORE_DAMAGE_KEY = new NbtKey<>("IgnoreDamage", NbtKey.Type.BOOLEAN);
    public static final NbtKey<Boolean> IGNORE_DATA_KEY = new NbtKey<>("IgnoreData", NbtKey.Type.BOOLEAN);
    public static final NbtKey<Boolean> INVERT_FILTER_KEY = new NbtKey<>("InvertFilter", NbtKey.Type.BOOLEAN);

    private final Set<BlockPos> links = new HashSet<>();
    private final List<ItemEntry> entries = new ArrayList<>();

    private BlockApiCache<Storage<ItemVariant>, Direction> storageCache;
    private Direction facing;

    @NotNull private Mode mode = Mode.IDLE;
    private int stackSize = 8;

    public boolean ignoreDamage = true;
    public boolean ignoreData = true;
    public boolean invertFilter = false;
    @NotNull private ItemStack filterStack = ItemStack.EMPTY;

    private long time = ThreadLocalRandom.current().nextLong(0, 10);
    private int startIndex = 0;

    public ItemTransferNodeBlockEntity(BlockPos pos, BlockState state) {
        super(AffinityBlocks.Entities.ITEM_TRANSFER_NODE, pos, state);
        this.facing = state.get(ItemTransferNodeBlock.FACING);
    }

    @Override
    public void setCachedState(BlockState state) {
        super.setCachedState(state);
        this.facing = state.get(ItemTransferNodeBlock.FACING);
    }

    @Override
    public Optional<String> beginLink(PlayerEntity player, NbtCompound linkData) {
        return Optional.empty();
    }

    @Override
    public Optional<LinkResult> finishLink(PlayerEntity player, BlockPos linkTo, NbtCompound linkData) {
        if (!(this.world.getBlockEntity(linkTo) instanceof ItemTransferNodeBlockEntity other)) return Optional.of(LinkResult.NO_TARGET);
        if (Math.abs(linkTo.getX() - this.pos.getX()) > 15 || Math.abs(linkTo.getY() - this.pos.getY()) > 15 || Math.abs(linkTo.getZ() - this.pos.getZ()) > 15) {
            return Optional.of(LinkResult.OUT_OF_RANGE);
        }

        if (!this.addLink(linkTo)) return Optional.of(LinkResult.ALREADY_LINKED);
        other.addLink(this.pos);

        return Optional.of(LinkResult.LINK_CREATED);
    }

    @Override
    public Optional<LinkResult> destroyLink(PlayerEntity player, BlockPos destroyFrom, NbtCompound linkData) {
        if (!this.removeLink(destroyFrom)) return Optional.of(LinkResult.NOT_LINKED);

        if (this.world.getBlockEntity(destroyFrom) instanceof ItemTransferNodeBlockEntity other) {
            other.removeLink(this.pos);
        }

        return Optional.of(LinkResult.LINK_CREATED);
    }

    private void clearLinks() {
        for (var link : this.links) {
            if (!(world.getBlockEntity(link) instanceof ItemTransferNodeBlockEntity node)) continue;
            node.removeLink(this.pos);
        }
    }

    public void onBroken() {
        this.clearLinks();
        for (var entry : this.entries) {
            if (!entry.insert) continue;
            this.dropItem(entry.item);
        }
    }

    private boolean addLink(BlockPos pos) {
        if (!this.links.add(pos)) return false;

        this.startIndex = 0;
        this.markDirty();

        return true;
    }

    private boolean removeLink(BlockPos pos) {
        if (!this.links.remove(pos)) return false;

        this.startIndex = 0;
        this.markDirty();

        return true;
    }

    @Override
    public boolean beforeMangroveBasketCapture(World world, BlockPos pos, MutableObject<BlockState> state, BlockEntity blockEntity) {
        this.clearLinks();
        return true;
    }

    @Override
    public void tickServer() {
        if (this.entries.removeIf(entry -> {
            if (++entry.age >= 10) {
                if (!entry.insert) return true;

                var item = this.storageTransaction((storage, transaction) -> {
                    int inserted = (int) storage.insert(ItemVariant.of(entry.item), entry.item.getCount(), transaction);
                    transaction.commit();

                    if (inserted != entry.item.getCount()) {
                        return entry.item.copyWithCount(entry.item.getCount() - inserted);
                    } else {
                        return ItemStack.EMPTY;
                    }
                });

                if (item == null) {
                    this.dropItem(entry.item);
                } else if (!item.isEmpty()) {
                    this.dropItem(item);
                }

                return true;
            } else {
                return false;
            }
        })) this.markDirty();

        if (this.time++ % 10 != 0) return;

        if (this.mode == Mode.SENDING) {
            if (!this.entries.isEmpty()) return;

            var targets = this.linkedNodes(mode -> mode != Mode.SENDING);

            if (!targets.isEmpty()) this.startIndex = (this.startIndex + 1) % targets.size();
            var firstTarget = targets.isEmpty() ? null : targets.get(this.startIndex);

            Predicate<ItemVariant> predicate = firstTarget == null
                    ? this::acceptsItem
                    : item -> this.acceptsItem(item) && firstTarget.acceptsItem(item);

            var stack = this.storageTransaction((storage, transaction) -> {
                var resource = StorageUtil.findExtractableResource(storage, predicate, transaction);
                if (resource == null) return ItemStack.EMPTY;

                int extracted = (int) storage.extract(resource, this.stackSize, transaction);
                transaction.commit();

                return resource.toStack(extracted);
            });

            if (stack == null || stack.isEmpty()) return;

            this.entries.add(new ItemEntry(stack.copy(), 0, false));
            this.markDirty();

            if (!targets.isEmpty()) {
                var insertVariant = ItemVariant.of(stack);

                int validTargets = 0;
                for (var node : targets) {
                    if (node.acceptsItem(insertVariant)) validTargets++;
                }

                int countPerTarget = (int) Math.ceil(stack.getCount() / (double) validTargets);

                for (int i = this.startIndex; i < targets.size() + startIndex; i++) {
                    if (stack.isEmpty()) break;

                    var node = targets.get(i % targets.size());
                    if (!node.acceptsItem(insertVariant)) continue;

                    int insertCount = Math.min(node.maxInsertCount(insertVariant), Math.min(countPerTarget, stack.getCount()));
                    if (insertCount == 0) continue;

                    var transferTime = Math.max(15, (int) Math.round(Math.sqrt(node.pos.getSquaredDistance(this.pos))) * 5);

                    AffinityParticleSystems.DISSOLVE_ITEM.spawn(this.world, particleOrigin(this), new AffinityParticleSystems.DissolveData(
                            stack, particleOrigin(node), 10, transferTime
                    ));

                    node.insertItem(stack.copyWithCount(insertCount), transferTime + 10);
                    stack.decrement(insertCount);
                }
            }

            if (!stack.isEmpty()) this.insertItem(stack, 0);
        }
    }

    @Override
    public void tickClient() {
        this.entries.forEach(entry -> entry.age++);
    }

    private void insertItem(ItemStack item, int delay) {
        this.entries.add(new ItemEntry(item, -delay, true));
        this.markDirty();
    }

    private @Nullable ItemStack storageTransaction(BiFunction<Storage<ItemVariant>, Transaction, @Nullable ItemStack> action) {
        var storage = this.attachedStorage();
        if (storage == null) return null;

        try (var transaction = Transaction.openOuter()) {
            return action.apply(storage, transaction);
        }
    }

    private @Nullable Storage<ItemVariant> attachedStorage() {
        if (this.storageCache == null) this.storageCache = BlockApiCache.create(ItemStorage.SIDED, (ServerWorld) this.world, this.pos.offset(this.facing));
        return this.storageCache.find(this.facing.getOpposite());
    }

    private void dropItem(ItemStack stack) {
        this.world.spawnEntity(new ItemEntity(
                this.world,
                this.pos.getX() + .5 - this.facing.getOffsetX() * .15,
                this.pos.getY() + .5 - this.facing.getOffsetY() * .15,
                this.pos.getZ() + .5 - this.facing.getOffsetZ() * .15,
                stack
        ));
    }

    private int maxInsertCount(ItemVariant variant) {
        var storage = this.attachedStorage();
        if (storage == null) return Integer.MAX_VALUE;

        try (var transaction = Transaction.openOuter()) {
            for (var entry : this.entries) {
                if (!entry.insert) continue;
                storage.insert(entry.variant(), entry.item.getCount(), transaction);
            }

            return (int) storage.insert(variant, Long.MAX_VALUE, transaction);
        }
    }

    private boolean acceptsItem(ItemVariant variant) {
        if (this.filterStack.isEmpty()) return true;
        return this.invertFilter != this.testFilter(variant.getItem(), variant.getNbt());
    }

    private boolean testFilter(Item item, @Nullable NbtCompound nbt) {
        if (this.filterStack.getItem() != item) return false;
        if (this.ignoreData || !this.filterStack.hasNbt()) return true;

        var standard = this.filterStack.getNbt();
        if (this.ignoreDamage) standard.remove("Damage");

        return NbtHelper.matches(standard, nbt, true);
    }

    private List<ItemTransferNodeBlockEntity> linkedNodes(Predicate<Mode> modePredicate) {
        var nodes = new ArrayList<ItemTransferNodeBlockEntity>();

        for (var link : this.links) {
            if (!(this.world.getBlockEntity(link) instanceof ItemTransferNodeBlockEntity node)) continue;
            if (modePredicate.test(node.mode)) {
                nodes.add(node);
            }
        }

        return nodes;
    }

    public Set<BlockPos> links() {
        return this.links;
    }

    @Override
    public void appendTooltipEntries(List<Entry> entries) {
        entries.add(Entry.text(Text.empty(), Text.literal(StringUtils.capitalize(this.mode.id))));
        entries.add(Entry.icon(Text.literal(String.valueOf(this.stackSize)), 8, 0));
    }

    public @NotNull ActionResult onScroll(PlayerEntity player, boolean direction) {
        int newStackSize = direction ? this.stackSize * 2 : this.stackSize / 2;
        this.stackSize = MathHelper.clamp(newStackSize, 1, 64);

        return this.stackSize == newStackSize ? ActionResult.SUCCESS : ActionResult.CONSUME;
    }

    @Override
    public ActionResult onUse(PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!player.isSneaking()) {
            if (world.isClient) return ActionResult.SUCCESS;
            player.openHandledScreen(new NamedScreenHandlerFactory() {
                @Override
                public Text getDisplayName() {
                    return ItemTransferNodeBlockEntity.this.getCachedState().getBlock().getName();
                }

                @Override
                public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity player) {
                    return new ItemTransferNodeScreenHandler(syncId, inv, ItemTransferNodeBlockEntity.this);
                }
            });
        } else {
            this.mode = this.mode.next();
            this.markDirty();
        }

        return ActionResult.SUCCESS;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        NbtUtil.writeBlockPosCollection(nbt, "Links", this.links);
        ItemEntry.writeEntries(nbt, "Entries", this.entries);

        nbt.put(MODE_KEY, this.mode);
        nbt.put(STACK_SIZE_KEY, this.stackSize);
        nbt.put(FILTER_STACK_KEY, this.filterStack);

        nbt.put(IGNORE_DAMAGE_KEY, this.ignoreDamage);
        nbt.put(IGNORE_DATA_KEY, this.ignoreData);
        nbt.put(INVERT_FILTER_KEY, this.invertFilter);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        NbtUtil.readBlockPosCollection(nbt, "Links", this.links);
        ItemEntry.readEntries(nbt, "Entries", this.entries);

        this.mode = nbt.get(MODE_KEY);
        this.stackSize = nbt.get(STACK_SIZE_KEY);
        this.filterStack = nbt.get(FILTER_STACK_KEY);

        this.ignoreDamage = nbt.get(IGNORE_DAMAGE_KEY);
        this.ignoreData = nbt.get(IGNORE_DATA_KEY);
        this.invertFilter = nbt.get(INVERT_FILTER_KEY);
    }

    public ItemStack previewItem() {
        return this.entries.isEmpty() || this.entries.get(0).age < 0
                ? ItemStack.EMPTY
                : this.entries.get(0).item;
    }

    public List<ItemStack> displayItems() {
        var list = new ArrayList<ItemStack>(this.entries.size());
        for (var entry : this.entries) {
            if (!entry.insert) continue;
            list.add(entry.item);
        }

        return list;
    }

    public @NotNull ItemStack filterStack() {
        return this.filterStack;
    }

    public void setFilterStack(ItemStack filterStack) {
        this.filterStack = filterStack.copyWithCount(1);
        this.markDirty();
    }

    public Direction facing() {
        return this.facing;
    }

    private static Vec3d particleOrigin(ItemTransferNodeBlockEntity node) {
        return Vec3d.ofCenter(node.pos).add(node.facing.getOffsetX() * .1, node.facing.getOffsetY() * .1, node.facing.getOffsetZ() * .1);
    }

    public static final class ItemEntry {
        private final ItemStack item;
        private final boolean insert;

        private int age;
        private @Nullable ItemVariant variant = null;

        public ItemEntry(ItemStack item, int age, boolean insert) {
            this.item = item;
            this.age = age;
            this.insert = insert;
        }

        public ItemVariant variant() {
            if (this.variant == null) this.variant = ItemVariant.of(this.item);
            return this.variant;
        }

        public static void writeEntries(NbtCompound nbt, String key, List<ItemEntry> entries) {
            var list = new NbtList();

            for (var entry : entries) {
                var entryNbt = new NbtCompound();
                entryNbt.put("Item", entry.item.writeNbt(new NbtCompound()));
                entryNbt.putInt("Age", entry.age);
                entryNbt.putBoolean("Insert", entry.insert);

                list.add(entryNbt);
            }

            nbt.put(key, list);
        }

        public static void readEntries(NbtCompound nbt, String key, List<ItemEntry> entries) {
            entries.clear();
            var list = nbt.getList(key, NbtElement.COMPOUND_TYPE);

            for (var entryNbt : list) {
                var entryData = (NbtCompound) entryNbt;
                entries.add(new ItemEntry(
                        ItemStack.fromNbt(entryData.getCompound("Item")),
                        entryData.getInt("Age"),
                        entryData.getBoolean("Insert")
                ));
            }

            nbt.put(key, list);
        }
    }

    public enum Mode {
        SENDING, IDLE;

        public final String id;

        Mode() {
            this.id = this.name().toLowerCase(Locale.ROOT);
        }

        public Mode next() {
            return this == SENDING ? IDLE : SENDING;
        }

        public static Mode byId(String id) {
            return "sending".equals(id) ? SENDING : IDLE;
        }
    }
}