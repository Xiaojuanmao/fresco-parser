/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.facebook.drawee.generic;

import javax.annotation.Nullable;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import com.facebook.common.internal.VisibleForTesting;
import com.facebook.drawee.drawable.ForwardingDrawable;
import com.facebook.drawee.drawable.VisibilityAwareDrawable;
import com.facebook.drawee.drawable.VisibilityCallback;

/**
 * The root drawable of a DraweeHierarchy.
 * 专门针对DraweeHierarchy定义的一个drawable类
 *
 * Root drawable has several functions:
 *
 * 这个drawable类中有一些函数，用来扩展这个drawable的功能
 *
 * <ul>
 * <li> A hierarchy always has the same instance of a root drawable. That means that internal
 * structural changes within the hierarchy don't require setting a new drawable to the view.
 *
 * 一个DraweeHierarchy总会持有一个RootDrawable实例，这就意味着不需要每次都重新新建一个drawable给view
 * 只需要变化这个RootDrawable就能够满足需求
 *
 * <li> Root drawable prevents intrinsic dimensions to escape the hierarchy. This in turn prevents
 * view to do any erroneous scaling based on those intrinsic dimensions, as the hierarchy is in
 * charge of all the required scaling.
 *
 * <li> Root drawable is visibility aware. Visibility callback is used to attach the controller
 * (if not already attached) when the hierarchy needs to be drawn. This prevents photo-not-loading
 * issues in case attach event has not been called (for whatever reason). It also helps with
 * memory management as the controller will get detached if the drawable is not visible.
 * <li> Root drawable supports controller overlay, a special overlay set by the controller. Typical
 * usages are debugging, diagnostics and other cases where controller-specific overlay is required.
 * </ul>
 */
public class RootDrawable extends ForwardingDrawable implements VisibilityAwareDrawable {

  @VisibleForTesting
  @Nullable
  Drawable mControllerOverlay = null; // 上面的注释说是用来debug或者其他用处的overlay

  @Nullable
  private VisibilityCallback mVisibilityCallback;  // 这个callback用来提示controller相关信息的，当drawable属性发生变化的时候

  // 从外面传入的drawable交给了父类ForwardingDrawable管理
  public RootDrawable(Drawable drawable) {
    super(drawable);
  }

  // 获得固定的宽高都是 -1, 也就是MATCH_PARENT
  @Override
  public int getIntrinsicWidth() {
    return -1;
  }

  @Override
  public int getIntrinsicHeight() {
    return -1;
  }

  @Override
  public void setVisibilityCallback(@Nullable VisibilityCallback visibilityCallback) {
    mVisibilityCallback = visibilityCallback;
  }

  // drawable的可见性发生变化的时候需要通知holder来attach和detach相关工作
  @Override
  public boolean setVisible(boolean visible, boolean restart) {
    if (mVisibilityCallback != null) {
      mVisibilityCallback.onVisibilityChange(visible);
    }
    return super.setVisible(visible, restart);
  }

  @SuppressLint("WrongCall")
  @Override
  public void draw(Canvas canvas) {
    if (!isVisible()) {
      return;
    }
    if (mVisibilityCallback != null) {
      mVisibilityCallback.onDraw();
    }
    super.draw(canvas);
    if (mControllerOverlay != null) {
      mControllerOverlay.setBounds(getBounds());
      mControllerOverlay.draw(canvas);
    }
  }

  public void setControllerOverlay(@Nullable Drawable controllerOverlay) {
    mControllerOverlay = controllerOverlay;
    invalidateSelf();
  }
}
