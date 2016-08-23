/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.cache;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import android.os.SystemClock;

import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.Supplier;
import com.facebook.common.internal.VisibleForTesting;
import com.facebook.common.memory.MemoryTrimType;
import com.facebook.common.memory.MemoryTrimmable;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.references.ResourceReleaser;

import com.android.internal.util.Predicate;

/**
 * Layer of memory cache stack responsible for managing eviction of the the cached items.
 *
 * 此类中有一个mCachedEntries,其类型为{@link CountingLruMap},用来存放所有的键值对
 * 还有一个mExclusiveEntries，类型同上，用来专门存放没有被外界引用的键值对，也就是此cache独立拥有的entry
 * 主要就是一些对cache的策略
 *
 * <p> This layer is responsible for LRU eviction strategy and for maintaining the size boundaries
 * of the cached items.
 *
 * <p> Only the exclusively owned elements, i.e. the elements not referenced by any client, can be
 * evicted.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
@ThreadSafe
public class CountingMemoryCache<K, V> implements MemoryCache<K, V>, MemoryTrimmable {

  /**
   * Interface used to specify the trimming strategy for the cache.
   * 根据当前的memoryTirmType，也就是cache大小的状态
   * 来决定trim的力度
   */
  public interface CacheTrimStrategy {
    double getTrimRatio(MemoryTrimType trimType);
  }

  /**
   * Interface used to observe the state changes of an entry.
   */
  public interface EntryStateObserver<K> {

    /**
     * Called when the exclusivity status of the entry changes.
     *
     * <p> The item can be reused if it is exclusively owned by the cache.
     */
    void onExclusivityChanged(K key, boolean isExclusive);
  }

  /**
   * The internal representation of a key-value pair stored by the cache.
   *
   * 缓存内部键值对的表现形式
   * 这种设计在HashMap中也能看到
   */
  @VisibleForTesting
  static class Entry<K, V> {
    public final K key;
    public final CloseableReference<V> valueRef;
    // The number of clients that reference the value.
    public int clientCount; // 用来记录外部对value有多少个引用

    // Whether or not this entry is tracked by this cache. Orphans are not tracked by the cache and
    // as soon as the last client of an orphaned entry closes their reference, the entry's copy is
    // closed too.
    /**
     * 在一个entry实例不被cache统一管理的时候，就被标记为orphans孤儿了
     * 孤立的entry在外界引用都close的时候，也会关闭自己内部的closableReference
     */
    public boolean isOrphan;

    /**
     * 在上面定义过的回调接口
     */
    @Nullable public final EntryStateObserver<K> observer;

    private Entry(K key, CloseableReference<V> valueRef, @Nullable EntryStateObserver<K> observer) {
      this.key = Preconditions.checkNotNull(key);
      this.valueRef = Preconditions.checkNotNull(CloseableReference.cloneOrNull(valueRef));
      this.clientCount = 0;
      this.isOrphan = false;
      this.observer = observer;
    }

    /**
     * Creates a new entry with the usage count of 0.
     *
     * 构造函数被私有了
     * 提供一个静态的of函数对外
     *
     */
    @VisibleForTesting
    static <K, V> Entry<K, V> of(
        final K key,
        final CloseableReference<V> valueRef,
        final @Nullable EntryStateObserver<K> observer) {
      return new Entry<>(key, valueRef, observer);
    }
  }

  // How often the cache checks for a new cache configuration.
  /**
   * 默认五分钟检查一次是否有新的configuration设置
   */
  @VisibleForTesting
  static final long PARAMS_INTERCHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);

  // Contains the items that are not being used by any client and are hence viable for eviction.
  /**
   * 一个用来管理没有被任何外部类使用的entry的工具类
   * 里面的entry是准备被回收的
   *
   * CountingLruMap内部依靠一个LinkedHashMap来作具体的存储工作
   * 主要进行了一些统计占用存储空间的工作
   */
  @GuardedBy("this")
  @VisibleForTesting
  final CountingLruMap<K, Entry<K, V>> mExclusiveEntries;

  // Contains all the cached items including the exclusively owned ones.
  /**
   * 和上面采用的统一款数据结构
   * 用来存放被cached的元素，包括上面的那群exclusively的元素
   */
  @GuardedBy("this")
  @VisibleForTesting
  final CountingLruMap<K, Entry<K, V>> mCachedEntries;

  /**
   * 用来统计V实例对象大小的工具接口
   */
  private final ValueDescriptor<V> mValueDescriptor;

  /**
   * 一个根据传入的{@link MemoryTrimType}来返回double数据的接口
   * 暂时不清楚有什么用
   *
   */
  private final CacheTrimStrategy mCacheTrimStrategy;

  // Cache size constraints.
  private final Supplier<MemoryCacheParams> mMemoryCacheParamsSupplier;
  @GuardedBy("this")
  protected MemoryCacheParams mMemoryCacheParams;
  @GuardedBy("this")
  private long mLastCacheParamsCheck; // 用来记录上次检测configuration是否改动的时间点，和上面的PARAMS_INTERCHECK_INTERVAL_MS常量结合起来使用

  public CountingMemoryCache(
      ValueDescriptor<V> valueDescriptor,
      CacheTrimStrategy cacheTrimStrategy,
      Supplier<MemoryCacheParams> memoryCacheParamsSupplier) {
    mValueDescriptor = valueDescriptor;
    mExclusiveEntries = new CountingLruMap<>(wrapValueDescriptor(valueDescriptor));
    mCachedEntries = new CountingLruMap<>(wrapValueDescriptor(valueDescriptor));
    mCacheTrimStrategy = cacheTrimStrategy;
    mMemoryCacheParamsSupplier = memoryCacheParamsSupplier;
    mMemoryCacheParams = mMemoryCacheParamsSupplier.get();
    mLastCacheParamsCheck = SystemClock.uptimeMillis();
  }

  /**
   * 在ValueDescription<V>外面包了一层
   * 对ValueDescriptor<Entry<K, V>>进行处理，实际上结果并没有差异
   *
   * @param evictableValueDescriptor
   * @return
     */
  private ValueDescriptor<Entry<K, V>> wrapValueDescriptor(
      final ValueDescriptor<V> evictableValueDescriptor) {
    return new ValueDescriptor<Entry<K,V>>() {
      @Override
      public int getSizeInBytes(Entry<K, V> entry) {
        return evictableValueDescriptor.getSizeInBytes(entry.valueRef.get());
      }
    };
  }

  /**
   * Caches the given key-value pair.
   * 将一对键值对缓存起来，并返回值的ClosableReference引用
   *
   * <p> Important: the client should use the returned reference instead of the original one.
   * It is the caller's responsibility to close the returned reference once not needed anymore.
   *
   * 比较重要的一点是，使用者应该使用返回的那个closableReference,而不是引用本身
   * 调用者应该在不需要的时候关掉持有的closableReference引用
   *
   * @return the new reference to be used, null if the value cannot be cached
   */
  public CloseableReference<V> cache(final K key, final CloseableReference<V> valueRef) {
    return cache(key, valueRef, null);
  }

  /**
   * Caches the given key-value pair.
   *
   * <p> Important: the client should use the returned reference instead of the original one.
   * It is the caller's responsibility to close the returned reference once not needed anymore.
   *
   * @return the new reference to be used, null if the value cannot be cached
   */
  public CloseableReference<V> cache(
      final K key,
      final CloseableReference<V> valueRef,
      final EntryStateObserver<K> observer) {
    Preconditions.checkNotNull(key);
    Preconditions.checkNotNull(valueRef);

    /**
     * 检查当前cache的一些参数是否发生了改变
     * 利用上面的两个变量
     */
    maybeUpdateCacheParams();

    Entry<K, V> oldExclusive;
    CloseableReference<V> oldRefToClose = null;
    CloseableReference<V> clientRef = null; // 即将返回给调用端的closableReference引用
    synchronized (this) {
      // remove the old item (if any) as it is stale now
      oldExclusive = mExclusiveEntries.remove(key);
      Entry<K, V> oldEntry = mCachedEntries.remove(key);

      if (oldEntry != null) {
        /**
         * 如果在mCachedEntries里存在对应K的entry
         * 则标记为orphan，并判定里面的计数器是否为0了
         * 如果为0则赋值oldRefToClose，在下面进行closeSafely操作
         */
        makeOrphan(oldEntry);
        oldRefToClose = referenceToClose(oldEntry);
      }

      if (canCacheNewValue(valueRef.get())) {
        /**
         * 还存在多余的空间
         * 能够对当前的键值对进行存储
         */
        Entry<K, V> newEntry = Entry.of(key, valueRef, observer);
        mCachedEntries.put(key, newEntry);
        clientRef = newClientReference(newEntry);
      }
    }
    // 将从mCachedEntries取出的引用关闭
    CloseableReference.closeSafely(oldRefToClose);

    // 通知回调接口，item被从mExclusiveEntries中移除了
    maybeNotifyExclusiveEntryRemoval(oldExclusive);

    //
    maybeEvictEntries();
    return clientRef;
  }

  /**
   *  Checks the cache constraints to determine whether the new value can be cached or not.
   *
   * 检查cache的大小，是否达到了memoryscaheparam规定的限制
   * 以此来决定能否缓存这个v实例
   */
  private synchronized boolean canCacheNewValue(V value) {
    int newValueSize = mValueDescriptor.getSizeInBytes(value);
    return (newValueSize <= mMemoryCacheParams.maxCacheEntrySize) &&
        (getInUseCount() <= mMemoryCacheParams.maxCacheEntries - 1) &&
        (getInUseSizeInBytes() <= mMemoryCacheParams.maxCacheSize - newValueSize);
  }

  /**
   * Gets the item with the given key, or null if there is no such item.
   * 从cache中取出对应key的引用
   *
   * <p> It is the caller's responsibility to close the returned reference once not needed anymore.
   */
  @Nullable
  public CloseableReference<V> get(final K key) {
    Preconditions.checkNotNull(key);
    Entry<K, V> oldExclusive;
    CloseableReference<V> clientRef = null;
    synchronized (this) {
      oldExclusive = mExclusiveEntries.remove(key);
      Entry<K, V> entry = mCachedEntries.get(key);
      if (entry != null) {
        clientRef = newClientReference(entry);
      }
    }
    maybeNotifyExclusiveEntryRemoval(oldExclusive);
    maybeUpdateCacheParams();
    maybeEvictEntries();
    return clientRef;
  }

  /** Creates a new reference for the client. */
  private synchronized CloseableReference<V> newClientReference(final Entry<K, V> entry) {
    increaseClientCount(entry);
    return CloseableReference.of(
        entry.valueRef.get(),
        new ResourceReleaser<V>() {
          @Override
          public void release(V unused) {
            releaseClientReference(entry);
          }
        });
  }

  /**
   * Called when the client closes its reference.
   * 在reference被关闭的时候会被回调
   * 对entry里面的count进行计计数
   */
  private void releaseClientReference(final Entry<K, V> entry) {
    Preconditions.checkNotNull(entry);
    boolean isExclusiveAdded;
    CloseableReference<V> oldRefToClose;
    synchronized (this) {
      decreaseClientCount(entry);
      /**
       * 在每次close的时候都会检测当前的entry是否能算作是exclusivity的
       * 在满足条件的情况下会加入到mExclusiveEntries里面去
       */
      isExclusiveAdded = maybeAddToExclusives(entry);
      oldRefToClose = referenceToClose(entry);
    }
    CloseableReference.closeSafely(oldRefToClose);
    maybeNotifyExclusiveEntryInsertion(isExclusiveAdded ? entry : null);
    maybeUpdateCacheParams();
    maybeEvictEntries();
  }

  /** Adds the entry to the exclusively owned queue if it is viable for eviction. */
  private synchronized boolean maybeAddToExclusives(Entry<K, V> entry) {
    if (!entry.isOrphan && entry.clientCount == 0) {
      mExclusiveEntries.put(entry.key, entry);
      return true;
    }
    return false;
  }

  /**
   * Gets the value with the given key to be reused, or null if there is no such value.
   * reuse一个value，仅仅当当前的这个value是被cache独立拥有的，也就是没有外部的引用指向
   * 则满足被重用的条件
   *
   * <p> The item can be reused only if it is exclusively owned by the cache.
   */
  @Nullable
  public CloseableReference<V> reuse(K key) {
    Preconditions.checkNotNull(key);
    CloseableReference<V> clientRef = null;
    boolean removed = false;
    Entry<K, V> oldExclusive = null;
    synchronized (this) {
      oldExclusive = mExclusiveEntries.remove(key);
      if (oldExclusive != null) {
        Entry<K, V> entry = mCachedEntries.remove(key);
        Preconditions.checkNotNull(entry);
        Preconditions.checkState(entry.clientCount == 0);
        // optimization: instead of cloning and then closing the original reference,
        // we just do a move
        clientRef = entry.valueRef;
        removed = true;
      }
    }
    if (removed) {
      maybeNotifyExclusiveEntryRemoval(oldExclusive);
    }
    return clientRef;
  }

  /**
   * Removes all the items from the cache whose key matches the specified predicate.
   * 将满足preidcate条件的所有item都remove掉
   *
   * @param predicate returns true if an item with the given key should be removed
   * @return number of the items removed from the cache
   */
  public int removeAll(Predicate<K> predicate) {
    ArrayList<Entry<K, V>> oldExclusives;
    ArrayList<Entry<K, V>> oldEntries;
    synchronized (this) {
      oldExclusives = mExclusiveEntries.removeAll(predicate);
      oldEntries = mCachedEntries.removeAll(predicate);
      makeOrphans(oldEntries);
    }
    maybeClose(oldEntries);
    maybeNotifyExclusiveEntryRemoval(oldExclusives);
    maybeUpdateCacheParams();
    maybeEvictEntries();
    return oldEntries.size();
  }

  /** Removes all the items from the cache. */
  public void clear() {
    ArrayList<Entry<K, V>> oldExclusives;
    ArrayList<Entry<K, V>> oldEntries;
    synchronized (this) {
      oldExclusives = mExclusiveEntries.clear();
      oldEntries = mCachedEntries.clear();
      makeOrphans(oldEntries);
    }
    maybeClose(oldEntries);
    maybeNotifyExclusiveEntryRemoval(oldExclusives);
    maybeUpdateCacheParams();
  }

  /**
   * Check if any items from the cache whose key matches the specified predicate.
   *
   * @param predicate returns true if an item with the given key matches
   * @return true is any items matches from the cache
   */
  @Override
  public synchronized boolean contains(Predicate<K> predicate) {
    return !mCachedEntries.getMatchingEntries(predicate).isEmpty();
  }

  /**
   *  Trims the cache according to the specified trimming strategy and the given trim type.
   *
   *  {@link MemoryTrimmable}接口中的方法，在裁剪内存的时候回调用到
   */
  @Override
  public void trim(MemoryTrimType trimType) {
    ArrayList<Entry<K, V>> oldEntries;
    final double trimRatio = mCacheTrimStrategy.getTrimRatio(trimType);
    synchronized (this) {
      int targetCacheSize = (int) (mCachedEntries.getSizeInBytes() * (1 - trimRatio));
      int targetEvictionQueueSize = Math.max(0, targetCacheSize - getInUseSizeInBytes());
      oldEntries = trimExclusivelyOwnedEntries(Integer.MAX_VALUE, targetEvictionQueueSize);
      makeOrphans(oldEntries);
    }
    maybeClose(oldEntries);
    maybeNotifyExclusiveEntryRemoval(oldEntries);
    maybeUpdateCacheParams();
    maybeEvictEntries();
  }

  /**
   * Updates the cache params (constraints) if enough time has passed since the last update.
   *
   * 如果已经过了定时检查的时间，则再次获取一下memoryCacheParams
   * 并记录事件戳
   */
  private synchronized void maybeUpdateCacheParams() {
    if (mLastCacheParamsCheck + PARAMS_INTERCHECK_INTERVAL_MS > SystemClock.uptimeMillis()) {
      return;
    }
    mLastCacheParamsCheck = SystemClock.uptimeMillis();
    mMemoryCacheParams = mMemoryCacheParamsSupplier.get();
  }

  /**
   * Removes the exclusively owned items until the cache constraints are met.
   * 对mExclusiveEntries进行裁剪
   * 直到低于限定的count或者是size
   *
   * <p> This method invokes the external {@link CloseableReference#close} method,
   * so it must not be called while holding the <code>this</code> lock.
   */
  private void maybeEvictEntries() {
    ArrayList<Entry<K, V>> oldEntries;
    synchronized (this) {
      int maxCount = Math.min(
          mMemoryCacheParams.maxEvictionQueueEntries,
          mMemoryCacheParams.maxCacheEntries - getInUseCount());
      int maxSize = Math.min(
          mMemoryCacheParams.maxEvictionQueueSize,
          mMemoryCacheParams.maxCacheSize - getInUseSizeInBytes());
      oldEntries = trimExclusivelyOwnedEntries(maxCount, maxSize);
      makeOrphans(oldEntries);
    }
    maybeClose(oldEntries);
    maybeNotifyExclusiveEntryRemoval(oldEntries);
  }

  /**
   * Removes the exclusively owned items until there is at most <code>count</code> of them
   * and they occupy no more than <code>size</code> bytes.
   *
   * 整理出一份list，当超过了限定的cache大小
   * 对mExclusiveEntries进行裁剪
   *
   * <p> This method returns the removed items instead of actually closing them, so it is safe to
   * be called while holding the <code>this</code> lock.
   */
  @Nullable
  private synchronized ArrayList<Entry<K, V>> trimExclusivelyOwnedEntries(int count, int size) {
    count = Math.max(count, 0);
    size = Math.max(size, 0);
    // fast path without array allocation if no eviction is necessary
    if (mExclusiveEntries.getCount() <= count && mExclusiveEntries.getSizeInBytes() <= size) {
      return null;
    }
    ArrayList<Entry<K, V>> oldEntries = new ArrayList<>();
    while (mExclusiveEntries.getCount() > count || mExclusiveEntries.getSizeInBytes() > size) {
      K key = mExclusiveEntries.getFirstKey();
      mExclusiveEntries.remove(key);
      oldEntries.add(mCachedEntries.remove(key));
    }
    return oldEntries;
  }

  /**
   * Notifies the client that the cache no longer tracks the given items.
   *
   * <p> This method invokes the external {@link CloseableReference#close} method,
   * so it must not be called while holding the <code>this</code> lock.
   */
  private void maybeClose(@Nullable ArrayList<Entry<K, V>> oldEntries) {
    if (oldEntries != null) {
      for (Entry<K, V> oldEntry : oldEntries) {
        CloseableReference.closeSafely(referenceToClose(oldEntry));
      }
    }
  }

  private void maybeNotifyExclusiveEntryRemoval(@Nullable ArrayList<Entry<K, V>> entries) {
    if (entries != null) {
      for (Entry<K, V> entry : entries) {
        maybeNotifyExclusiveEntryRemoval(entry);
      }
    }
  }

  private static <K, V> void maybeNotifyExclusiveEntryRemoval(@Nullable Entry<K, V> entry) {
    if (entry != null && entry.observer != null) {
      entry.observer.onExclusivityChanged(entry.key, false);
    }
  }

  private static <K, V> void maybeNotifyExclusiveEntryInsertion(@Nullable Entry<K, V> entry) {
    if (entry != null && entry.observer != null) {
      entry.observer.onExclusivityChanged(entry.key, true);
    }
  }

  /** Marks the given entries as orphans. */
  private synchronized void makeOrphans(@Nullable ArrayList<Entry<K, V>> oldEntries) {
    if (oldEntries != null) {
      for (Entry<K, V> oldEntry : oldEntries) {
        makeOrphan(oldEntry);
      }
    }
  }

  /** Marks the entry as orphan. */
  private synchronized void makeOrphan(Entry<K, V> entry) {
    Preconditions.checkNotNull(entry);
    Preconditions.checkState(!entry.isOrphan);
    entry.isOrphan = true;
  }

  /** Increases the entry's client count. */
  private synchronized void increaseClientCount(Entry<K, V> entry) {
    Preconditions.checkNotNull(entry);
    Preconditions.checkState(!entry.isOrphan);
    entry.clientCount++;
  }

  /** Decreases the entry's client count. */
  private synchronized void decreaseClientCount(Entry<K, V> entry) {
    Preconditions.checkNotNull(entry);
    Preconditions.checkState(entry.clientCount > 0);
    entry.clientCount--;
  }

  /** Returns the value reference of the entry if it should be closed, null otherwise. */
  @Nullable
  private synchronized CloseableReference<V> referenceToClose(Entry<K, V> entry) {
    Preconditions.checkNotNull(entry);
    return (entry.isOrphan && entry.clientCount == 0) ? entry.valueRef : null;
  }

  /** Gets the total number of all currently cached items. */
  public synchronized int getCount() {
    return mCachedEntries.getCount();
  }

  /** Gets the total size in bytes of all currently cached items. */
  public synchronized int getSizeInBytes() {
    return mCachedEntries.getSizeInBytes();
  }

  /** Gets the number of the cached items that are used by at least one client. */
  public synchronized int getInUseCount() {
    return mCachedEntries.getCount() - mExclusiveEntries.getCount();
  }

  /** Gets the total size in bytes of the cached items that are used by at least one client. */
  public synchronized int getInUseSizeInBytes() {
    return mCachedEntries.getSizeInBytes() - mExclusiveEntries.getSizeInBytes();
  }

  /** Gets the number of the exclusively owned items. */
  public synchronized int getEvictionQueueCount() {
    return mExclusiveEntries.getCount();
  }

  /** Gets the total size in bytes of the exclusively owned items. */
  public synchronized int getEvictionQueueSizeInBytes() {
    return mExclusiveEntries.getSizeInBytes();
  }
}
