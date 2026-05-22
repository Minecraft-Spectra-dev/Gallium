package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.shader.GlowPipeline;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ItemEffectsManager implements SimpleSynchronousResourceReloadListener {

    private static final Identifier RELOAD_ID = Identifier.fromNamespaceAndPath("gallium", "item_effects");
    private static final Identifier RESOURCE_PATH = Identifier.fromNamespaceAndPath("gallium", "item_effects.json");

    private static volatile ItemEffectConfig defaultConfig = ItemEffectConfig.DEFAULT;
    private static volatile List<ItemEffectRule> rules = List.of();
    private static volatile boolean active = false;

    public static boolean isActive() { return active; }

    @Override
    public Identifier getFabricId() {
        return RELOAD_ID;
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        GlowPipeline.clearAll();
        cn.spectra.gallium.glowoutline.capture.GlowCaptureManager.clearAll();

        var resource = manager.getResource(RESOURCE_PATH);
        if (resource.isEmpty()) {
            defaultConfig = ItemEffectConfig.DEFAULT;
            rules = List.of();
            active = false;
            Gallium.LOGGER.info("No item_effects.json found, glow outline inactive.");
            return;
        }

        try (InputStream stream = resource.get().open()) {
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            parseConfig(root);
            active = true;
        } catch (Exception e) {
            Gallium.LOGGER.error("Failed to parse item_effects.json", e);
            defaultConfig = ItemEffectConfig.DEFAULT;
            rules = List.of();
            active = false;
        }
    }

    private void parseConfig(JsonObject root) {
        ItemEffectConfig baseDefault = ItemEffectConfig.DEFAULT;
        if (root.has("default")) {
            baseDefault = parseEffect(root.getAsJsonObject("default"), baseDefault);
        }
        defaultConfig = baseDefault;

        List<ItemEffectRule> parsed = new ArrayList<>();

        if (root.has("rules")) {
            JsonArray rulesArr = root.getAsJsonArray("rules");
            for (int i = 0; i < rulesArr.size(); i++) {
                JsonObject ruleObj = rulesArr.get(i).getAsJsonObject();
                ItemEffectRule rule = parseRule(ruleObj, baseDefault, rulesArr.size() - i);
                if (rule != null) parsed.add(rule);
            }
        }

        // Legacy format: "items" and "tags" top-level fields
        if (root.has("items")) {
            JsonObject itemsObj = root.getAsJsonObject("items");
            int priority = 1000;
            for (var entry : itemsObj.entrySet()) {
                var ref = BuiltInRegistries.ITEM.get(Identifier.parse(entry.getKey()));
                if (ref.isPresent()) {
                    ItemEffectConfig effect = parseEffect(entry.getValue().getAsJsonObject(), baseDefault);
                    List<ItemCondition> conds = List.of(new ItemCondition.Items(Set.of(ref.get().value())));
                    parsed.add(new ItemEffectRule(priority--, MatchMode.ALL_OF, conds, effect));
                }
            }
        }
        if (root.has("tags")) {
            JsonObject tagsObj = root.getAsJsonObject("tags");
            int priority = 500;
            for (var entry : tagsObj.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("#")) key = key.substring(1);
                TagKey<Item> tag = TagKey.create(BuiltInRegistries.ITEM.key(), Identifier.parse(key));
                ItemEffectConfig effect = parseEffect(entry.getValue().getAsJsonObject(), baseDefault);
                List<ItemCondition> conds = List.of(new ItemCondition.Tag(tag));
                parsed.add(new ItemEffectRule(priority--, MatchMode.ALL_OF, conds, effect));
            }
        }

        parsed.sort(null);
        rules = List.copyOf(parsed);

        // Register pipelines for all referenced shaders
        Set<String> shaders = new HashSet<>();
        shaders.add(baseDefault.shader());
        for (ItemEffectRule rule : parsed) {
            shaders.add(rule.effect().shader());
        }
        for (String shader : shaders) {
            GlowPipeline.getOrCreate(shader);
        }

        Gallium.LOGGER.info("Loaded item effects: {} rules, {} shaders", rules.size(), shaders.size());
    }

    private static ItemEffectRule parseRule(JsonObject obj, ItemEffectConfig baseDefault, int implicitPriority) {
        int priority = obj.has("priority") ? obj.get("priority").getAsInt() : implicitPriority;

        JsonObject matchObj = obj.has("match") ? obj.getAsJsonObject("match") : obj;

        List<ItemCondition> conditions = new ArrayList<>();
        parseConditions(matchObj, conditions);

        ItemEffectConfig effect = baseDefault;
        if (obj.has("effect")) {
            effect = parseEffect(obj.getAsJsonObject("effect"), baseDefault);
        }

        if (conditions.isEmpty()) return null;
        return new ItemEffectRule(priority, MatchMode.ALL_OF, List.copyOf(conditions), effect);
    }

    private static void parseConditions(JsonObject matchObj, List<ItemCondition> conditions) {
        if (matchObj.has("items")) {
            JsonArray arr = matchObj.getAsJsonArray("items");
            Set<Item> items = new HashSet<>();
            for (JsonElement el : arr) {
                var ref = BuiltInRegistries.ITEM.get(Identifier.parse(el.getAsString()));
                ref.ifPresent(r -> items.add(r.value()));
            }
            if (!items.isEmpty()) conditions.add(new ItemCondition.Items(Set.copyOf(items)));
        }

        if (matchObj.has("tags")) {
            JsonArray arr = matchObj.getAsJsonArray("tags");
            for (JsonElement el : arr) {
                String key = el.getAsString();
                if (key.startsWith("#")) key = key.substring(1);
                TagKey<Item> tag = TagKey.create(BuiltInRegistries.ITEM.key(), Identifier.parse(key));
                conditions.add(new ItemCondition.Tag(tag));
            }
        }

        if (matchObj.has("predicates")) {
            JsonArray preds = matchObj.getAsJsonArray("predicates");
            for (int i = 0; i < preds.size(); i++) {
                ItemCondition cond = parseConditionNode(preds.get(i).getAsJsonObject());
                if (cond != null) conditions.add(cond);
            }
        }
    }

    private static ItemCondition parseConditionNode(JsonObject node) {
        // Logical operators
        if (node.has("and")) {
            JsonArray arr = node.getAsJsonArray("and");
            List<ItemCondition> children = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                ItemCondition c = parseConditionNode(arr.get(i).getAsJsonObject());
                if (c != null) children.add(c);
            }
            return children.isEmpty() ? null : new ItemCondition.And(List.copyOf(children));
        }
        if (node.has("or")) {
            JsonArray arr = node.getAsJsonArray("or");
            List<ItemCondition> children = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                ItemCondition c = parseConditionNode(arr.get(i).getAsJsonObject());
                if (c != null) children.add(c);
            }
            return children.isEmpty() ? null : new ItemCondition.Or(List.copyOf(children));
        }
        if (node.has("not")) {
            ItemCondition child = parseConditionNode(node.getAsJsonObject("not"));
            return child == null ? null : new ItemCondition.Not(child);
        }

        // Path-based condition
        if (!node.has("path")) return null;
        String path = node.get("path").getAsString();

        // Resolve component from path (e.g. "components.minecraft:enchantments")
        if (!path.startsWith("components.")) return null; // nbt.* paths ignored on this version
        String compId = path.substring("components.".length());
        DataComponentType<?> compType = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(compId));
        if (compType == null) return null;

        // Determine check mode from fields
        ItemCondition.Path.CheckMode mode;
        String value = "";
        float min = Float.NEGATIVE_INFINITY, max = Float.POSITIVE_INFINITY;

        if (node.has("not_empty")) {
            mode = ItemCondition.Path.CheckMode.NOT_EMPTY;
        } else if (node.has("contains")) {
            mode = ItemCondition.Path.CheckMode.CONTAINS;
            value = node.get("contains").getAsString();
        } else if (node.has("min") || node.has("max")) {
            mode = ItemCondition.Path.CheckMode.RANGE;
            if (node.has("min")) min = node.get("min").getAsFloat();
            if (node.has("max")) max = node.get("max").getAsFloat();
        } else if (node.has("equals")) {
            mode = ItemCondition.Path.CheckMode.EQUALS;
            value = node.get("equals").getAsString();
        } else {
            mode = ItemCondition.Path.CheckMode.EXISTS;
        }

        return new ItemCondition.Path(compType, mode, value, min, max);
    }

    static ItemEffectConfig parseEffect(JsonObject obj, ItemEffectConfig fallback) {
        String shader = fallback.shader();
        Vector3f inner = new Vector3f(fallback.innerColor());
        Vector3f outer = new Vector3f(fallback.outerColor());
        float intensity = fallback.intensity();
        float pulseSpeed = fallback.pulseSpeed();
        float waveSpeed = fallback.waveSpeed();

        if (obj.has("shader")) shader = obj.get("shader").getAsString();
        if (obj.has("inner_color")) inner = parseColor(obj, "inner_color", inner);
        if (obj.has("outer_color")) outer = parseColor(obj, "outer_color", outer);
        if (obj.has("color")) {
            Vector3f c = parseHexColor(obj.get("color").getAsString());
            if (c != null) {
                inner = c;
                outer = new Vector3f(c).mul(0.7f);
            }
        }
        if (obj.has("intensity")) intensity = obj.get("intensity").getAsFloat();
        if (obj.has("pulse_speed")) pulseSpeed = obj.get("pulse_speed").getAsFloat();
        if (obj.has("wave_speed")) waveSpeed = obj.get("wave_speed").getAsFloat();

        return new ItemEffectConfig(shader, inner, outer, intensity, pulseSpeed, waveSpeed);
    }

    private static Vector3f parseColor(JsonObject obj, String key, Vector3f fallback) {
        JsonElement el = obj.get(key);
        if (el.isJsonArray()) {
            var arr = el.getAsJsonArray();
            return new Vector3f(arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
        } else if (el.isJsonPrimitive()) {
            Vector3f c = parseHexColor(el.getAsString());
            return c != null ? c : fallback;
        }
        return fallback;
    }

    private static Vector3f parseHexColor(String hex) {
        if (hex == null) return null;
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            int rgb = Integer.parseUnsignedInt(hex, 16);
            if (hex.length() == 8) rgb = rgb >>> 8;
            return new Vector3f(
                    ((rgb >> 16) & 0xFF) / 255.0f,
                    ((rgb >> 8) & 0xFF) / 255.0f,
                    (rgb & 0xFF) / 255.0f
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static ItemEffectConfig getConfig(ItemStack stack) {
        if (stack.isEmpty()) return defaultConfig;

        for (ItemEffectRule rule : rules) {
            if (rule.matches(stack)) return rule.effect();
        }

        return defaultConfig;
    }

    public static ItemEffectConfig getDefaultConfig() {
        return defaultConfig;
    }
}