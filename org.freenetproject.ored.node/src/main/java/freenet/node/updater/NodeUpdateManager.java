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

package freenet.node.updater;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import freenet.bucket.Bucket;
import freenet.client.request.PriorityClasses;
import freenet.clients.fcp.FCPUserAlert;
import freenet.config.BooleanCallback;
import freenet.config.Config;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.keys.FreenetURI;
import freenet.l10n.NodeL10n;
import freenet.node.Node;
import freenet.node.NodeFile;
import freenet.node.NodeStarter;
import freenet.node.OpennetManager;
import freenet.node.Version;
import freenet.node.event.EventBus;
import freenet.node.event.update.BlownEvent;
import freenet.node.event.update.UpdateManagerStatusUpdatedEvent;
import freenet.node.event.update.UpdateManagerStatusUpdatedEvent.StatusType;
import freenet.node.updater.usk.ManifestUSKUpdateFileFetcher;
import freenet.node.updater.usk.UpdateOverUSKManager;
import freenet.node.useralerts.RevocationKeyFoundUserAlert;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UpdatedVersionAvailableUserAlert;
import freenet.nodelogger.Logger;
import freenet.pluginmanager.OfficialPlugins.OfficialPluginDescription;
import freenet.pluginmanager.PluginInfoWrapper;
import freenet.support.HTMLNode;
import freenet.support.node.NodeInitException;
import org.greenrobot.eventbus.Subscribe;

/**
 * <p>
 * Supervises NodeUpdater's. Enables us to easily update multiple files, change the URI's
 * on the fly, eliminates some messy code in the callbacks etc.
 * </p>
 *
 * <p>
 * Procedure for updating the update key: Create a new key. Create a new build X, the
 * "transition version". This must be UOM-compatible with the previous transition version.
 * UOM-compatible means UOM should work from the older builds. This in turn means that it
 * should support an overlapping set of connection setup negTypes (@link
 * FNPPacketMangler.supportedNegTypes()). Similarly there may be issues with changes to
 * the UOM messages, or to messages in general. Build X is inserted to both the old key
 * and the new key. Build X's SSK URI (on the old auto-update key) will be hard-coded as
 * the new transition version. Then the next build, X+1, can get rid of some of the back
 * compatibility cruft (especially old connection setup types), and will be inserted only
 * to the new key. Secure backups of the new key are required and are documented
 * elsewhere.
 * </p>
 *
 * FIXME: See bug #6009 for some current UOM compatibility issues.
 */
public class NodeUpdateManager {

	public static final String UPDATE_URI = "USK@/manifest/" + Version.buildNumber();

	public static final String REVOCATION_URI = "SSK@/revoked";

	// These are necessary to prevent DoS.
	public static final long MAX_REVOCATION_KEY_LENGTH = 32 * 1024;

	public static final long MAX_REVOCATION_KEY_TEMP_LENGTH = 64 * 1024;

	public static final long MAX_JAVA_INSTALLER_LENGTH = 300 * 1024 * 1024;

	public static final long MAX_WINDOWS_INSTALLER_LENGTH = 300 * 1024 * 1024;

	public static final long MAX_IP_TO_COUNTRY_LENGTH = 24 * 1024 * 1024;

	public static final long MAX_SEEDNODES_LENGTH = 3 * 1024 * 1024;
	static final String TEMP_BLOB_SUFFIX = ".updater.fblob.tmp";
	static final String TEMP_FILE_SUFFIX = ".updater.tmp";

	private static final Object deployLock = new Object();

	private static final long WAIT_FOR_SECOND_FETCH_TO_COMPLETE = TimeUnit.MINUTES.toMillis(4);

	private static final long RECENT_REVOCATION_INTERVAL = TimeUnit.MINUTES.toMillis(2);

