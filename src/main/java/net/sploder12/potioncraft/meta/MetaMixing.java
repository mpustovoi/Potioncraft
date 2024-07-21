package net.sploder12.potioncraft.meta;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.cauldron.CauldronBehavior;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.sploder12.potioncraft.*;
import net.sploder12.potioncraft.meta.templates.CustomTemplate;
import net.sploder12.potioncraft.meta.templates.MetaEffectTemplate;
import net.sploder12.potioncraft.util.FluidHelper;
import net.sploder12.potioncraft.util.HeatHelper;
import net.sploder12.potioncraft.util.Json;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public class MetaMixing {

    // The logic and parsing for meta mixing files.

    // all the possible effects a meta file is capable of
    public static final HashMap<String, MetaEffectTemplate> templates = new HashMap<>();

    //public static final CauldronBehavior.CauldronBehaviorMap interactions = CauldronBehavior.createMap("potion");
    public static final Map<Item, CauldronBehavior> interactions = CauldronBehavior.createMap();

    public static final HashMap<StatusEffect, StatusEffect> inversions = new HashMap<>();

    public static void addMutualInversion(StatusEffect first, StatusEffect second) {
        inversions.put(first, second);
        inversions.put(second, first);
    }

    public static void addInversion(StatusEffect from, StatusEffect to) {
        inversions.put(from, to);
    }


    public static HashMap<Identifier, Map<Item, CauldronBehavior>> customBehaviors = new HashMap<>();
    public static Map<Item, CauldronBehavior> getBehavior(Identifier id) {
        if (id == null) {
            return null;
        }

        if (id.equals(PotionCauldronBlock.POTION_CAULDRON_ID)) {
            return interactions;
        }

        if (id.equals(Registries.BLOCK.getId(Blocks.CAULDRON))) {
            return CauldronBehavior.EMPTY_CAULDRON_BEHAVIOR;
        }

        if (id.equals(Registries.BLOCK.getId(Blocks.WATER_CAULDRON))) {
            return CauldronBehavior.WATER_CAULDRON_BEHAVIOR;
        }

        if (id.equals(Registries.BLOCK.getId(Blocks.LAVA_CAULDRON))) {
            return CauldronBehavior.LAVA_CAULDRON_BEHAVIOR;
        }

        if (id.equals(Registries.BLOCK.getId(Blocks.POWDER_SNOW_CAULDRON))) {
            return CauldronBehavior.POWDER_SNOW_CAULDRON_BEHAVIOR;
        }

        if (customBehaviors.containsKey(id)) {
            return customBehaviors.get(id);
        }

        return null;
    }


    public static CauldronBehavior addInteraction(Item item, Map<Item, CauldronBehavior> behaviorMap, Collection<MetaEffect> effects, boolean keepOld, int potency) {
        CauldronBehavior prevBehavior = behaviorMap.get(item);
        if (prevBehavior == null) {
            keepOld = false;
        }

        final boolean keepOldFinal = keepOld;
        CauldronBehavior behavior = (BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, ItemStack itemStack) -> {

            CauldronData data = CauldronData.from(state, world, pos);
            if (data == null) {
                if (keepOldFinal) {
                    return prevBehavior.interact(state, world, pos, player, hand, itemStack);
                }
                return ActionResult.PASS;
            }

            int initLevel = data.entity.getLevel();

            int tmpPotency = getTmpPotency(potency, itemStack, data);

            final int maxPotency = PotionCauldronBlockEntity.getMaxPotency();
            final int newPotency = data.entity.getPotency() + tmpPotency;
            if (maxPotency >= 0 && newPotency > maxPotency) {
                return ActionResult.PASS;
            }

            ActionResult prev = ActionResult.success(world.isClient);
            for (MetaEffect effect : effects) {
                prev = effect.interact(prev, data, world, pos, player, hand, itemStack);
            }

            if (prev != ActionResult.PASS) {
                data.entity.setPotency(newPotency);
            }

            data.transformBlock(world, initLevel);

            if (keepOldFinal && prev == ActionResult.PASS) {
                return prevBehavior.interact(state, world, pos, player, hand, itemStack);
            }

            return prev;
        };

        return behaviorMap.put(item, behavior);
    }

    private static void parseTemplate(String name, JsonObject template) {
        CustomTemplate out = CustomTemplate.parse(template, name);
        if (out == null) {
            Main.log("WARNING: could not parse template " + name + ", is it missing effects?");
            return;
        }

        templates.put("${" + name + "}", out);
    }

    private static int getTmpPotency(int potency, ItemStack itemStack, CauldronData data) {
        int tmpPotency = potency;

        // if using the item's potency, it uses the max of current and held
        if (tmpPotency == -1337) {
            NbtCompound nbt = itemStack.getNbt();
            if (nbt != null && nbt.contains("potency")) {
                tmpPotency = nbt.getInt("potency");
            }
            else {
                tmpPotency = Config.getInteger(Config.FieldID.DEFAULT_POTION_POTENCY);
            }

            int resultPotency = Math.max(tmpPotency, data.entity.getPotency());
            tmpPotency = resultPotency - data.entity.getPotency();
        }
        return tmpPotency;
    }

    public static Collection<MetaEffect> parseEffects(JsonArray effects, String id) {
        ArrayList<MetaEffect> out = new ArrayList<>();

        for (JsonElement elem : effects) {
            if (elem.isJsonObject()) {
                JsonObject obj = elem.getAsJsonObject();

                JsonElement eid = obj.get("id");
                if (eid == null || !eid.isJsonPrimitive()) {
                    Main.log("WARNING: id does not exist in effect " + id);
                    continue;
                }

                JsonPrimitive eprim = eid.getAsJsonPrimitive();
                if (!eprim.isString()) {
                    Main.log("WARNING: effect id must be a string " + id);
                    continue;
                }

                MetaEffectTemplate template = templates.get(eprim.getAsString());
                if (template == null) {
                    Main.log("WARNING: " + eprim.getAsString() + " does not name an effect template " + id);
                    continue;
                }

                Optional<ActionResult> quickfail = Json.getActionResult(obj.get("quickfail"));

                JsonObject params = Json.getObj(obj.get("params"));

                if (params == null) {
                    params = new JsonObject();
                }

                MetaEffect effect = template.apply(params);
                if (quickfail.isPresent()) {

                    ActionResult finalQuickfail = quickfail.get();
                    out.add((ActionResult prev, CauldronData data, World world, BlockPos pos, PlayerEntity player, Hand hand, ItemStack stack) -> {
                        if (finalQuickfail == prev) {
                            return ActionResult.PASS;
                        }

                        return effect.interact(prev, data, world, pos, player, hand, stack);
                    });
                }
                else {
                    out.add(effect);
                }

            }
        }
        return out;
    }

    private static void parseRecipe(Item item, Map<Item, CauldronBehavior> behaviorMap, JsonObject recipe, String id) {
        JsonElement effectsObj = recipe.get("effects");
        if (effectsObj == null || !effectsObj.isJsonArray()) {
            Main.log("WARNING: " + item.toString() + " does not have a effects array " + id);
            return;
        }

        JsonArray effects = effectsObj.getAsJsonArray();
        Collection<MetaEffect> vals = parseEffects(effects, id);
        if (vals.isEmpty()) {
            return;
        }

        boolean keepOld = Json.getBoolOr(recipe.get("keepOld"), false);

        int potency = Json.getIntOr(recipe.get("potency"), 0);

        CauldronBehavior old = addInteraction(item, behaviorMap, vals, keepOld, potency);
    }

    private static void parseRecipes (Map<Item, CauldronBehavior> behaviorMap, JsonObject recipes, String id) {
        recipes.asMap().forEach((String item, JsonElement elem) -> {
            if (!elem.isJsonObject()) {
                Main.log("WARNING: " + item + " does not have a JSON object " + id);
                return;
            }

            Identifier idi = Identifier.tryParse(item);
            if (idi == null) {
                Main.log("WARNING: " + item + " is not a valid identifier " + id);
                return;
            }

            Item itemT = Registries.ITEM.get(idi);
            if (itemT == Items.AIR) {
                Main.log("WARNING: " + item + " is not a valid item " + id);
                return;
            }

            parseRecipe(itemT, behaviorMap, elem.getAsJsonObject(), id);
        });
    }


    private static void parseInversions(JsonArray inversions, String id) {
        for (JsonElement inversionE : inversions) {
            if (inversionE.isJsonObject()) {
                JsonObject inversion = inversionE.getAsJsonObject();

                Identifier from = Json.getId(inversion.get("from"));

                Identifier to = Json.getId(inversion.get("to"));

                if (from == null || to == null || from.equals(to)) {
                    Main.log("WARNING: invalid inversion in " + id);
                    continue;
                }

                boolean mutual = Json.getBoolOr(inversion.get("mutual"), false);

                StatusEffect fromE = Registries.STATUS_EFFECT.get(from);
                StatusEffect toE = Registries.STATUS_EFFECT.get(to);

                // note: the default effect returned is luck.
                // therefore there is no way to determine if it is valid or not.

                if (mutual) {
                    addMutualInversion(fromE, toE);
                }
                else {
                    addInversion(fromE, toE);
                }
            }
        }
    }

    private static void parseHeats(JsonObject heats, String id) {
        heats.asMap().forEach((String blockStr, JsonElement obj) -> {
            if (!obj.isJsonPrimitive()) {
                return;
            }

            JsonPrimitive prim = obj.getAsJsonPrimitive();
            if (!prim.isNumber()) {
                return;
            }

            int heat = prim.getAsInt();

            Identifier blockId = Identifier.tryParse(blockStr);
            if (blockId == null) {
                Main.log("WARNING: block " + blockStr + " is not an identifier " + id);
                return;
            }

            Block block = Registries.BLOCK.get(blockId);
            if (block == Blocks.AIR && !blockId.getPath().equalsIgnoreCase("air")) {
                Main.log("WARNING: block " + blockStr + " is not a valid identifier " + id);
                return;
            }

            HeatHelper.addStaticMapping(block, heat);
        });
    }

    public static void register() {
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return new Identifier("potioncraft", "metamixing");
            }

            @Override
            public void reload(ResourceManager manager) {
                // Clear Caches Here

                FluidHelper.reset();
                HeatHelper.reset();

                CauldronBehavior.EMPTY_CAULDRON_BEHAVIOR.clear();
                CauldronBehavior.WATER_CAULDRON_BEHAVIOR.clear();
                CauldronBehavior.LAVA_CAULDRON_BEHAVIOR.clear();
                CauldronBehavior.POWDER_SNOW_CAULDRON_BEHAVIOR.clear();
                CauldronBehavior.registerBehavior();

                interactions.clear();
                inversions.clear();
                templates.clear();

                MetaEffectTemplate.register();


                // @TODO clear custom behaviors

                Config.loadConfig(); // test this

                Map<Identifier, Resource> resources = manager.findResources("metamixing", id -> id.toString().endsWith(".json"));
                resources.forEach((id, resource) -> {
                    try (InputStream stream = resource.getInputStream(); JsonReader reader = new JsonReader(new InputStreamReader(stream))) {
                        // gson kinda blows ngl
                        JsonParser parser = new JsonParser();

                        JsonElement rootE = parser.parse(reader);
                        if (rootE == null || !rootE.isJsonObject()) {
                            Main.log("Encountered malformed resource " + id);
                            return;
                        }

                        JsonObject root = rootE.getAsJsonObject();

                        JsonElement templatesE = root.get("templates");
                        if (templatesE != null && templatesE.isJsonObject()) {
                            JsonObject templates = templatesE.getAsJsonObject();
                            templates.asMap().forEach((String templateId, JsonElement elem) -> {
                                if (!elem.isJsonObject()) {
                                    Main.log("WARNING: templates must be an object " + templateId);
                                    return;
                                }

                                parseTemplate(templateId, elem.getAsJsonObject());
                            });
                        }

                        JsonElement recipesE = root.get("recipes");
                        if (recipesE != null && recipesE.isJsonObject()) {
                            // parse the recipes
                            JsonObject recipes = recipesE.getAsJsonObject();
                            recipes.asMap().forEach((String blockId, JsonElement elem) -> {
                                if (!elem.isJsonObject()) {
                                    Main.log("WARNING: recipes for " + blockId + " not JSON object " + id);
                                    return;
                                }

                                Identifier bid = Identifier.tryParse(blockId);
                                Map<Item, CauldronBehavior> behaviorMap = getBehavior(bid);

                                if (behaviorMap == null) {
                                    Main.log("WARNING: " + blockId + " does not have cauldron behavior " + id);
                                    return;
                                }

                                JsonObject obj = elem.getAsJsonObject();
                                parseRecipes(behaviorMap, obj, id.toString());
                            });
                        }
                        else {
                            Main.log("WARNING: recipes resource malformed " + id);
                        }

                        JsonElement inversionsE = root.get("inversions");
                        if (inversionsE != null && inversionsE.isJsonArray()) {
                            parseInversions(inversionsE.getAsJsonArray(), id.toString());
                        }
                        else {
                            Main.log("WARNING: inversions resource malformed " + id);
                        }

                        JsonElement heatsE = root.get("heats");
                        if (heatsE != null && heatsE.isJsonObject()) {
                            parseHeats(heatsE.getAsJsonObject(), id.toString());
                        }
                        else {
                            Main.log("WARNING: heats resource malformed " + id);
                        }
                    }
                    catch (Exception e) {
                        Main.log("Error occurred while loading resource " + id + ' ' + e);
                    }
                });
            }
        });
    }
}
