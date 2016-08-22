/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.producers;

import android.net.Uri;

import com.facebook.imagepipeline.image.EncodedImage;

/**
 * Used by {@link NetworkFetcher} to encapsulate the state of one network fetch.
 *
 * 封装了一次网络fetch的状态
 *
 * <p>Implementations can subclass this to store additional fetch-scoped fields.
 */
public class FetchState {

  // 等待被通知的consumer，这里的对象是EncodedImage
  private final Consumer<EncodedImage> mConsumer;

  // 具体需要producer来执行的工作内容
  private final ProducerContext mContext;
  private long mLastIntermediateResultTimeMs;

  public FetchState(
      Consumer<EncodedImage> consumer,
      ProducerContext context) {
    mConsumer = consumer;
    mContext = context;
    mLastIntermediateResultTimeMs = 0;
  }

  public Consumer<EncodedImage> getConsumer() {
    return mConsumer;
  }

  public ProducerContext getContext() {
    return mContext;
  }

  public String getId() {
    return mContext.getId();
  }

  public ProducerListener getListener() {
    return mContext.getListener();
  }

  public Uri getUri() {
    return mContext.getImageRequest().getSourceUri();
  }

  public long getLastIntermediateResultTimeMs() {
    return mLastIntermediateResultTimeMs;
  }

  public void setLastIntermediateResultTimeMs(long lastIntermediateResultTimeMs) {
    mLastIntermediateResultTimeMs = lastIntermediateResultTimeMs;
  }
}
