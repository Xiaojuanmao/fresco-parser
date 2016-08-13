/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.memory;

import javax.annotation.concurrent.ThreadSafe;

import android.annotation.TargetApi;
import android.graphics.Bitmap;

import com.facebook.common.internal.Preconditions;
import com.facebook.common.memory.MemoryTrimmableRegistry;
import com.facebook.imageutils.BitmapUtil;

/**
 * Manages a pool of bitmaps. This allows us to reuse bitmaps instead of constantly allocating
 * them (and pressuring the Java GC to garbage collect unused bitmaps).
 * 管理一个bitmap池
 * 达到重用的目的，而不是一直去申请存储空间，给垃圾回收器制造更多压力
 *
 * <p>
 * The pool supports a get/release paradigm.
 * get() allows for a bitmap in the pool to be reused if it matches the desired
 * dimensions; if no such bitmap is found in the pool, a new one is allocated.
 *
 * {@link BasePool#get(int)}此方法会返回一个符合要求的范式对象，这里的范式对象指的是bitmap
 * 如果在池子里找到了符合尺寸要求的即返回，否则新申请一片空间并返回
 *
 * release() returns a bitmap to the pool.
 *
 * {@link BasePool#release(Object)}此方法将一个范式对象回收到pool里
 *
 */

@ThreadSafe
@TargetApi(21)
public class BitmapPool extends BasePool<Bitmap> {

  /**
   * Creates an instance of a bitmap pool.
   * 创建一个bitmap池的实例
   *
   *
   * @param memoryTrimmableRegistry the memory manager to register with
   * @param poolParams pool parameters
   */
  public BitmapPool(
      MemoryTrimmableRegistry memoryTrimmableRegistry,
      PoolParams poolParams,
      PoolStatsTracker poolStatsTracker) {
    super(memoryTrimmableRegistry, poolParams, poolStatsTracker);
    initialize();
  }

  /**
   * Allocate a bitmap that has a backing memory allocacation of 'size' bytes.
   * This is configuration agnostic so the size is the actual size in bytes of the bitmap.
   *
   * ceil()方法是向上取整计算，它返回的是大于或等于函数参数
   *
   * 创建了一个宽度为1px，高度为size / RGB_565 (一个像素点占用两个byte)
   *
   * @param size the 'size' in bytes of the bitmap
   * @return a new bitmap with the specified size in memory
   */
  @Override
  protected Bitmap alloc(int size) {
    return Bitmap.createBitmap(
        1,
        (int) Math.ceil(size / (double) BitmapUtil.RGB_565_BYTES_PER_PIXEL),
        Bitmap.Config.RGB_565);
  }

  /**
   * Frees the bitmap
   *
   * 传入一个bitmap，将其内存进行释放
   * 这里并不是立即释放堆内存，调用了recycle方法只是标记此bitmap不再可用
   * 在recycle会调用native方法来释放存在native中的bitmap对象
   *
   * @param value the bitmap to free
   */
  @Override
  protected void free(Bitmap value) {
    Preconditions.checkNotNull(value);
    value.recycle();
  }

  /**
   * Gets the bucketed size (typically something the same or larger than the requested size)
   * @param requestSize the logical request size
   * @return the 'bucketed' size
   */
  @Override
  protected int getBucketedSize(int requestSize) {
    return requestSize;
  }

  /**
   * Gets the bucketed size of the value.
   * We don't check the 'validity' of the value (beyond the not-null check). That's handled
   * in {@link #isReusable(Bitmap)}
   *
   * 在native存储的bitmap不支持reconfigure，在native中所占用的内存一直是固定的
   *
   * @param value the value
   * @return bucketed size of the value
   */
  @Override
  protected int getBucketedSizeForValue(Bitmap value) {
    Preconditions.checkNotNull(value);
    return value.getAllocationByteCount();
  }

  /**
   * Gets the size in bytes for the given bucketed size
   * @param bucketedSize the bucketed size
   * @return size in bytes
   */
  @Override
  protected int getSizeInBytes(int bucketedSize) {
    return  bucketedSize;
  }

  /**
   * Determine if this bitmap is reusable (i.e.) if subsequent {@link #get(int)} requests can
   * use this value.
   * The bitmap is reusable if
   *  - it has not already been recycled AND
   *  - it is mutable
   *
   *  判断一个bitmap是否能够再被使用
   *  当一个bitmap没有被回收并且是不可变的，即能够被重用
   *
   * @param value the value to test for reusability
   * @return true, if the bitmap can be reused
   */
  @Override
  protected boolean isReusable(Bitmap value) {
    Preconditions.checkNotNull(value);
    return !value.isRecycled() &&
        value.isMutable();
  }
}
