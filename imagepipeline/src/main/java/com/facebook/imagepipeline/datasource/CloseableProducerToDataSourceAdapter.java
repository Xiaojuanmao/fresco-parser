/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.datasource;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import com.facebook.common.references.CloseableReference;
import com.facebook.datasource.DataSource;
import com.facebook.imagepipeline.producers.Producer;
import com.facebook.imagepipeline.producers.SettableProducerContext;
import com.facebook.imagepipeline.listener.RequestListener;

/**
 * DataSource<CloseableReference<T>> backed by a Producer<CloseableReference<T>>
 *
 * 此类作为一个转换类，将Producer转换成了一个DataSource类
 *
 * @param <T>
 */
@ThreadSafe
public class CloseableProducerToDataSourceAdapter<T>
    extends AbstractProducerToDataSourceAdapter<CloseableReference<T>> {

  public static <T> DataSource<CloseableReference<T>> create(
      Producer<CloseableReference<T>> producer,
      SettableProducerContext settableProducerContext,
      RequestListener listener) {
    return new CloseableProducerToDataSourceAdapter<T>(
        producer, settableProducerContext, listener);
  }

  private CloseableProducerToDataSourceAdapter(
      Producer<CloseableReference<T>> producer,
      SettableProducerContext settableProducerContext,
      RequestListener listener) {
    super(producer, settableProducerContext, listener);
  }

  @Override
  @Nullable
  public CloseableReference<T> getResult() {
    return CloseableReference.cloneOrNull(super.getResult());
  }

  @Override
  protected void closeResult(CloseableReference<T> result) {
    CloseableReference.closeSafely(result);
  }

  @Override
  protected void onNewResultImpl(CloseableReference<T> result, boolean isLast) {
    super.onNewResultImpl(CloseableReference.cloneOrNull(result), isLast);
  }
}
