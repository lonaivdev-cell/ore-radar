package net.loserver.oreradar.fabric.mixin;

import net.loserver.oreradar.client.OutlineRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the through-walls outlines first on the HUD, under the crosshair, hotbar and chat, as
 * NeoForge's "below all" layer does. Fabric API's {@code HudRenderCallback} runs last, over the chat,
 * and Fabric API for 1.21.1 has no HUD layers.
 */
@Mixin(Gui.class)
abstract class GuiMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void oreradar$renderOutlines(GuiGraphics graphics, DeltaTracker delta, CallbackInfo callback) {
        OutlineRenderer.renderHud(graphics);
    }
}
