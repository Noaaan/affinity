package io.wispforest.affinity.mixin;

import net.minecraft.inventory.CraftingInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.recipe.TippedArrowRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TippedArrowRecipe.class)
public class TippedArrowRecipeMixin {
    @Inject(method = "craft(Lnet/minecraft/inventory/CraftingInventory;)Lnet/minecraft/item/ItemStack;", at = @At(value = "RETURN", ordinal = 1))
    private void addExtraData(CraftingInventory craftingInventory, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack arrowStack = craftingInventory.getStack(1 + craftingInventory.getWidth());

        if (arrowStack.hasNbt() && arrowStack.getNbt().contains("ExtraPotionNbt", NbtElement.COMPOUND_TYPE))
            cir.getReturnValue().getOrCreateNbt().put("ExtraPotionNbt", arrowStack.getSubNbt("ExtraPotionNbt"));
    }
}