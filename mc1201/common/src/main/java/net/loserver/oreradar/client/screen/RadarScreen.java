package net.loserver.oreradar.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.loserver.oreradar.client.CatalogModel;
import net.loserver.oreradar.client.RadarClient;
import net.loserver.oreradar.client.StatusText;
import net.loserver.oreradar.core.config.ClientConfig;
import net.loserver.oreradar.core.config.EffectiveClientSettings;
import net.loserver.oreradar.core.config.Setting;
import net.loserver.oreradar.core.energy.ChargeMath;
import net.loserver.oreradar.core.net.ConfigPacket;
import net.loserver.oreradar.core.net.RadarProtocol;
import net.loserver.oreradar.core.net.ScanPacket;
import net.loserver.oreradar.item.RadarItem;
import net.loserver.oreradar.item.TargetNames;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The radar screen (DESIGN §6): choose what the radar in one inventory slot tracks. Inventory-style
 * and non-pausing, so the world, the outlines and the HUD stay live behind it.
 *
 * <pre>
 *  tabs | title                              count |
 *   All | [search: name, @mod, #tag            ]   | [ preview: rotating block |#]
 *   ... | [ 9 x 5 grid of targets           ] [s]  |   name, mod, status        |#] charge
 *   ★ ⟲ |                                          |   charge text              |#]
 *   ⚙   | [o][a][b][p]  [probe slider]  [ Clear target ]
 * </pre>
 * Left-click tracks an entry, right-click stars it. The tabs are All (left out when one category
 * already lists everything), the server's categories, Favourites and Recent (kept per server in
 * {@code oreradar-client-data.json}) and the settings gear.
 * With more tabs than the panel is tall, the grid gets more rows so the tabs fit, then the tabs move
 * closer together, and past that they scroll. On a screen narrower than the panel and its tabs (GUI
 * scale Auto on a 4:3 or 5:4 window can leave 320 pixels) the preview column gets narrower.
 */
public final class RadarScreen extends Screen {
    /** The panel's natural width; narrower screens take it down to what fits (see {@link #init}). */
    private static final int PANEL_WIDTH = 326;
    private static final int COLUMNS = 9;
    private static final int MIN_ROWS = 5;
    private static final int SLOT = GuiSprites.SLOT_SIZE;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 34;
    private static final int SEARCH_X = 9;
    private static final int SEARCH_Y = 18;
    private static final int SEARCH_WIDTH = 176;
    private static final int SEARCH_HEIGHT = 12;
    private static final int SCROLL_X = GRID_X + COLUMNS * SLOT + 2;
    private static final int SCROLL_WIDTH = 14;
    private static final int PREVIEW_X = 190;
    private static final int PREVIEW_Y = 17;
    /** The panel's border right of the preview column. */
    private static final int PREVIEW_MARGIN = 7;
    private static final int BAR_WIDTH = 8;
    /** The preview's turning block (centre and size) and the name under it, from the display's top. */
    private static final int PREVIEW_BLOCK_Y = 22;
    private static final float PREVIEW_BLOCK_SIZE = 24;
    private static final int PREVIEW_TEXT_Y = 44;
    private static final int CONTROLS_GAP = 6;
    private static final int BOTTOM_MARGIN = 7;
    private static final int TAB_WIDTH = 28;
    private static final int TAB_TOP = 4;
    /** Tabs move this close together before they scroll instead: their icons then just do not overlap. */
    private static final int MIN_TAB_PITCH = 18;
    /** Room kept between the tabs or the panel and the screen's edges. */
    private static final int SCREEN_MARGIN = 2;
    private static final int LABEL_COLOR = 0x404040;
    private static final int PREVIEW_TEXT = 0xFFE8E8E8;
    private static final int PREVIEW_MUTED = 0xFF8C9399;
    /** One full turn of the preview block, in milliseconds. */
    private static final long TURN_MILLIS = 8000;
    private static final FormattedText ELLIPSIS = FormattedText.of("…");

    private enum TabKind { ALL, CATEGORY, FAVOURITES, RECENT, SETTINGS }

    private record Tab(TabKind kind, String id, Component name, @Nullable ItemStack item, @Nullable GuiSprites.Icon icon, int category) {
        void drawIcon(GuiGraphics graphics, int x, int y) {
            if (item != null) graphics.renderItem(item, x, y);
            else if (icon != null) icon.draw(graphics, x, y);
        }
    }

