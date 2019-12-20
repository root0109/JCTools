/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.queues.atomic;

import org.jctools.util.Pow2;
import org.jctools.util.RangeUtil;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MessagePassingQueue.Supplier;
import org.jctools.queues.MessagePassingQueueUtil;
import org.jctools.queues.QueueProgressIndicators;
import org.jctools.queues.IndexedQueueSizeUtil;
import static org.jctools.queues.atomic.LinkedAtomicArrayQueueUtil.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jctools.queues.MpmcArrayQueue;

/**
 * NOTE: This class was automatically generated by org.jctools.queues.atomic.JavaParsingAtomicLinkedQueueGenerator
 * which can found in the jctools-build module. The original source file is SpscChunkedArrayQueue.java.
 *
 * An SPSC array queue which starts at <i>initialCapacity</i> and grows to <i>maxCapacity</i> in linked chunks
 * of the initial size. The queue grows only when the current chunk is full and elements are not copied on
 * resize, instead a link to the new chunk is stored in the old chunk for the consumer to follow.<br>
 *
 * @param <E>
 */
public class SpscChunkedAtomicArrayQueue<E> extends BaseSpscLinkedAtomicArrayQueue<E> {

    private final int maxQueueCapacity;

    private long producerQueueLimit;

    public SpscChunkedAtomicArrayQueue(int capacity) {
        this(Math.max(8, Pow2.roundToPowerOfTwo(capacity / 8)), capacity);
    }

    public SpscChunkedAtomicArrayQueue(int chunkSize, int capacity) {
        RangeUtil.checkGreaterThanOrEqual(capacity, 16, "capacity");
        // minimal chunk size of eight makes sure minimal lookahead step is 2
        RangeUtil.checkGreaterThanOrEqual(chunkSize, 8, "chunkSize");
        maxQueueCapacity = Pow2.roundToPowerOfTwo(capacity);
        int chunkCapacity = Pow2.roundToPowerOfTwo(chunkSize);
        RangeUtil.checkLessThan(chunkCapacity, maxQueueCapacity, "chunkCapacity");
        long mask = chunkCapacity - 1;
        // need extra element to point at next array
        AtomicReferenceArray<E> buffer = allocate(chunkCapacity + 1);
        producerBuffer = buffer;
        producerMask = mask;
        consumerBuffer = buffer;
        consumerMask = mask;
        // we know it's all empty to start with
        producerBufferLimit = mask - 1;
        producerQueueLimit = maxQueueCapacity;
    }

    @Override
    final boolean offerColdPath(AtomicReferenceArray<E> buffer, long mask, long pIndex, int offset, E v, Supplier<? extends E> s) {
        // use a fixed lookahead step based on buffer capacity
        final long lookAheadStep = (mask + 1) / 4;
        long pBufferLimit = pIndex + lookAheadStep;
        long pQueueLimit = producerQueueLimit;
        if (pIndex >= pQueueLimit) {
            // we tested against a potentially out of date queue limit, refresh it
            final long cIndex = lvConsumerIndex();
            producerQueueLimit = pQueueLimit = cIndex + maxQueueCapacity;
            // if we're full we're full
            if (pIndex >= pQueueLimit) {
                return false;
            }
        }
        // cannot use Math.min
        if (pBufferLimit - pQueueLimit > 0) {
            pBufferLimit = pQueueLimit;
        }
        // go around the buffer or add a new buffer
        if (// there's sufficient room in buffer/queue to use pBufferLimit
        pBufferLimit > pIndex + 1 && null == lvElement(buffer, calcCircularElementOffset(pBufferLimit, mask))) {
            // joy, there's plenty of room
            producerBufferLimit = pBufferLimit - 1;
            writeToQueue(buffer, v == null ? s.get() : v, pIndex, offset);
        } else if (null == lvElement(buffer, calcCircularElementOffset(pIndex + 1, mask))) {
            // buffer is not full
            writeToQueue(buffer, v == null ? s.get() : v, pIndex, offset);
        } else {
            // we got one slot left to write into, and we are not full. Need to link new buffer.
            // allocate new buffer of same length
            final AtomicReferenceArray<E> newBuffer = allocate((int) (mask + 2));
            producerBuffer = newBuffer;
            linkOldToNew(pIndex, buffer, offset, newBuffer, offset, v == null ? s.get() : v);
        }
        return true;
    }

    @Override
    public int capacity() {
        return maxQueueCapacity;
    }
}
