/*
 * Bitronix Transaction Manager
 *
 * Copyright (c) 2010, Bitronix Software.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA 02110-1301 USA
 */
package bitronix.tm.resource.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Last Recently Used PreparedStatement cache with eviction listeners
 * support implementation.
 *
 *
 * @author lorban, brettw
 */
public class LruStatementCache {

    private final static Logger log = LoggerFactory.getLogger(LruStatementCache.class);

    /**
     * The <i>target</i> maxSize of the cache.  The cache may drift slightly
     * higher in size in the case that every statement in the cache is 
     * in use and therefore nothing can be evicted.  But eventually
     * (probably quickly) the cache will return to maxSize.
     */
    private int maxSize;

    /**
     * We use a LinkedHashMap with _access order_ specified in the
     * constructor.  According to the LinkedHashMap documentation:
     * <pre>
     *   A special constructor is provided to create a linked hash map
     *   whose order of iteration is the order in which its entries
     *   were last accessed, from least-recently accessed to most-recently
     *   (access-order). This kind of map is well-suited to building LRU
     *   caches. Invoking the put or get method results in an access to
     *   the corresponding entry (assuming it exists after the invocation
     *   completes).
     * </pre>
     */
    private final LinkedHashMap<CacheKey, StatementTracker> cache;

    /**
     * A list of listeners concerned with prepared statement cache
     * evictions.
     */
    private final List<LruEvictionListener> evictionListners;

    /**
     * See the LinkedHashMap documentation.  We maintain our own size
     * here, rather than calling size(), because size() on a LinkedHashMap
     * is proportional in time (O(n)) with the size of the collection -- i.e.
     * calling size() must traverse the entire list and count the elements.
     * Tracking size ourselves provides O(1) access.
     */
    private int size;

    public LruStatementCache(int maxSize) {
        this.maxSize = maxSize;
        cache = new LinkedHashMap<CacheKey, StatementTracker>(maxSize, 0.75f, true /* access order */);
        evictionListners = new CopyOnWriteArrayList<LruEvictionListener>();
    }

    /**
     * The provided key is just a 'shell' JdbcPreparedStatementHandle, it comes
     * in with no actual 'delegate' PreparedStatement.  However, it contains all
     * other pertinent information such as SQL statement, autogeneratedkeys
     * flag, cursor holdability, etc.  See the equals() method in the
     * JdbcPreparedStatementHandle class.  It is a complete key for a cached
     * statement.
     *
     * If there is a matching cached PreparedStatement, it will be set as the
     * delegate in the provided JdbcPreparedStatementHandle.
     *
     * @param key the cache key
     * @return the cached JdbcPreparedStatementHandle statement, or null
     */
    public PreparedStatement get(CacheKey key) {
    	synchronized (cache) {
            // See LinkedHashMap documentation.  Getting an entry means it is
	        // updated as the 'youngest' (Most Recently Used) entry.
	        StatementTracker cached = cache.get(key);
	        if (cached != null) {
	            cached.usageCount++;
	            if (log.isDebugEnabled()) log.debug("delivered from cache with usage count " + cached.usageCount + " statement <" + key + ">");
	            return cached.statement;
	        }

	        return null;
    	}
    }

    /**
     * A statement is put into the cache.  This is called when a
     * statement is first prepared and also when a statement is
     * closed (by the client).  A "closed" statement has it's
     * usage counter decremented in the cache.
     *
     * @param key a prepared statement handle
     * @return a prepared statement
     */
    public PreparedStatement put(CacheKey key, PreparedStatement statement) {
    	synchronized (cache) {
            if (maxSize < 1) {
	            return null;
	        }

	        // See LinkedHashMap documentation.  Getting an entry means it is
	        // updated as the 'youngest' (Most Recently Used) entry.
	        StatementTracker cached = cache.get(key);
	        if (cached == null) {
	            if (log.isDebugEnabled()) log.debug("adding to cache statement <" + key + ">");
	            cache.put(key, new StatementTracker(statement));
	            size++;
	        } else {
	            cached.usageCount--;
	            statement = cached.statement;
	            if (log.isDebugEnabled()) log.debug("returning to cache statement <" + key + "> with usage count " + cached.usageCount);
	        }

	        // If the size is exceeded, we will _try_ to evict one (or more)
	        // statements until the max level is again reached.  However, if
	        // every statement in the cache is 'in use', the size of the cache
	        // is not reduced.  Eventually the cache will be reduced, no worries.
	        if (size > maxSize) {
	            tryEviction();
	        }

	        return statement;
    	}
    }

