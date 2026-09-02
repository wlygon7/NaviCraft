package com.wlygon.navicraft.net;

import com.wlygon.navicraft.NaviCraft;
import com.wlygon.navicraft.nav.NavLeg;
import com.wlygon.navicraft.nav.NavTarget;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C payload syncing the leg the client should render toward.
 * {@code active == false} means navigation was cleared; every other field is
 * then meaningless.
 *
 * <p>{@code mode} is one of the {@link NavLeg} MODE_* constants. The position is
 * the leg point (portal marker in MODE_PORTAL, destination otherwise);
 * {@code finalLabel} names the ultimate destination for HUD text like
 * "portal (then home)".
 */
public record NavTargetPayload(boolean active, byte mode, String dimensionId,
                               double x, double y, double z,
                               String legLabel, String finalLabel)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NavTargetPayload> TYPE =
            new CustomPacketPayload.Type<>(NaviCraft.id("nav_target"));

    public static final StreamCodec<FriendlyByteBuf, NavTargetPayload> STREAM_CODEC =
            CustomPacketPayload.codec(NavTargetPayload::write, NavTargetPayload::new);

    private NavTargetPayload(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readByte(), buf.readUtf(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readUtf(), buf.readUtf());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeByte(mode);
        buf.writeUtf(dimensionId);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeUtf(legLabel);
        buf.writeUtf(finalLabel);
    }

    public static NavTargetPayload of(NavLeg leg, NavTarget finalTarget) {
        return new NavTargetPayload(true, leg.mode(), leg.dimensionId(),
                leg.pos().x, leg.pos().y, leg.pos().z,
                leg.label(), finalTarget.label());
    }

    public static NavTargetPayload clear() {
        return new NavTargetPayload(false, NavLeg.MODE_DIRECT, "", 0, 0, 0, "", "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
