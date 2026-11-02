package net.loserver.oreradar.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.loserver.oreradar.client.screen.RadarSettingsScreen;

/**
 * ModMenu's Config button opens the client settings page (DESIGN §6). ModMenu is optional: this
 * entrypoint is compiled against it but loaded only by ModMenu itself.
 */
public final class OreRadarModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return RadarSettingsScreen::new;
    }
}