    /**
     * Evict all statements from the cache.  This likely happens on
     * connection close.
     */
    protected void clear() {
    	synchronized (cache) {
	        Iterator<Entry<CacheKey, StatementTracker>> it = cache.entrySet().iterator();
	        while (it.hasNext()) {
	            Entry<CacheKey, StatementTracker> entry = it.next();
	            StatementTracker tracker = entry.getValue();
	            it.remove();
	            fireEvictionEvent(tracker.statement);
	        }
	        cache.clear();
	        size = 0;
    	}
    }

    /**
     * Try to evict statements from the cache.  Only statements with a
     * current usage count of zero will be evicted.  Statements are
     * evicted until the cache is reduced to maxSize.
     */
    private void tryEviction() {
        // Iteration order of the LinkedHashMap is from LRU to MRU
    	Iterator<Entry<CacheKey, StatementTracker>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
        	Entry<CacheKey, StatementTracker> entry = it.next();
            StatementTracker tracker = entry.getValue();
            if (tracker.usageCount == 0) {
                it.remove();
                size--;
                CacheKey key = entry.getKey();
                if (log.isDebugEnabled()) { log.debug("evicting from cache statement <" + key + "> " + entry.getValue().statement); }
                fireEvictionEvent(tracker.statement);
                // We can stop evicting if we're at maxSize...
                if (size <= maxSize) {
                    break;
                }
            }
        }
    }

    private void fireEvictionEvent(Object value) {
        for (LruEvictionListener listener : evictionListners) {
            listener.onEviction(value);
        }
    }

    public void addEvictionListener(LruEvictionListener listener) {
        evictionListners.add(listener);
    }

    public void removeEvictionListener(LruEvictionListener listener) {
        evictionListners.remove(listener);
    }

    public static final class CacheKey {
        // All of these attributes must match a proposed statement before the
        // statement can be considered "the same" and delivered from the cache.
        private String sql;
        private int resultSetType = ResultSet.TYPE_FORWARD_ONLY;
        private int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY;
        private Integer resultSetHoldability;
        private Integer autoGeneratedKeys;
        private int[] columnIndexes;
        private String[] columnNames;

        public CacheKey(String sql) {
            this.sql = sql;
        }

        public CacheKey(String sql, int autoGeneratedKeys) {
            this.sql = sql;
            this.autoGeneratedKeys = new Integer(autoGeneratedKeys);
        }

        public CacheKey(String sql, int resultSetType, int resultSetConcurrency) {
            this.sql = sql;
            this.resultSetType = resultSetType;
            this.resultSetConcurrency = resultSetConcurrency;
        }

        public CacheKey(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) {
            this.sql = sql;
            this.resultSetType = resultSetType;
            this.resultSetConcurrency = resultSetConcurrency;
            this.resultSetHoldability = new Integer(resultSetHoldability);
        }

        public CacheKey(String sql, int[] columnIndexes) {
            this.sql = sql;
            this.columnIndexes = new int[columnIndexes.length];
            System.arraycopy(columnIndexes, 0, this.columnIndexes, 0, columnIndexes.length);
        }

        public CacheKey(String sql, String[] columnNames) {
            this.sql = sql;
            this.columnNames = new String[columnNames.length];
            System.arraycopy(columnNames, 0, this.columnNames, 0, columnNames.length);
        }

        /**
         * Overridden equals() that takes all PreparedStatement attributes into
         * account.
         */
        public boolean equals(Object obj) {
            if (!(obj instanceof CacheKey)) {
                return false;
            }

            CacheKey otherKey = (CacheKey) obj;
            if (!sql.equals(otherKey.sql)) {
                return false;
            } else if (resultSetType != otherKey.resultSetType) {
                return false;
            } else if (resultSetConcurrency != otherKey.resultSetConcurrency) {
                return false;
            } else if (!Arrays.equals(columnIndexes, otherKey.columnIndexes)) {
                return false;
            } else if (!Arrays.equals(columnNames, otherKey.columnNames)) {
                return false;
            } else if ((autoGeneratedKeys == null && otherKey.autoGeneratedKeys != null) ||
                    (autoGeneratedKeys != null && !autoGeneratedKeys.equals(otherKey.autoGeneratedKeys))) {
                return false;
            } else if ((resultSetHoldability == null && otherKey.resultSetHoldability != null) ||
                    (resultSetHoldability != null && !resultSetHoldability.equals(otherKey.resultSetHoldability))) {
                return false;
            }

            return true;
        }

        public int hashCode() {
            return sql != null ? sql.hashCode() : System.identityHashCode(this);
        }
    }

    private static final class StatementTracker {
        private final PreparedStatement statement;
        private int usageCount;

        private StatementTracker(PreparedStatement stmt) {
            this.statement = stmt;
            this.usageCount = 1;
        }
    }
}
