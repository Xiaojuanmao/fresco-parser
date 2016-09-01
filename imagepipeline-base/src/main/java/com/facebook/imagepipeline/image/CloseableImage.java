/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.image;

import java.io.Closeable;

import com.facebook.common.logging.FLog;

/**
 * A simple wrapper around an image that implements {@link Closeable}
 *
 * 一个实现了Closeable接口，用来对image进行简单包装的类
 */
public abstract class CloseableImage implements Closeable, ImageInfo {
  private static final String TAG = "CloseableImage";

  /**
   * @return size in bytes of the bitmap(s)
   *
   * 返回当前CloseableImage所包装的image大小
   */
  public abstract int getSizeInBytes();

  /**
   * Closes this instance and releases the resources.
   *
   * 用来释放资源，关闭引用的方法
   */
  @Override
  public abstract void close();

  /**
   * Returns whether this instance is closed.
   */
  public abstract boolean isClosed();

  /**
   * Returns quality information for the image.
   * <p> Image classes that can contain intermediate results should override this as appropriate.
   *
   * 返回有关图像质量的信息
   * 如果所包装的图像具有中间产物，例如渐进式的结果或者其他的，需要提供这个方法的实现
   */
  @Override
  public QualityInfo getQualityInfo() {
    return ImmutableQualityInfo.FULL_QUALITY;
  }

  /**
   * Whether or not this image contains state for a particular view of the image (for example,
   * the image for an animated GIF might contain the current frame being viewed). This means
   * that the image should not be stored in the bitmap cache.
   *
   * 此选项是用来设置被包装的image是否能够处于某种状态
   * 例如gif图片，view中总会显示某一帧
   */
  public boolean isStateful() {
    return false;
  }

  /**
   * Ensures that the underlying resources are always properly released.
   *
   * 确保资源已经完全释放
   */
  @Override
  protected void finalize() throws Throwable {
    if (isClosed()) {
      return;
    }
    FLog.w(
        TAG,
        "finalize: %s %x still open.",
        this.getClass().getSimpleName(),
        System.identityHashCode(this));
    try {
      close();
    } finally {
      super.finalize();
    }
  }
}
