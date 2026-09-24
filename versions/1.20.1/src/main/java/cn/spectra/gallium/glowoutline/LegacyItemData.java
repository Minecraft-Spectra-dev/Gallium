package cn.spectra.gallium.glowoutline;

import java.util.Map;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Read-only bridge from 1.20.1 item NBT to resource-pack predicate values. */
public final class LegacyItemData {
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("enchantments", "Enchantments"), Map.entry("stored_enchantments", "StoredEnchantments"),
        Map.entry("custom_name", "display.Name"), Map.entry("lore", "display.Lore"),
        Map.entry("custom_model_data", "CustomModelData"), Map.entry("repair_cost", "RepairCost"),
        Map.entry("unbreakable", "Unbreakable"), Map.entry("dyed_color", "display.color"),
        Map.entry("trim", "Trim"), Map.entry("attribute_modifiers", "AttributeModifiers"),
        Map.entry("potion_contents", "Potion"), Map.entry("lodestone_tracker", "LodestonePos"));
    private LegacyItemData() {}
    public static String parsePath(String path) {
        if (path.startsWith("nbt.") && path.length() > 4) return path;
        if (!path.startsWith("components.")) return null;
        String key = path.substring(11);
        if (key.startsWith("minecraft:")) key = key.substring(10);
        if (ALIASES.containsKey(key) || key.equals("damage") || key.equals("max_damage") || key.equals("custom_data") || key.equals("enchantment_glint_override"))
            return "components." + key;
        return null;
    }
    public static Object get(ItemStack stack, String path) {
        if (path == null) return null;
        String key = path.startsWith("components.") ? path.substring(11) : null;
        if ("damage".equals(key)) return stack.isDamageableItem() ? stack.getDamageValue() : null;
        if ("max_damage".equals(key)) return stack.isDamageableItem() ? stack.getMaxDamage() : null;
        if ("custom_data".equals(key)) return stack.getTag();
        String nbtPath = key == null ? path.substring(4) : ALIASES.get(key);
        if (nbtPath == null) return null;
        Tag value = stack.getTag();
        for (String part : nbtPath.split("\\.")) {
            if (value instanceof CompoundTag compound) value = compound.get(part);
            else if (value instanceof ListTag list) {
                try { int index = Integer.parseInt(part); value = index >= 0 && index < list.size() ? list.get(index) : null; }
                catch (NumberFormatException invalid) { return null; }
            } else return null;
        }
        if (value instanceof ListTag list && ("enchantments".equals(key) || "stored_enchantments".equals(key)))
            return new Enchantments(list);
        if (value instanceof NumericTag number) return number.getAsNumber();
        if (value instanceof StringTag string) return string.getAsString();
        return value;
    }
    public record Enchantments(ListTag entries) {
        public boolean isEmpty() { return entries.isEmpty(); }
        public boolean contains(ResourceLocation id) {
            for (Tag entry : entries) if (entry instanceof CompoundTag tag && id.toString().equals(tag.getString("id"))) return true;
            return false;
        }
    }
}
