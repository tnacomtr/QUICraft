// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.fabric.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import rs.sudoe.quicraft.core.QuicSupport;
import rs.sudoe.quicraft.core.connect.ConnectDecision.Mode;

/**
 * QUICraft's settings, opened from the multiplayer screen: the transport setting
 * (docs/protocol.md §4), whether QUIC can run on this computer, and a way to forget remembered
 * QUIC failures. Built from widgets only, so it renders the same on 1.21.11 and 26.x.
 */
public final class QuicraftSettingsScreen extends Screen {
    static final String TITLE = "quicraft.settings.title";
    static final String TRANSPORT = "quicraft.settings.transport";
    static final String FORGET = "quicraft.settings.forget_failures";

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    public QuicraftSettingsScreen(Screen parent) {
        super(Component.translatable(TITLE));
        this.parent = parent;
    }

    static Component modeName(Mode mode) {
        return Component.translatable("quicraft.settings.transport." + ClientSettings.name(mode));
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        LinearLayout contents = layout.addToContents(LinearLayout.vertical().spacing(8));
        QuicraftClient client = QuicraftClient.get();
        Mode current = client != null ? client.mode() : Mode.AUTO;
        CycleButton<Mode> transport = CycleButton.builder(QuicraftSettingsScreen::modeName, current)
                .withValues(Mode.AUTO, Mode.TCP_ONLY, Mode.QUIC_ONLY)
                .withTooltip(mode -> Tooltip.create(Component.translatable(
                        "quicraft.settings.transport." + ClientSettings.name(mode) + ".tooltip")))
                .create(0, 0, 310, 20, Component.translatable(TRANSPORT), (button, mode) -> {
                    if (client != null) {
                        client.setMode(mode);
                    }
                });
        transport.active = client != null;
        contents.addChild(transport);
        Button forget = Button.builder(Component.translatable(FORGET), button -> {
            if (client != null) {
                client.connector().forgetFailures();
                button.active = false;
            }
        }).width(310).tooltip(Tooltip.create(Component.translatable(FORGET + ".tooltip"))).build();
        forget.active = client != null;
        contents.addChild(forget);
        contents.addChild(new MultiLineTextWidget(Component.translatable(QuicSupport.isAvailable()
                ? "quicraft.settings.native.available" : "quicraft.settings.native.unavailable"), font)
                .setMaxWidth(310));
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