    private final RadarClient client;
    private final int slot;
    private final List<Tab> tabs = new ArrayList<>();
    private Tab tab;
    private String tabId;
    private String query = "";
    private List<CatalogModel.Entry> shown = List.of();
    private int scrollRow;
    private boolean draggingScroller;
    /** The catalog the tabs were built from; a reload on the server sends a new one. */
    private CatalogModel builtFrom;

    private int left;
    private int top;
    private int rows;
    private int panelWidth;
    private int panelHeight;
    private int previewWidth;
    /** The width of the preview's text, beside its charge bar. */
    private int textWidth;
    /** The tabs down the panel's side: how far apart, how many show, and the first that shows. */
    private int tabPitch;
    private int visibleTabs;
    private int tabScroll;
    private EditBox search;
    private Button clear;

    /** @param slot the radar's protocol slot (0-35, off hand 40), or -1 when the player holds none */
    public RadarScreen(RadarClient client, int slot) {
        super(Component.translatable("oreradar.screen.title"));
        this.client = client;
        this.slot = slot;
        this.tabId = client.lastTab();
    }

    @Override
    protected void init() {
        buildTabs();
        int tabsHeight = TAB_TOP * 2 + tabs.size() * GuiSprites.TAB_HEIGHT;
        int baseHeight = panelHeight(MIN_ROWS);
        int extraRows = Math.max(0, Mth.positiveCeilDiv(tabsHeight - baseHeight, SLOT));
        int fitRows = Math.max(MIN_ROWS, MIN_ROWS + (height - 8 - baseHeight) / SLOT);
        rows = MIN_ROWS + Math.min(extraRows, fitRows - MIN_ROWS);
        panelHeight = panelHeight(rows);
        // Minecraft guarantees 320 pixels; the grid and the controls need their full width, so a
        // narrow screen takes it from the preview column.
        panelWidth = Math.min(PANEL_WIDTH, width - TAB_WIDTH - 2 * SCREEN_MARGIN);
        previewWidth = panelWidth - PREVIEW_X - PREVIEW_MARGIN;
        textWidth = previewWidth - BAR_WIDTH - 10;
        left = (width - panelWidth + TAB_WIDTH) / 2;
        top = Math.max(4, (height - panelHeight) / 2);
        int tabRoom = panelHeight - TAB_TOP * 2 - GuiSprites.TAB_HEIGHT;
        tabPitch = Mth.clamp(tabRoom / Math.max(1, tabs.size() - 1), MIN_TAB_PITCH, GuiSprites.TAB_HEIGHT);
        visibleTabs = Math.min(tabs.size(), tabRoom / tabPitch + 1);

        search = new EditBox(font, left + SEARCH_X, top + SEARCH_Y, SEARCH_WIDTH, SEARCH_HEIGHT, Component.translatable("oreradar.screen.search"));
        search.setHint(Component.translatable("oreradar.screen.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            refresh();
        });
        addRenderableWidget(search);

        int controlsY = top + GRID_Y + rows * SLOT + CONTROLS_GAP;
        int x = left + GRID_X;
        // The view toggles are the client's own settings, usable anywhere unless the server forbids the aid.
        addRenderableWidget(new ToggleButton(x, controlsY, GuiSprites.ICON_OUTLINES, Component.translatable("oreradar.screen.toggle.outlines"),
                () -> client.config().outlinesEnabled(), () -> forbiddenUnless(limits -> limits.outlines() && limits.maxMarkers() > 0),
                () -> toggle(ClientConfig.OUTLINES_ENABLED)));
        x += ToggleButton.SIZE + 2;
        addRenderableWidget(new ToggleButton(x, controlsY, GuiSprites.ICON_ARROW, Component.translatable("oreradar.screen.toggle.arrow"),
                () -> client.config().arrowEnabled(), () -> forbiddenUnless(ConfigPacket.Limits::arrow),
                () -> toggle(ClientConfig.ARROW_ENABLED)));
        x += ToggleButton.SIZE + 2;
        addRenderableWidget(new ToggleButton(x, controlsY, GuiSprites.ICON_BEEP, Component.translatable("oreradar.screen.toggle.beep"),
                () -> client.config().beepEnabled(), () -> forbiddenUnless(ConfigPacket.Limits::beep),
                () -> toggle(ClientConfig.BEEP_ENABLED)));
        x += ToggleButton.SIZE + 2;
        addRenderableWidget(new ToggleButton(x, controlsY, GuiSprites.ICON_POWER, Component.translatable("oreradar.screen.toggle.power"),
                () -> hasRadar() && !RadarItem.isOff(stack()), this::powerUnavailable,
                () -> client.power(slot, RadarItem.isOff(stack()))));
        x += ToggleButton.SIZE + 4;
        int sliderWidth = left + PREVIEW_X - 4 - x;
        addRenderableWidget(new ProbeSlider(x, controlsY, sliderWidth));
        clear = addRenderableWidget(Button.builder(Component.translatable("oreradar.screen.clear"), button -> client.select(slot, ""))
                .bounds(left + PREVIEW_X, controlsY, previewWidth, 20)
                .build());

        selectTab(tabId);
        scrollTabsTo(tabs.indexOf(tab));
        updateClearButton();
        setInitialFocus(search);
    }

