package raknetserver.pipeline.raknet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.Channel;
import io.netty.handler.codec.DecoderException;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.raknet.RakNetEncapsulatedData;
import raknetserver.packet.raknet.RakNetPacket;
import raknetserver.packet.raknet.RakNetReliability.REntry;
import raknetserver.packet.raknet.RakNetReliability.RakNetACK;
import raknetserver.packet.raknet.RakNetReliability.RakNetNACK;
import raknetserver.utils.Constants;
import raknetserver.utils.PacketHandlerRegistry;
import raknetserver.utils.UINT;

import java.util.concurrent.TimeUnit;

public class RakNetPacketReliabilityHandler extends ChannelDuplexHandler {

	protected static final int WINDOW = 4096;
	protected static final int HALF_WINDOW = WINDOW / 2;
	protected static final int RTT_FLOOR = 5; //millis

	protected static final PacketHandlerRegistry<RakNetPacketReliabilityHandler, RakNetPacket> registry = new PacketHandlerRegistry<>();
	static {
		registry.register(RakNetEncapsulatedData.class, (ctx, handler, packet) -> handler.handleEncapsulatedData(ctx, packet));
		registry.register(RakNetACK.class, (ctx, handler, packet) -> handler.handleAck(ctx, packet));
		registry.register(RakNetNACK.class, (ctx, handler, packet) -> handler.handleNack(ctx, packet));
	}

	protected final Channel channel;
	protected final Int2ObjectOpenHashMap<RakNetEncapsulatedData> sentPackets = new Int2ObjectOpenHashMap<>();

	protected int lastReceivedSeqId = 0;
	protected int nextSendSeqId = 0;
	protected long minRTT = 2000;

	public RakNetPacketReliabilityHandler(Channel channel) {
		this.channel = channel;
		startResendTimer();
	}

	private void startResendTimer() {
		channel.eventLoop().schedule(() -> {
			if (channel.isOpen()) {
				startResendTimer();
				resendTick();
			}
		}, minRTT, TimeUnit.MILLISECONDS);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof RakNetPacket) {
			registry.handle(ctx, this, (RakNetPacket) msg);
		} else {
			ctx.fireChannelRead(msg);
		}
	}

	protected boolean idWithinWindow(int id) {
		return Math.abs(UINT.B3.minusWrap(id, lastReceivedSeqId)) < HALF_WINDOW;
	}

	protected void handleEncapsulatedData(ChannelHandlerContext ctx, RakNetEncapsulatedData packet) {
		int packetSeqId = packet.getSeqId();
		ctx.writeAndFlush(new RakNetACK(packetSeqId)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
		if (!idWithinWindow(packetSeqId)) { //ignore duplicate packet
			return;
		}
		int seqIdDiff = UINT.B3.minusWrap(packetSeqId, lastReceivedSeqId);
		if (seqIdDiff > 0) {
			if (seqIdDiff > 1) {
				ctx.writeAndFlush(new RakNetNACK(UINT.B3.plus(lastReceivedSeqId, 1), UINT.B3.minus(packetSeqId, 1))).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
			}
			lastReceivedSeqId = packetSeqId;
		}
		packet.getPackets().forEach(ctx::fireChannelRead); //read encapsulated packets
	}

	protected void handleAck(ChannelHandlerContext ctx, RakNetACK ack) {
		for (REntry entry : ack.getEntries()) {
			int idStart = entry.idStart;
			int idFinish = entry.idFinish;
			int idDiff = UINT.B3.minusWrap(idFinish, idStart);
			if (idDiff > Constants.MAX_PACKET_LOSS) {
				throw new DecoderException("Too big packet loss (ack confirm range)");
			}
			for (int i = 0; i <= idDiff; i++) {
				int packetId = UINT.B3.plus(idStart, i);
				RakNetEncapsulatedData packet = sentPackets.remove(packetId);
				if (packet != null) {
					minRTT = Math.min(minRTT, Math.max(packet.timeSinceSend(), RTT_FLOOR));
				}
			}
		}
	}

	protected void handleNack(ChannelHandlerContext ctx, RakNetNACK nack) {
		for (REntry entry : nack.getEntries()) {
			int idStart = entry.idStart;
			int idFinish = entry.idFinish;
			int idDiff = UINT.B3.minusWrap(idFinish, idStart);
			if (idDiff > Constants.MAX_PACKET_LOSS) {
				throw new DecoderException("Too big packet loss (nack resend range)");
			}
			for (int i = 0; i <= idDiff; i++) {
				RakNetEncapsulatedData packet = sentPackets.remove(UINT.B3.plus(idStart, i));
				if (packet != null) {
					sendPacket(packet, null);
				}
			}
		}
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (msg instanceof EncapsulatedPacket) {
			if (sentPackets.size() > Constants.MAX_PACKET_LOSS) {
				throw new DecoderException("Too big packet loss (unconfirmed sent packets)");
			}
			sendPacket(new RakNetEncapsulatedData((EncapsulatedPacket) msg), promise);
		} else {
			ctx.writeAndFlush(msg, promise);
		}
	}

	protected void sendPacket(RakNetEncapsulatedData packet, ChannelPromise promise) {
		packet.setSeqId(nextSendSeqId);
		nextSendSeqId = UINT.B3.plus(nextSendSeqId, 1);
		sentPackets.put(packet.getSeqId(), packet);
		packet.refreshResend();
		if (promise != null) {
			channel.writeAndFlush(packet, promise);
		} else {
			channel.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
		}
	}

	protected void resendTick() {
		for (RakNetEncapsulatedData packet : sentPackets.values()) {
			if (packet.resendTick()) {
				sentPackets.remove(packet.getSeqId());
				sendPacket(packet, null); //resend packet
			}
		}
	}
}
