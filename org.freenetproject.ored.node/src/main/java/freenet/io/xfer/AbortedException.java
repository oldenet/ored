/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.io.xfer;

/**
 * Thrown when a transfer is aborted, and caller tries to do something on PRB, in order to
 * avoid some races.
 */
public class AbortedException extends Exception {

	private static final long serialVersionUID = -1;

	public AbortedException(String msg) {
		super(msg);
	}

}
