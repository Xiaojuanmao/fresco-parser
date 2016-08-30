/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.datasource;

import javax.annotation.Nullable;

import java.util.concurrent.Executor;

/**
 * An alternative to Java Futures for the image pipeline.
 *
 * <p>Unlike Futures, DataSource can issue a series of results, rather than just one. A prime
 * example is decoding progressive images, which have a series of intermediate results before the
 * final one.
 *
 * DataSource能够分发一系列的结果，而不仅仅是一个
 * 比较典型的例子就是能够处理渐进式的图片，渐进式加载图片的时候，在最终结果产生之前还会有许多图片会产生
 *
 * <p>DataSources MUST be closed (close() is called on the DataSource) else resources may leak.
 *
 * 此类在使用完毕之后必须被close，否则会产生内存泄漏
 *
 *@param <T> the type of the result
 */
public interface DataSource<T> {

  /**
   * 用来检测当前的DataSource是否被关闭
   *
   * @return true if the data source is closed, false otherwise
   */
  boolean isClosed();

  /**
   * The most recent result of the asynchronous computation.
   *
   * 用来获取最近一次计算出来的同步结果
   *
   * <p>The caller gains ownership of the object and is responsible for releasing it.
   * Note that subsequent calls to getResult might give different results. Later results should be
   * considered to be of higher quality.
   *
   * 调用此方法并获得一个T类型的对象，调用者拥有对象的所有权，并应当在合适的时候将其释放
   * 在不同时候调用此方法会获得不同的结果，迟一些获得的结果应当比之前获得的具有更高的质量
   *
   * <p>This method will return null in the following cases:
   * <ul>
   * <li>when the DataSource does not have a result ({@code hasResult} returns false).
   * <li>when the last result produced was null.
   * </ul>
   *
   * 在下面两种情况下，此方法会返回null:
   * - 当DataSource没有结果，也就是hasResult方法返回false的时候
   * - 当最后产生的结果为null时
   *
   * @return current best result
   */
  @Nullable T getResult();

  /**
   * @return true if any result (possibly of lower quality) is available right now, false otherwise
   */
  boolean hasResult();

  /**
   * @return true if request is finished, false otherwise
   */
  boolean isFinished();

  /**
   * @return true if request finished due to error
   */
  boolean hasFailed();

  /**
   * @return failure cause if the source has failed, else null
   */
  @Nullable Throwable getFailureCause();

  /**
   * 获得当前的进度
   *
   * @return progress in range [0, 1]
   */
  float getProgress();

  /**
   * Cancels the ongoing request and releases all associated resources.
   * 取消当前正在进行的请求，并需要释放所有的资源
   *
   * <p>Subsequent calls to {@link #getResult} will return null.
   * @return true if the data source is closed for the first time
   */
  boolean close();

  /**
   * Subscribe for notifications whenever the state of the DataSource changes.
   *
   * 用来订阅当前request的一举一动
   *
   * <p>All changes will be observed on the provided executor.
   * @param dataSubscriber
   * @param executor
   */
  void subscribe(DataSubscriber<T> dataSubscriber, Executor executor);
}
