package net.loserver.oreradar.client.screen;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A square on/off button showing an icon instead of text: vanilla's button behind it, a red slash
 * over the icon while off. While it cannot be used it is greyed out, and its tooltip says why: the
 * server forbids the feature ("Disabled by this server", DESIGN §6), or for the power button, no
 * radar is held or the server has no Ore Radar. Its state is read every frame, so it follows changes
 * made elsewhere.
 */
final class ToggleButton extends AbstractButton {
    static final int SIZE = 20;

    private final GuiSprites.Icon icon;
    private final Component name;
    private final BooleanSupplier on;
    private final Supplier<Component> disabledReason;
    private final Runnable toggle;
    /** What the tooltip was made for, so it is rebuilt only when that changes. */
    private boolean shownOn;
    @Nullable private Component shownReason;
    private boolean tooltipMade;

    /** @param disabledReason why the button cannot be used right now, or null when it can */
    ToggleButton(int x, int y, GuiSprites.Icon icon, Component name, BooleanSupplier on, Supplier<Component> disabledReason,
                 Runnable toggle) {
        super(x, y, SIZE, SIZE, name);
        this.icon = icon;
        this.name = name;
        this.on = on;
        this.disabledReason = disabledReason;
        this.toggle = toggle;
    }

    @Override
    public void onPress() {
        toggle.run();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Component reason = disabledReason.get();
        boolean isOn = reason == null && on.getAsBoolean();
        active = reason == null;
        if (!tooltipMade || isOn != shownOn || !Objects.equals(reason, shownReason)) {
            tooltipMade = true;
            shownOn = isOn;
            shownReason = reason;
            setTooltip(Tooltip.create(reason != null ? reason
                    : Component.translatable("oreradar.screen.toggle", name, isOn ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF)));
        }
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    /** The icon takes the place of the label; a greyed-out button dims it. */
    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        int x = getX() + (width - 16) / 2;
        int y = getY() + (height - 16) / 2;
        if (!active) graphics.setColor(0.5F, 0.5F, 0.5F, 1F);
        icon.draw(graphics, x, y);
        if (!active || !on.getAsBoolean()) GuiSprites.ICON_OFF.draw(graphics, x, y);
        graphics.setColor(1F, 1F, 1F, 1F);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
