/*
 * Copyright 1999-2022 The Freenet Project
 * Copyright 2022 Marine Master
 *
 * This file is part of Oldenet.
 *
 * Oldenet is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version.
 *
 * Oldenet is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Oldenet.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package freenet.node;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import freenet.l10n.NodeL10n;
import freenet.nodelogger.Logger;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger.LogLevel;
import freenet.support.TimeUtil;
import freenet.support.io.NativeThread;
import freenet.support.math.MersenneTwister;

/**
 * @author amphibian
 *
 * Thread that sends a packet whenever: - A packet needs to be resent immediately -
 * Acknowledgments or resend requests need to be sent urgently.
 */
// j16sdiz (22-Dec-2008):
// FIXME this is the only class implements Ticker, everbody is using this as
// a generic task scheduler. Either rename this class, or create another tricker for
// non-Packet tasks
public class PacketSender implements Runnable {

	private static volatile boolean logMINOR;

	private static volatile boolean logDEBUG;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	/** Maximum time we will queue a message for in milliseconds */
	static final long MAX_COALESCING_DELAY = TimeUnit.MILLISECONDS.toMillis(100);

	/**
	 * Maximum time we will queue a message for in milliseconds if it is bulk data. Note
	 * that we will send the data immediately anyway because it will normally be big
	 * enough to send a full packet. However this impacts the choice of whether to send
	 * realtime or bulk data, see PeerMessageQueue.addMessages().
	 */
	static final long MAX_COALESCING_DELAY_BULK = TimeUnit.SECONDS.toMillis(5);

	final NativeThread myThread;

	final Node node;

	NodeStats stats;

	long lastReportedNoPackets;

	long lastReceivedPacketFromAnyNode;

	private final MersenneTwister localRandom;

	PacketSender(Node node) {
		this.node = node;
		this.myThread = new NativeThread(this, "PacketSender thread for " + node.getDarknetPortNumber(),
				NativeThread.MAX_PRIORITY, false);
		this.myThread.setDaemon(true);
		this.localRandom = node.createRandom();
	}

	void start(NodeStats stats) {
		this.stats = stats;
		Logger.normal(this, "Starting PacketSender");
		System.out.println("Starting PacketSender");
		this.myThread.start();
	}

	private void schedulePeriodicJob() {

		this.node.ticker.queueTimedJob(new Runnable() {

			@Override
			public void run() {
				try {
					long now = System.currentTimeMillis();
					if (logMINOR) {
						Logger.minor(PacketSender.class, "Starting shedulePeriodicJob() at " + now);
					}
					PeerManager pm = PacketSender.this.node.peers;
					pm.maybeLogPeerNodeStatusSummary(now);
					pm.maybeUpdateOldestNeverConnectedDarknetPeerAge(now);
					PacketSender.this.stats.maybeUpdatePeerManagerUserAlertStats(now);
					PacketSender.this.stats.maybeUpdateNodeIOStats(now);
					pm.maybeUpdatePeerNodeRoutableConnectionStats(now);

					if (logMINOR) {
						Logger.minor(PacketSender.class,
								"Finished running shedulePeriodicJob() at " + System.currentTimeMillis());
					}
				}
				finally {
					PacketSender.this.node.ticker.queueTimedJob(this, 1000);
				}
			}
		}, 1000);
	}

	@Override
	public void run() {
		if (logMINOR) {
			Logger.minor(this, "In PacketSender.run()");
		}
		freenet.support.Logger.OSThread.logPID(this);

		this.schedulePeriodicJob();
		/*
		 * Index of the point in the nodes list at which we sent a packet and then ran out
		 * of bandwidth. We start the loop from here next time.
		 */
		while (true) {
			this.lastReceivedPacketFromAnyNode = this.lastReportedNoPackets;
			try {
				this.realRun();
			}
			catch (Throwable ex) {
				Logger.error(this, "Caught in PacketSender: " + ex, ex);
				System.err.println("Caught in PacketSender: " + ex);
				ex.printStackTrace();
			}
		}
	}