    private static int panelHeight(int rows) {
        return GRID_Y + rows * SLOT + CONTROLS_GAP + 20 + BOTTOM_MARGIN;
    }

    // ------------------------------------------------------------------ state

    @Nullable
    private ConfigPacket.Limits limits() {
        return client.limits();
    }

    private ItemStack stack() {
        LocalPlayer player = minecraft.player;
        return player != null ? RadarClient.stackIn(player, slot) : ItemStack.EMPTY;
    }

    private boolean hasRadar() {
        return RadarProtocol.isValidSlot(slot) && stack().getItem() instanceof RadarItem;
    }

    @Nullable
    private String trackedSpec() {
        return hasRadar() ? RadarItem.target(stack()) : null;
    }

    private void toggle(Setting<Boolean> setting) {
        ClientConfig config = client.config();
        client.updateConfig(config.with(setting, !config.get(setting)));
    }

    /** "Disabled by this server" when the connected server's limits forbid a feature, else null. */
    @Nullable
    private Component forbiddenUnless(Predicate<ConfigPacket.Limits> allowed) {
        ConfigPacket.Limits limits = limits();
        return limits != null && !allowed.test(limits) ? Component.translatable("oreradar.screen.disabled_by_server") : null;
    }

    /** Why the power button cannot be used: the server has no (compatible) Ore Radar, or no radar is held. */
    @Nullable
    private Component powerUnavailable() {
        if (!client.isReady()) return connectionMessage();
        return hasRadar() ? null : Component.translatable("oreradar.screen.toggle.power.no_radar");
    }

    private void buildTabs() {
        builtFrom = client.catalog();
        tabs.clear();
        List<CatalogModel.Entry> entries = builtFrom.entries();
        for (CatalogModel.Category category : builtFrom.categories()) {
            boolean empty = entries.stream().noneMatch(e -> e.inCategory(category.index()));
            // A category with nothing the rules allow is hidden (DESIGN §4).
            if (!empty) {
                tabs.add(new Tab(TabKind.CATEGORY, "category:" + category.id(), category.name(), category.icon(), null, category.index()));
            }
        }
        // "All" would repeat a lone category that lists every entry, as the default (ores only) does.
        boolean oneTabHasAll = tabs.size() == 1 && entries.stream().allMatch(e -> e.inCategory(tabs.get(0).category()));
        if (!oneTabHasAll) {
            tabs.add(0, new Tab(TabKind.ALL, "all", Component.translatable("oreradar.screen.tab.all"), null, GuiSprites.ICON_ALL, -1));
        }
        tabs.add(new Tab(TabKind.FAVOURITES, "favourites", Component.translatable("oreradar.screen.tab.favourites"), null,
                GuiSprites.ICON_FAVOURITES, -1));
        tabs.add(new Tab(TabKind.RECENT, "recent", Component.translatable("oreradar.screen.tab.recent"), null, GuiSprites.ICON_RECENT, -1));
        tabs.add(new Tab(TabKind.SETTINGS, "settings", Component.translatable("oreradar.screen.tab.settings"), null,
                GuiSprites.ICON_SETTINGS, -1));
    }

    private void selectTab(@Nullable String id) {
        Tab chosen = tabs.get(0);
        for (Tab t : tabs) {
            if (t.id().equals(id) && t.kind() != TabKind.SETTINGS) chosen = t;
        }
        if (chosen != tab) scrollRow = 0;
        tab = chosen;
        tabId = chosen.id();
        client.setLastTab(tabId);
        refresh();
    }

