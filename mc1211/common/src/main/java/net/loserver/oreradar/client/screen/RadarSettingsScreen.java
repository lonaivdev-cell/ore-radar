package net.loserver.oreradar.client.screen;

import java.util.List;
import java.util.Locale;

import net.loserver.oreradar.client.RadarClient;
import net.loserver.oreradar.core.ActiveFrom;
import net.loserver.oreradar.core.config.ClientConfig;
import net.loserver.oreradar.core.config.Colors;
import net.loserver.oreradar.core.config.ConfigSchema;
import net.loserver.oreradar.core.config.Setting;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * The client settings page (DESIGN §6), generated from {@link ClientConfig#SCHEMA}: one row per
 * key, grouped by table. Booleans are toggles, numbers sliders, enums cycle buttons, text (colours,
 * the beep sound) edit boxes that only take valid values. Every change applies at once, is written
 * to {@code oreradar-client.toml} shortly after, and reaches the server as prefs when it concerns
 * the scan. Opened from the radar screen's gear tab and from the loaders' mod lists.
 */
public final class RadarSettingsScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    /** The rows' natural width; a screen too narrow for it and the scrollbar gets narrower rows. */
    private static final int ROW_WIDTH = 320;
    private static final int SCROLLBAR_GAP = 8;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int WIDGET_WIDTH = 150;

    @Nullable private final Screen parent;
    private final RadarClient client;

    public RadarSettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("oreradar.settings.title"));
        this.parent = parent;
        this.client = RadarClient.get();
    }

    @Override
    protected void init() {
        addRenderableWidget(new SettingsList());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 - 100, height - 27, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        client.flushConfig();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return parent == null || parent.isPauseScreen();
    }

    private <T> void set(Setting<T> setting, T value) {
        client.updateConfig(client.config().with(setting, value));
    }

    /** "Outline colour", from the lang file, else the schema's English description. */
    private static Component label(Setting<?> setting) {
        String key = "oreradar.settings." + setting.path();
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(setting.description());
    }

    private Component tooltip(Setting<?> setting) {
        String key = "oreradar.settings." + setting.path() + ".tooltip";
        MutableComponent text = Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(setting.description());
        Component note = serverNote(setting);
        if (note != null) text.append("\n").append(note.copy().withStyle(ChatFormatting.GOLD));
        return text;
    }

    /** What the connected server allows of this setting, when it allows less than the setting can say. */
    @Nullable
    private Component serverNote(Setting<?> setting) {
        ConfigPacket.Limits limits = client.isReady() ? client.limits() : null;
        if (limits == null) return null;
        if (setting == ClientConfig.OUTLINES_ENABLED && (!limits.outlines() || limits.maxMarkers() == 0)
                || setting == ClientConfig.ARROW_ENABLED && !limits.arrow()
                || (setting == ClientConfig.ARROW_SHOW_DISTANCE || setting == ClientConfig.ARROW_SHOW_HEIGHT) && !limits.distance()
                || setting == ClientConfig.BEEP_ENABLED && !limits.beep()) {
            return Component.translatable("oreradar.screen.disabled_by_server");
        }
        if (setting == ClientConfig.PROBE_CHUNKS) return Component.translatable("oreradar.settings.server_max", limits.probeChunks());
        if (setting == ClientConfig.OUTLINES_MAX) return Component.translatable("oreradar.settings.server_max", limits.maxMarkers());
        if (setting == ClientConfig.ACTIVE_FROM && limits.activeFromMax() != ActiveFrom.INVENTORY) {
            return Component.translatable("oreradar.settings.server_max", choiceName(limits.activeFromMax()));
        }
        return null;
    }

    private static Component choiceName(Enum<?> value) {
        String key = "oreradar.settings.value." + value.name().toLowerCase(Locale.ROOT);
        return Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(value.name());
    }

    /** The widget that edits one setting, or null for a kind the client file does not use. */
    @Nullable
    private AbstractWidget widgetFor(Setting<?> setting) {
        ClientConfig config = client.config();
        AbstractWidget widget;
        if (setting instanceof Setting.Bool bool) {
            widget = CycleButton.onOffBuilder(config.get(bool)).displayOnlyValue()
                    .create(0, 0, WIDGET_WIDTH, 20, label(bool), (button, value) -> set(bool, value));
        } else if (setting instanceof Setting.Choice<?> choice) {
            widget = choiceButton(choice);
        } else if (setting instanceof Setting.Int number) {
            // The probe reach's -1, "as far as the server allows", is the largest reach: it sits at the
            // right end, past the largest number, as on the radar screen's probe slider.
            boolean serverMax = number == ClientConfig.PROBE_CHUNKS;
            int top = serverMax ? number.max() + 1 : number.max();
            int initial = config.get(number);
            widget = new NumberSlider(serverMax ? 0 : number.min(), top, 1, serverMax && initial < 0 ? top : initial, serverMax) {
                @Override
                void apply(double value) {
                    int chosen = (int) Math.round(value);
                    set(number, serverMax && chosen == top ? -1 : chosen);
                }
            };
        } else if (setting instanceof Setting.Real number) {
            double range = number.max() - number.min();
            int stepsPerUnit = range <= 1 ? 100 : range <= 4 ? 20 : 10;
            widget = new NumberSlider(number.min(), number.max(), stepsPerUnit, config.get(number), false) {
                @Override
                void apply(double value) {
                    set(number, value);
                }
            };
        } else if (setting instanceof Setting.Text text) {
            widget = textBox(text, config.get(text));
        } else {
            return null;
        }
        widget.setTooltip(Tooltip.create(tooltip(setting)));
        return widget;
    }

    private <E extends Enum<E>> CycleButton<E> choiceButton(Setting.Choice<E> choice) {
        return CycleButton.<E>builder(RadarSettingsScreen::choiceName)
                .withValues(choice.values())
                .withInitialValue(client.config().get(choice))
                .displayOnlyValue()
                .create(0, 0, WIDGET_WIDTH, 20, label(choice), (button, value) -> set(choice, value));
    }

    /** Takes what the setting accepts; anything else turns red and is not saved. */
    private EditBox textBox(Setting.Text setting, String value) {
        EditBox box = new EditBox(font, 0, 0, WIDGET_WIDTH - 2, 18, label(setting));
        box.setMaxLength(256);
        box.setValue(value);
        box.setResponder(text -> {
            boolean valid = setting.isValid(text);
            box.setTextColor(valid ? 0xE0E0E0 : 0xFF5555);
            if (valid && !text.equals(client.config().get(setting))) set(setting, text);
        });
        return box;
    }

    /**
     * A slider over [min, max] in steps of {@code 1 / stepsPerUnit}. A value is the nearest step
     * itself, so the file gets 0.7 rather than 0.7000000000000001 (7 × 0.1). With
     * {@code topIsServerMax} the right end reads "as far as allowed".
     */
    private abstract static class NumberSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final int stepsPerUnit;
        private final boolean topIsServerMax;

        NumberSlider(double min, double max, int stepsPerUnit, double initial, boolean topIsServerMax) {
            super(0, 0, WIDGET_WIDTH, 20, Component.empty(), (initial - min) / (max - min));
            this.min = min;
            this.max = max;
            this.stepsPerUnit = stepsPerUnit;
            this.topIsServerMax = topIsServerMax;
            updateMessage();
        }

        double current() {
            double raw = min + value * (max - min);
            return Math.max(min, Math.min(max, Math.round(raw * stepsPerUnit) / (double) stepsPerUnit));
        }

        abstract void apply(double value);

        @Override
        protected void updateMessage() {
            double v = current();
            if (topIsServerMax && v >= max) {
                setMessage(Component.translatable("oreradar.settings.value.server_max"));
            } else if (stepsPerUnit == 1) {
                setMessage(Component.literal(Long.toString(Math.round(v))));
            } else {
                // 2 decimals at most, without trailing zeros: "2", "0.12", "1.4".
                setMessage(Component.literal(String.format(Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "")));
            }
        }

        @Override
        protected void applyValue() {
            apply(current());
        }
    }

    // ------------------------------------------------------------------ the list

    private final class SettingsList extends ContainerObjectSelectionList<SettingsList.Row> {
        SettingsList() {
            super(RadarSettingsScreen.this.minecraft, RadarSettingsScreen.this.width, RadarSettingsScreen.this.height - 64, 32, ROW_HEIGHT);
            for (ConfigSchema.Node node : ClientConfig.SCHEMA.nodes()) {
                if (!(node instanceof ConfigSchema.Table table)) continue;
                addEntry(new Header(Component.translatable("oreradar.settings.section." + table.path())));
                for (Setting<?> setting : table.settings()) {
                    AbstractWidget widget = widgetFor(setting);
                    if (widget == null) continue;
                    boolean colour = setting instanceof Setting.Text text && Colors.isColor(text.defaultValue());
                    addEntry(new SettingRow(label(setting), widget, colour ? (Setting.Text) setting : null));
                }
            }
        }

        @Override
        public int getRowWidth() {
            return Math.min(ROW_WIDTH, width - 2 * (SCROLLBAR_GAP + SCROLLBAR_WIDTH));
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + SCROLLBAR_GAP;
        }

        abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {}

        /** A table's heading. */
        final class Header extends Row {
            private final Component text;

            Header(Component text) {
                this.text = text.copy().withStyle(ChatFormatting.BOLD, ChatFormatting.YELLOW);
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovered, float partialTick) {
                graphics.drawCenteredString(font, text, left + width / 2, top + height - 13, 0xFFFFFF);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of();
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(new NarratableEntry() {
                    @Override
                    public NarrationPriority narrationPriority() {
                        return NarrationPriority.HOVERED;
                    }

                    @Override
                    public void updateNarration(NarrationElementOutput output) {
                        output.add(NarratedElementType.TITLE, text);
                    }
                });
            }
        }

        /** A setting: its label on the left, its widget on the right, and a swatch for colours. */
        final class SettingRow extends Row {
            private final Component label;
            private final AbstractWidget widget;
            @Nullable private final Setting.Text colour;

            SettingRow(Component label, AbstractWidget widget, @Nullable Setting.Text colour) {
                this.label = label;
                this.widget = widget;
                this.colour = colour;
                // The box leaves room on its right for a swatch of the colour it spells.
                if (colour != null) widget.setWidth(WIDGET_WIDTH - 22);
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                               boolean hovered, float partialTick) {
                int labelWidth = width - WIDGET_WIDTH - 8;
                List<FormattedCharSequence> lines = font.split(label, labelWidth);
                int y = top + (20 - Math.min(2, lines.size()) * 9) / 2 + 1;
                for (int i = 0; i < Math.min(2, lines.size()); i++) {
                    graphics.drawString(font, lines.get(i), left, y + i * 9, 0xFFFFFF, false);
                }
                int widgetX = left + width - WIDGET_WIDTH;
                if (colour != null) {
                    int rgb = Colors.parse(client.config().get(colour), 0);
                    graphics.fill(widgetX + WIDGET_WIDTH - 18, top + 1, widgetX + WIDGET_WIDTH, top + 19, 0xFF000000);
                    graphics.fill(widgetX + WIDGET_WIDTH - 17, top + 2, widgetX + WIDGET_WIDTH - 1, top + 18, 0xFF000000 | rgb);
                }
                widget.setX(widget instanceof EditBox ? widgetX + 1 : widgetX);
                widget.setY(widget instanceof EditBox ? top + 1 : top);
                widget.render(graphics, mouseX, mouseY, partialTick);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(widget);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(widget);
            }
        }
    }
}