	/**
	 * Send loop. Strategy: - Each peer can tell us when its data needs to be sent by.
	 * This is usually 100ms after it is posted. It could vary by message type.
	 * Acknowledgements also become valid 100ms after being queued. - If any peer's data
	 * is overdue, send the data from the most overdue peer. - If there are peers with
	 * more than a packet's worth of data queued, send the data from the peer with the
	 * oldest data. - If there are peers with overdue ack's, send to the peer whose acks
	 * are oldest.
	 *
	 * It does not attempt to ensure fairness, it attempts to minimise latency. Fairness
	 * is best dealt with at a higher level e.g. requests, although some transfers are not
	 * part of requests, e.g. bulk f2f transfers, so we may need to reconsider this
	 * eventually...
	 */
	private void realRun() {
		long now = System.currentTimeMillis();
		PeerManager pm;
		PeerNode[] nodes;

		pm = this.node.peers;
		nodes = pm.myPeers();

		long nextActionTime = Long.MAX_VALUE;
		long oldTempNow = now;

		final boolean canSendThrottled;

		int MAX_PACKET_SIZE = this.node.darknetCrypto.socket.getMaxPacketSize();
		long count = this.node.outputThrottle.getCount();
		if (count > MAX_PACKET_SIZE) {
			canSendThrottled = true;
		}
		else {
			long canSendAt = this.node.outputThrottle.getNanosPerTick() * (MAX_PACKET_SIZE - count);
			canSendAt = TimeUnit.MILLISECONDS.convert(canSendAt + TimeUnit.MILLISECONDS.toNanos(1) - 1,
					TimeUnit.NANOSECONDS);
			if (logMINOR) {
				Logger.minor(this, "Can send throttled packets in " + canSendAt + "ms");
			}
			nextActionTime = Math.min(nextActionTime, now + canSendAt);
			canSendThrottled = false;
		}

		/*
		 * The earliest time at which a peer needs to send a packet, which is before now.
		 * Throttled if canSendThrottled, otherwise not throttled. Note: we only use it to
		 * sort the full-packed peers by priority, don't rely on it when setting
		 * nextActionTime!
		 */
		long lowestUrgentSendTime = Long.MAX_VALUE;
		/* The peer(s) which lowestUrgentSendTime is referring to */
		ArrayList<PeerNode> urgentSendPeers = null;
		/*
		 * The earliest time at which a peer needs to send a packet, which is after now,
		 * where there is a full packet's worth of data to send. Throttled if
		 * canSendThrottled, otherwise not throttled.
		 */
		long lowestFullPacketSendTime = Long.MAX_VALUE;
		/* The peer(s) which lowestFullPacketSendTime is referring to */
		ArrayList<PeerNode> urgentFullPacketPeers = null;
		/* The earliest time at which a peer needs to send an ack, before now. */
		long lowestAckTime = Long.MAX_VALUE;
		/* The peer(s) which lowestAckTime is referring to */
		ArrayList<PeerNode> ackPeers = null;
		/* The earliest time at which a peer needs to handshake. */
		long lowestHandshakeTime = Long.MAX_VALUE;
		/* The peer(s) which lowestHandshakeTime is referring to */
		ArrayList<PeerNode> handshakePeers = null;

		for (PeerNode pn : nodes) {
			now = System.currentTimeMillis();

			// Basic peer maintenance.

			// For purposes of detecting not having received anything, which indicates a
			// serious connectivity problem, we want to look for *any* packets received,
			// including auth packets.
			this.lastReceivedPacketFromAnyNode = Math.max(pn.lastReceivedPacketTime(),
					this.lastReceivedPacketFromAnyNode);
			pn.maybeOnConnect();
			if (pn.shouldDisconnectAndRemoveNow() && !pn.isDisconnecting()) {
				// Might as well do it properly.
				this.node.peers.disconnectAndRemove(pn, true, true, false);
			}

			if (pn.isConnected()) {

				boolean shouldThrottle = pn.shouldThrottle();

				pn.checkForLostPackets();

				// Is the node dead?
				// It might be disconnected in terms of FNP but trying to reconnect via
				// JFK's, so we need to use the time when we last got a *data* packet.
				if (now - pn.lastReceivedDataPacketTime() > pn.maxTimeBetweenReceivedPackets()) {
					Logger.normal(this, "Disconnecting from " + pn + " - haven't received packets recently");
					// Hopefully this is a transient network glitch, but stuff will have
					// already started to timeout, so lets dump the pending messages.
					pn.disconnected(true, false);
					continue;
				}
				else if (now - pn.lastReceivedAckTime() > pn.maxTimeBetweenReceivedAcks() && !pn.isDisconnecting()) {
					// FIXME better to disconnect immediately??? Or check canSend()???
					Logger.normal(this, "Disconnecting from " + pn + " - haven't received acks recently");
					// Do it properly.
					// There appears to be connectivity from them to us but not from us to
					// them.
					// So it is helpful for them to know that we are disconnecting.
					this.node.peers.disconnect(pn, true, true, false, true, false, TimeUnit.SECONDS.toMillis(5));
					continue;
				}
				else if (pn.isRoutable() && pn.noLongerRoutable()) {
					/*
					 * NOTE: Whereas isRoutable() && noLongerRoutable() are generally
					 * mutually exclusive, this code will only execute because of the
					 * scheduled-runnable in start() which executes
					 * updateVersionRoutablity() on all our peers. We don't disconnect the
					 * peer, but mark it as being incompatible.
					 */
					pn.invalidate(now);
					Logger.normal(this,
							"shouldDisconnectNow has returned true : marking the peer as incompatible: " + pn);
					continue;
				}

				// The peer is connected.

				if (canSendThrottled || !shouldThrottle) {
					// We can send to this peer.
					long sendTime = pn.getNextUrgentTime(now);
					if (sendTime != Long.MAX_VALUE) {
						if (sendTime <= now) {
							// Message is urgent.
							if (sendTime < lowestUrgentSendTime) {
								lowestUrgentSendTime = sendTime;
								if (urgentSendPeers != null) {
									urgentSendPeers.clear();
								}
								else {
									urgentSendPeers = new ArrayList<>();
								}
							}
							if (sendTime <= lowestUrgentSendTime) {
								urgentSendPeers.add(pn);
							}
						}
						else if (pn.fullPacketQueued()) {
							if (sendTime < lowestFullPacketSendTime) {
								lowestFullPacketSendTime = sendTime;
								if (urgentFullPacketPeers != null) {
									urgentFullPacketPeers.clear();
								}
								else {
									urgentFullPacketPeers = new ArrayList<>();
								}
							}
							if (sendTime <= lowestFullPacketSendTime) {
								urgentFullPacketPeers.add(pn);
							}
						}
					}
				}
				else {
					long ackTime = pn.timeSendAcks();
					if (ackTime != Long.MAX_VALUE) {
						if (ackTime <= now) {
							if (ackTime < lowestAckTime) {
								lowestAckTime = ackTime;
								if (ackPeers != null) {
									ackPeers.clear();
								}
								else {
									ackPeers = new ArrayList<>();
								}
							}
							if (ackTime <= lowestAckTime) {
								ackPeers.add(pn);
							}
						}
					}
				}

				if (canSendThrottled || !shouldThrottle) {
					long urgentTime = pn.getNextUrgentTime(now);
					// Should spam the logs, unless there is a deadlock
					if (urgentTime < Long.MAX_VALUE && logMINOR) {
						Logger.minor(this,
								"Next urgent time: " + urgentTime + "(in " + (urgentTime - now) + ") for " + pn);
					}
					nextActionTime = Math.min(nextActionTime, urgentTime);
				}
				else {
					nextActionTime = Math.min(nextActionTime, pn.timeCheckForLostPackets());
				}
			}
			else if (pn.noContactDetails()) {
				// Not connected
				pn.startARKFetcher();
			}

			long handshakeTime = pn.timeSendHandshake(now);
			if (handshakeTime != Long.MAX_VALUE) {
				if (handshakeTime < lowestHandshakeTime) {
					lowestHandshakeTime = handshakeTime;
					if (handshakePeers != null) {
						handshakePeers.clear();
					}
					else {
						handshakePeers = new ArrayList<>();
					}
				}
				if (handshakeTime <= lowestHandshakeTime) {
					handshakePeers.add(pn);
				}
			}

			long tempNow = System.currentTimeMillis();
			if ((tempNow - oldTempNow) > TimeUnit.SECONDS.toMillis(5)) {
				Logger.error(this, "tempNow is more than 5 seconds past oldTempNow (" + (tempNow - oldTempNow)
						+ ") in PacketSender working with " + pn.userToString());
			}
			oldTempNow = tempNow;
		}

		// We may send a packet, send an ack-only packet, or send a handshake.

		PeerNode toSendPacket = null;
		PeerNode toSendAckOnly = null;
		PeerNode toSendHandshake = null;

		long t = Long.MAX_VALUE;

		if (lowestUrgentSendTime <= now) {
			// We need to send a full packet.
			toSendPacket = urgentSendPeers.get(this.localRandom.nextInt(urgentSendPeers.size()));
			t = lowestUrgentSendTime;
		}
		else if (lowestFullPacketSendTime < Long.MAX_VALUE) {
			toSendPacket = urgentFullPacketPeers.get(this.localRandom.nextInt(urgentFullPacketPeers.size()));
			t = lowestFullPacketSendTime;
		}
		else if (lowestAckTime <= now) {
			// We need to send an ack
			toSendAckOnly = ackPeers.get(this.localRandom.nextInt(ackPeers.size()));
			t = lowestAckTime;
		}

		if (lowestHandshakeTime <= now && t > lowestHandshakeTime) {
			toSendHandshake = handshakePeers.get(this.localRandom.nextInt(handshakePeers.size()));
			toSendPacket = null;
			toSendAckOnly = null;
		}

		if (toSendPacket != null) {
			try {
				if (toSendPacket.maybeSendPacket(now, false)) {
					// Round-robin over the loop to update nextActionTime appropriately
					nextActionTime = now;
				}
			}
			catch (BlockedTooLongException ex) {
				Logger.error(this,
						"Waited too long: " + TimeUtil.formatTime(ex.delta) + " to allocate a packet number to send to "
								+ toSendPacket + " : " + ("(new packet format)") + " (version "
								+ toSendPacket.getVersionNumber() + ") - DISCONNECTING!");
				toSendPacket.forceDisconnect();
			}
		}
		else if (toSendAckOnly != null) {
			try {
				if (toSendAckOnly.maybeSendPacket(now, true)) {
					// Round-robin over the loop to update nextActionTime appropriately
					nextActionTime = now;
				}
			}
			catch (BlockedTooLongException ex) {
				Logger.error(this,
						"Waited too long: " + TimeUtil.formatTime(ex.delta) + " to allocate a packet number to send to "
								+ toSendAckOnly + " : " + ("(new packet format)") + " (version "
								+ toSendAckOnly.getVersionNumber() + ") - DISCONNECTING!");
				toSendAckOnly.forceDisconnect();
			}
		}

		/*
		 * Estimating of nextActionTime logic: FullPackets: - A full packet available,
		 * bandwidth available -->> now - A full packet available for non-throttled peer
		 * -->> now - A full packet available, no bandwidth -->> wait till bandwidth
		 * available - No packet -->> don't care, will wake up anyway when one arrives,
		 * goto Nothing UrgentMessages: Only applies when there's enough bandwidth to send
		 * a full packet, Includes any urgent acks - There's an urgent message,
		 * deadline(urgentMessage) > now -->> deadline(urgentMessage) - There's an urgent
		 * message, deadline(urgentMessage) <= now -->> now - There's an urgent message,
		 * but there's not enough bandwidth for a full packet -->> wait till bandwidth
		 * available - There's no urgent message -->> don't care, goto Nothing Nothing:
		 * -->> timeCheckForLostPackets
		 */

		if (toSendHandshake != null) {
			// Send handshake if necessary
			long beforeHandshakeTime = System.currentTimeMillis();
			toSendHandshake.getOutgoingMangler().sendHandshake(toSendHandshake, false);
			long afterHandshakeTime = System.currentTimeMillis();
			if ((afterHandshakeTime - beforeHandshakeTime) > TimeUnit.SECONDS.toMillis(2)) {
				Logger.error(this,
						"afterHandshakeTime is more than 2 seconds past beforeHandshakeTime ("
								+ (afterHandshakeTime - beforeHandshakeTime) + ") in PacketSender working with "
								+ toSendHandshake.userToString());
			}
		}

		// All of these take into account whether the data can be sent already.
		// So we can include them in nextActionTime.
		nextActionTime = Math.min(nextActionTime, lowestUrgentSendTime);
		nextActionTime = Math.min(nextActionTime, lowestFullPacketSendTime);
		nextActionTime = Math.min(nextActionTime, lowestAckTime);
		nextActionTime = Math.min(nextActionTime, lowestHandshakeTime);

		// FIXME: If we send something we will have to go around the loop again.
		// OPTIMISATION: We could track the second best, and check how many are in the
		// array.

		/*
		 * Attempt to connect to old-opennet-peers. Constantly send handshake packets, in
		 * order to get through a NAT. Most JFK(1)'s are less than 300 bytes. 25*300/15 =
		 * avg 500B/sec bandwidth cost. Well worth it to allow us to reconnect more
		 * quickly.
		 */

		OpennetManager om = this.node.getOpennet();
		if (om != null && this.node.getUptime() > TimeUnit.SECONDS.toMillis(30)) {
			OpennetPeerNode[] peers = om.getOldPeers();

			for (OpennetPeerNode pn : peers) {
				long lastConnected = pn.timeLastConnected(now);
				if (lastConnected <= 0) {
					Logger.error(this, "Last connected is zero or negative for old-opennet-peer " + pn);
				}
				// Will be removed by next line.
				if (now - lastConnected > OpennetManager.MAX_TIME_ON_OLD_OPENNET_PEERS) {
					om.purgeOldOpennetPeer(pn);
					if (logMINOR) {
						Logger.minor(this, "Removing old opennet peer (too old): " + pn + " age is "
								+ TimeUtil.formatTime(now - lastConnected));
					}
					continue;
				}
				if (pn.isConnected()) {
					continue; // Race condition??
				}
				if (pn.noContactDetails()) {
					pn.startARKFetcher();
					continue;
				}
				if (pn.shouldSendHandshake()) {
					// Send handshake if necessary
					long beforeHandshakeTime = System.currentTimeMillis();
					pn.getOutgoingMangler().sendHandshake(pn, true);
					long afterHandshakeTime = System.currentTimeMillis();
					if ((afterHandshakeTime - beforeHandshakeTime) > TimeUnit.SECONDS.toMillis(2)) {
						Logger.error(this,
								"afterHandshakeTime is more than 2 seconds past beforeHandshakeTime ("
										+ (afterHandshakeTime - beforeHandshakeTime) + ") in PacketSender working with "
										+ pn.userToString());
					}
				}
			}

		}

		long oldNow = now;

		// Send may have taken some time
		now = System.currentTimeMillis();

		if ((now - oldNow) > TimeUnit.SECONDS.toMillis(10)) {
			Logger.error(this, "now is more than 10 seconds past oldNow (" + (now - oldNow) + ") in PacketSender");
		}

		long sleepTime = nextActionTime - now;

		// MAX_COALESCING_DELAYms maximum sleep time - same as the maximum coalescing
		// delay
		sleepTime = Math.min(sleepTime, MAX_COALESCING_DELAY);

		if (now - this.node.startupTime > TimeUnit.MINUTES.toMillis(5)) {
			if (now - this.lastReceivedPacketFromAnyNode > Node.ALARM_TIME) {
				Logger.error(this, "Have not received any packets from any node in last "
						+ TimeUnit.SECONDS.convert(Node.ALARM_TIME, TimeUnit.MILLISECONDS) + " seconds");
				this.lastReportedNoPackets = now;
			}
		}

		if (sleepTime > 0) {
			// Update logging only when have time to do so
			try {
				if (logMINOR) {
					Logger.minor(this, "Sleeping for " + sleepTime);
				}
				synchronized (this) {
					this.wait(sleepTime);
				}
			}
			catch (InterruptedException ignored) {
				// Ignore, just wake up. Probably we got interrupt()ed
				// because a new packet came in.
			}
		}
		else {
			if (logDEBUG) {
				Logger.debug(this, "Next urgent time is " + (now - nextActionTime) + "ms in the past");
			}
		}
	}

	/** Wake up, and send any queued packets. */
	void wakeUp() {
		// Wake up if needed
		synchronized (this) {
			this.notifyAll();
		}
	}

	protected String l10n(String key, String[] patterns, String[] values) {
		return NodeL10n.getBase().getString("PacketSender." + key, patterns, values);
	}

	protected String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("PacketSender." + key, pattern, value);
	}

}
