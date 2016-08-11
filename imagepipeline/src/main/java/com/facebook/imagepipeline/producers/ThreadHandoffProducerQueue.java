/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.producers;

import com.facebook.common.internal.Preconditions;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

public class ThreadHandoffProducerQueue {

  // 一个标志位，为true的时候进来的runnable都在队列中等待被执行，如果为false,则进来的runnable能够立马得到执行
  private boolean mQueueing = false;

  // 一个双端队列，头尾都支持加入以及移除操作,队列里面存放着runnable对象
  private final Deque<Runnable> mRunnableList;

  // 从外面传入的一个executor, 可能是一个线程池或者啥，专门用来处理队列里面的runnable
  private final Executor mExecutor;

  public ThreadHandoffProducerQueue(Executor executor) {
    mExecutor = Preconditions.checkNotNull(executor);

    // ArrayDeque最开始只有16个空位，在后期如果发现空位不够还会自己扩充容量
    mRunnableList = new ArrayDeque<>();

  }

  public synchronized void addToQueueOrExecute(Runnable runnable) {
    if (mQueueing) {
      mRunnableList.add(runnable);
    } else {
      mExecutor.execute(runnable);
    }
  }

  public synchronized void startQueueing() {
    mQueueing = true;
  }

  public synchronized void stopQueuing() {
    mQueueing = false;
    execInQueue();
  }

  private void execInQueue() {
    while (!mRunnableList.isEmpty()) {
      mExecutor.execute(mRunnableList.pop());
    }
    mRunnableList.clear();
  }

  public synchronized void remove(Runnable runnable) {
    mRunnableList.remove(runnable);
  }

  public synchronized boolean isQueueing() {
    return mQueueing;
  }
}
