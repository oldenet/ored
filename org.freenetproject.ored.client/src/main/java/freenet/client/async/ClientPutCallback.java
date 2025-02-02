/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.client.async;

import freenet.bucket.Bucket;
import freenet.client.InsertException;
import freenet.keys.FreenetURI;

/**
 * Internal callback interface for inserts (including site inserts). Methods are called on
 * the database thread if the request is persistent, otherwise on whatever thread
 * completed the request (therefore with a null container).
 */
public interface ClientPutCallback extends ClientBaseCallback {

	/**
	 * Called when URI is known (e.g. after encode all CHK blocks). Won't be called if we
	 * are returning metadata instead.
	 * @param state The original BaseClientPutter object which was returned by the
	 * .insert() method which started this insert. Can be casted to the return type of
	 * that .insert().
	 */
	public void onGeneratedURI(FreenetURI uri, BaseClientPutter state);

	/**
	 * Called when we are returning metadata rather than a URI. This only happens if the
	 * originator specified a metadata threshold, and we can get the metadata below that
	 * threshold without inserting a single top block.
	 * @param metadata Bucket containing the metadata. Persistent if the insert is
	 * persistent. Recipient may keep it, but must eventually free it. The caller will not
	 * free it.
	 * @param state The original BaseClientPutter object which was returned by the
	 * .insert() method which started this insert. Can be casted to the return type of
	 * that .insert().
	 */
	public void onGeneratedMetadata(Bucket metadata, BaseClientPutter state);

	/**
	 * Called when the inserted data is fetch able (just a hint, don't rely on this).
	 * @param state The original BaseClientPutter object which was returned by the
	 * .insert() method which started this insert. Can be casted to the return type of
	 * that .insert().
	 */
	public void onFetchable(BaseClientPutter state);

	/**
	 * Called on successful insert. In this callback you must free the Bucket which you
	 * specified for the insert!
	 * @param state The original BaseClientPutter object which was returned by the
	 * .insert() method which started this insert. Can be casted to the return type of
	 * that .insert() (to obtain the Bucket).
	 */
	public void onSuccess(BaseClientPutter state);

	/**
	 * Called on failed/canceled insert. In this callback you must free the Bucket which
	 * you specified for the insert!
	 * @param state The original BaseClientPutter object which was returned by the
	 * .insert() method which started this insert. Can be casted to the return type of
	 * that .insert() (to obtain the Bucket).
	 */
	public void onFailure(InsertException e, BaseClientPutter state);

}
