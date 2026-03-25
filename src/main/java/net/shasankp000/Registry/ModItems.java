package net.shasankp000.Registry;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.shasankp000.AetherialSkies;
import net.shasankp000.Ship.Item.ShipCrateItem;
import net.shasankp000.Ship.Item.ShipwrightWandItem;

public final class ModItems {
    public static final Item SHIPWRIGHTS_WORKBENCH = registerItem(
            "shipwright_workbench",
            new BlockItem(ModBlocks.SHIPWRIGHTS_WORKBENCH, new Item.Settings())
    );

    public static final Item SHIP_HELM = registerItem(
            "ship_helm",
            new BlockItem(ModBlocks.SHIP_HELM, new Item.Settings())
    );

    public static final Item SHIPWRIGHTS_WAND = registerItem(
            "shipwright_wand",
            new ShipwrightWandItem(new Item.Settings().maxCount(1))
    );

    public static final Item SHIP_CRATE = registerItem(
            "ship_crate",
            new ShipCrateItem(new Item.Settings().maxCount(1))
    );

    private ModItems() {
    }

    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, new Identifier(AetherialSkies.MOD_ID, name), item);
    }

    public static void registerModItems() {
        AetherialSkies.LOGGER.info("Registering ship items for {}", AetherialSkies.MOD_ID);

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
            entries.add(SHIPWRIGHTS_WORKBENCH);
            entries.add(SHIP_HELM);
            entries.add(SHIPWRIGHTS_WAND);
            entries.add(SHIP_CRATE);
        });
    }
}
