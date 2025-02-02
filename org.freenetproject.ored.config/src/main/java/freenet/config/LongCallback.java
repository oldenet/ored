/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.config;

import freenet.config.ConfigCallback;

/**
 * A callback to be called when a config value of long type changes. Also reports the
 * current value.
 */
public abstract class LongCallback extends ConfigCallback<Long> {

}
