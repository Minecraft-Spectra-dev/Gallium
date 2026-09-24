package cn.spectra.gallium.glowoutline;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LegacyItemDataTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static ItemCondition.Path path(String key, ItemCondition.Path.CheckMode mode, String value) {
        return ItemCondition.Path.compile(LegacyItemData.parsePath(key), mode, value, 2, 8);
    }
    @Test void aliasesAndNativePathsDoNotModifyTheItem() {
        ItemStack item = new ItemStack(Items.DIAMOND_SWORD);
        item.getOrCreateTag().putInt("CustomModelData", 7);
        item.getOrCreateTagElement("display").putString("Name", "named sword");
        CompoundTag before = item.getTag().copy();
        assertTrue(path("components.minecraft:custom_model_data", ItemCondition.Path.CheckMode.RANGE, "").test(item));
        assertTrue(path("nbt.display.Name", ItemCondition.Path.CheckMode.CONTAINS, "sword").test(item));
        assertEquals(before, item.getTag());
    }
    @Test void enchantmentsMatchIdsExactly() {
        ItemStack item = new ItemStack(Items.DIAMOND_SWORD);
        ListTag list = new ListTag(); CompoundTag enchant = new CompoundTag();
        enchant.putString("id", "minecraft:sharpness"); enchant.putShort("lvl", (short) 2); list.add(enchant);
        item.getOrCreateTag().put("Enchantments", list);
        assertTrue(path("components.minecraft:enchantments", ItemCondition.Path.CheckMode.CONTAINS, "minecraft:sharpness").test(item));
        assertFalse(path("components.enchantments", ItemCondition.Path.CheckMode.CONTAINS, "minecraft:sharp").test(item));
        assertTrue(path("components.enchantments", ItemCondition.Path.CheckMode.NOT_EMPTY, "").test(item));
        assertTrue(path("nbt.Enchantments.0.lvl", ItemCondition.Path.CheckMode.EQUALS, "2").test(item));
    }
    @Test void missingAndEmptyValuesStayDistinct() {
        ItemStack item = new ItemStack(Items.STICK);
        assertFalse(path("nbt.nope", ItemCondition.Path.CheckMode.EXISTS, "").test(item));
        item.getOrCreateTag().put("empty", new CompoundTag());
        assertTrue(path("nbt.empty", ItemCondition.Path.CheckMode.EXISTS, "").test(item));
        assertFalse(path("nbt.empty", ItemCondition.Path.CheckMode.NOT_EMPTY, "").test(item));
        assertFalse(path("components.damage", ItemCondition.Path.CheckMode.EXISTS, "").test(item));
        assertNull(LegacyItemData.parsePath("components.minecraft:made_up"));
        assertNull(LegacyItemData.parsePath("nbt."));
    }
    @Test void unavailableComponentDoesNotBroadenAnItemCondition() {
        var condition = ItemEffectsManager.parseConditionNode(
            com.google.gson.JsonParser.parseString("{\"path\":\"components.minecraft:unknown_future_component\"}"), 0, "root");
        assertNotNull(condition);
        var sword = new ItemStack(Items.DIAMOND_SWORD);
        var combined = new ItemCondition.And(java.util.List.of(new ItemCondition.Items(java.util.Set.of(Items.DIAMOND_SWORD)), condition));
        assertFalse(combined.test(sword));
        assertFalse(path("components.minecraft:enchantment_glint_override", ItemCondition.Path.CheckMode.EQUALS, "false").test(sword));
    }
}
