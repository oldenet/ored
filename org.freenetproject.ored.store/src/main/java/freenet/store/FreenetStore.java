package freenet.store;

import java.io.Closeable;
import java.io.IOException;

import freenet.client.async.UserAlertRegister;
import freenet.keys.BlockMetadata;
import freenet.keys.KeyCollisionException;
import freenet.support.Ticker;
import freenet.keys.StorableBlock;
import freenet.support.node.stats.StoreAccessStats;

/**
 * Datastore interface
 */
public interface FreenetStore<T extends StorableBlock> extends Closeable {

	public enum StoreType {

		CHK, PUBKEY, SSK

	};

	/**
	 * Retrieve a block. Use the StoreCallback to convert it to the appropriate type of
	 * block.
	 * @param routingKey The routing key i.e. the database key under which the block is
	 * stored.
	 * @param dontPromote If true, don't promote the block to the top of the LRU.
	 * @return A StorableBlock, or null if the key cannot be found.
	 * @param canReadClientCache Whether we can read the client-cache, for purposes of
	 * finding the pubkey for an SSK.
	 * @throws IOException If a disk I/O error occurs.
	 */
	T fetch(byte[] routingKey, byte[] fullKey, boolean dontPromote, boolean canReadClientCache,
			boolean canReadSlashdotCache, boolean ignoreOldBlocks, BlockMetadata meta) throws IOException;

	/**
	 * Store a block.
	 * @throws KeyCollisionException If the key already exists and
	 * <code>callback.collisionPossible()</code> is <code>true</code>.
	 * @param overwrite If true, overwrite old content rather than throwing a
	 * <code>KeyCollisionException</code>.
	 * @param oldBlock If true, the block really shouldn't be in the datastore, but we are
	 * storing it anyway; it should not have the new block flag, so it should be excluded
	 * from Bloom filter sharing.
	 */
	public void put(T block, byte[] data, byte[] header, boolean overwrite, boolean oldBlock)
			throws IOException, KeyCollisionException;

	/**
	 * Change the store size.
	 * @param maxStoreKeys The maximum number of keys to be cached.
	 * @param shrinkNow If false, don't shrink the store immediately.
	 * @throws IOException
	 */
	public void setMaxKeys(long maxStoreKeys, boolean shrinkNow) throws IOException;

	public long getMaxKeys();

	public long hits();

	public long misses();

	public long writes();

	public long keyCount();

	public long getBloomFalsePositive();

	/**
	 * Check if a routing key probably
	 * @param routingKey
	 * @return <code>false</code> <b>only</b> if the key does not exist in store.
	 */
	public boolean probablyInStore(byte[] routingKey);

	public abstract StoreAccessStats getSessionAccessStats();

	public abstract StoreAccessStats getTotalAccessStats();

	public boolean start(Ticker ticker, boolean longStart) throws IOException;

	public void close();

	public void setUserAlertRegister(UserAlertRegister userAlertRegister);

	public FreenetStore<T> getUnderlyingStore();

}
