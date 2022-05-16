package io.wispforest.affinity.enchantment.impl;

import io.wispforest.affinity.Affinity;
import io.wispforest.affinity.enchantment.template.AffinityEnchantment;
import io.wispforest.affinity.misc.quack.AffinityEntityAddon;
import io.wispforest.affinity.object.AffinityEnchantments;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.tag.TagKey;
import net.minecraft.util.registry.Registry;

public class CriticalGambleEnchantment extends AffinityEnchantment {

    public static final TagKey<EntityType<?>> BLACKLIST = TagKey.of(Registry.ENTITY_TYPE_KEY, Affinity.id("critical_gamble_blacklist"));
    public static final AffinityEntityAddon.DataKey<Long> ACTIVATED_AT = AffinityEntityAddon.DataKey.withDefaultConstant(-1L);

    public CriticalGambleEnchantment() {
        super(Rarity.RARE, EnchantmentTarget.WEAPON, EquipmentSlot.MAINHAND);
    }

    @Override
    public int getMaxLevel() {
        return 5;
    }

    @Override
    protected boolean canAccept(Enchantment other) {
        return other != AffinityEnchantments.WOUNDING;
    }
}
