package cn.spectra.gallium.glowoutline;

import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import cn.spectra.gallium.glowoutline.shader.GuiGlowElementPipeline;
import cn.spectra.gallium.glowoutline.shader.GlowPipeline;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
//#if MC>=1_21_09
import net.minecraft.IdentifierException;
//#else
//$$ import net.minecraft.ResourceLocationException;
//#endif
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
//#if MC>=1_21_09
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import net.minecraft.server.packs.resources.ResourceManager;
//#if MC>=1_21_09
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
//#else
//$$ import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
//#endif
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

//#if MC>=1_21_09
public class ItemEffectsManager implements ResourceManagerReloadListener {

    public static final Identifier RELOAD_ID = Identifier.fromNamespaceAndPath("gallium", "item_effects");
    private static final Identifier RESOURCE_PATH = Identifier.fromNamespaceAndPath("gallium", "item_effects.json");
//#else
//$$ // 1.21.6–1.21.8 register through ResourceManagerHelper, which wants an
//$$ // IdentifiableResourceReloadListener. SimpleSynchronousResourceReloadListener
//$$ // supplies both the sync onResourceManagerReload contract and getFabricId().
//$$ public class ItemEffectsManager implements SimpleSynchronousResourceReloadListener {
//$$
//$$     public static final ResourceLocation RELOAD_ID = ResourceLocation.fromNamespaceAndPath("gallium", "item_effects");
//$$     private static final ResourceLocation RESOURCE_PATH = ResourceLocation.fromNamespaceAndPath("gallium", "item_effects.json");
//$$
//$$     @Override
//$$     public ResourceLocation getFabricId() {
//$$         return RELOAD_ID;
//$$     }
//#endif

    private static volatile List<ItemEffectRule> rules = List.of();
    private static volatile boolean active = false;

    public static boolean isActive() { return active; }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        // Drop runtime GPU resources (mask targets, capture buffers). Pipelines are pruned
        // incrementally below so unchanged shaders don't have to rebuild.
        GlowResources.disposeRuntime();

