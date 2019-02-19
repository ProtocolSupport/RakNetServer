package raknetserver.pipeline.raknet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import raknetserver.RakNetServer;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.raknet.RakNetEncapsulatedData;
import raknetserver.packet.raknet.RakNetPacket;
import raknetserver.packet.raknet.RakNetReliability.REntry;
import raknetserver.packet.raknet.RakNetReliability.RakNetACK;
import raknetserver.packet.raknet.RakNetReliability.RakNetNACK;
import raknetserver.utils.Constants;
import raknetserver.utils.PacketHandlerRegistry;
import raknetserver.utils.UINT;

public class RakNetPacketReliabilityHandler extends ChannelDuplexHandler {

    protected static final PacketHandlerRegistry<RakNetPacketReliabilityHandler, RakNetPacket> registry = new PacketHandlerRegistry<>();

    static {
        registry.register(RakNetEncapsulatedData.class, (ctx, handler, packet) -> handler.handleEncapsulatedData(ctx, packet));
        registry.register(RakNetACK.class, (ctx, handler, packet) -> handler.handleAck(ctx, packet));
        registry.register(RakNetNACK.class, (ctx, handler, packet) -> handler.handleNack(ctx, packet));
    }

    protected final Int2ObjectOpenHashMap<RakNetEncapsulatedData> sentPackets = new Int2ObjectOpenHashMap<>();

    protected int lastReceivedSeqId = 0;
    protected int lastAckdId = 0;
    protected int nextSendSeqId = 0;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RakNetPacket) {
            registry.handle(ctx, this, (RakNetPacket) msg);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof EncapsulatedPacket) {
            if (sentPackets.size() > Constants.MAX_PACKET_LOSS) {
                throw new DecoderException("Too big packet loss (unconfirmed sent packets)");
            }
            sendPacket(ctx, (EncapsulatedPacket) msg);
            promise.trySuccess();
        } else {
            ctx.writeAndFlush(msg, promise);
        }
    }

    protected void handleEncapsulatedData(ChannelHandlerContext ctx, RakNetEncapsulatedData packet) {
        final int packetSeqId = packet.getSeqId();
        final int seqIdDiff = UINT.B3.minusWrap(packetSeqId, lastReceivedSeqId);
        if (seqIdDiff > 0) {
            if (seqIdDiff > 1) {
                ctx.writeAndFlush(new RakNetNACK(UINT.B3.plus(lastReceivedSeqId, 1),
                        UINT.B3.minus(packetSeqId, 1))).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            }
            lastReceivedSeqId = packetSeqId;
        }
        ctx.writeAndFlush(new RakNetACK(packetSeqId)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        packet.getPackets().forEach(ctx::fireChannelRead); //read encapsulated packets
    }

    protected void handleAck(ChannelHandlerContext ctx, RakNetACK ack) {
        int nAck = 0;
        int maxAckdId = -1;
        for (REntry entry : ack.getEntries()) {
            final int max = UINT.B3.plus(entry.idFinish, 1);
            for (int id = entry.idStart ; id != max ; id = UINT.B3.plus(id, 1)) {
                sentPackets.remove(id);
                maxAckdId = id;
                if (nAck++ > Constants.MAX_PACKET_LOSS) {
                    throw new DecoderException("Too big packet loss (ack confirm range)");
                }
            }
        }
        //resend remaining packets with ids before the last ack id
        while (maxAckdId != -1 && UINT.B3.minusWrap(maxAckdId, lastAckdId) > 0) {
            resendPacket(ctx, lastAckdId);
            lastAckdId = UINT.B3.plus(lastAckdId, 1);
        }
    }

    protected void handleNack(ChannelHandlerContext ctx, RakNetNACK nack) {
        int nNack = 0;
        for (REntry entry : nack.getEntries()) {
            final int max = UINT.B3.plus(entry.idFinish, 1);
            for (int id = entry.idStart ; id != max ; id = UINT.B3.plus(id, 1)) {
                resendPacket(ctx, id);
                if (nNack++ > Constants.MAX_PACKET_LOSS) {
                    throw new DecoderException("Too big packet loss (ack confirm range)");
                }
            }
        }
    }

    protected void sendPacket(ChannelHandlerContext ctx, RakNetEncapsulatedData packet) {
        packet.setSeqId(nextSendSeqId);
        nextSendSeqId = UINT.B3.plus(nextSendSeqId, 1);
        sentPackets.put(packet.getSeqId(), packet);
        ctx.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    protected void sendPacket(ChannelHandlerContext ctx, EncapsulatedPacket packet) {
        sendPacket(ctx, new RakNetEncapsulatedData(packet));
    }

    protected void resendPacket(ChannelHandlerContext ctx, int id) {
        final RakNetEncapsulatedData packet = sentPackets.remove(id);
        if (packet != null) {
            sendPacket(ctx, packet);
        }
    }

}