	/**
	 * After 5 minutes, deploy the update even if we haven't got 3 DNFs on the revocation
	 * key yet. Reason: we want to be able to deploy UOM updates on nodes with all TOO NEW
	 * or leaf nodes whose peers are overloaded/broken. Note that with UOM, revocation
	 * certs are automatically propagated node to node, so this should be *relatively*
	 * safe. Any better ideas, tell us.
	 */
	private static final long REVOCATION_FETCH_TIMEOUT = TimeUnit.MINUTES.toMillis(5);

	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(NodeUpdateManager.class);
	}

	public final Node node;

	public UpdateOverMandatoryManager uom;

	// TODO
	// public final UpdateOverMandatoryManager uom;

	RevocationChecker revocationChecker;

	private final boolean wasEnabledOnStartup;

	// FIXME make configurable
	private final boolean updateIPToCountry = true;

	// Update alert
	private final UpdatedVersionAvailableUserAlert alert;

	private FreenetURI updateURI;

	private FreenetURI revocationURI;

	private UpdateOverUSKManager updateOverUSKManager;

	private Map<String, PluginJarUpdateFileFetcher> pluginUpdaters;

	private boolean autoDeployPluginsOnRestart;

	/** Is auto-update enabled? */
	private volatile boolean isAutoUpdateAllowed;

	/** Has the user given the go-ahead? */
	private volatile boolean armed;

	/**
	 * Currently deploying an update? Set when we start to deploy an update. Which means
	 * it should not be un-set, except in the case of a severe error causing a valid
	 * update to fail. However, it is un-set in this case, so that we can try again with
	 * another build.
	 */
	private boolean isDeployingUpdate;

	private String revocationMessage;

	private volatile boolean peersSayBlown;

	private volatile boolean hasBeenBlown;

	private boolean updateSeednodes;

	// Revocation alert
	private RevocationKeyFoundUserAlert revocationAlert;

	/** True if update is disabled for this session. */
	private boolean disabledThisSession;

	private boolean disabledNotBlown;

	/**
	 * Best version fetched by fetchers. Best version is the version that is: 1) the
	 * latest 2) not revoked
	 */
	private int bestFetchedVersion;

	/** The blob file for the current version, for UOM */
	protected File currentVersionBlobFile;

	public NodeUpdateManager(Node node, Config config) throws InvalidConfigValueException {
		EventBus.get().register(this);

		this.node = node;
		this.hasBeenBlown = false;
		// Post blown event
		this.postStatusUpdatedEvent();

		this.alert = new UpdatedVersionAvailableUserAlert(this);
		this.alert.isValid(false);

		SubConfig updaterConfig = config.createSubConfig("node.updater");

		updaterConfig.register("enabled", true, 1, false, false, "NodeUpdateManager.enabled",
				"NodeUpdateManager.enabledLong", new UpdaterEnabledCallback());

		this.wasEnabledOnStartup = updaterConfig.getBoolean("enabled");

		// is the auto-update allowed ?
		updaterConfig.register("autoupdate", false, 2, false, true, "NodeUpdateManager.installNewVersions",
				"NodeUpdateManager.installNewVersionsLong", new AutoUpdateAllowedCallback());
		this.isAutoUpdateAllowed = updaterConfig.getBoolean("autoupdate");

		// Set default update URI for new nodes depending on JVM version.
		updaterConfig.register("URI", UPDATE_URI, 3, true, true, "NodeUpdateManager.updateURI",
				"NodeUpdateManager.updateURILong", new UpdateURICallback());

		try {
			this.updateURI = new FreenetURI(updaterConfig.getString("URI"));
		}
		catch (MalformedURLException ex) {
			throw new InvalidConfigValueException(this.l10n("invalidUpdateURI", "error", ex.getLocalizedMessage()));
		}

		this.updateURI = this.updateURI.setSuggestedEdition(Version.buildNumber());
		if (this.updateURI.hasMetaStrings()) {
			throw new InvalidConfigValueException(this.l10n("updateURIMustHaveNoMetaStrings"));
		}
		if (!this.updateURI.isUSK()) {
			throw new InvalidConfigValueException(this.l10n("updateURIMustBeAUSK"));
		}

		updaterConfig.register("revocationURI", REVOCATION_URI, 4, true, false, "NodeUpdateManager.revocationURI",
				"NodeUpdateManager.revocationURILong", new UpdateRevocationURICallback());

		try {
			this.revocationURI = new FreenetURI(updaterConfig.getString("revocationURI"));
		}
		catch (MalformedURLException ex) {
			throw new InvalidConfigValueException(this.l10n("invalidRevocationURI", "error", ex.getLocalizedMessage()));
		}

		updaterConfig.register("updateSeednodes", this.wasEnabledOnStartup, 6, true, true,
				"NodeUpdateManager.updateSeednodes", "NodeUpdateManager.updateSeednodesLong", new BooleanCallback() {

					@Override
					public Boolean get() {
						return NodeUpdateManager.this.updateSeednodes;
					}

					@Override
					public void set(Boolean val) throws NodeNeedRestartException {
						if (NodeUpdateManager.this.updateSeednodes == val) {
							return;
						}
						NodeUpdateManager.this.updateSeednodes = val;
						if (val) {
							throw new NodeNeedRestartException("Must restart to fetch the seednodes");
						}
						else {
							throw new NodeNeedRestartException(
									"Must restart to stop the seednodes fetch if it is still running");
						}
					}

				});

		this.updateSeednodes = updaterConfig.getBoolean("updateSeednodes");

		updaterConfig.finishedInitialization();
	}

	/**
	 * Create a NodeUpdateManager. Called by node constructor.
	 * @param node The node object.
	 * @param config The global config object. Options will be added to a subconfig called
	 * node.updater.
	 * @return A new NodeUpdateManager
	 * @throws InvalidConfigValueException If there is an error in the config.
	 */
	public static NodeUpdateManager maybeCreate(Node node, Config config) throws InvalidConfigValueException {
		return new NodeUpdateManager(node, config);
	}

	/**
	 * Use this lock when deploying an update of any kind which will require us to
	 * restart. If the update succeeds, you should call waitForever() if you don't
	 * immediately exit. There could be rather nasty race conditions if we deploy two
	 * updates at once.
	 * @return A mutex for serialising update deployments.
	 */
	static Object deployLock() {
		return deployLock;
	}

	/**
	 * Does not return. Should be called, inside the deployLock(), if you are in a
	 * situation where you've deployed an update but the exit hasn't actually happened
	 * yet.
	 */
	static void waitForever() {
		// noinspection InfiniteLoopStatement
		while (true) {
			System.err.println("Waiting for shutdown after deployed update...");
			try {
				// noinspection BusyWait
				Thread.sleep(60 * 1000);
			}
			catch (InterruptedException ignored) {
				// Ignore.
			}
		}
	}

	// region EventBus
	// ================================================================================
	@Subscribe
	public void onBlown(BlownEvent event) {
		this.blow(event.message(), event.disabledNotBlown());
	}

	private void postStatusUpdatedEvent() {
		EnumMap<StatusType, Boolean> map = new EnumMap<>(StatusType.class);
		map.put(StatusType.ENABLED, this.isEnabled());
		map.put(StatusType.BLOWN, this.isBlown());
		map.put(StatusType.DEPLOYING, this.isDeployingUpdate());

		EventBus.get().postSticky(new UpdateManagerStatusUpdatedEvent(map));
	}
	// ================================================================================
	// endregion

	public void start() throws InvalidConfigValueException {

		this.node.clientCore.alerts.register(this.alert);

		this.enable(this.wasEnabledOnStartup);

		// Fetch seednodes to the nodeDir.
		if (this.updateSeednodes) {

			SimplePuller seedrefsGetter = new SimplePuller(this.node, this.getSeednodesURI(), NodeFile.Seednodes);
			seedrefsGetter.start(PriorityClasses.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, MAX_SEEDNODES_LENGTH);
		}

		// Fetch IP-to-country files to the runDir.
		if (this.updateIPToCountry) {
			SimplePuller ip4Getter = new SimplePuller(this.node, this.getIPv4ToCountryURI(), NodeFile.IPv4ToCountry);
			ip4Getter.start(PriorityClasses.UPDATE_PRIORITY_CLASS, MAX_IP_TO_COUNTRY_LENGTH);
		}

	}

	/**
	 * Is auto-update enabled?
	 */
	public synchronized boolean isEnabled() {
		return (this.updateOverUSKManager != null);
	}

	/**
	 * Enable or disable auto-update.
	 * @param enable Whether auto-update should be enabled.
	 */
	void enable(boolean enable) {
		UpdateOverUSKManager oldUpdateOverUSKManager = null;
		UpdateOverMandatoryManager oldUOM = null;
		RevocationChecker oldRevocationChecker = null;
		Map<String, PluginJarUpdateFileFetcher> oldPluginUpdaters = null;

		// We need to run the revocation checker even if auto-update is
		// disabled.
		// Two reasons:
		// 1. For the benefit of other nodes, and because even if auto-update is
		// off, it's something the user should probably know about.
		// 2. When the key is blown, we turn off auto-update!!!!
		this.revocationChecker.start(false);

		synchronized (this) {
			boolean enabled = (this.updateOverUSKManager != null);
			if (enabled == enable) {
				return;
			}
			if (!enable) {
				// Kill it
				this.updateOverUSKManager.preKill();
				oldUpdateOverUSKManager = this.updateOverUSKManager;
				this.updateOverUSKManager = null;

				oldUOM = this.uom;
				this.uom = null;

				oldRevocationChecker = this.revocationChecker;
				this.revocationChecker = null;

				oldPluginUpdaters = this.pluginUpdaters;
				this.pluginUpdaters = null;

				this.disabledNotBlown = false;
			}
			else {
				// Start it
				this.updateOverUSKManager = new UpdateOverUSKManager(this);

				// TODO: Start it only when USK failed
				this.uom = new UpdateOverMandatoryManager(this);
				this.uom.removeOldTempFiles();

				this.revocationChecker = new RevocationChecker(this,
						new File(this.node.clientCore.getPersistentTempDir(), "revocation-key.fblob"));

				// TODO
				this.pluginUpdaters = new HashMap<>();
			}
			// Post enabled event
			this.postStatusUpdatedEvent();
		}
		if (!enable) {
			if (oldUpdateOverUSKManager != null) {
				oldUpdateOverUSKManager.kill();
			}
			if (oldRevocationChecker != null) {
				oldRevocationChecker.kill();
			}
			// TODO
			// this.stopPluginUpdaters(oldPluginUpdaters);
		}
		else {
			// FIXME copy it, dodgy locking.
			try {
				// Must be run before starting everything else as it cleans up tempfiles
				// too.
				this.updateOverUSKManager.cleanup();
			}
			catch (Throwable ex) {
				// Don't let it block startup, but be very loud!
				Logger.error(this, "Caught " + ex + " setting up Update Over Mandatory", ex);
				System.err.println("Updater error: " + ex);
				ex.printStackTrace();
			}
			this.updateOverUSKManager.start();
			// TODO
			// this.startPluginUpdaters();
		}
	}

	// region Plugin Updater
	// ================================================================================
	private void startPluginUpdaters() {
		for (OfficialPluginDescription plugin : this.node.getPluginManager().getOfficialPlugins()) {
			this.startPluginUpdater(plugin.name);
		}
	}

	/**
	 * @param plugName The filename for loading/config purposes for an official plugin.
	 * E.g. "Library" (no .jar)
	 */
	public void startPluginUpdater(String plugName) {
		if (logMINOR) {
			Logger.minor(this, "Starting plugin updater for " + plugName);
		}
		OfficialPluginDescription plugin = this.node.getPluginManager().getOfficialPlugin(plugName);
		if (plugin != null) {
			this.startPluginUpdater(plugin);
		}
		else
		// Most likely not an official plugin
		if (logMINOR) {
			Logger.minor(this, "No such plugin " + plugName + " in startPluginUpdater()");
		}
	}

	void startPluginUpdater(OfficialPluginDescription plugin) {
		String name = plugin.name;
		// @see https://emu.freenetproject.org/pipermail/devl/2015-November/038581.html
		long minVer = (plugin.essential ? plugin.minimumVersion : plugin.recommendedVersion);
		// But it might already be past that ...
		PluginInfoWrapper info = this.node.pluginManager.getPluginInfo(name);
		if (info == null) {
			if (!(this.node.pluginManager.isPluginLoadedOrLoadingOrWantLoad(name))) {
				if (logMINOR) {
					Logger.minor(this, "Plugin not loaded");
				}
				return;
			}
		}
		if (info != null) {
			minVer = Math.max(minVer, info.getPluginLongVersion());
		}
		FreenetURI uri = this.updateURI.setDocName(name).setSuggestedEdition(minVer);
		PluginJarUpdateFileFetcher updater = new PluginJarUpdateFileFetcher(this, uri, (int) minVer, -1,
				(plugin.essential ? (int) minVer : Integer.MAX_VALUE), name + "-", name, this.node.pluginManager,
				this.autoDeployPluginsOnRestart);
		synchronized (this) {
			if (this.pluginUpdaters == null) {
				if (logMINOR) {
					Logger.minor(this, "Updating not enabled");
				}
				return; // Not enabled
			}
			if (this.pluginUpdaters.containsKey(name)) {
				if (logMINOR) {
					Logger.minor(this, "Already in updaters list");
				}
				return; // Already started
			}
			this.pluginUpdaters.put(name, updater);
		}
		updater.start();
		System.out.println("Started plugin update fetcher for " + name);
	}

	public void stopPluginUpdater(String plugName) {
		OfficialPluginDescription plugin = this.node.getPluginManager().getOfficialPlugin(plugName);
		if (plugin == null) {
			return; // Not an official plugin
		}
		PluginJarUpdateFileFetcher updater;
		synchronized (this) {
			if (this.pluginUpdaters == null) {
				if (logMINOR) {
					Logger.minor(this, "Updating not enabled");
				}
				return; // Not enabled
			}
			updater = this.pluginUpdaters.remove(plugName);
		}
		if (updater != null) {
			updater.kill();
		}
	}

	private void stopPluginUpdaters(Map<String, PluginJarUpdateFileFetcher> oldPluginUpdaters) {
		for (PluginJarUpdateFileFetcher u : oldPluginUpdaters.values()) {
			u.kill();
		}
	}

	private void deployPluginUpdates() {
		PluginJarUpdateFileFetcher[] updaters = null;
		synchronized (this) {
			if (this.pluginUpdaters != null) {
				updaters = this.pluginUpdaters.values().toArray(new PluginJarUpdateFileFetcher[0]);
			}
		}
		boolean restartRevocationFetcher = false;
		if (updaters != null) {
			for (PluginJarUpdateFileFetcher u : updaters) {
				if (u.onNoRevocation()) {
					restartRevocationFetcher = true;
				}
			}
		}
		if (restartRevocationFetcher) {
			this.revocationChecker.start(true, true);
		}
	}
	// ================================================================================
	// endregion

	// region Getter/Setter for all kinds of URIs
	// ================================================================================
	/**
	 * Get the URI for update manifest.
	 */
	public synchronized FreenetURI getUpdateURI() {
		return this.updateURI;
	}

	/**
	 * Set the URI update manifest file should be updated from.
	 * @param uri The URI to set.
	 */
	public void setUpdateURI(FreenetURI uri) {
		// FIXME plugins!!
		UpdateOverUSKManager updateOverUSKManager;
		Map<String, PluginJarUpdateFileFetcher> oldPluginUpdaters;
		synchronized (this) {
			if (this.updateURI.equals(uri)) {
				return;
			}
			this.updateURI = uri;
			this.updateURI = this.updateURI.setSuggestedEdition(Version.buildNumber());
			updateOverUSKManager = this.updateOverUSKManager;
			oldPluginUpdaters = this.pluginUpdaters;
			this.pluginUpdaters = new HashMap<>();
			if (updateOverUSKManager == null) {
				return;
			}
		}
		updateOverUSKManager.onChangeURI(uri);
		// TODO
		// this.stopPluginUpdaters(oldPluginUpdaters);
		// this.startPluginUpdaters();
	}

	/**
	 * @return URI for the user-facing changelog.
	 */
	public synchronized FreenetURI getChangelogURI() {
		return this.updateURI.setDocName("changelog");
	}

	public synchronized FreenetURI getDeveloperChangelogURI() {
		return this.updateURI.setDocName("fullchangelog");
	}

	public FreenetURI getSeednodesURI() {
		return this.updateURI.sskForUSK().setDocName("seednodes-" + Version.buildNumber());
	}

	public FreenetURI getIPv4ToCountryURI() {
		return this.updateURI.sskForUSK().setDocName("iptocountryv4-" + Version.buildNumber());
	}

	/**
	 * @return The revocation URI.
	 */
	public synchronized FreenetURI getRevocationURI() {
		return this.revocationURI;
	}

	/**
	 * Set the revocation URI.
	 * @param uri The new revocation URI.
	 */
	public void setRevocationURI(FreenetURI uri) {
		synchronized (this) {
			if (this.revocationURI.equals(uri)) {
				return;
			}
			this.revocationURI = uri;
		}
		this.revocationChecker.onChangeRevocationURI();
	}
	// ================================================================================
	// endregion

	/**
	 * Add links to the changelog for the given version to the given node.
	 * @param version USK edition to point to
	 * @param node to add links to
	 */
	public synchronized void addChangelogLinks(long version, HTMLNode node) {
		String changelogUri = this.getChangelogURI().setSuggestedEdition(version).sskForUSK().toASCIIString();
		String developerDetailsUri = this.getDeveloperChangelogURI().setSuggestedEdition(version).sskForUSK()
				.toASCIIString();
		node.addChild("a", "href", '/' + changelogUri + "?type=text/plain",
				NodeL10n.getBase().getString("UpdatedVersionAvailableUserAlert.changelog"));
		node.addChild("br");
		node.addChild("a", "href", '/' + developerDetailsUri + "?type=text/plain",
				NodeL10n.getBase().getString("UpdatedVersionAvailableUserAlert.devchangelog"));
	}

	/**
	 * @return Is auto-update currently enabled?
	 */
	public boolean isAutoUpdateAllowed() {
		return this.isAutoUpdateAllowed;
	}

	/**
	 * Enable or disable auto-update.
	 * @param val If true, enable auto-update (and immediately update if an update is
	 * ready). If false, disable it.
	 */
	public void setAutoUpdateAllowed(boolean val) {
		synchronized (this) {
			if (val == this.isAutoUpdateAllowed) {
				return;
			}
			this.isAutoUpdateAllowed = val;
			if (val) {
				if (!this.isReadyToDeployUpdate(false)) {
					return;
				}
			}
			else {
				return;
			}
		}
		this.deployOffThread(0, false);
	}

	/**
	 * Does the updater have an update ready to deploy? May be called synchronized(this).
	 * @param ignoreRevocation If true, return whether we will deploy when the revocation
	 * check finishes. If false, return whether we can deploy now, and if not, deploy
	 * after a delay with deployOffThread().
	 */
	private boolean isReadyToDeployUpdate(boolean ignoreRevocation) {
		long now = System.currentTimeMillis();
		int waitForNextFile = -1;
		synchronized (this) {
			if (this.updateOverUSKManager == null) {
				// updater is not enabled
				return false;
			}
			if (!this.hasNewFile) {
				// no new file fetched
				return false;
			}
			if (this.hasBeenBlown) {
				// this version has been blown
				return false;
			}
			if (this.peersSayBlown) {
				if (logMINOR) {
					Logger.minor(this, "Not deploying, peers say blown");
				}
				return false;
			}
			// Don't immediately deploy if still fetching
			if (this.updateOverUSKManager.getStartedFetchingNextFile() > 0) {
				waitForNextFile = (int) (this.updateOverUSKManager.getStartedFetchingNextFile()
						+ WAIT_FOR_SECOND_FETCH_TO_COMPLETE - now);
				if (waitForNextFile > 0) {
					if (logMINOR) {
						Logger.minor(this, "Not ready: Still fetching");
					}
					// Wait for running fetch to complete
				}
			}

			// Check revocation.
			if (waitForNextFile <= 0) {
				if (!ignoreRevocation) {
					if (now - this.revocationChecker.lastSucceeded() < RECENT_REVOCATION_INTERVAL) {
						if (logMINOR) {
							Logger.minor(this, "Ready to deploy (revocation checker succeeded recently)");
						}
						return true;
					}
					if (this.updateOverUSKManager.getGotFileTime() > 0
							&& now - this.updateOverUSKManager.getGotFileTime() >= REVOCATION_FETCH_TIMEOUT) {
						if (logMINOR) {
							Logger.minor(this, "Ready to deploy (got jar before timeout)");
						}
						return true;
					}
				}
			}
		}
		if (logMINOR) {
			Logger.minor(this, "Still here in isReadyToDeployUpdate");
		}
		// Apparently everything is ready except the revocation fetch. So start
		// it.
		this.revocationChecker.start(true);
		if (ignoreRevocation) {
			if (logMINOR) {
				Logger.minor(this, "Returning true because of ignoreRevocation");
			}
			return true;
		}
		long waitTime = Math.max(REVOCATION_FETCH_TIMEOUT, waitForNextFile);
		if (logMINOR) {
			Logger.minor(this, "Will deploy in " + waitTime + "ms");
		}
		this.deployOffThread(waitTime, false);
		return false;
	}

	/** Check whether there is an update to deploy. If there is, do it. */
	private void deployUpdate() {
		boolean started = false;
		boolean success = false;
		try {
			synchronized (this) {
				if (this.disabledThisSession) {
					String msg = "Not deploying update because disabled for this session (bad java version??)";
					Logger.error(this, msg);
					System.err.println(msg);
					return;
				}
				if (this.hasBeenBlown) {
					String msg = "Trying to update but key has been blown! Not updating, message was "
							+ this.revocationMessage;
					Logger.error(this, msg);
					System.err.println(msg);
					return;
				}
				if (this.peersSayBlown) {
					String msg = "Trying to update but at least one peer says the key has been blown! Not updating.";
					Logger.error(this, msg);
					System.err.println(msg);
					return;

				}
				if (!this.isEnabled()) {
					if (logMINOR) {
						Logger.minor(this, "Not enabled");
					}
					return;
				}
				if (!(this.isAutoUpdateAllowed || this.armed)) {
					if (logMINOR) {
						Logger.minor(this, "Not armed");
					}
					return;
				}
				if (!this.isReadyToDeployUpdate(false)) {
					if (logMINOR) {
						Logger.minor(this, "Not ready to deploy update");
					}
					return;
				}
				if (this.isDeployingUpdate) {
					if (logMINOR) {
						Logger.minor(this, "Already deploying update");
					}
					return;
				}
				started = true;
				this.isDeployingUpdate = true;
				// Post deploying event
				this.postStatusUpdatedEvent();
			}

			synchronized (deployLock()) {
				success = this.innerDeployUpdate();
				if (success) {
					waitForever();
				}
			}
			// isDeployingUpdate remains true as we are about to restart.
		}
		catch (Throwable ex) {
			Logger.error(this, "DEPLOYING UPDATE FAILED: " + ex, ex);
			System.err.println("UPDATE FAILED: CAUGHT " + ex);
			System.err.println(
					"YOUR NODE DID NOT UPDATE. THIS IS PROBABLY A BUG OR SERIOUS PROBLEM SUCH AS OUT OF MEMORY.");
			System.err.println("Cause of the problem: " + ex);
			ex.printStackTrace();
			this.failUpdate(ex.getMessage());
			String error = this.l10n("updateFailedInternalError", "reason", ex.getMessage());
			this.node.clientCore.alerts
					.register(new SimpleUserAlert(false, error, error, error, FCPUserAlert.CRITICAL_ERROR));
		}
		finally {
			if (started && !success) {
				Bucket toFree = null;
				synchronized (this) {
					this.isDeployingUpdate = false;
					// Post deploying event
					this.postStatusUpdatedEvent();

					if (this.updateOverUSKManager.getMaybeNextFileVersion() > this.updateOverUSKManager
							.getFetchedFileVersion()) {
						// A newer version has been fetched in the meantime.
						toFree = this.updateOverUSKManager.getFetchedFileData();
						this.updateOverUSKManager
								.setFetchedFileVersion(this.updateOverUSKManager.getMaybeNextFileVersion());
						this.updateOverUSKManager.setFetchedFileData(this.updateOverUSKManager.getMaybeNextFileData());
						this.updateOverUSKManager.setMaybeNextFileVersion(-1);
						this.updateOverUSKManager.setMaybeNextFileData(null);
					}
				}
				if (toFree != null) {
					toFree.free();
				}
			}
		}
	}

	/**
	 * Deploy the update. Inner method. Doesn't check anything, just does it.
	 */
	private boolean innerDeployUpdate() {
		// TODO
		return true;
	}

	/** Restart the node. Does not return. */
	private void restart(UpdateDeployContext ctx) {
		if (logMINOR) {
			Logger.minor(this, "Restarting...");
		}
		this.node.getNodeStarter().restart();
		try {
			Thread.sleep(TimeUnit.MINUTES.toMillis(5));
		}
		catch (InterruptedException ignored) {
			// Break
		} // in case it's still restarting
		System.err.println("Failed to restart. Exiting, please restart the node.");
		System.exit(NodeInitException.EXIT_RESTART_FAILED);
	}

	private void failUpdate(String reason) {
		Logger.error(this, "Update failed: " + reason);
		System.err.println("Update failed: " + reason);
		this.killUpdateAlerts();
		this.node.clientCore.alerts.register(
				new SimpleUserAlert(true, this.l10n("updateFailedTitle"), this.l10n("updateFailed", "reason", reason),
						this.l10n("updateFailedShort", "reason", reason), FCPUserAlert.CRITICAL_ERROR));
	}

	private String l10n(String key) {
		return NodeL10n.getBase().getString("NodeUpdateManager." + key);
	}

	private String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("NodeUpdateManager." + key, pattern, value);
	}

	/**
	 * @param msg
	 * @param disabledNotBlown If true, the auto-updating system is broken, and should be
	 * disabled, but the problem *could* be local e.g. out of disk space and a node sends
	 * us a revocation certificate.
	 */
	public void blow(String msg, boolean disabledNotBlown) {
		UpdateOverUSKManager updateOverUSKManager;
		synchronized (this) {
			if (this.hasBeenBlown) {
				if (this.disabledNotBlown && !disabledNotBlown) {
					disabledNotBlown = true;
				}
				Logger.error(this, "The key has ALREADY been marked as blown! Message was " + this.revocationMessage
						+ " new message " + msg);
				return;
			}
			else {
				this.revocationMessage = msg;
				this.hasBeenBlown = true;
				// Post blown event
				this.postStatusUpdatedEvent();

				this.disabledNotBlown = disabledNotBlown;
				// We must get to the lower part, and show the user the message
				try {
					if (disabledNotBlown) {
						System.err.println("THE AUTO-UPDATING SYSTEM HAS BEEN DISABLED!");
						System.err.println(
								"We do not know whether this is a local problem or the auto-update system has in fact been compromised. What we do know:\n"
										+ this.revocationMessage);
					}
					else {
						System.err.println("THE AUTO-UPDATING SYSTEM HAS BEEN COMPROMISED!");
						System.err.println("The auto-updating system revocation key has been inserted. It says: "
								+ this.revocationMessage);
					}
				}
				catch (Throwable ex) {
					try {
						Logger.error(this, "Caught " + ex, ex);
					}
					catch (Throwable ignored) {
					}
				}
			}
			updateOverUSKManager = this.updateOverUSKManager;
			if (updateOverUSKManager != null) {
				updateOverUSKManager.preKill();
			}
			this.updateOverUSKManager = null;
		}
		if (updateOverUSKManager != null) {
			updateOverUSKManager.kill();
		}
		if (this.revocationAlert == null) {
			this.revocationAlert = new RevocationKeyFoundUserAlert(msg, disabledNotBlown);
			this.node.clientCore.alerts.register(this.revocationAlert);
			// we don't need to advertize updates : we are not going to do them
			this.killUpdateAlerts();
		}
		// TODO
		// this.uom.killAlert();
		// this.broadcastUOMAnnounceManifest();
	}

	/**
	 * Kill all UserAlerts asking the user whether he wants to update.
	 */
	private void killUpdateAlerts() {
		this.node.clientCore.alerts.unregister(this.alert);
	}

	/** Called when the RevocationChecker has got 3 DNFs on the revocation key */
	public void noRevocationFound() {
		this.deployUpdate(); // May have been waiting for the revocation.
		this.deployPluginUpdates();
		// If we're still here, we didn't update.
		// TODO
		// this.broadcastUOMAnnounceManifest();
		this.node.ticker.queueTimedJob(() -> NodeUpdateManager.this.revocationChecker.start(false),
				this.node.random.nextInt((int) TimeUnit.DAYS.toMillis(1)));
	}

	public void arm() {
		this.armed = true;
		this.postStatusUpdatedEvent();

		OpennetManager om = this.node.getOpennet();
		if (om != null) {
			if (om.waitingForUpdater()) {
				synchronized (this) {
					// Reannounce and count it from now.
					if (this.updateOverUSKManager.getGotFileTime() > 0) {
						this.updateOverUSKManager.setGotFileTime(System.currentTimeMillis());
					}
				}
				om.reannounce();
			}
		}
		this.deployOffThread(0, false);
	}

	void deployOffThread(long delay, final boolean announce) {
		this.node.ticker.queueTimedJob(new Runnable() {
			@Override
			public void run() {
				if (announce) {
					NodeUpdateManager.this.maybeBroadcastUOMAnnounceManifest();
				}
				if (logMINOR) {
					Logger.minor(this, "Running deployOffThread");
				}
				NodeUpdateManager.this.deployUpdate();
				if (logMINOR) {
					Logger.minor(this, "Run deployOffThread");
				}
			}
		}, delay);
	}

	protected void maybeBroadcastUOMAnnounceManifest() {
		if (logMINOR) {
			Logger.minor(this, "Maybe broadcast UOM announces new");
		}
		synchronized (NodeUpdateManager.this) {
			if (this.hasBeenBlown) {
				return;
			}
			if (this.peersSayBlown) {
				return;
			}
		}
		if (logMINOR) {
			Logger.minor(this, "Maybe broadcast UOM announces new (2)");
		}
		// If the node has no peers, noRevocationFound will never be called.
		// TODO
		// this.broadcastUOMAnnounceManifest();
	}

	/**
	 * Has the private key been revoked?
	 */
	public boolean isBlown() {
		return this.hasBeenBlown;
	}

	public boolean isHasNewFile() {
		return this.hasNewFile;
	}

	/**
	 * What version has been fetched?
	 *
	 * This includes manifest file fetched via UOM, because the UOM code feeds its results
	 * through the mainUpdater.
	 */
	public int getNewVersion() {
		if (this.updateOverUSKManager == null) {
			return -1;
		}
		return this.updateOverUSKManager.getFetchedVersion();
	}

	public boolean fetchingNewMainJar() {
		return (this.updateOverUSKManager != null && this.updateOverUSKManager.isFetching());
	}

	public int fetchingNewMainJarVersion() {
		if (this.updateOverUSKManager == null) {
			return -1;
		}
		return this.updateOverUSKManager.fetchingVersion();
	}

	public boolean inFinalCheck() {
		return this.isReadyToDeployUpdate(true) && !this.isReadyToDeployUpdate(false);
	}

	public int getRevocationDNFCounter() {
		return this.revocationChecker.getRevocationDNFCounter();
	}

	/**
	 * What version is the node currently running?
	 */
	public int getMainVersion() {
		return Version.buildNumber();
	}

	public int getExtVersion() {
		return NodeStarter.extBuildNumber;
	}

	public boolean isArmed() {
		return this.armed || this.isAutoUpdateAllowed;
	}

	/**
	 * Is the node able to update as soon as the revocation fetch has been completed?
	 */
	public boolean canUpdateNow() {
		return this.isReadyToDeployUpdate(true);
	}

	/**
	 * Is the node able to update *immediately*? (i.e. not only is it ready in every other
	 * sense, but also a revocation fetch has completed recently enough not to need
	 * another one)
	 */
	public boolean canUpdateImmediately() {
		return this.isReadyToDeployUpdate(false);
	}

	/**
	 * Called when a peer indicates in its UOMAnnounce that it has fetched the revocation
	 * key (or failed to do so in a way suggesting that somebody knows the key).
	 */
	void peerClaimsKeyBlown() {
		// Note that UpdateOverMandatoryManager manages the list of peers who
		// think this.
		// All we have to do is cancel the update.

		this.peersSayBlown = true;
	}

	/** Called inside locks, so don't lock anything */
	public void notPeerClaimsKeyBlown() {
		this.peersSayBlown = false;
		this.node.executor.execute(NodeUpdateManager.this::deployUpdate, "Check for updates");
		this.node.getTicker().queueTimedJob(NodeUpdateManager.this::maybeBroadcastUOMAnnounceManifest,
				REVOCATION_FETCH_TIMEOUT);
	}

	boolean peersSayBlown() {
		return this.peersSayBlown;
	}

	// TODO
	// public File getMainBlob(int version) {
	// AbstractUSKUpdateFileFetcher updater;
	// synchronized (this) {
	// if (this.hasBeenBlown) {
	// return null;
	// }
	// updater = this.manifestUSKUpdater;
	// if (updater == null) {
	// return null;
	// }
	// }
	// return updater.getBlobFile(version);
	// }

	public synchronized long timeRemainingOnCheck() {
		long now = System.currentTimeMillis();
		return Math.max(0, REVOCATION_FETCH_TIMEOUT - (now - this.updateOverUSKManager.getGotFileTime()));
	}

	public void disableThisSession() {
		this.disabledThisSession = true;
	}

	// TODO
	// public void disconnected(PeerNode pn) {
	// this.uom.disconnected(pn);
	// }

	public void deployPlugin(String fn) throws IOException {
		PluginJarUpdateFileFetcher updater;
		synchronized (this) {
			if (this.hasBeenBlown) {
				Logger.error(this, "Not deploying update for " + fn + " because revocation key has been blown!");
				return;
			}
			updater = this.pluginUpdaters.get(fn);
		}
		updater.writeJar();
	}

	public void deployPluginWhenReady(String fn) {
		PluginJarUpdateFileFetcher updater;
		synchronized (this) {
			if (this.hasBeenBlown) {
				Logger.error(this, "Not deploying update for " + fn + " because revocation key has been blown!");
				return;
			}
			updater = this.pluginUpdaters.get(fn);
		}
		boolean wasRunning = this.revocationChecker.start(true, true);
		updater.arm(wasRunning);
	}

	public boolean dontAllowUOM() {
		if (this.node.isOpennetEnabled() && this.node.wantAnonAuth(true)) {
			// We are a seednode.
			// Normally this means we won't send UOM.
			// However, if something breaks severely, we need an escape route.
			return this.node.getUptime() <= TimeUnit.MINUTES.toMillis(5)
					|| this.node.peers.countCompatibleRealPeers() != 0;
		}
		return false;
	}

	// TODO
	// public boolean fetchingFromUOM() {
	// return this.uom.isFetchingMain();
	// }

	/** Show the progress of individual dependencies if possible */
	public void renderProgress(HTMLNode alertNode) {
		// TODO
		// MainJarUpdater m;
		// synchronized (this) {
		// if(this.fetchedMainJarData == null) return;
		// m = mainUpdater;
		// if(m == null) return;
		// }
		// m.renderProperties(alertNode);
	}

	public boolean brokenDependencies() {
		return false;
		// TODO
		// MainJarUpdater m;
		// synchronized (this) {
		// m = mainUpdater;
		// if(m == null) return false;
		// }
		// return m.brokenDependencies();
	}

	// TODO
	// public void onStartFetchingUOM() {
	// ManifestUSKUpdateFileFetcher m;
	// synchronized (this) {
	// m = this.manifestUSKUpdater;
	// if (m == null) {
	// return;
	// }
	// }
	// m.onStartFetchingUOM();
	// }

	public ManifestUSKUpdateFileFetcher getManifestUpdater() {
		return this.updateOverUSKManager;
	}

	public boolean isDeployingUpdate() {
		return this.isDeployingUpdate;
	}

	// Config callbacks

	// TODO
	// private void parseManifest(int fetched, FetchResult result) {
	// try (var is = result.asBucket().getInputStream()) {
	// var fieldSet = new SimpleFieldSet(new BufferedReader(new InputStreamReader(is,
	// StandardCharsets.UTF_8)),
	// false, true);
	//
	// var count = 0;
	// var iter = fieldSet.keyIterator();
	// while (iter.hasNext()) {
	// count++;
	// iter.next();
	// }
	//
	// fieldSet.keyIterator().forEachRemaining((key) -> {
	// // TODO: UoM
	// try {
	// String filenameForMe = null;
	// switch (Platform.getOSType()) {
	// case Platform.WINDOWS:
	// filenameForMe = "windows." + Platform.ARCH + ".exe";
	// break;
	// case Platform.LINUX:
	// // TODO
	// break;
	// case Platform.MAC:
	// // TODO
	// break;
	// default:
	// Logger.error(this, "Unsupported OS");
	// }
	// final String finalFilenameForMe = filenameForMe;
	// var puller = new SimplePuller(this.node, new FreenetURI(fieldSet.get(key)), fetched
	// + "/" + key,
	// this.node.runDir(), (pullerResult, state) -> {
	// if (key.equals(finalFilenameForMe)) {
	// // TODO: make isReadyToDeployUpdate return true
	// this.hasNewPackageFile = true;
	// }
	// }, (ex, state) -> {
	// if (key.equals(finalFilenameForMe)) {
	// // We just care about this platform. Ignore errors of
	// // fetching other packages
	// if (ex instanceof FetchException fetchException) {
	// ManifestUSKUpdateFileFetcher.this.onFailure(fetchException, state);
	// }
	// else {
	// var fe = new FetchException(FetchException.FetchExceptionMode.INTERNAL_ERROR);
	// ManifestUSKUpdateFileFetcher.this.onFailure(fe, state);
	// }
	// }
	// });
	// puller.start(PriorityClasses.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
	// MAX_PACKAGE_LENGTH);
	// }
	// catch (MalformedURLException ex) {
	// throw new RuntimeException(ex);
	// }
	// });
	//
	// }
	// catch (IOException ex) {
	// Logger.error(this, "IOException trying to read manifest on update");
	// }
	// }

	private static class UpdateFailedException extends Exception {

		UpdateFailedException(String message) {
			super(message);
		}

	}

	class UpdaterEnabledCallback extends BooleanCallback {

		@Override
		public Boolean get() {
			if (NodeUpdateManager.this.isEnabled()) {
				return true;
			}
			synchronized (NodeUpdateManager.this) {
				if (NodeUpdateManager.this.disabledNotBlown) {
					return true;
				}
			}
			return false;
		}

		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			NodeUpdateManager.this.enable(val);
		}

	}

	class AutoUpdateAllowedCallback extends BooleanCallback {

		@Override
		public Boolean get() {
			return NodeUpdateManager.this.isAutoUpdateAllowed();
		}

		@Override
		public void set(Boolean val) throws InvalidConfigValueException {
			NodeUpdateManager.this.setAutoUpdateAllowed(val);
		}

	}

	class UpdateURICallback extends StringCallback {

		@Override
		public String get() {
			return NodeUpdateManager.this.getUpdateURI().toString(false, false);
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			FreenetURI uri;
			try {
				uri = new FreenetURI(val);
			}
			catch (MalformedURLException ex) {
				throw new InvalidConfigValueException(
						NodeUpdateManager.this.l10n("invalidUpdateURI", "error", ex.getLocalizedMessage()));
			}
			if (uri.hasMetaStrings()) {
				throw new InvalidConfigValueException(NodeUpdateManager.this.l10n("updateURIMustHaveNoMetaStrings"));
			}
			if (!uri.isUSK()) {
				throw new InvalidConfigValueException(NodeUpdateManager.this.l10n("updateURIMustBeAUSK"));
			}
			NodeUpdateManager.this.setUpdateURI(uri);
		}

	}

	public class UpdateRevocationURICallback extends StringCallback {

		@Override
		public String get() {
			return NodeUpdateManager.this.getRevocationURI().toString(false, false);
		}

		@Override
		public void set(String val) throws InvalidConfigValueException {
			FreenetURI uri;
			try {
				uri = new FreenetURI(val);
			}
			catch (MalformedURLException ex) {
				throw new InvalidConfigValueException(
						NodeUpdateManager.this.l10n("invalidRevocationURI", "error", ex.getLocalizedMessage()));
			}
			NodeUpdateManager.this.setRevocationURI(uri);
		}

	}

}
