/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.facebook.imagepipeline.platform;

import android.graphics.Bitmap;

import com.facebook.common.references.CloseableReference;
import com.facebook.imagepipeline.image.EncodedImage;

/**
 * 这个接口和{@link com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory}的模式很像额
 * 提供了俩解码的方法
 */
public interface PlatformDecoder {
  /**
   * Creates a bitmap from encoded bytes. Supports JPEG but callers should use {@link
   * #decodeJPEGFromEncodedImage} for partial JPEGs.
   *
   * JPEG走下面那个方法
   * 其余的走这个
   *
   * @param encodedImage the reference to the encoded image with the reference to the encoded bytes
   * @param bitmapConfig the {@link android.graphics.Bitmap.Config} used to create the decoded
   * Bitmap
   * @return the bitmap
   * @throws TooManyBitmapsException if the pool is full
   * @throws java.lang.OutOfMemoryError if the Bitmap cannot be allocated
   */
  CloseableReference<Bitmap> decodeFromEncodedImage(
      final EncodedImage encodedImage,
      Bitmap.Config bitmapConfig);

  /**
   * Creates a bitmap from encoded JPEG bytes. Supports a partial JPEG image.
   *
   * 支持部分JPEG解码并展示
   *
   *
   * @param encodedImage the reference to the encoded image with the reference to the encoded bytes
   * @param bitmapConfig the {@link android.graphics.Bitmap.Config} used to create the decoded
   * Bitmap
   * @param length the number of encoded bytes in the buffer
   * @return the bitmap
   * @throws TooManyBitmapsException if the pool is full
   * @throws java.lang.OutOfMemoryError if the Bitmap cannot be allocated
   */
  CloseableReference<Bitmap> decodeJPEGFromEncodedImage(
      EncodedImage encodedImage,
      Bitmap.Config bitmapConfig,
      int length);
}
