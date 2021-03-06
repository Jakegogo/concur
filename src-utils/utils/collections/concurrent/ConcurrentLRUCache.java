/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package utils.collections.concurrent;

import utils.collections.PriorityQueue;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A LRU cache implementation based upon ConcurrentHashMap and other techniques to reduce
 * contention and synchronization overhead to utilize multiple CPU cores more effectively.
 * <p/>
 * Note that the implementation does not follow a true LRU (least-recently-used) eviction
 * strategy. Instead it strives to remove least recently used items but when the initial
 * cleanup does not remove enough items to reach the 'acceptableWaterMark' limit, it can
 * remove more items forcefully regardless of access order.
 *
 *
 * @since solr 1.4
 */
public class ConcurrentLRUCache<K, V>
{
    //private static Logger log = Logger.getLogger(ConcurrentLRUCache.class
    //        .getName());

    private final ConcurrentHashMapV8<Object, CacheEntry<K, V>> map;
    private final int upperWaterMark;
    private final int lowerWaterMark;
    private final ReentrantLock markAndSweepLock = new ReentrantLock(true);
    private boolean isCleaning = false; // not volatile... piggybacked on other volatile vars
    private final boolean newThreadForCleanup;
    private volatile boolean islive = true;
    private final Stats stats = new Stats();
    private final int acceptableWaterMark;
    private long oldestEntry = 0; // not volatile, only accessed in the cleaning method
    private final EvictionListener<K, V> evictionListener;
    private CleanupThread cleanupThread;

    private CacheEntry<K, V> NULL_ENTRY = new NullEntry();


    public ConcurrentLRUCache(int size, int lowerWatermark)
    {
        this(size, lowerWatermark, (int) Math
                .floor((lowerWatermark + size) / 2), (int) Math
                .ceil(0.75f * size), false, false, 16, null, null);
    }


    public ConcurrentLRUCache(int upperWaterMark, final int lowerWaterMark,
            int acceptableWatermark, int initialSize, boolean runCleanupThread,
            boolean runNewThreadForCleanup, int concurrencyLevel,
            EvictionListener<K, V> evictionListener, CleanupThread cleanupThread)
    {
        if (upperWaterMark < 1)
        {
            throw new IllegalArgumentException("upperWaterMark must be > 0");
        }
        if (lowerWaterMark >= upperWaterMark)
        {
            throw new IllegalArgumentException(
                    "lowerWaterMark must be  < upperWaterMark");
        }
        map = new ConcurrentHashMapV8<Object, CacheEntry<K, V>>(initialSize, 0.75f, concurrencyLevel);
        newThreadForCleanup = runNewThreadForCleanup;
        this.upperWaterMark = upperWaterMark;
        this.lowerWaterMark = lowerWaterMark;
        this.acceptableWaterMark = acceptableWatermark;
        this.evictionListener = evictionListener;
        if(cleanupThread != null) {
            this.cleanupThread = cleanupThread;
            cleanupThread.addConcurrentLRUCache(this);
            this.cleanupThread.start();
        } else if (runCleanupThread)
        {
            this.cleanupThread = new CleanupThread(this);
            this.cleanupThread.start();
        }
    }


    public void setAlive(boolean live)
    {
        islive = live;
    }

    public V get(K key)
    {
        CacheEntry<K, V> e = map.get(key);
        if (e == null || e == NULL_ENTRY)
        {
            if (islive)
            {
                stats.missCounter.increment();
            }
            return null;
        }
        if (islive)
        {
            stats.accessCounter.increment();
            e.lastAccessed = stats.accessCounter.longValue();
        }
        return e.value;
    }

    public V remove(K key)
    {
        CacheEntry<K, V> cacheEntry = map.remove(key);
        if (cacheEntry != null && cacheEntry != NULL_ENTRY)
        {
            stats.size.decrement();
            return cacheEntry.value;
        }
        return null;
    }

