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

package freenet.client.async;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import freenet.bucket.NullBucket;
import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.NullClientCallback;
import freenet.client.request.PriorityClasses;
import freenet.client.request.RequestClient;
import freenet.client.request.RequestClientBuilder;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.support.Executor;
import freenet.support.LRUMap;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.Toadlet;

/**
 * Tracks the latest version of every known USK. Also does auto-updates.
 *
 * Note that this is a transient class. It is not stored in the database. All fetchers and
 * subscriptions are likewise transient.
 *
 * Plugin authors: Don't construct it yourself, get it from ClientContext from
 * NodeClientCore.
 */
public class USKManager {

	private static volatile boolean logDEBUG;

	private static volatile boolean logMINOR;

	static RequestClient rcRT = new RequestClientBuilder().realTime().build();

	static RequestClient rcBulk = new RequestClientBuilder().build();

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {

			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
				logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
			}
		});
	}

	/** Latest version successfully fetched by blanked-edition-number USK */
	final Map<USK, Long> latestKnownGoodByClearUSK;

	/** Latest SSK slot known to be by the author by blanked-edition-number USK */
	final Map<USK, Long> latestSlotByClearUSK;

	/** Subscribers by clear USK */
	final Map<USK, USKCallback[]> subscribersByClearUSK;

	/**
	 * Backgrounded USKFetchers by USK. These have pollForever=true and are only created
	 * when subscribe(,true) is called.
	 */
	final Map<USK, USKFetcher> backgroundFetchersByClearUSK;

	/**
	 * Temporary fetchers, started when a USK (with a positive edition number) is fetched.
	 * These have pollForever=false. Keyed by the clear USK, i.e. one per USK, not one per
	 * {USK, start edition}, unlike fetchersByUSK.
	 */
	final LRUMap<USK, USKFetcher> temporaryBackgroundFetchersLRU;

	/**
	 * Temporary fetchers where we have been asked to prefetch content. We track the time
	 * we last had a new last-slot, so that if there is no new last-slot found in 60
	 * seconds, we start prefetching. We delete the entry when the fetcher finishes. FIXME
	 * this should be TreeMap-based to prevent hash collision DoS'es. But we also need it
	 * to be weak ... how to implement?
	 */
	final WeakHashMap<USK, Long> temporaryBackgroundFetchersPrefetch;

	final FetchContext backgroundFetchContext;

	final FetchContext backgroundFetchContextIgnoreDBR;

	/** This one actually fetches data */
	final FetchContext realFetchContext;

	final Executor executor;

	private ClientContext context;

	public USKManager(HighLevelSimpleClient client, Executor executor) {
		client.setMaxIntermediateLength(Toadlet.FProxy.MAX_LENGTH_NO_PROGRESS);
		client.setMaxLength(Toadlet.FProxy.MAX_LENGTH_NO_PROGRESS);
		this.backgroundFetchContext = client.getFetchContext();
		this.backgroundFetchContext.followRedirects = false;
		this.backgroundFetchContextIgnoreDBR = this.backgroundFetchContext.clone();
		this.backgroundFetchContextIgnoreDBR.ignoreUSKDatehints = true;
		this.realFetchContext = client.getFetchContext();
		// Performance: I'm pretty sure there is no spatial locality in the underlying
		// data, so it's okay to use the FAST_COMPARATOR here.
		// That is, even if two USKs are by the same author, they won't necessarily be
		// updated or polled at the same time.
		this.latestKnownGoodByClearUSK = new TreeMap<>(USK.FAST_COMPARATOR);
		this.latestSlotByClearUSK = new TreeMap<>(USK.FAST_COMPARATOR);
		this.subscribersByClearUSK = new TreeMap<>(USK.FAST_COMPARATOR);
		this.backgroundFetchersByClearUSK = new TreeMap<>(USK.FAST_COMPARATOR);
		this.temporaryBackgroundFetchersLRU = LRUMap.createSafeMap(USK.FAST_COMPARATOR);
		this.temporaryBackgroundFetchersPrefetch = new WeakHashMap<>();
		this.executor = executor;
	}

	public void init(ClientContext context) {
		this.context = context;
	}

	/**
	 * Look up the latest known working version of the given USK.
	 * @return The latest known edition number, or -1.
	 */
	public synchronized long lookupKnownGood(USK usk) {
		Long l = this.latestKnownGoodByClearUSK.get(usk.clearCopy());
		if (l != null) {
			return l;
		}
		else {
			return -1;
		}
	}

	/**
	 * Look up the latest SSK slot, whether the data it links to has been successfully
	 * fetched or not, of the given USK.
	 * @return The latest known edition number, or -1.
	 */
	public synchronized long lookupLatestSlot(USK usk) {
		Long l = this.latestSlotByClearUSK.get(usk.clearCopy());
		if (l != null) {
			return l;
		}
		else {
			return -1;
		}
	}

	public USKFetcherTag getFetcher(USK usk, FetchContext ctx, boolean keepLast, boolean persistent, boolean realTime,
			USKFetcherCallback callback, boolean ownFetchContext, ClientContext context, boolean checkStoreOnly) {
		return USKFetcherTag.create(usk, callback, persistent, realTime, ctx, keepLast, 0, ownFetchContext,
				checkStoreOnly || ctx.localRequestOnly);
	}

	USKFetcher getFetcher(USK usk, FetchContext ctx, ClientRequester requester, boolean keepLastData,
			boolean checkStoreOnly) {
		return new USKFetcher(usk, this, ctx, requester, 3, false, keepLastData, checkStoreOnly);
	}

	public USKFetcherTag getFetcherForInsertDontSchedule(USK usk, short prioClass, USKFetcherCallback cb,
			RequestClient client, ClientContext context, boolean persistent, boolean ignoreUSKDatehints) {
		FetchContext fctx = ignoreUSKDatehints ? this.backgroundFetchContextIgnoreDBR : this.backgroundFetchContext;
		return this.getFetcher(usk, persistent ? new FetchContext(fctx, FetchContext.IDENTICAL_MASK) : fctx, true,
				client.persistent(), client.realTimeFlag(), cb, true, context, false);
	}

	/**
	 * A non-authoritative hint that a specific edition *might* exist. At the moment, we
	 * just fetch the block. We do not fetch the contents, and it is possible that
	 * USKFetcher's are also fetching the block. FIXME would it be more efficient to pass
	 * it along to a USKFetcher?
	 */
	public void hintUpdate(USK usk, long edition, ClientContext context) {
		if (edition < this.lookupLatestSlot(usk)) {
			return;
		}
		FreenetURI uri = usk.copy(edition).getURI().sskForUSK();
		final ClientGetter get = new ClientGetter(new NullClientCallback(rcBulk), uri,
				new FetchContext(this.backgroundFetchContext, FetchContext.IDENTICAL_MASK),
				PriorityClasses.UPDATE_PRIORITY_CLASS, new NullBucket(), null, null);
		try {
			get.start(context);
		}
		catch (FetchException ex) {
			// Ignore
		}
	}

	public void hintUpdate(FreenetURI uri, ClientContext context) throws MalformedURLException {
		this.hintUpdate(uri, context, PriorityClasses.UPDATE_PRIORITY_CLASS);
	}

	/**
	 * A non-authoritative hint that a specific edition *might* exist. At the moment, we
	 * just fetch the block. We do not fetch the contents, and it is possible that
	 * USKFetcher's are also fetching the block. FIXME would it be more efficient to pass
	 * it along to a USKFetcher?
	 * @throws MalformedURLException If the uri passed in is not a USK.
	 */
	public void hintUpdate(FreenetURI uri, ClientContext context, short priority) throws MalformedURLException {
		if (uri.getSuggestedEdition() < this.lookupLatestSlot(USK.create(uri))) {
			if (logMINOR) {
				Logger.minor(this, "Ignoring hint because edition is " + uri.getSuggestedEdition() + " but latest is "
						+ this.lookupLatestSlot(USK.create(uri)));
			}
			return;
		}
		uri = uri.sskForUSK();
		if (logMINOR) {
			Logger.minor(this, "Doing hint fetch for " + uri);
		}
		final ClientGetter get = new ClientGetter(new NullClientCallback(rcBulk), uri,
				new FetchContext(this.backgroundFetchContext, FetchContext.IDENTICAL_MASK), priority, new NullBucket(),
				null, null);
		try {
			get.start(context);
		}
		catch (FetchException ex) {
			if (logMINOR) {
				Logger.minor(this, "Cannot start hint fetch for " + uri + " : " + ex, ex);
			}
			// Ignore
		}
	}

	/**
	 * Simply check whether the block exists, in such a way that we don't fetch the full
	 * content. If it does exist then the USK tracker, and therefore any fetchers, will be
	 * updated. You can pass either an SSK or a USK.
	 */
	public void hintCheck(FreenetURI uri, final Object token, ClientContext context, short priority,
			final HintCallback cb) {
		final FreenetURI origURI = uri;
		if (uri.isUSK()) {
			uri = uri.sskForUSK();
		}
		if (logMINOR) {
			Logger.minor(this, "Doing hint fetch for " + uri);
		}
		final ClientGetter get = new ClientGetter(new ClientGetCallback() {

			@Override
			public void onSuccess(FetchResult result, ClientGetter state) {
				cb.success(origURI, token);
			}

			@Override
			public void onFailure(FetchException e, ClientGetter state) {
				if (e.isDataFound()) {
					cb.success(origURI, token);
				}
				else if (e.isDNF()) {
					cb.dnf(origURI, token, e);
				}
				else {
					cb.failed(origURI, token, e);
				}
			}

			@Override
			public void onResume(ClientContext context) {
				// Do nothing.
			}

			@Override
			public RequestClient getRequestClient() {
				return rcBulk;
			}

		}, uri, new FetchContext(this.backgroundFetchContext, FetchContext.IDENTICAL_MASK), priority, new NullBucket(),
				null, null);
		try {
			get.start(context);
		}
		catch (FetchException ex) {
			if (logMINOR) {
				Logger.minor(this, "Cannot start hint fetch for " + uri + " : " + ex, ex);
			}
			if (ex.isDataFound()) {
				cb.success(origURI, token);
			}
			else if (ex.isDNF()) {
				cb.dnf(origURI, token, ex);
			}
			else {
				cb.failed(origURI, token, ex);
			}
		}
	}

	public void startTemporaryBackgroundFetcher(USK usk, ClientContext context, final FetchContext fctx,
			boolean prefetchContent, boolean realTimeFlag) {
		final USK clear = usk.clearCopy();
		USKFetcher sched = null;
		ArrayList<USKFetcher> toCancel = null;
		synchronized (this) {
			// int x = 0;
			// for(USK key: backgroundFetchersByClearUSK.keySet()) {
			// System.err.println("Fetcher "+x+": "+key);
			// x++;
			// }
			USKFetcher f = this.temporaryBackgroundFetchersLRU.get(clear);
			if (f == null) {
				f = new USKFetcher(usk, this,
						fctx.ignoreUSKDatehints ? this.backgroundFetchContextIgnoreDBR : this.backgroundFetchContext,
						new USKFetcherWrapper(usk, PriorityClasses.UPDATE_PRIORITY_CLASS, realTimeFlag ? rcRT : rcBulk),
						3, false, false, false);
				sched = f;
				this.temporaryBackgroundFetchersLRU.push(clear, f);
			}
			else {
				f.addHintEdition(usk.suggestedEdition);
			}
			if (prefetchContent) {
				long fetchTime = -1;
				// If nothing in 60 seconds, try fetching the last known slot.
				long slot = this.lookupLatestSlot(clear);
				long good = this.lookupKnownGood(clear);
				if (slot > -1 && good != slot) {
					fetchTime = System.currentTimeMillis();
				}
				this.temporaryBackgroundFetchersPrefetch.put(clear, fetchTime);
				if (logMINOR) {
					Logger.minor(this, "Prefetch: set " + fetchTime + " for " + clear);
				}
				this.schedulePrefetchChecker();
			}
			this.temporaryBackgroundFetchersLRU.push(clear, f);
			while (this.temporaryBackgroundFetchersLRU.size() > context.maxBackgroundUSKFetchers) {
				USKFetcher fetcher = this.temporaryBackgroundFetchersLRU.popValue();
				assert fetcher != null;
				this.temporaryBackgroundFetchersPrefetch.remove(fetcher.getOriginalUSK().clearCopy());
				if (!fetcher.hasSubscribers()) {
					if (toCancel == null) {
						toCancel = new ArrayList<>(2);
					}
					toCancel.add(fetcher);
				}
				else {
					if (logMINOR) {
						Logger.minor(this, "Allowing temporary background fetcher to continue as it has subscribers... "
								+ fetcher);
					}
				}
			}
		}
		final ArrayList<USKFetcher> cancelled = toCancel;
		final USKFetcher scheduleMe = sched;
		// This is just a prefetching method. so it should not unnecessarily delay the
		// parent, nor should it take important locks.
		// So we should do the actual schedule/cancels off-thread.
		// However, the above is done on-thread because a lot of the time it will already
		// be running.
		if (cancelled != null || sched != null) {
			this.executor.execute(() -> {
				if (cancelled != null) {
					for (USKFetcher fetcher : cancelled) {
						fetcher.cancel(USKManager.this.context);
					}
				}
				if (scheduleMe != null) {
					scheduleMe.schedule(USKManager.this.context);
				}
			});
		}
	}

	static final long PREFETCH_DELAY = TimeUnit.SECONDS.toMillis(60);

	private void schedulePrefetchChecker() {
		this.context.ticker.queueTimedJob(this.prefetchChecker, "Check for USKs to prefetch", PREFETCH_DELAY, false,
				true);
	}

	private final Runnable prefetchChecker = new Runnable() {

		@Override
		public void run() {
			if (logDEBUG) {
				Logger.debug(this, "Running prefetch checker...");
			}
			ArrayList<USK> toFetch = null;
			long now = System.currentTimeMillis();
			boolean empty = true;
			synchronized (USKManager.this) {
				for (Map.Entry<USK, Long> entry : USKManager.this.temporaryBackgroundFetchersPrefetch.entrySet()) {
					empty = false;
					if (entry.getValue() > 0 && now - entry.getValue() >= PREFETCH_DELAY) {
						if (toFetch == null) {
							toFetch = new ArrayList<>();
						}
						USK clear = entry.getKey();
						long l = USKManager.this.lookupLatestSlot(clear);
						if (USKManager.this.lookupKnownGood(clear) < l) {
							toFetch.add(clear.copy(l));
						}
						entry.setValue(-1L); // Reset counter until new data comes in
					}
					else {
						if (logMINOR) {
							Logger.minor(this, "Not prefetching: " + entry.getKey() + " : " + entry.getValue());
						}
					}
				}
			}
			if (toFetch == null) {
				return;
			}
			for (final USK key : toFetch) {
				final long l = key.suggestedEdition;
				if (logMINOR) {
					Logger.minor(this, "Prefetching content for background fetch for edition " + l + " on " + key);
				}
				FetchContext fctx = new FetchContext(USKManager.this.realFetchContext, FetchContext.IDENTICAL_MASK);
				final ClientGetter get = new ClientGetter(new ClientGetCallback() {

					@Override
					public void onFailure(FetchException e, ClientGetter state) {
						if (e.newURI != null) {
							if (logMINOR) {
								Logger.minor(this, "Prefetch succeeded with redirect for " + key);
							}
							USKManager.this.updateKnownGood(key, l, USKManager.this.context);
						}
						else {
							if (logMINOR) {
								Logger.minor(this, "Prefetch failed later: " + e + " for " + key, e);
							}
							// Ignore
						}
					}

					@Override
					public void onSuccess(FetchResult result, ClientGetter state) {
						if (logMINOR) {
							Logger.minor(this, "Prefetch succeeded for " + key);
						}
						result.asBucket().free();
						USKManager.this.updateKnownGood(key, l, USKManager.this.context);
					}

					@Override
					public void onResume(ClientContext context) {
						// Do nothing. Not persistent.
					}

					@Override
					public RequestClient getRequestClient() {
						return rcBulk;
					}
				}, key.getURI().sskForUSK() /* FIXME add getSSKURI() */, fctx, PriorityClasses.UPDATE_PRIORITY_CLASS,
						new NullBucket(), null, null);
				try {
					get.start(USKManager.this.context);
				}
				catch (FetchException ex) {
					if (logMINOR) {
						Logger.minor(this, "Prefetch failed: " + ex, ex);
					}
					// Ignore
				}
			}
			USKManager.this.schedulePrefetchChecker();
		}

	};

	void updateKnownGood(final USK origUSK, final long number, final ClientContext context) {
		if (logMINOR) {
			Logger.minor(this, "Updating (known good) " + origUSK.getURI() + " : " + number);
		}
		USK clear = origUSK.clearCopy();
		final USKCallback[] callbacks;
		boolean newSlot = false;
		synchronized (this) {
			Long l = this.latestKnownGoodByClearUSK.get(clear);
			if (logMINOR) {
				Logger.minor(this, "Old known good: " + l);
			}
			if ((l == null) || (number > l)) {
				l = number;
				this.latestKnownGoodByClearUSK.put(clear, l);
				if (logMINOR) {
					Logger.minor(this, "Put " + number);
				}
			}
			else {
				return; // If it's in KnownGood, it will also be in Slot
			}

			l = this.latestSlotByClearUSK.get(clear);
			if (logMINOR) {
				Logger.minor(this, "Old slot: " + l);
			}
			if ((l == null) || (number > l)) {
				l = number;
				this.latestSlotByClearUSK.put(clear, l);
				if (logMINOR) {
					Logger.minor(this, "Put " + number);
				}
				newSlot = true;
			}

			callbacks = this.subscribersByClearUSK.get(clear);
		}
		if (callbacks != null) {
			// Run off-thread, because of locking, and because client callbacks may take
			// some time
			final USK usk = origUSK.copy(number);
			final boolean newSlotToo = newSlot;
			for (final USKCallback callback : callbacks) {
				context.mainExecutor.execute(() -> callback.onFoundEdition(number, usk, // non-persistent
						context, false, (short) -1, null, true, newSlotToo),
						"USKManager callback executor for " + callback);
			}
		}
	}

	void updateSlot(final USK origUSK, final long number, final ClientContext context) {
		if (logMINOR) {
			Logger.minor(this, "Updating (slot) " + origUSK.getURI() + " : " + number);
		}
		USK clear = origUSK.clearCopy();
		final USKCallback[] callbacks;
		synchronized (this) {
			Long l = this.latestSlotByClearUSK.get(clear);
			if (logMINOR) {
				Logger.minor(this, "Old slot: " + l);
			}
			if ((l == null) || (number > l)) {
				l = number;
				this.latestSlotByClearUSK.put(clear, l);
				if (logMINOR) {
					Logger.minor(this, "Put " + number);
				}
			}
			else {
				return;
			}

			callbacks = this.subscribersByClearUSK.get(clear);
			if (this.temporaryBackgroundFetchersPrefetch.containsKey(clear)) {
				this.temporaryBackgroundFetchersPrefetch.put(clear, System.currentTimeMillis());
				this.schedulePrefetchChecker();
			}
		}
		if (callbacks != null) {
			// Run off-thread, because of locking, and because client callbacks may take
			// some time
			final USK usk = origUSK.copy(number);
			for (final USKCallback callback : callbacks) {
				context.mainExecutor.execute(() -> callback.onFoundEdition(number, usk, // non-persistent
						context, false, (short) -1, null, false, false),
						"USKManager callback executor for " + callback);
			}
		}
	}

	/**
	 * Subscribe to a given USK, and poll it in the background, but only report new
	 * editions when we've been through a round and are confident that we won't find more
	 * in the near future. Note that it will ignore KnownGood, it only cares about latest
	 * slot.
	 * @return The proxy object which was actually subscribed. The caller MUST record this
	 * and pass it in to unsubscribe() when unsubscribing.
	 */
	public USKSparseProxyCallback subscribeSparse(USK origUSK, USKCallback cb, boolean ignoreUSKDatehints,
			RequestClient client) {
		USKSparseProxyCallback proxy = new USKSparseProxyCallback(cb, origUSK);
		this.subscribe(origUSK, proxy, true, ignoreUSKDatehints, client);
		return proxy;
	}

	public USKSparseProxyCallback subscribeSparse(USK origUSK, USKCallback cb, RequestClient client) {
		return this.subscribeSparse(origUSK, cb, false, client);
	}

	/**
	 * Subscribe to a given USK. Callback will be notified when it is updated. Note that
	 * this does not imply that the USK will be checked on a regular basis, unless
	 * runBackgroundFetch=true.
	 */
	public void subscribe(USK origUSK, USKCallback cb, boolean runBackgroundFetch, boolean ignoreUSKDatehints,
			RequestClient client) {
		if (logMINOR) {
			Logger.minor(this, "Subscribing to " + origUSK + " for " + cb);
		}
		if (client.persistent()) {
			throw new UnsupportedOperationException("USKManager subscriptions cannot be persistent");
		}
		USKFetcher sched = null;
		long ed = origUSK.suggestedEdition;
		if (ed < 0) {
			Logger.error(this, "Subscribing to USK with negative edition number: " + ed);
			ed = -ed;
		}
		long curEd;
		curEd = this.lookupLatestSlot(origUSK);
		long goodEd;
		goodEd = this.lookupKnownGood(origUSK);
		synchronized (this) {
			USK clear = origUSK.clearCopy();
			USKCallback[] callbacks = this.subscribersByClearUSK.get(clear);
			if (callbacks == null) {
				callbacks = new USKCallback[] { cb };
			}
			else {
				boolean mustAdd = true;
				for (USKCallback callback : callbacks) {
					if (callback == cb) {
						// Already subscribed.
						// But it may still be waiting for the callback.
						if (!(curEd > ed || goodEd > ed)) {
							return;
						}
						mustAdd = false;
					}
				}
				if (mustAdd) {
					callbacks = Arrays.copyOf(callbacks, callbacks.length + 1);
					callbacks[callbacks.length - 1] = cb;
				}
			}
			this.subscribersByClearUSK.put(clear, callbacks);
			if (runBackgroundFetch) {
				USKFetcher f = this.backgroundFetchersByClearUSK.get(clear);
				if (f == null) {
					f = new USKFetcher(origUSK, this,
							ignoreUSKDatehints ? this.backgroundFetchContextIgnoreDBR : this.backgroundFetchContext,
							new USKFetcherWrapper(origUSK, PriorityClasses.UPDATE_PRIORITY_CLASS, client), 3, true,
							false, false);
					sched = f;
					this.backgroundFetchersByClearUSK.put(clear, f);
				}
				f.addSubscriber(cb, origUSK.suggestedEdition);
			}
		}
		if (goodEd > ed) {
			cb.onFoundEdition(goodEd, origUSK.copy(curEd), this.context, false, (short) -1, null, true, curEd > ed);
		}
		else if (curEd > ed) {
			cb.onFoundEdition(curEd, origUSK.copy(curEd), this.context, false, (short) -1, null, false, false);
		}
		final USKFetcher fetcher = sched;
		if (fetcher != null) {
			this.executor.execute(new Runnable() {
				@Override
				public void run() {
					if (logMINOR) {
						Logger.minor(this, "Starting " + fetcher);
					}
					fetcher.schedule(USKManager.this.context);
				}
			}, "USKManager.schedule for " + fetcher);
		}
	}

	public void subscribe(USK origUSK, USKCallback cb, boolean runBackgroundFetch, RequestClient client) {
		this.subscribe(origUSK, cb, runBackgroundFetch, false, client);
	}

	public void unsubscribe(USK origUSK, USKCallback cb) {
		USKFetcher toCancel = null;
		synchronized (this) {
			USK clear = origUSK.clearCopy();
			USKCallback[] callbacks = this.subscribersByClearUSK.get(clear);
			if (callbacks == null) { // maybe we should throw something ? shall we allow
										// multiple unsubscriptions ?
				if (logMINOR) {
					Logger.minor(this, "No longer subscribed");
				}
				return;
			}
			int j = 0;
			for (USKCallback c : callbacks) {
				if ((c != null) && (c != cb)) {
					callbacks[j++] = c;
				}
			}
			USKCallback[] newCallbacks = Arrays.copyOf(callbacks, j);
			if (newCallbacks.length > 0) {
				this.subscribersByClearUSK.put(clear, newCallbacks);
			}
			else {
				this.subscribersByClearUSK.remove(clear);
			}
			USKFetcher f = this.backgroundFetchersByClearUSK.get(clear);
			if (f != null) {
				f.removeSubscriber(cb, this.context);
				if (!f.hasSubscribers()) {
					toCancel = f;
					this.backgroundFetchersByClearUSK.remove(clear);
				}
			}
			// Temporary background fetchers run once and then die.
			// They do not care about callbacks.
		}
		if (toCancel != null) {
			toCancel.cancel(this.context);
		}
		else {
			if (logMINOR) {
				Logger.minor(this, "Not found unsubscribing: " + cb + " for " + origUSK);
			}
		}
	}

	/**
	 * Subscribe to a USK. When it is updated, the content will be fetched (subject to the
	 * limits in fctx), and returned to the callback. If we are asked to do a background
	 * fetch, we will only fetch editions when we are fairly confident there are no more
	 * to fetch.
	 * @param origUSK The USK to poll.
	 * @param cb Callback, called when we have downloaded a new key.
	 * @param runBackgroundFetch If true, start a background fetcher for the key, which
	 * will run forever until we unsubscribe. Note that internally we use
	 * subscribeSparse() in this case, i.e. we will only download editions which we are
	 * confident about.
	 * @param fctx Fetcher context for actually fetching the keys. Not used by the USK
	 * polling.
	 * @param prio Priority for fetching the content (see constants in RequestScheduler).
	 */
	public USKRetriever subscribeContent(USK origUSK, USKRetrieverCallback cb, boolean runBackgroundFetch,
			FetchContext fctx, short prio, RequestClient client) {
		USKRetriever ret = new USKRetriever(fctx, prio, client, cb, origUSK);
		USKCallback toSub = ret;
		if (logMINOR) {
			Logger.minor(this, "Subscribing to " + origUSK + " for " + cb);
		}
		if (runBackgroundFetch) {
			USKSparseProxyCallback proxy = new USKSparseProxyCallback(ret, origUSK);
			ret.setProxy(proxy);
			toSub = proxy;
		}
		this.subscribe(origUSK, toSub, runBackgroundFetch, fctx.ignoreUSKDatehints, client);
		return ret;
	}

	/**
	 * Subscribe to a USK with a custom FetchContext. This is "off the books", i.e. the
	 * background fetcher isn't started by subscribe().
	 */
	public USKRetriever subscribeContentCustom(USK origUSK, USKRetrieverCallback cb, FetchContext fctx, short prio,
			RequestClient client) {
		USKRetriever ret = new USKRetriever(fctx, prio, client, cb, origUSK);
		USKCallback toSub;
		if (logMINOR) {
			Logger.minor(this, "Subscribing to " + origUSK + " for " + cb);
		}
		USKSparseProxyCallback proxy = new USKSparseProxyCallback(ret, origUSK);
		ret.setProxy(proxy);
		toSub = proxy;
		/* runBackgroundFetch=false -> ignoreUSKDatehints unused */
		this.subscribe(origUSK, toSub, false, client);
		USKFetcher f = new USKFetcher(origUSK, this, fctx, new USKFetcherWrapper(origUSK, prio, client), 3, true, false,
				false);
		ret.setFetcher(f);
		return ret;
	}

	public void unsubscribeContent(USK origUSK, USKRetriever ret, boolean runBackgroundFetch) {
		ret.unsubscribe(this);
	}

	// REMOVE: DO NOT Synchronize! ... debugging only.
	/**
	 * The result of that method will be displayed on the Statistic Toadlet : it will help
	 * catching #1147 Afterwards it should be removed: it's not usefull :)
	 * @return the number of BackgroundFetchers started by USKManager
	 */
	public int getBackgroundFetcherByUSKSize() {
		return this.backgroundFetchersByClearUSK.size();
	}

	/**
	 * The result of that method will be displayed on the Statistic Toadlet : it will help
	 * catching #1147 Afterwards it should be removed: it's not usefull :)
	 * @return the size of temporaryBackgroundFetchersLRU
	 */
	public int getTemporaryBackgroundFetchersLRU() {
		return this.temporaryBackgroundFetchersLRU.size();
	}

	public void onFinished(USKFetcher fetcher) {
		this.onFinished(fetcher, false);
	}

	public void onFinished(USKFetcher fetcher, boolean ignoreError) {
		USK orig = fetcher.getOriginalUSK();
		USK clear = orig.clearCopy();
		synchronized (this) {
			if (this.backgroundFetchersByClearUSK.get(clear) == fetcher) {
				this.backgroundFetchersByClearUSK.remove(clear);
				if (!ignoreError) {
					// This shouldn't happen, it's a sanity check: the only way we get
					// cancelled is from USKManager, which removes us before calling
					// cancel().
					Logger.error(this, "onCancelled for " + fetcher + " - was still registered, how did this happen??",
							new Exception("debug"));
				}
			}
			if (this.temporaryBackgroundFetchersLRU.get(clear) == fetcher) {
				this.temporaryBackgroundFetchersLRU.removeKey(clear);
				this.temporaryBackgroundFetchersPrefetch.remove(clear);
			}
		}
	}

	public boolean persistent() {
		return false;
	}

	ClientContext getContext() {
		return this.context;
	}

	public void checkUSK(FreenetURI uri, boolean persistent, boolean isMetadata) {
		try {
			FreenetURI uu;
			if (uri.isSSK() && uri.isSSKForUSK()) {
				uu = uri.setMetaString(null).uskForSSK();
			}
			else if (uri.isUSK()) {
				uu = uri;
			}
			else {
				return;
			}
			USK usk = USK.create(uu);
			if (!isMetadata) {
				this.context.uskManager.updateKnownGood(usk, uu.getSuggestedEdition(), this.context);
			}
			else {
				// We don't know whether the metadata is fetchable.
				// FIXME add a callback so if the rest of the request completes we
				// updateKnownGood().
				this.context.uskManager.updateSlot(usk, uu.getSuggestedEdition(), this.context);
			}
		}
		catch (MalformedURLException ex) {
			Logger.error(this, "Caught " + ex, ex);
		}
		catch (Throwable ex) {
			// Don't let the USK hint cause us to not succeed on the block.
			Logger.error(this, "Caught " + ex, ex);
		}
	}

	public interface HintCallback {

		/**
		 * The SSK block exists. The USK tracker will have been updated. We did not try to
		 * fetch the rest of the key.
		 * @param origURI The original FreenetURI object.
		 * @param token The token object passed in by the caller.
		 */
		void success(FreenetURI origURI, Object token);

		/**
		 * The SSK block does not exist. We got a DNF, DNF with RecentlyFailed, check
		 * store only and it wasn't in the datastore etc.
		 * @param origURI The original FreenetURI object.
		 * @param token The token object passed in by the caller.
		 * @param e The exception.
		 */
		void dnf(FreenetURI origURI, Object token, FetchException e);

		/**
		 * Some other error. We don't necessarily know that it doesn't exist.
		 * @param origURI The original FreenetURI object.
		 * @param token The token object passed in by the caller.
		 * @param e The exception.
		 */
		void failed(FreenetURI origURI, Object token, FetchException e);

	}

}
