package net.loserver.oreradar.fabric.mixin;

import net.loserver.oreradar.client.OutlineRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the through-walls outlines first on the HUD, under the crosshair, hotbar and chat, as Forge's
 * "below all" overlay does. Fabric API's {@code HudRenderCallback} runs last, over the chat.
 */
@Mixin(Gui.class)
abstract class GuiMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void oreradar$renderOutlines(GuiGraphics graphics, float partialTick, CallbackInfo callback) {
        OutlineRenderer.renderHud(graphics);
    }
}
