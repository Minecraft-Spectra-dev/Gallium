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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ItemEffectsManager implements SimpleSynchronousResourceReloadListener {

    private static final Identifier RELOAD_ID = Identifier.fromNamespaceAndPath("gallium", "item_effects");
    private static final Identifier RESOURCE_PATH = Identifier.fromNamespaceAndPath("gallium", "item_effects.json");

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
        cn.spectra.gallium.glowoutline.shader.GuiGlowElementPipeline.clear();
        cn.spectra.gallium.glowoutline.capture.GlowCaptureManager.clearAll();

        var resource = manager.getResource(RESOURCE_PATH);
        if (resource.isEmpty()) {
            rules = List.of();
            active = false;
            Gallium.LOGGER.info("No item_effects.json found, glow outline inactive.");
            return;
        }

        try (InputStream stream = resource.get().open()) {
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            parseConfig(root);
            active = !rules.isEmpty();
        } catch (Exception e) {
            Gallium.LOGGER.error("Failed to parse item_effects.json", e);
            rules = List.of();
            active = false;
        }
    }

    private void parseConfig(JsonObject root) {
        List<ItemEffectRule> parsed = new ArrayList<>();

        if (root.has("rules")) {
            JsonArray rulesArr = root.getAsJsonArray("rules");
            for (int i = 0; i < rulesArr.size(); i++) {
                JsonObject ruleObj = rulesArr.get(i).getAsJsonObject();
                ItemEffectRule rule = parseRule(ruleObj, i);
                if (rule != null) parsed.add(rule);
            }
        }

        rules = List.copyOf(parsed);

        Set<String> shaders = new HashSet<>();
        for (ItemEffectRule rule : parsed) {
            shaders.add(rule.effect().shader());
        }
        for (String shader : shaders) {
            if (!shader.isEmpty()) GlowPipeline.getOrCreate(shader);
        }
        // Pre-create GUI element pipelines per config
        for (ItemEffectRule rule : parsed) {
            ItemEffectConfig cfg = rule.effect();
            if (cfg != null && !cfg.shader().isEmpty()) {
                cn.spectra.gallium.glowoutline.shader.GuiGlowElementPipeline.getOrCreate(cfg);
            }
        }

        Gallium.LOGGER.info("Loaded item effects: {} rules, {} shaders", rules.size(), shaders.size());
    }

    private static ItemEffectRule parseRule(JsonObject obj, int index) {
        JsonObject matchObj = obj.has("match") ? obj.getAsJsonObject("match") : obj;

        MatchMode mode = MatchMode.ALL_OF;
        if (matchObj.has("mode")) {
            String modeStr = matchObj.get("mode").getAsString();
            mode = switch (modeStr) {
                case "any_of" -> MatchMode.ANY_OF;
                case "none_of" -> MatchMode.NONE_OF;
                case "all_of" -> MatchMode.ALL_OF;
                default -> {
                    Gallium.LOGGER.warn("item_effects rule[{}]: unknown match mode '{}', defaulting to all_of", index, modeStr);
                    yield MatchMode.ALL_OF;
                }
            };
        }

        List<ItemCondition> conditions = new ArrayList<>();
        parseConditions(matchObj, conditions, index);
        if (conditions.isEmpty()) {
            Gallium.LOGGER.warn("item_effects rule[{}]: no valid conditions, skipping rule", index);
            return null;
        }
        if (!obj.has("effect")) {
            Gallium.LOGGER.warn("item_effects rule[{}]: missing 'effect', skipping rule", index);
            return null;
        }

        ItemEffectConfig effect = parseEffect(obj.getAsJsonObject("effect"), index);
        if (effect == null) return null;

        return new ItemEffectRule(mode, List.copyOf(conditions), effect);
    }

    private static void parseConditions(JsonObject matchObj, List<ItemCondition> conditions, int ruleIndex) {
        if (matchObj.has("items")) {
            JsonArray arr = matchObj.getAsJsonArray("items");
            Set<Item> items = new HashSet<>();
            for (JsonElement el : arr) {
                String id = el.getAsString();
                var ref = BuiltInRegistries.ITEM.get(Identifier.parse(id));
                if (ref.isPresent()) {
                    items.add(ref.get().value());
                } else {
                    Gallium.LOGGER.warn("item_effects rule[{}]: unknown item id '{}'", ruleIndex, id);
                }
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
                ItemCondition cond = parseConditionNode(preds.get(i).getAsJsonObject(), ruleIndex);
                if (cond != null) conditions.add(cond);
            }
        }
    }

    private static ItemCondition parseConditionNode(JsonObject node, int ruleIndex) {
        if (node.has("and")) {
            JsonArray arr = node.getAsJsonArray("and");
            List<ItemCondition> children = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                ItemCondition c = parseConditionNode(arr.get(i).getAsJsonObject(), ruleIndex);
                if (c != null) children.add(c);
            }
            return children.isEmpty() ? null : new ItemCondition.And(List.copyOf(children));
        }
        if (node.has("or")) {
            JsonArray arr = node.getAsJsonArray("or");
            List<ItemCondition> children = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                ItemCondition c = parseConditionNode(arr.get(i).getAsJsonObject(), ruleIndex);
                if (c != null) children.add(c);
            }
            return children.isEmpty() ? null : new ItemCondition.Or(List.copyOf(children));
        }
        if (node.has("not")) {
            ItemCondition child = parseConditionNode(node.getAsJsonObject("not"), ruleIndex);
            return child == null ? null : new ItemCondition.Not(child);
        }

        if (!node.has("path")) {
            Gallium.LOGGER.warn("item_effects rule[{}]: predicate missing 'path', skipping", ruleIndex);
            return null;
        }
        String path = node.get("path").getAsString();

        if (!path.startsWith("components.")) {
            Gallium.LOGGER.warn("item_effects rule[{}]: predicate path '{}' must start with 'components.'", ruleIndex, path);
            return null;
        }
        String compId = path.substring("components.".length());
        DataComponentType<?> compType = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(Identifier.parse(compId));
        if (compType == null) {
            Gallium.LOGGER.warn("item_effects rule[{}]: unknown data component '{}'", ruleIndex, compId);
            return null;
        }

        ItemCondition.Path.CheckMode mode;
        String value = "";
        float min = java.lang.Float.NEGATIVE_INFINITY, max = java.lang.Float.POSITIVE_INFINITY;

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

    static ItemEffectConfig parseEffect(JsonObject obj, int ruleIndex) {
        if (obj == null || !obj.has("shader")) {
            Gallium.LOGGER.warn("item_effects rule[{}]: 'effect' missing 'shader'", ruleIndex);
            return null;
        }
        String shader = obj.get("shader").getAsString();

        List<ShaderParam> params = new ArrayList<>();
        if (obj.has("params")) {
            JsonObject paramsObj = obj.getAsJsonObject("params");
            for (var entry : paramsObj.entrySet()) {
                ShaderParam param = parseParam(entry.getKey(), entry.getValue());
                if (param == null) {
                    Gallium.LOGGER.warn("item_effects rule[{}]: failed to parse param '{}'", ruleIndex, entry.getKey());
                } else {
                    params.add(param);
                }
            }
        }

        return new ItemEffectConfig(shader, List.copyOf(params));
    }

    private static ShaderParam parseParam(String name, JsonElement element) {
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isNumber()) {
                return new ShaderParam.Float(name, element.getAsFloat());
            }
            if (element.getAsJsonPrimitive().isString()) {
                String str = element.getAsString();
                if (str.startsWith("#")) {
                    float[] rgb = parseHexColor(str);
                    if (rgb != null) return new ShaderParam.Vec3(name, rgb[0], rgb[1], rgb[2]);
                }
            }
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            return switch (arr.size()) {
                case 2 -> new ShaderParam.Vec2(name, arr.get(0).getAsFloat(), arr.get(1).getAsFloat());
                case 3 -> new ShaderParam.Vec3(name, arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat());
                case 4 -> new ShaderParam.Vec4(name, arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat(), arr.get(3).getAsFloat());
                default -> null;
            };
        }
        return null;
    }

    private static float[] parseHexColor(String hex) {
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            int rgb = Integer.parseUnsignedInt(hex, 16);
            if (hex.length() == 8) rgb = rgb >>> 8;
            return new float[]{
                    ((rgb >> 16) & 0xFF) / 255.0f,
                    ((rgb >> 8) & 0xFF) / 255.0f,
                    (rgb & 0xFF) / 255.0f
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static ItemEffectConfig getConfig(ItemStack stack) {
        if (stack.isEmpty()) return null;

        for (ItemEffectRule rule : rules) {
            if (rule.matches(stack)) return rule.effect();
        }

        return null;
    }
}
