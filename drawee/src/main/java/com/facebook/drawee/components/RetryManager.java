/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.drawee.components;

/**
 * Manages retries for an image.
 */
public class RetryManager {

  private static final int MAX_TAP_TO_RETRY_ATTEMPTS = 4; // 默认最多重复请求4次

  private boolean mTapToRetryEnabled; // 是否支持点击重试
  private int mMaxTapToRetryAttempts; // 最大尝试次数
  private int mTapToRetryAttempts; // 当前尝试次数

  public RetryManager() {
    init();
  }

  public static RetryManager newInstance() {
    return new RetryManager();
  }

  /**
   * Initializes component to its initial state.
   */
  public void init() {
    mTapToRetryEnabled = false;
    mMaxTapToRetryAttempts = MAX_TAP_TO_RETRY_ATTEMPTS;
    reset();
  }

  /**
   * Resets component.
   * This will reset the number of attempts.
   */
  public void reset() {
    mTapToRetryAttempts = 0;
  }

  public boolean isTapToRetryEnabled() {
    return mTapToRetryEnabled;
  }

  public void setTapToRetryEnabled(boolean tapToRetryEnabled) {
    mTapToRetryEnabled = tapToRetryEnabled;
  }

  public void setMaxTapToRetryAttemps(int maxTapToRetryAttemps) {
    this.mMaxTapToRetryAttempts = maxTapToRetryAttemps;
  }

  public boolean shouldRetryOnTap() {
    return mTapToRetryEnabled && mTapToRetryAttempts < mMaxTapToRetryAttempts;
  }

  public void notifyTapToRetry() {
    mTapToRetryAttempts++;
  }
}
