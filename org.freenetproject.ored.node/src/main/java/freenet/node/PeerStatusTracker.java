package freenet.node;

import java.util.HashMap;
import java.util.List;

import freenet.nodelogger.Logger;
import freenet.support.WeakHashSet;

/** Track a collection of PeerNode's for each status. */
class PeerStatusTracker<K extends Object> {

	private static volatile boolean logMINOR;
	static {
		Logger.registerClass(PeerManager.class);
	}

	/**
	 * PeerNode statuses, by status. WARNING: LOCK THIS LAST. Must NOT call PeerNode
	 * inside this lock.
	 */
	private final HashMap<K, WeakHashSet<PeerNode>> statuses;

	PeerStatusTracker() {
		statuses = new HashMap<K, WeakHashSet<PeerNode>>();
	}

	public synchronized void addStatus(K peerNodeStatus, PeerNode peerNode, boolean noLog) {
		WeakHashSet<PeerNode> statusSet = statuses.get(peerNodeStatus);
		if (statusSet != null) {
			if (statusSet.contains(peerNode)) {
				if (!noLog)
					Logger.error(this, "addPeerNodeStatus(): node already in peerNodeStatuses: " + peerNode + " status "
							+ peerNodeStatus, new Exception("debug"));
				return;
			}
			statuses.remove(peerNodeStatus);
		}
		else
			statusSet = new WeakHashSet<PeerNode>();
		if (logMINOR)
			Logger.minor(this, "addPeerNodeStatus(): adding PeerNode for '" + peerNode.getIdentityString()
					+ "' with status '" + peerNodeStatus + "'");
		statusSet.add(peerNode);
		statuses.put(peerNodeStatus, statusSet);
	}

	public synchronized int statusSize(K pnStatus) {
		WeakHashSet<PeerNode> statusSet = statuses.get(pnStatus);
		if (statusSet != null)
			return statusSet.size();
		else
			return 0;
	}

	public synchronized void removeStatus(K peerNodeStatus, PeerNode peerNode, boolean noLog) {
		WeakHashSet<PeerNode> statusSet = statuses.get(peerNodeStatus);
		if (statusSet != null) {
			if (!statusSet.remove(peerNode)) {
				if (!noLog)
					Logger.error(this,
							"removePeerNodeStatus(): identity '" + peerNode.getIdentityString() + " for "
									+ peerNode.shortToString() + "' not in peerNodeStatuses with status '"
									+ peerNodeStatus + "'",
							new Exception("debug"));
				return;
			}
			if (statusSet.isEmpty())
				statuses.remove(peerNodeStatus);
		}
		if (logMINOR)
			Logger.minor(this, "removePeerNodeStatus(): removing PeerNode for '" + peerNode.getIdentityString()
					+ "' with status '" + peerNodeStatus + "'");
	}

	public synchronized void changePeerNodeStatus(PeerNode peerNode, K oldPeerNodeStatus, K peerNodeStatus,
			boolean noLog) {
		if (logMINOR)
			Logger.minor(this,
					"Peer status change: " + oldPeerNodeStatus + " -> " + peerNodeStatus + " on " + peerNode);
		removeStatus(oldPeerNodeStatus, peerNode, noLog);
		addStatus(peerNodeStatus, peerNode, noLog);
	}

	public synchronized void addStatusList(List<K> list) {
		list.addAll(statuses.keySet());
	}

}
