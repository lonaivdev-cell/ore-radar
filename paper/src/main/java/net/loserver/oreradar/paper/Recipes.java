package net.loserver.oreradar.paper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ShapedRecipe;

/**
 * The radar and station recipes (DESIGN §3, §8), vanilla items only. The results come from
 * {@link RadarItems}, so they are rebuilt when a reload changes what the items show.
 */
final class Recipes {
    private Recipes() {}

    static void register(ServerRules rules) {
        remove();
        ShapedRecipe radar = new ShapedRecipe(Keys.RECIPE_RADAR, RadarItems.recipeResult(rules));
        radar.shape(" L ", "IGI", "RCR");
        radar.setIngredient('L', Material.LIGHTNING_ROD);
        radar.setIngredient('I', Material.IRON_INGOT);
        radar.setIngredient('G', Material.GLASS_PANE);
        radar.setIngredient('R', Material.REDSTONE);
        radar.setIngredient('C', Material.COMPASS);
        Bukkit.addRecipe(radar);

        ShapedRecipe station = new ShapedRecipe(Keys.RECIPE_STATION, RadarItems.newStation(0, rules));
        station.shape("ILI", "CBC", "III");
        station.setIngredient('I', Material.IRON_INGOT);
        station.setIngredient('L', Material.LIGHTNING_ROD);
        station.setIngredient('C', Material.COPPER_INGOT);
        station.setIngredient('B', Material.REDSTONE_BLOCK);
        Bukkit.addRecipe(station);
    }

    static void remove() {
        Bukkit.removeRecipe(Keys.RECIPE_RADAR);
        Bukkit.removeRecipe(Keys.RECIPE_STATION);
    }
}
