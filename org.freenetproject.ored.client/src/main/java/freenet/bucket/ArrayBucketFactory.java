/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.bucket;

import java.io.IOException;

public class ArrayBucketFactory implements BucketFactory {

	@Override
	public RandomAccessBucket makeBucket(long size) throws IOException {
		return new ArrayBucket();
	}

	public void freeBucket(Bucket b) throws IOException {
		b.free();
	}

}
