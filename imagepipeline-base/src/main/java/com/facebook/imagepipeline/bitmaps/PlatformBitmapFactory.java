/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.bitmaps;

import android.graphics.Bitmap;

import com.facebook.common.references.CloseableReference;

/**
 * Bitmap factory optimized for the platform.
 *
 * 包装了创建bitmap的方法的抽象类
 * 具体实现类对不同android系统版本进行了支持
 * android5.0以上的bitmap会直接存放在堆内存中
 * 而在2.3和3.1也有不同的实现方式
 *
 */
public abstract class PlatformBitmapFactory {

  /**
   * Creates a bitmap of the specified width and height.
   *
   * 根据传入的宽高以及质量属性来创建一个bitmap
   * 并返回了一个CloseableReference,这类型的引用在不需要的时候要调用close方法，不会等到垃圾回收启动
   * 就会回收这部分内存，官方的解释是专门针对那些开销比较大的对象挺有用的，= =对bitmap挺对症的
   *
   *
   * @param width the width of the bitmap
   * @param height the height of the bitmap
   * @param bitmapConfig the Bitmap.Config used to create the Bitmap
   * @return a reference to the bitmap
   * @throws TooManyBitmapsException if the pool is full
   * @throws java.lang.OutOfMemoryError if the Bitmap cannot be allocated
   */
  public abstract CloseableReference<Bitmap> createBitmap(
      int width,
      int height,
      Bitmap.Config bitmapConfig);

  /**
   * Creates a bitmap of the specified width and height.
   * The bitmap will be created with the default ARGB_8888 configuration
   *
   * 和上面的一个方法没啥区别，只是提供了一个最高质量的bitmap参数
   *
   * @param width the width of the bitmap
   * @param height the height of the bitmap
   * @return a reference to the bitmap
   * @throws TooManyBitmapsException if the pool is full
   * @throws java.lang.OutOfMemoryError if the Bitmap cannot be allocated
   */
  public CloseableReference<Bitmap> createBitmap(int width, int height) {
    return createBitmap(width, height, Bitmap.Config.ARGB_8888);
  }
}