    public V put(K key, V val)
    {
        CacheEntry<K, V> e;
        if (val == null)
        {
            e = NULL_ENTRY;
        } else {
            stats.accessCounter.increment();
            e = new CacheEntry<K, V>(key, val,
                    stats.accessCounter.longValue());
        }

        CacheEntry<K, V> oldCacheEntry = map.put(key, e);
        int currentSize;
        if (oldCacheEntry == null || oldCacheEntry == NULL_ENTRY) {
            stats.size.increment();
            currentSize = stats.size.intValue();
        }
        else
        {
            currentSize = stats.size.intValue();
        }
        if (islive)
        {
            stats.putCounter.increment();
        }
        else
        {
            stats.nonLivePutCounter.increment();
        }

        // Check if we need to clear out old entries from the cache.
        // isCleaning variable is checked instead of markAndSweepLock.isLocked()
        // for performance because every put invokation will check until
        // the size is back to an acceptable level.
        //
        // There is a race between the check and the call to markAndSweep, but
        // it's unimportant because markAndSweep actually aquires the lock or returns if it can't.
        //
        // Thread safety note: isCleaning read is piggybacked (comes after) other volatile reads
        // in this method.
        if (currentSize > upperWaterMark && !isCleaning)
        {
            if (newThreadForCleanup)
            {
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        markAndSweep();
                    }
                }.start();
            }
            else if (cleanupThread != null)
            {
                cleanupThread.wakeThread(this);
            }
            else
            {
                markAndSweep();
            }
        }
        return oldCacheEntry == null ? null : oldCacheEntry.value;
    }


    public V putIfAbsent(K key, V val)
    {
        CacheEntry<K, V> e;
        if (val == null)
        {
            e = NULL_ENTRY;
        } else {
            stats.accessCounter.increment();
            e = new CacheEntry<K, V>(key, val,
                    stats.accessCounter.longValue());
        }

        CacheEntry<K, V> oldCacheEntry = map.putIfAbsent(key, e);
        int currentSize;
        if (oldCacheEntry == null || oldCacheEntry == NULL_ENTRY)
        {
            stats.size.increment();
            currentSize = stats.size.intValue();
        }
        else
        {
            currentSize = stats.size.intValue();
        }
        if (islive)
        {
            stats.putCounter.increment();
        }
        else
        {
            stats.nonLivePutCounter.increment();
        }

        // Check if we need to clear out old entries from the cache.
        // isCleaning variable is checked instead of markAndSweepLock.isLocked()
        // for performance because every put invokation will check until
        // the size is back to an acceptable level.
        //
        // There is a race between the check and the call to markAndSweep, but
        // it's unimportant because markAndSweep actually aquires the lock or returns if it can't.
        //
        // Thread safety note: isCleaning read is piggybacked (comes after) other volatile reads
        // in this method.
        if (currentSize > upperWaterMark && !isCleaning)
        {
            if (newThreadForCleanup)
            {
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        markAndSweep();
                    }
                }.start();
            }
            else if (cleanupThread != null)
            {
                cleanupThread.wakeThread(this);
            }
            else
            {
                markAndSweep();
            }
        }
        return oldCacheEntry == null ? null : oldCacheEntry.value;
    }



    public boolean replace(K key, V oldVal, V newVal)
    {
        CacheEntry<K, V> e;
        if(newVal == null) {
            e = NULL_ENTRY;
        } else {
            stats.accessCounter.increment();
            e = new CacheEntry<K, V>(key, newVal,
                    stats.accessCounter.longValue());
        }

        CacheEntry<K, V> eOld;
        if(oldVal == null) {
            eOld = NULL_ENTRY;
        } else {
            eOld = new CacheEntry<K, V>(key, oldVal,
                    stats.accessCounter.longValue());
        }

        CacheEntry<K, V> oldCacheEntry;
        if(map.replace(key, eOld, e)) {
            oldCacheEntry = eOld;
        } else {
            return false;
        }
        int currentSize;
        if (oldCacheEntry == null || oldCacheEntry == NULL_ENTRY)
        {
            stats.size.increment();
            currentSize = stats.size.intValue();
        }
        else
        {
            currentSize = stats.size.intValue();
        }
        if (islive)
        {
            stats.putCounter.increment();
        }
        else
        {
            stats.nonLivePutCounter.increment();
        }

        // Check if we need to clear out old entries from the cache.
        // isCleaning variable is checked instead of markAndSweepLock.isLocked()
        // for performance because every put invokation will check until
        // the size is back to an acceptable level.
        //
        // There is a race between the check and the call to markAndSweep, but
        // it's unimportant because markAndSweep actually aquires the lock or returns if it can't.
        //
        // Thread safety note: isCleaning read is piggybacked (comes after) other volatile reads
        // in this method.
        if (currentSize > upperWaterMark && !isCleaning)
        {
            if (newThreadForCleanup)
            {
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        markAndSweep();
                    }
                }.start();
            }
            else if (cleanupThread != null)
            {
                cleanupThread.wakeThread(this);
            }
            else
            {
                markAndSweep();
            }
        }
        return true;
    }



    /**
     * Removes items from the cache to bring the size down
     * to an acceptable value ('acceptableWaterMark').
     * <p/>
     * It is done in two stages. In the first stage, least recently used items are evicted.
     * If, after the first stage, the cache size is still greater than 'acceptableSize'
     * config parameter, the second stage takes over.
     * <p/>
     * The second stage is more intensive and tries to bring down the cache size
     * to the 'lowerWaterMark' config parameter.
     */
    public void markAndSweep()
    {
        // if we want to keep at least 1000 entries, then timestamps of
        // current through current-1000 are guaranteed not to be the oldest (but that does
        // not mean there are 1000 entries in that group... it's acutally anywhere between
        // 1 and 1000).
        // Also, if we want to remove 500 entries, then
        // oldestEntry through oldestEntry+500 are guaranteed to be
        // removed (however many there are there).

        if (!markAndSweepLock.tryLock())
        {
            return;
        }
        try
        {
            long oldestEntry = this.oldestEntry;
            isCleaning = true;
            this.oldestEntry = oldestEntry; // volatile write to make isCleaning visible

            long timeCurrent = stats.accessCounter.longValue();
            int sz = stats.size.intValue();

            int numRemoved = 0;
            int numKept = 0;
            long newestEntry = timeCurrent;
            long newNewestEntry = -1;
            long newOldestEntry = Long.MAX_VALUE;

            int wantToKeep = lowerWaterMark;
            int wantToRemove = sz - lowerWaterMark;

            @SuppressWarnings("unchecked")
            // generic array's are anoying
            CacheEntry<K, V>[] eset = new CacheEntry[sz];
            int eSize = 0;

            // System.out.println("newestEntry="+newestEntry + " oldestEntry="+oldestEntry);
            // System.out.println("items removed:" + numRemoved + " numKept=" + numKept +
            //    " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));

            for (CacheEntry<K, V> ce : map.values())
            {
                // set lastAccessedCopy to avoid more volatile reads
                ce.lastAccessedCopy = ce.lastAccessed;
                long thisEntry = ce.lastAccessedCopy;

                // since the wantToKeep group is likely to be bigger than wantToRemove, check it first
                if (thisEntry > newestEntry - wantToKeep)
                {
                    // this entry is guaranteed not to be in the bottom
                    // group, so do nothing.
                    numKept++;
                    newOldestEntry = Math.min(thisEntry, newOldestEntry);
                }
                else if (thisEntry < oldestEntry + wantToRemove)
                { // entry in bottom group?
                    // this entry is guaranteed to be in the bottom group
                    // so immediately remove it from the map.
                    evictEntry(ce.key);
                    numRemoved++;
                }
                else
                {
                    // This entry *could* be in the bottom group.
                    // Collect these entries to avoid another full pass... this is wasted
                    // effort if enough entries are normally removed in this first pass.
                    // An alternate impl could make a full second pass.
                    if (eSize < eset.length - 1)
                    {
                        eset[eSize++] = ce;
                        newNewestEntry = Math.max(thisEntry, newNewestEntry);
                        newOldestEntry = Math.min(thisEntry, newOldestEntry);
                    }
                }
            }

            // System.out.println("items removed:" + numRemoved + " numKept=" + numKept +
            //    " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));
            // TODO: allow this to be customized in the constructor?
            int numPasses = 1; // maximum number of linear passes over the data

            // if we didn't remove enough entries, then make more passes
            // over the values we collected, with updated min and max values.
            while (sz - numRemoved > acceptableWaterMark && --numPasses >= 0)
            {

                oldestEntry = newOldestEntry == Long.MAX_VALUE ? oldestEntry
                        : newOldestEntry;
                newOldestEntry = Long.MAX_VALUE;
                newestEntry = newNewestEntry;
                newNewestEntry = -1;
                wantToKeep = lowerWaterMark - numKept;
                wantToRemove = sz - lowerWaterMark - numRemoved;

                // iterate backward to make it easy to remove items.
                for (int i = eSize - 1; i >= 0; i--)
                {
                    CacheEntry<K, V> ce = eset[i];
                    long thisEntry = ce.lastAccessedCopy;

                    if (thisEntry > newestEntry - wantToKeep)
                    {
                        // this entry is guaranteed not to be in the bottom
                        // group, so do nothing but remove it from the eset.
                        numKept++;
                        // remove the entry by moving the last element to it's position
                        eset[i] = eset[eSize - 1];
                        eSize--;

                        newOldestEntry = Math.min(thisEntry, newOldestEntry);

                    }
                    else if (thisEntry < oldestEntry + wantToRemove)
                    { // entry in bottom group?

                        // this entry is guaranteed to be in the bottom group
                        // so immediately remove it from the map.
                        evictEntry(ce.key);
                        numRemoved++;

                        // remove the entry by moving the last element to it's position
                        eset[i] = eset[eSize - 1];
                        eSize--;
                    }
                    else
                    {
                        // This entry *could* be in the bottom group, so keep it in the eset,
                        // and update the stats.
                        newNewestEntry = Math.max(thisEntry, newNewestEntry);
                        newOldestEntry = Math.min(thisEntry, newOldestEntry);
                    }
                }
                // System.out.println("items removed:" + numRemoved + " numKept=" +
                //    numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));
            }

            // if we still didn't remove enough entries, then make another pass while
            // inserting into a priority queue
            if (sz - numRemoved > acceptableWaterMark)
            {

                oldestEntry = newOldestEntry == Long.MAX_VALUE ? oldestEntry
                        : newOldestEntry;
                newOldestEntry = Long.MAX_VALUE;
                newestEntry = newNewestEntry;
                newNewestEntry = -1;
                wantToKeep = lowerWaterMark - numKept;
                wantToRemove = sz - lowerWaterMark - numRemoved;

                PQueue<K, V> queue = new PQueue<K, V>(wantToRemove);

                for (int i = eSize - 1; i >= 0; i--)
                {
                    CacheEntry<K, V> ce = eset[i];
                    long thisEntry = ce.lastAccessedCopy;

                    if (thisEntry > newestEntry - wantToKeep)
                    {
                        // this entry is guaranteed not to be in the bottom
                        // group, so do nothing but remove it from the eset.
                        numKept++;
                        // removal not necessary on last pass.
                        // eset[i] = eset[eSize-1];
                        // eSize--;

                        newOldestEntry = Math.min(thisEntry, newOldestEntry);

                    }
                    else if (thisEntry < oldestEntry + wantToRemove)
                    { // entry in bottom group?
                        // this entry is guaranteed to be in the bottom group
                        // so immediately remove it.
                        evictEntry(ce.key);
                        numRemoved++;

                        // removal not necessary on last pass.
                        // eset[i] = eset[eSize-1];
                        // eSize--;
                    }
                    else
                    {
                        // This entry *could* be in the bottom group.
                        // add it to the priority queue

                        // everything in the priority queue will be removed, so keep track of
                        // the lowest value that ever comes back out of the queue.

                        // first reduce the size of the priority queue to account for
                        // the number of items we have already removed while executing
                        // this loop so far.
                        queue.myMaxSize = sz - lowerWaterMark - numRemoved;
                        while (queue.size() > queue.myMaxSize
                                && queue.size() > 0)
                        {
                            CacheEntry otherEntry = (CacheEntry) queue.pop();
                            newOldestEntry = Math
                                    .min(otherEntry.lastAccessedCopy,
                                            newOldestEntry);
                        }
                        if (queue.myMaxSize <= 0)
                        {
                            break;
                        }

                        Object o = queue.myInsertWithOverflow(ce);
                        if (o != null)
                        {
                            newOldestEntry = Math.min(
                                    ((CacheEntry) o).lastAccessedCopy,
                                    newOldestEntry);
                        }
                    }
                }

                // Now delete everything in the priority queue.
                // avoid using pop() since order doesn't matter anymore
                for (CacheEntry<K, V> ce : queue.getValues())
                {
                    if (ce == null)
                    {
                        continue;
                    }
                    evictEntry(ce.key);
                    numRemoved++;
                }

                // System.out.println("items removed:" + numRemoved + " numKept=" + numKept +
                //    " initialQueueSize="+ wantToRemove + " finalQueueSize=" +
                //      queue.size() + " sz-numRemoved=" + (sz-numRemoved));
            }

            oldestEntry = newOldestEntry == Long.MAX_VALUE ? oldestEntry
                    : newOldestEntry;
            this.oldestEntry = oldestEntry;
        }
        finally
        {
            isCleaning = false; // set before markAndSweep.unlock() for visibility
            markAndSweepLock.unlock();
        }
    }

    private static class PQueue<K, V> extends PriorityQueue<CacheEntry<K, V>>
    {
        int myMaxSize;
        final Object[] heap;

        PQueue(int maxSz)
        {
            super(maxSz);
            heap = getHeapArray();
            myMaxSize = maxSz;
        }

        @SuppressWarnings("unchecked")
        Iterable<CacheEntry<K, V>> getValues()
        {
            return (Iterable) Collections.unmodifiableCollection(Arrays
                    .asList(heap));
        }

        @Override
        protected boolean lessThan(CacheEntry a, CacheEntry b)
        {
            // reverse the parameter order so that the queue keeps the oldest items
            return b.lastAccessedCopy < a.lastAccessedCopy;
        }

        // necessary because maxSize is private in base class
        @SuppressWarnings("unchecked")
        public CacheEntry<K, V> myInsertWithOverflow(CacheEntry<K, V> element)
        {
            if (size() < myMaxSize)
            {
                add(element);
                return null;
            }
            else if (size() > 0
                    && !lessThan(element, (CacheEntry<K, V>) heap[1]))
            {
                CacheEntry<K, V> ret = (CacheEntry<K, V>) heap[1];
                heap[1] = element;
                updateTop();
                return ret;
            }
            else
            {
                return element;
            }
        }
    }

    private void evictEntry(K key)
    {
        CacheEntry<K, V> o = map.get(key);
        if (o == null)
        {
            return;
        }
        if (evictionListener != null)
        {
            evictionListener.evictedEntry(o.key, o.value);
        }
        map.remove(key);
        stats.size.decrement();
        stats.evictionCounter.increment();
    }

    /**
     * Returns 'n' number of oldest accessed entries present in this cache.
     *
     * This uses a TreeSet to collect the 'n' oldest items ordered by ascending last access time
     *  and returns a LinkedHashMap containing 'n' or less than 'n' entries.
     * @param n the number of oldest items needed
     * @return a LinkedHashMap containing 'n' or less than 'n' entries
     */
    public Map<K, V> getOldestAccessedItems(int n)
    {
        Map<K, V> result = new LinkedHashMap<K, V>();
        if (n <= 0)
        {
            return result;
        }
        TreeSet<CacheEntry<K, V>> tree = new TreeSet<CacheEntry<K, V>>();
        markAndSweepLock.lock();
        try
        {
            for (Map.Entry<Object, CacheEntry<K, V>> entry : map.entrySet())
            {
                CacheEntry<K, V> ce = entry.getValue();
                ce.lastAccessedCopy = ce.lastAccessed;
                if (tree.size() < n)
                {
                    tree.add(ce);
                }
                else
                {
                    if (ce.lastAccessedCopy < tree.first().lastAccessedCopy)
                    {
                        tree.remove(tree.first());
                        tree.add(ce);
                    }
                }
            }
        }
        finally
        {
            markAndSweepLock.unlock();
        }
        for (CacheEntry<K, V> e : tree)
        {
            result.put(e.key, e.value);
        }
        return result;
    }

    public Map<K, V> getLatestAccessedItems(int n)
    {
        Map<K, V> result = new LinkedHashMap<K, V>();
        if (n <= 0)
        {
            return result;
        }
        TreeSet<CacheEntry<K, V>> tree = new TreeSet<CacheEntry<K, V>>();
        // we need to grab the lock since we are changing lastAccessedCopy
        markAndSweepLock.lock();
        try
        {
            for (Map.Entry<Object, CacheEntry<K, V>> entry : map.entrySet())
            {
                CacheEntry<K, V> ce = entry.getValue();
                ce.lastAccessedCopy = ce.lastAccessed;
                if (tree.size() < n)
                {
                    tree.add(ce);
                }
                else
                {
                    if (ce.lastAccessedCopy > tree.last().lastAccessedCopy)
                    {
                        tree.remove(tree.last());
                        tree.add(ce);
                    }
                }
            }
        }
        finally
        {
            markAndSweepLock.unlock();
        }
        for (CacheEntry<K, V> e : tree)
        {
            result.put(e.key, e.value);
        }
        return result;
    }

    public int size()
    {
        return stats.size.intValue();
    }

    public void clear()
    {
        map.clear();
    }

    public Map<Object, CacheEntry<K, V>> getMap()
    {
        return map;
    }

    private static class CacheEntry<K, V> implements
            Comparable<CacheEntry<K, V>>
    {
        K key;
        V value;
        volatile long lastAccessed = 0;
        long lastAccessedCopy = 0;

        private CacheEntry(){}

        public CacheEntry(K key, V value, long lastAccessed)
        {
            this.key = key;
            this.value = value;
            this.lastAccessed = lastAccessed;
        }

        public void setLastAccessed(long lastAccessed)
        {
            this.lastAccessed = lastAccessed;
        }

        public int compareTo(CacheEntry<K, V> that)
        {
            if (this.lastAccessedCopy == that.lastAccessedCopy)
            {
                return 0;
            }
            return -1;
        }

        @Override
        public int hashCode()
        {
            return value.hashCode();
        }

        @SuppressWarnings("unchecked")
		@Override
        public boolean equals(Object obj)
        {
        	if (obj == this) {
        		return true;
        	}
        	if (obj == null) {
        		return false;
        	}
        	if (!(obj instanceof CacheEntry)) {
        		return false;
        	}
        	CacheEntry<K, V> entry = (CacheEntry<K, V>) obj;
            return value.equals(entry.value);
        }

        @Override
        public String toString()
        {
            return "key: " + key + " value: " + value + " lastAccessed:"
                    + lastAccessed;
        }
    }


    private static class NullEntry extends CacheEntry{
        Object key;

        public NullEntry()
        {
            this.key = new Object();
        }

        public int compareTo(NullEntry that)
        {
            if (that == null || that == this) {
                return 0;
            }
            return -1;
        }

        @Override
        public int hashCode()
        {
            return 0;
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj == null || obj == this;
        }

        @Override
        public String toString()
        {
            return "key: " + key + " value: " + null + " lastAccessed:"
                    + -1;
        }
    }

    private boolean isDestroyed = false;

    public void destroy()
    {
        try
        {
            if (cleanupThread != null)
            {
                cleanupThread.stopThread(this);
            }
        }
        finally
        {
            isDestroyed = true;
        }
    }

    public Stats getStats()
    {
        return stats;
    }

    public static class Stats
    {
        private final LongAdder accessCounter = new LongAdder();
        private final LongAdder putCounter = new LongAdder();
        private final LongAdder nonLivePutCounter = new LongAdder();
        private final LongAdder missCounter = new LongAdder();
        private final LongAdder size = new LongAdder();// int
        private LongAdder evictionCounter = new LongAdder();

        public long getCumulativeLookups()
        {
            return (accessCounter.longValue() - putCounter.longValue() - nonLivePutCounter
                    .longValue()) + missCounter.longValue();
        }

        public long getCumulativeHits()
        {
            return accessCounter.longValue() - putCounter.longValue()
                    - nonLivePutCounter.longValue();
        }

        public long getCumulativePuts()
        {
            return putCounter.longValue();
        }

        public long getCumulativeEvictions()
        {
            return evictionCounter.longValue();
        }

        public int getCurrentSize()
        {
            return size.intValue();
        }

        public long getCumulativeNonLivePuts()
        {
            return nonLivePutCounter.longValue();
        }

        public long getCumulativeMisses()
        {
            return missCounter.longValue();
        }

        public void add(Stats other)
        {
            accessCounter.add(other.accessCounter.longValue());
            putCounter.add(other.putCounter.longValue());
            nonLivePutCounter.add(other.nonLivePutCounter.longValue());
            missCounter.add(other.missCounter.longValue());
            evictionCounter.add(other.evictionCounter.longValue());
            size.set(Math.max(size.intValue(), other.size.intValue()));
        }
    }

    public static interface EvictionListener<K, V>
    {
        public void evictedEntry(K key, V value);
    }

    @Override
    protected void finalize() throws Throwable
    {
        try
        {
            if (!isDestroyed)
            {
                // This log message is useless, because it is not supposed to use
                // thread cleanup strategy for this class.
                //log.severe("ConcurrentLRUCache was not destroyed prior to finalize()," +
                //        " indicates a bug -- POSSIBLE RESOURCE LEAK!!!");
                destroy();
            }
        }
        finally
        {
            super.finalize();
        }
    }
}