        var resource = manager.getResource(RESOURCE_PATH);
        if (resource.isEmpty()) {
            rules = List.of();
            active = false;
            GlowPipeline.retainOnly(java.util.Set.of());
            //#if MC<1_21_06
            //$$ GlowPipeline.retainOnlyConfigs(java.util.Set.of());
            //#endif
            GuiGlowElementPipeline.retainOnly(java.util.Set.of());
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
            GlowPipeline.retainOnly(java.util.Set.of());
            //#if MC<1_21_06
            //$$ GlowPipeline.retainOnlyConfigs(java.util.Set.of());
            //#endif
            GuiGlowElementPipeline.retainOnly(java.util.Set.of());
        }
    }

    private static void parseConfig(JsonObject root) {
        List<ItemEffectRule> parsed = new ArrayList<>();
        // One-shot per reload: silence repeat flat-schema deprecation warns from large packs.
        boolean[] flatSchemaWarned = { false };

        if (root.has("rules")) {
            JsonArray rulesArr = root.getAsJsonArray("rules");
            for (int i = 0; i < rulesArr.size(); i++) {
                ItemEffectRule rule;
                try {
                    rule = parseRule(rulesArr.get(i).getAsJsonObject(), i, flatSchemaWarned);
                } catch (Exception e) {
                    Gallium.LOGGER.warn("item_effects rule[{}] threw during parse, skipping: {}", i, e.toString());
                    continue;
                }
                if (rule != null) parsed.add(rule);
            }
        }

        rules = List.copyOf(parsed);

        Set<String> shaders = new HashSet<>();
        Set<ItemEffectConfig> liveConfigs = new HashSet<>();
        for (ItemEffectRule rule : parsed) {
            ItemEffectConfig cfg = rule.effect();
            if (cfg == null) continue;
            String shader = cfg.shader();
            if (!shader.isEmpty()) {
                shaders.add(shader);
                liveConfigs.add(cfg);
            }
        }
        for (ItemEffectConfig cfg : liveConfigs) {
            GlowPipeline.getOrCreate(cfg);
        }
        for (ItemEffectConfig cfg : liveConfigs) {
            GuiGlowElementPipeline.getOrCreate(cfg);
        }
        GlowPipeline.retainOnly(shaders);
        //#if MC<1_21_06
        //$$ // 1.21.5 also has a per-config pipeline cache (statically declares per-param
        //$$ // uniforms). retainOnly(Set<String>) only prunes the unused String-keyed
        //$$ // REGISTRY on 1.21.5; without this, a config whose params changed across
        //$$ // reload but whose shader name stayed the same would leave its stale,
        //$$ // wrong-uniforms pipeline cached forever.
        //$$ GlowPipeline.retainOnlyConfigs(liveConfigs);
        //#endif
        GuiGlowElementPipeline.retainOnly(liveConfigs);

        Gallium.LOGGER.info("Loaded item effects: {} rules, {} shaders", rules.size(), shaders.size());
    }

    private static ItemEffectRule parseRule(JsonObject obj, int index, boolean[] flatSchemaWarned) {
        if (!obj.has("effect")) {
            Gallium.LOGGER.warn("item_effects rule[{}]: missing 'effect', skipping rule", index);
            return null;
        }
        ItemEffectConfig effect = parseEffect(obj.getAsJsonObject("effect"), index);
        if (effect == null) return null;

        boolean hasMatch = obj.has("match");
        JsonObject matchObj = hasMatch ? obj.getAsJsonObject("match") : obj;
        if (!hasMatch && !flatSchemaWarned[0]) {
            // Legacy flat form — match conditions read off the rule object itself instead of a
            // nested "match". Still works, but documenting it as deprecated nudges pack authors
            // toward the canonical schema before we have to pick between the two on a conflict.
            flatSchemaWarned[0] = true;
            Gallium.LOGGER.warn(
                "item_effects rule[{}]: missing 'match' object, falling back to flat-schema (deprecated). "
                    + "Wrap conditions in a \"match\": {...} block. Further occurrences in this reload silenced.",
                index);
        }
        MatchMode mode = parseMode(matchObj, index);

        List<ItemCondition> conditions = new ArrayList<>();
        parseConditions(matchObj, conditions, index);
        if (conditions.isEmpty()) {
            Gallium.LOGGER.warn("item_effects rule[{}]: no valid conditions, skipping rule", index);
            return null;
        }

        return new ItemEffectRule(mode, List.copyOf(conditions), effect);
    }

    static MatchMode parseMode(JsonObject matchObj, int index) {
        if (!matchObj.has("mode")) return MatchMode.ALL_OF;
        String modeStr = matchObj.get("mode").getAsString();
        return switch (modeStr) {
            case "any_of" -> MatchMode.ANY_OF;
            case "none_of" -> MatchMode.NONE_OF;
            case "all_of" -> MatchMode.ALL_OF;
            default -> {
                Gallium.LOGGER.warn("item_effects rule[{}]: unknown match mode '{}', defaulting to all_of", index, modeStr);
                yield MatchMode.ALL_OF;
            }
        };
    }

    private static void parseConditions(JsonObject matchObj, List<ItemCondition> conditions, int ruleIndex) {
        if (matchObj.has("items")) {
            JsonArray arr = matchObj.getAsJsonArray("items");
            Set<Item> items = new HashSet<>();
            for (JsonElement el : arr) {
                String id = el.getAsString();
                var itemId = tryParseId(id, ruleIndex, "item");
                if (itemId == null) continue;
                var ref = BuiltInRegistries.ITEM.get(itemId);
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
                var tagId = tryParseId(key, ruleIndex, "tag");
                if (tagId == null) continue;
                TagKey<Item> tag = TagKey.create(BuiltInRegistries.ITEM.key(), tagId);
                conditions.add(new ItemCondition.Tag(tag));
            }
        }

        if (matchObj.has("predicates")) {
            JsonArray preds = matchObj.getAsJsonArray("predicates");
            for (int i = 0; i < preds.size(); i++) {
                ItemCondition cond = parseConditionNode(preds.get(i), ruleIndex, "predicates[" + i + "]");
                if (cond != null) conditions.add(cond);
            }
        }
    }

    static ItemCondition parseConditionNode(JsonElement element, int ruleIndex, String path) {
        if (element == null || !element.isJsonObject()) {
            Gallium.LOGGER.warn("item_effects rule[{}] {}: expected object, got {}",
                    ruleIndex, path, element == null ? "null" : element.getClass().getSimpleName());
            return null;
        }
        JsonObject node = element.getAsJsonObject();
        try {
            return parseConditionNodeInner(node, ruleIndex, path);
        } catch (Exception e) {
            Gallium.LOGGER.warn("item_effects rule[{}] {}: failed to parse condition: {}",
                    ruleIndex, path, e.toString());
            return null;
        }
    }

    private static ItemCondition parseConditionNodeInner(JsonObject node, int ruleIndex, String path) {
        if (node.has("and")) {
            JsonArray arr = node.getAsJsonArray("and");
            List<ItemCondition> children = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                ItemCondition c = parseConditionNode(arr.get(i), ruleIndex, path + ".and[" + i + "]");
                if (c != null) children.add(c);
            }
            return children.isEmpty() ? null : new ItemCondition.And(List.copyOf(children));
        }
        if (node.has("or")) {
            JsonArray arr = node.getAsJsonArray("or");
            List<ItemCondition> children = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                ItemCondition c = parseConditionNode(arr.get(i), ruleIndex, path + ".or[" + i + "]");
                if (c != null) children.add(c);
            }
            return children.isEmpty() ? null : new ItemCondition.Or(List.copyOf(children));
        }
        if (node.has("not")) {
            ItemCondition child = parseConditionNode(node.get("not"), ruleIndex, path + ".not");
            return child == null ? null : new ItemCondition.Not(child);
        }

        if (!node.has("path")) {
            Gallium.LOGGER.warn("item_effects rule[{}] {}: predicate missing 'path', skipping", ruleIndex, path);
            return null;
        }
        String pathValue = node.get("path").getAsString();
        if (!pathValue.startsWith("components.")) {
            Gallium.LOGGER.warn("item_effects rule[{}] {}: predicate path '{}' must start with 'components.'",
                    ruleIndex, path, pathValue);
            return null;
        }
        String compId = pathValue.substring("components.".length());
        var componentId = tryParseId(compId, ruleIndex, "component");
        if (componentId == null) return null;
        DataComponentType<?> compType = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(componentId);
        if (compType == null) {
            Gallium.LOGGER.warn("item_effects rule[{}] {}: unknown data component '{}'", ruleIndex, path, compId);
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

    private static ItemEffectConfig parseEffect(JsonObject obj, int ruleIndex) {
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

    static ShaderParam parseParam(String name, JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return new ShaderParam.Float(name, element.getAsFloat());
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

    //#if MC>=1_21_09
    private static Identifier tryParseId(String raw, int ruleIndex, String kind) {
        try {
            return Identifier.parse(raw);
        } catch (IdentifierException e) {
            Gallium.LOGGER.warn("item_effects rule[{}]: invalid {} id '{}': {}", ruleIndex, kind, raw, e.getMessage());
            return null;
        }
    }
    //#else
    //$$ private static ResourceLocation tryParseId(String raw, int ruleIndex, String kind) {
    //$$     try {
    //$$         return ResourceLocation.parse(raw);
    //$$     } catch (ResourceLocationException e) {
    //$$         Gallium.LOGGER.warn("item_effects rule[{}]: invalid {} id '{}': {}", ruleIndex, kind, raw, e.getMessage());
    //$$         return null;
    //$$     }
    //$$ }
    //#endif

    public static ItemEffectConfig getConfig(ItemStack stack) {
        if (stack.isEmpty()) return null;

        for (ItemEffectRule rule : rules) {
            if (rule.matches(stack)) return rule.effect();
        }

        return null;
    }
}
