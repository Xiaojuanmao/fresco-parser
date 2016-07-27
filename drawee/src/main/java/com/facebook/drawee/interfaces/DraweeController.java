/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.drawee.interfaces;

import javax.annotation.Nullable;

import android.graphics.drawable.Animatable;
import android.view.MotionEvent;

/**
 * Interface that represents a Drawee controller used by a DraweeView.
 * <p> The view forwards events to the controller. The controller controls
 * its hierarchy based on those events.
 *
 * 定义了一系列的接口，用来管理view的逻辑事件
 * 以及通过触发不同的事件，控制hierarchy的drawable层级
 *
 */
public interface DraweeController {

  /**
   * Gets the hierarchy.
   *
   * controller里面也有hierarchy的实例，需要通过view的不同事件来控制hierarchy行为
   */
  @Nullable
  DraweeHierarchy getHierarchy();

  /**
   * Sets a new hierarchy.
   *
   */
  void setHierarchy(@Nullable DraweeHierarchy hierarchy);

  /**
   * Called when the view containing the hierarchy is attached to a window
   * (either temporarily or permanently).
   *
   * 当view attach上window的时候触发对应逻辑，例如处理drawable的加载等等
   *
   */
  void onAttach();

  /**
   * Called when the view containing the hierarchy is detached from a window
   * (either temporarily or permanently).
   *
   * 当view从屏幕上上被detach的时候，可能触发一些停止网络加载图片等等逻辑，保证异步操作在适当时候的同步
   * 防止内存泄漏以及不必要的系统开销等等
   *
   */
  void onDetach();

  /**
   * Called when the view containing the hierarchy receives a touch event.
   * @return true if the event was handled by the controller, false otherwise
   *
   *
   */
  boolean onTouchEvent(MotionEvent event);

  /**
   * For an animated image, returns an Animatable that lets clients control the animation.
   * @return animatable, or null if the image is not animated or not loaded yet
   *
   * 为用户在使用fresco的时候能够自定义动画，留出的接口
   */
  Animatable getAnimatable();

  /** Sets the accessibility content description. */
  void setContentDescription(String contentDescription);

  /**
   * Gets the accessibility content description.
   * @return content description, or null if the image has no content description
   */
  String getContentDescription();
}