    /** Recomputes the grid's entries from the tab and the search. */
    private void refresh() {
        CatalogModel catalog = client.catalog();
        List<CatalogModel.Entry> base = switch (tab.kind()) {
            case CATEGORY -> catalog.entries().stream().filter(e -> e.inCategory(tab.category())).toList();
            case FAVOURITES -> catalog.findAll(client.favourites());
            case RECENT -> catalog.findAll(client.recents());
            default -> catalog.entries();
        };
        shown = CatalogModel.search(base, query, client::modName);
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, Mth.positiveCeilDiv(shown.size(), COLUMNS) - rows);
    }

    @Override
    public void tick() {
        search.tick();
        if (client.catalog() != builtFrom) rebuildWidgets();
        updateClearButton();
    }

    private void updateClearButton() {
        clear.active = client.isReady() && trackedSpec() != null;
    }

    /** The world stays live behind the radar screen, as behind the inventory. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        client.flushConfig();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && !query.isEmpty()) {
            search.setValue("");
            return true;
        }
        if (!search.isFocused() && minecraft.options.keyInventory.matches(key, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Tab clickedTab = tabAt(mouseX, mouseY);
        if (clickedTab != null && button == InputConstants.MOUSE_BUTTON_LEFT) {
            playClick();
            if (clickedTab.kind() == TabKind.SETTINGS) minecraft.setScreen(new RadarSettingsScreen(this));
            else selectTab(clickedTab.id());
            return true;
        }
        int index = slotAt(mouseX, mouseY);
        if (index >= 0) {
            CatalogModel.Entry entry = index < shown.size() ? shown.get(index) : null;
            if (entry != null && button == InputConstants.MOUSE_BUTTON_LEFT && hasRadar() && client.isReady()) {
                playClick();
                client.select(slot, entry.spec());
                if (tab.kind() == TabKind.RECENT) refresh();
            } else if (entry != null && button == InputConstants.MOUSE_BUTTON_RIGHT) {
                playClick();
                client.toggleFavourite(entry.spec());
                if (tab.kind() == TabKind.FAVOURITES) refresh();
            }
            return true;
        }
        if (isOverScrollbar(mouseX, mouseY) && button == InputConstants.MOUSE_BUTTON_LEFT) {
            draggingScroller = maxScroll() > 0;
            scrollTo(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroller) {
            scrollTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScroller = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isOverGrid(mouseX, mouseY) || isOverScrollbar(mouseX, mouseY)) {
            scrollRow = Mth.clamp(scrollRow - (int) Math.signum(delta), 0, maxScroll());
            return true;
        }
        if (visibleTabs < tabs.size() && mouseX >= left - TAB_WIDTH && mouseX < left && mouseY >= top && mouseY < top + panelHeight) {
            tabScroll = Mth.clamp(tabScroll - (int) Math.signum(delta), 0, tabs.size() - visibleTabs);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** Scrolls the tabs, when they scroll at all, so that the tab at this index shows. */
    private void scrollTabsTo(int index) {
        if (index < tabScroll) tabScroll = index;
        else if (index >= tabScroll + visibleTabs) tabScroll = index - visibleTabs + 1;
        tabScroll = Mth.clamp(tabScroll, 0, tabs.size() - visibleTabs);
    }

    private void scrollTo(double mouseY) {
        int track = rows * SLOT - 2 - GuiSprites.SCROLLER.height();
        double fraction = (mouseY - (top + GRID_Y + 1) - GuiSprites.SCROLLER.height() / 2.0) / track;
        scrollRow = Mth.clamp((int) Math.round(fraction * maxScroll()), 0, maxScroll());
    }

    private void playClick() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1F));
    }

    private boolean isOverGrid(double mouseX, double mouseY) {
        return mouseX >= left + GRID_X && mouseX < left + GRID_X + COLUMNS * SLOT
                && mouseY >= top + GRID_Y && mouseY < top + GRID_Y + rows * SLOT;
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        return mouseX >= left + SCROLL_X && mouseX < left + SCROLL_X + SCROLL_WIDTH
                && mouseY >= top + GRID_Y && mouseY < top + GRID_Y + rows * SLOT;
    }

    /** The index into {@link #shown} of the cell under the mouse (possibly past its end), or -1. */
    private int slotAt(double mouseX, double mouseY) {
        if (!isOverGrid(mouseX, mouseY)) return -1;
        int column = (int) (mouseX - left - GRID_X) / SLOT;
        int row = (int) (mouseY - top - GRID_Y) / SLOT;
        return (scrollRow + row) * COLUMNS + column;
    }

    /**
     * The tab under the mouse, as drawn: the selected tab lies over the others, and where tabs sit
     * closer than their height each one shows only down to where the next one starts.
     */
    @Nullable
    private Tab tabAt(double mouseX, double mouseY) {
        if (mouseX >= left || mouseX < left - TAB_WIDTH) return null;
        int selected = tabs.indexOf(tab);
        if (isTabShown(selected) && mouseY >= tabY(selected) && mouseY < tabY(selected) + GuiSprites.TAB_HEIGHT) return tab;
        int last = tabScroll + visibleTabs - 1;
        for (int i = tabScroll; i <= last; i++) {
            int y = tabY(i);
            int shownHeight = i == last ? GuiSprites.TAB_HEIGHT : tabPitch;
            if (mouseX >= left - TAB_WIDTH + 2 && mouseY >= y && mouseY < y + shownHeight) return tabs.get(i);
        }
        return null;
    }

    private boolean isTabShown(int index) {
        return index >= tabScroll && index < tabScroll + visibleTabs;
    }

    private int tabY(int index) {
        return top + TAB_TOP + (index - tabScroll) * tabPitch;
    }

    // ------------------------------------------------------------------ drawing

    /** Lighter than vanilla's dimming: the radar is used while looking at the world. */
    @Override
    public void renderBackground(GuiGraphics graphics) {
        if (minecraft.level != null) graphics.fill(0, 0, width, height, 0x60101010);
        else super.renderBackground(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int lastTab = tabScroll + visibleTabs;
        for (int i = tabScroll; i < lastTab; i++) {
            if (tabs.get(i) != tab) GuiSprites.TAB.draw(graphics, left - TAB_WIDTH + 2, tabY(i));
        }
        GuiSprites.panel(graphics, left, top, panelWidth, panelHeight);
        if (isTabShown(tabs.indexOf(tab))) GuiSprites.TAB_SELECTED.draw(graphics, left - TAB_WIDTH, tabY(tabs.indexOf(tab)));
        for (int i = tabScroll; i < lastTab; i++) {
            Tab t = tabs.get(i);
            // Centred in the part of the tab that shows: the selected one sticks out 2 pixels further.
            t.drawIcon(graphics, left - TAB_WIDTH + (t == tab ? 6 : 7), tabY(i) + 3);
        }
        // Where tabs scroll, a small arrow above or below says that more are hidden there.
        int arrowX = left - TAB_WIDTH / 2 - 1;
        if (tabScroll > 0) drawTabArrow(graphics, arrowX, tabY(tabScroll) - 1, -1);
        if (lastTab < tabs.size()) drawTabArrow(graphics, arrowX, tabY(lastTab - 1) + GuiSprites.TAB_HEIGHT, 1);

        Component count = shown.size() == 1 ? Component.translatable("oreradar.screen.count.one")
                : Component.translatable("oreradar.screen.count", RadarItem.formatNumber(shown.size()));
        int countWidth = client.isReady() ? font.width(count) : 0;
        Component title = Component.translatable("oreradar.screen.title_tab", this.title, tab.name());
        drawTrimmed(graphics, title, left + 8, top + 6, panelWidth - 16 - countWidth - 10, LABEL_COLOR);
        if (countWidth > 0) graphics.drawString(font, count, left + panelWidth - 8 - countWidth, top + 6, 0x707070, false);

        CatalogModel.Entry hovered = renderGrid(graphics, mouseX, mouseY);
        renderScrollbar(graphics);
        boolean overBar = renderPreview(graphics, hovered, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (query.isEmpty() && search.isFocused()) {
            graphics.drawString(font, search.getMessage(), search.getX() + 4, search.getY() + (SEARCH_HEIGHT - 8) / 2, 0xFF606060, false);
        }

        if (hovered != null) {
            graphics.renderComponentTooltip(font, entryTooltip(hovered), mouseX, mouseY);
        } else if (overBar) {
            graphics.renderTooltip(font, chargeTooltip(), mouseX, mouseY);
        } else {
            Tab overTab = tabAt(mouseX, mouseY);
            if (overTab != null) graphics.renderTooltip(font, overTab.name(), mouseX, mouseY);
        }
    }

    /** A 3-pixel-tall arrowhead: pointing up ({@code direction} -1) with its base on row y, or down (+1) from row y. */
    private static void drawTabArrow(GuiGraphics graphics, int centreX, int y, int direction) {
        for (int row = 0; row < 3; row++) {
            int halfWidth = direction < 0 ? row : 2 - row;
            int rowY = direction < 0 ? y - 2 + row : y + row;
            graphics.fill(centreX - halfWidth, rowY, centreX + halfWidth + 1, rowY + 1, 0xFFE0E0E0);
        }
    }

    /** The slots, their items and stars; returns the entry under the mouse. */
    @Nullable
    private CatalogModel.Entry renderGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        CatalogModel.Entry tracked = client.catalog().find(trackedSpec());
        int hoveredIndex = slotAt(mouseX, mouseY);
        CatalogModel.Entry hovered = null;
        for (int cell = 0; cell < COLUMNS * rows; cell++) {
            int x = left + GRID_X + (cell % COLUMNS) * SLOT;
            int y = top + GRID_Y + (cell / COLUMNS) * SLOT;
            int index = scrollRow * COLUMNS + cell;
            CatalogModel.Entry entry = index < shown.size() ? shown.get(index) : null;
            (entry != null && entry == tracked ? GuiSprites.SLOT_TRACKED : GuiSprites.SLOT).draw(graphics, x, y);
            if (entry == null) continue;
            graphics.renderItem(entry.icon(), x + 1, y + 1);
            if (client.isFavourite(entry.spec())) {
                graphics.pose().pushPose();
                // Above the item, which renders at about z 150.
                graphics.pose().translate(0, 0, 250);
                GuiSprites.STAR.draw(graphics, x + SLOT - 1 - GuiSprites.STAR.width(), y + 1);
                graphics.pose().popPose();
            }
            if (index == hoveredIndex) {
                hovered = entry;
                AbstractContainerScreen.renderSlotHighlight(graphics, x + 1, y + 1, 0);
            }
        }
        Component empty = emptyMessage();
        if (empty != null) {
            List<FormattedCharSequence> lines = font.split(empty, COLUMNS * SLOT - 16);
            int textHeight = lines.size() * 10;
            int textWidth = lines.stream().mapToInt(font::width).max().orElse(0);
            int centreX = left + GRID_X + COLUMNS * SLOT / 2;
            int y = top + GRID_Y + (rows * SLOT - textHeight) / 2;
            // A patch of panel behind the words, so the slot lines do not run through them.
            graphics.fill(centreX - textWidth / 2 - 4, y - 4, centreX + textWidth / 2 + 4, y + textHeight + 2, 0xF0C6C6C6);
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, centreX - font.width(line) / 2, y, LABEL_COLOR, false);
                y += 10;
            }
        }
        return hovered;
    }

    /** What the empty grid says, or null when it has entries. */
    @Nullable
    private Component emptyMessage() {
        if (!client.isReady()) return connectionMessage();
        if (!shown.isEmpty()) return null;
        if (!query.isBlank()) return Component.translatable("oreradar.screen.empty.search", query.trim());
        return switch (tab.kind()) {
            case FAVOURITES -> Component.translatable("oreradar.screen.empty.favourites");
            case RECENT -> Component.translatable("oreradar.screen.empty.recent");
            default -> Component.translatable("oreradar.screen.empty.category");
        };
    }

    /** Why the radar is inert: still waiting, no Ore Radar on the server, or another protocol. */
    private Component connectionMessage() {
        return switch (client.connection()) {
            case INCOMPATIBLE -> Component.translatable("oreradar.screen.incompatible", client.serverProtocol(), RadarProtocol.VERSION);
            case WAITING -> client.isAwaitingConfig() ? Component.translatable("oreradar.screen.connecting")
                    : Component.translatable("oreradar.screen.no_server");
            default -> Component.translatable("oreradar.screen.no_server");
        };
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int x = left + SCROLL_X;
        int y = top + GRID_Y;
        int trackHeight = rows * SLOT;
        GuiSprites.inset(graphics, x, y, SCROLL_WIDTH, trackHeight);
        int max = maxScroll();
        int travel = trackHeight - 2 - GuiSprites.SCROLLER.height();
        int offset = max > 0 ? Math.round(travel * scrollRow / (float) max) : 0;
        (max > 0 ? GuiSprites.SCROLLER : GuiSprites.SCROLLER_DISABLED).draw(graphics, x + 1, y + 1 + offset);
    }

    /** The preview display; returns whether the mouse is over its charge bar. */
    private boolean renderPreview(GuiGraphics graphics, @Nullable CatalogModel.Entry hovered, int mouseX, int mouseY) {
        int x = left + PREVIEW_X;
        int y = top + PREVIEW_Y;
        int previewHeight = GRID_Y + rows * SLOT - PREVIEW_Y;
        GuiSprites.display(graphics, x, y, previewWidth, previewHeight);
        int centre = x + 5 + textWidth / 2;

        String tracked = trackedSpec();
        CatalogModel.Entry entry = hovered != null ? hovered : client.catalog().find(tracked);
        int textY = y + PREVIEW_TEXT_Y;
        if (entry != null) {
            renderPreviewIcon(graphics, entry.previewBlock(), entry.icon(), centre, y + PREVIEW_BLOCK_Y);
            drawCentredTrimmed(graphics, entry.name(), centre, textY, PREVIEW_TEXT);
            if (!entry.namespace().isEmpty()) {
                drawCentredTrimmed(graphics, Component.literal(client.modName(entry.namespace())), centre, textY + 10, PREVIEW_MUTED);
            }
        } else if (tracked != null) {
            // A spec the catalog does not list, such as a 1.0 radar's "#forge:ores/iron" (1.0 stored
            // tags; 2.0 lists groups): the block that stands for it, from the synced tags.
            Block standIn = TargetNames.derive(tracked);
            if (standIn != null) {
                renderPreviewIcon(graphics, CatalogModel.previewState(standIn), CatalogModel.iconFor(standIn, tracked), centre,
                        y + PREVIEW_BLOCK_Y);
            }
            drawCentredTrimmed(graphics, TargetNames.of(tracked), centre, textY, PREVIEW_TEXT);
            drawCentredTrimmed(graphics, Component.literal(tracked), centre, textY + 10, PREVIEW_MUTED);
        } else {
            drawCentredTrimmed(graphics, Component.translatable("oreradar.screen.no_target"), centre, textY, PREVIEW_MUTED);
        }

        int statusColor = statusColor();
        int lineY = textY + 22;
        int chargeY = y + previewHeight - 11;
        for (Component part : status()) {
            for (FormattedCharSequence line : font.split(part, textWidth)) {
                if (lineY + font.lineHeight > chargeY) break;
                graphics.drawString(font, line, centre - font.width(line) / 2, lineY, statusColor, false);
                lineY += 10;
            }
        }

        ConfigPacket.Limits limits = limits();
        if (limits == null || !limits.energy() || !hasRadar()) return false;
        int charge = client.chargeOf(minecraft.player, slot);
        int capacity = limits.chargeCapacity();
        long minutes = ChargeMath.minutesLeft(charge, limits.drainPerSecond());
        int percent = Math.round(100F * charge / capacity);
        Component chargeText = minutes >= 0 ? Component.translatable("oreradar.screen.charge_time", percent, minutes)
                : Component.translatable("oreradar.screen.charge", percent);
        drawCentredTrimmed(graphics, chargeText, centre, chargeY, charge > 0 ? 0xFFFFE070 : StatusText.PROBLEM);

        int barX = x + previewWidth - BAR_WIDTH - 4;
        int barY = y + 4;
        int barHeight = previewHeight - 8;
        GuiSprites.display(graphics, barX, barY, BAR_WIDTH, barHeight);
        GuiSprites.chargeFill(graphics, barX + 1, barY + 1, BAR_WIDTH - 2, barHeight - 2, charge / (float) capacity);
        return mouseX >= barX && mouseX < barX + BAR_WIDTH && mouseY >= barY && mouseY < barY + barHeight;
    }

    /** The block, large and slowly turning; without one (fluids, blocks without a model) the icon instead. */
    private void renderPreviewIcon(GuiGraphics graphics, @Nullable BlockState block, ItemStack icon, int centreX, int centreY) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        if (block != null) {
            float size = PREVIEW_BLOCK_SIZE;
            float angle = (Util.getMillis() % TURN_MILLIS) * 360F / TURN_MILLIS;
            pose.translate(centreX, centreY, 150);
            pose.scale(size, -size, size);
            pose.mulPose(Axis.XP.rotationDegrees(30));
            pose.mulPose(Axis.YP.rotationDegrees(angle));
            pose.translate(-0.5F, -0.5F, -0.5F);
            Lighting.setupFor3DItems();
            minecraft.getBlockRenderer().renderSingleBlock(block, pose, graphics.bufferSource(), LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY);
            graphics.flush();
        } else {
            pose.translate(centreX - 16, centreY - 16, 0);
            pose.scale(2, 2, 1);
            graphics.renderItem(icon, 0, 0);
        }
        pose.popPose();
    }

    /** The status under the preview: the connection, the radar, or the last scan of this radar. */
    private List<Component> status() {
        if (!client.isReady()) return List.of(connectionMessage());
        if (!hasRadar()) return List.of(Component.translatable("oreradar.screen.no_radar"));
        ScanPacket scan = client.lastScan();
        if (scan != null && scan.slot() == slot && scan.status() != ScanPacket.Status.IDLE) {
            EffectiveClientSettings settings = client.effective();
            RadarClient.Nearest nearest = settings.distance() ? client.nearest(minecraft.player) : null;
            return StatusText.lines(scan, limits(), nearest, settings.height(), text -> font.width(text) <= textWidth);
        }
        if (RadarItem.isOff(stack())) return List.of(Component.translatable("oreradar.status.off"));
        if (trackedSpec() == null) return List.of(Component.translatable("oreradar.screen.choose_target"));
        return List.of(Component.translatable("oreradar.status.idle"));
    }

    private int statusColor() {
        if (!client.isReady() || !hasRadar()) return StatusText.IDLE;
        ScanPacket scan = client.lastScan();
        if (scan != null && scan.slot() == slot && scan.status() != ScanPacket.Status.IDLE) return StatusText.color(scan.status());
        return StatusText.IDLE;
    }

    private List<Component> entryTooltip(CatalogModel.Entry entry) {
        List<Component> lines = new ArrayList<>();
        lines.add(entry.name());
        lines.add(Component.literal(entry.spec()).withStyle(ChatFormatting.DARK_GRAY));
        if (entry.memberCount() > 1) {
            lines.add(Component.translatable("oreradar.screen.variants", entry.memberCount()).withStyle(ChatFormatting.GRAY));
        }
        if (!entry.namespace().isEmpty()) {
            lines.add(Component.literal(client.modName(entry.namespace())).withStyle(ChatFormatting.BLUE, ChatFormatting.ITALIC));
        }
        lines.add(Component.translatable(client.isFavourite(entry.spec()) ? "oreradar.screen.hint_unstar" : "oreradar.screen.hint_star")
                .withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private Component chargeTooltip() {
        ConfigPacket.Limits limits = limits();
        int charge = client.chargeOf(minecraft.player, slot);
        Component amount = Component.translatable("oreradar.tooltip.charge", RadarItem.formatNumber(charge),
                RadarItem.formatNumber(limits.chargeCapacity()));
        long minutes = ChargeMath.minutesLeft(charge, limits.drainPerSecond());
        return minutes >= 0 ? Component.translatable("oreradar.tooltip.charge_time", amount, minutes) : amount;
    }

    private void drawTrimmed(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int color) {
        graphics.drawString(font, trim(text, maxWidth), x, y, color, false);
    }

    private void drawCentredTrimmed(GuiGraphics graphics, Component text, int centreX, int y, int color) {
        FormattedCharSequence line = trim(text, textWidth);
        graphics.drawString(font, line, centreX - font.width(line) / 2, y, color, false);
    }

    /** The text, cut with an ellipsis if it is wider than maxWidth. */
    private FormattedCharSequence trim(Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text.getVisualOrderText();
        FormattedText cut = font.substrByWidth(text, maxWidth - font.width(ELLIPSIS));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, ELLIPSIS));
    }

    // ------------------------------------------------------------------ probe slider

    /** The outline reach past the nearest match, 0 to the server's maximum in chunks (DESIGN §6). */
    private final class ProbeSlider extends AbstractSliderButton {
        ProbeSlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), 0);
            int max = serverMax();
            int chosen = EffectiveClientSettings.clampProbe(client.config().probeChunks(), max);
            value = max > 0 ? chosen / (double) max : 0;
            active = client.isReady() && max > 0;
            setTooltip(Tooltip.create(Component.translatable("oreradar.screen.probe.tooltip")));
            updateMessage();
        }

        private int serverMax() {
            ConfigPacket.Limits limits = limits();
            return limits != null ? limits.probeChunks() : 0;
        }

        private int chunks() {
            return (int) Math.round(value * serverMax());
        }

        /** Without the server's limits there is no range to choose: the label says so instead of "0 chunks". */
        @Override
        protected void updateMessage() {
            setMessage(client.isReady() ? Component.translatable("oreradar.screen.probe", chunks())
                    : Component.translatable("oreradar.screen.probe.unavailable"));
        }

        /** The server's maximum is stored as -1, "as far as allowed", so it follows the server. */
        @Override
        protected void applyValue() {
            int chunks = chunks();
            int stored = chunks >= serverMax() ? -1 : chunks;
            if (stored != client.config().probeChunks()) {
                client.updateConfig(client.config().with(ClientConfig.PROBE_CHUNKS, stored));
            }
        }
    }
}
