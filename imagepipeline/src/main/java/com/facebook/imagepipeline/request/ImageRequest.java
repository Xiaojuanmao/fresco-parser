/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.request;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import java.io.File;

import android.net.Uri;

import com.facebook.common.internal.Objects;
import com.facebook.imagepipeline.common.ImageDecodeOptions;
import com.facebook.imagepipeline.common.Priority;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imageutils.BitmapUtil;

/**
 * Immutable object encapsulating everything pipeline has to know about requested image to proceed.
 *
 * 一个封装着关于调用者需要的图片的所有信息
 */
@Immutable
public class ImageRequest {

  /**
   * Cache choice
   *
   * 一个枚举类型
   * 用来决定缓存是取出小图还是原图
   */
  private final CacheChoice mCacheChoice;

  /**
   * Source Uri
   *
   * 需要请求的图片的uri
   */
  private final Uri mSourceUri;

  /**
   * Source File - for local fetches only, lazily initialized
   *
   * 仅仅只是用于加载本地图片
   */
  private File mSourceFile;

  /**
   * If set - the client will receive intermediate results
   *
   * 用来设置是否支持渐进式的渲染
   * - true 调用者会收到中间产物
   * - false 不会收到
   */
  private final boolean mProgressiveRenderingEnabled;

  /**
   *  If set the client will receive thumbnail previews for local images, before the whole image
   *
   * - true 调用方会在整张图片之前收到缩略图
   * - false 不会
   */
  private final boolean mLocalThumbnailPreviewsEnabled;

  /**
   * 用来记录部分图像decode时候的属性,供ImageDecode用
   */
  private final ImageDecodeOptions mImageDecodeOptions;

  /**
   *  resize options
   *
   *  用来规范decode出来图片的大小
   */
  @Nullable
  ResizeOptions mResizeOptions = null;

  /**
   * Is auto-rotate enabled?
   *
   * 是否允许图片自动旋转，不明白是个啥子意思
   * 看到后面应该能懂
   */
  private final boolean mAutoRotateEnabled;

  /**
   * Priority levels of this request.
   *
   * 用来衡量此request优先级的类
   * 总共分为三个等级
   */
  private final Priority mRequestPriority;

  /**
   * Lowest level that is permitted to fetch an image from
   * 能够允许获取image的最差的方式
   * 网络、磁盘缓存、bitmap缓存、图片字节码缓存等等
   */
  private final RequestLevel mLowestPermittedRequestLevel;

  /**
   *  Whether the disk cache should be used for this request
   *  是否允许使用磁盘缓存
   */
  private final boolean mIsDiskCacheEnabled;

  /**
   * Postprocessor to run on the output bitmap.
   *
   * 预处理器，能够在获得目标bitmap，并在其返回结果给调用方之前
   * 对bitmap进行进一步的处理
   */
  private final Postprocessor mPostprocessor;

  public static ImageRequest fromUri(@Nullable Uri uri) {
    return (uri == null) ? null : ImageRequestBuilder.newBuilderWithSource(uri).build();
  }

  public static ImageRequest fromUri(@Nullable String uriString) {
    return (uriString == null || uriString.length() == 0) ? null : fromUri(Uri.parse(uriString));
  }

  protected ImageRequest(ImageRequestBuilder builder) {
    mCacheChoice = builder.getCacheChoice();
    mSourceUri = builder.getSourceUri();

    mProgressiveRenderingEnabled = builder.isProgressiveRenderingEnabled();
    mLocalThumbnailPreviewsEnabled = builder.isLocalThumbnailPreviewsEnabled();

    mImageDecodeOptions = builder.getImageDecodeOptions();

    mResizeOptions = builder.getResizeOptions();
    mAutoRotateEnabled = builder.isAutoRotateEnabled();

    mRequestPriority = builder.getRequestPriority();
    mLowestPermittedRequestLevel = builder.getLowestPermittedRequestLevel();
    mIsDiskCacheEnabled = builder.isDiskCacheEnabled();

    mPostprocessor = builder.getPostprocessor();
  }

  public CacheChoice getCacheChoice() {
    return mCacheChoice;
  }

  public Uri getSourceUri() {
    return mSourceUri;
  }

  public int getPreferredWidth() {
    return (mResizeOptions != null) ? mResizeOptions.width : (int) BitmapUtil.MAX_BITMAP_SIZE;
  }

  public int getPreferredHeight() {
    return (mResizeOptions != null) ? mResizeOptions.height : (int) BitmapUtil.MAX_BITMAP_SIZE;
  }

  public @Nullable ResizeOptions getResizeOptions() {
    return mResizeOptions;
  }

  public ImageDecodeOptions getImageDecodeOptions() {
    return mImageDecodeOptions;
  }

  public boolean getAutoRotateEnabled() {
    return mAutoRotateEnabled;
  }

  public boolean getProgressiveRenderingEnabled() {
    return mProgressiveRenderingEnabled;
  }

  public boolean getLocalThumbnailPreviewsEnabled() {
    return mLocalThumbnailPreviewsEnabled;
  }

  public Priority getPriority() {
    return mRequestPriority;
  }

  public RequestLevel getLowestPermittedRequestLevel() {
    return mLowestPermittedRequestLevel;
  }

  public boolean isDiskCacheEnabled() {
    return mIsDiskCacheEnabled;
  }

  public synchronized File getSourceFile() {
    if (mSourceFile == null) {
      mSourceFile = new File(mSourceUri.getPath());
    }
    return mSourceFile;
  }

  public @Nullable Postprocessor getPostprocessor() {
    return mPostprocessor;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ImageRequest)) {
      return false;
    }
    ImageRequest request = (ImageRequest) o;
    return Objects.equal(mSourceUri, request.mSourceUri) &&
        Objects.equal(mCacheChoice, request.mCacheChoice) &&
        Objects.equal(mSourceFile, request.mSourceFile);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(mCacheChoice, mSourceUri, mSourceFile);
  }

  /**
   * An enum describing the cache choice.
   * 一个枚举类型，决定从缓存中取出的原图还是小图
   */
  public enum CacheChoice {
    /* Indicates that this image should go in the small disk cache, if one is being used */
    SMALL,

    /* Default */
    DEFAULT,
  }

  /**
   * Level down to we are willing to go in order to find an image. E.g., we might only want to go
   * down to bitmap memory cache, and not check the disk cache or do a full fetch.
   *
   * 用来确定当前的request希望在哪一层上来查找想要的image
   */
  public enum RequestLevel {
    /**
     * Fetch (from the network or local storage)
     * 从网络获取或者从本地文件获取
     */
    FULL_FETCH(1),

    /**
     *  Disk caching
     * 从磁盘缓存
     */
    DISK_CACHE(2),

    /* Encoded memory caching */
    ENCODED_MEMORY_CACHE(3),

    /* Bitmap caching */
    BITMAP_MEMORY_CACHE(4);

    private int mValue;

    private RequestLevel(int value) {
      mValue = value;
    }

    public int getValue() {
      return mValue;
    }

    public static RequestLevel getMax(RequestLevel requestLevel1, RequestLevel requestLevel2) {
      return requestLevel1.getValue() > requestLevel2.getValue() ? requestLevel1 : requestLevel2;
    }
  }
}
