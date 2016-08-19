/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.memory;

import javax.annotation.concurrent.NotThreadSafe;

import com.facebook.common.internal.Preconditions;

/**
 * Factory class for pools.
 * 为pool而生的一个factory
 * 里面各种池子 = =，为了省点开销
 * byte数组池、bitmap池等等
 *
 */
@NotThreadSafe
public class PoolFactory {

  // pool的参数
  private final PoolConfig mConfig;

  // 用来管理bitmap的一个池，继承自BasePool
  private BitmapPool mBitmapPool;

  // byte数组的一个池
  private FlexByteArrayPool mFlexByteArrayPool;

  // 管理native内存块的池
  private NativeMemoryChunkPool mNativeMemoryChunkPool;
  private PooledByteBufferFactory mPooledByteBufferFactory;
  private PooledByteStreams mPooledByteStreams;
  private SharedByteArray mSharedByteArray;
  private ByteArrayPool mSmallByteArrayPool;

  public PoolFactory(PoolConfig config) {
    mConfig = Preconditions.checkNotNull(config);
  }

  public BitmapPool getBitmapPool() {
    if (mBitmapPool == null) {
      mBitmapPool = new BitmapPool(
          mConfig.getMemoryTrimmableRegistry(),
          mConfig.getBitmapPoolParams(),
          mConfig.getBitmapPoolStatsTracker());
    }
    return mBitmapPool;
  }

  public FlexByteArrayPool getFlexByteArrayPool() {
    if (mFlexByteArrayPool == null) {
      mFlexByteArrayPool = new FlexByteArrayPool(
          mConfig.getMemoryTrimmableRegistry(),
          mConfig.getFlexByteArrayPoolParams());
    }
    return mFlexByteArrayPool;
  }

  public int getFlexByteArrayPoolMaxNumThreads() {
    return mConfig.getFlexByteArrayPoolParams().maxNumThreads;
  }

  public NativeMemoryChunkPool getNativeMemoryChunkPool() {
    if (mNativeMemoryChunkPool == null) {
      mNativeMemoryChunkPool = new NativeMemoryChunkPool(
          mConfig.getMemoryTrimmableRegistry(),
          mConfig.getNativeMemoryChunkPoolParams(),
          mConfig.getNativeMemoryChunkPoolStatsTracker());
    }
    return mNativeMemoryChunkPool;
  }

  public PooledByteBufferFactory getPooledByteBufferFactory() {
    if (mPooledByteBufferFactory == null) {
      mPooledByteBufferFactory = new NativePooledByteBufferFactory(
          getNativeMemoryChunkPool(),
          getPooledByteStreams());
    }
    return mPooledByteBufferFactory;
  }

  public PooledByteStreams getPooledByteStreams() {
    if (mPooledByteStreams == null) {
      mPooledByteStreams = new PooledByteStreams(getSmallByteArrayPool());
    }
    return mPooledByteStreams;
  }

  public SharedByteArray getSharedByteArray() {
    if (mSharedByteArray == null) {
      mSharedByteArray = new SharedByteArray(
          mConfig.getMemoryTrimmableRegistry(),
          mConfig.getFlexByteArrayPoolParams());
    }
    return mSharedByteArray;
  }

  public ByteArrayPool getSmallByteArrayPool() {
    if (mSmallByteArrayPool == null) {
      mSmallByteArrayPool = new GenericByteArrayPool(
          mConfig.getMemoryTrimmableRegistry(),
          mConfig.getSmallByteArrayPoolParams(),
          mConfig.getSmallByteArrayPoolStatsTracker());
    }
    return mSmallByteArrayPool;
  }
}
