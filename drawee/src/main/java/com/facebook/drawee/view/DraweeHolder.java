/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.facebook.drawee.view;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.facebook.common.internal.Objects;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.logging.FLog;
import com.facebook.common.memory.MemoryUiTrimmable;
import com.facebook.common.memory.MemoryUiTrimmableRegistry;
import com.facebook.drawee.components.DraweeEventTracker;
import com.facebook.drawee.drawable.VisibilityAwareDrawable;
import com.facebook.drawee.drawable.VisibilityCallback;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.interfaces.DraweeHierarchy;

import javax.annotation.Nullable;

import static com.facebook.drawee.components.DraweeEventTracker.Event;

/**
 * A holder class for Drawee controller and hierarchy.
 *
 * 一个用来管理DraweeController和Hierarchy的holder类，DraweeView直接交互的角色
 *
 *
 * <p>Drawee users, should, as a rule, use {@link DraweeView} or its subclasses. There are
 * situations where custom views are required, however, and this class is for those circumstances.
 *
 * <p>Each {@link DraweeHierarchy} object should be contained in a single instance of this
 * class.
 *
 * <p>Users of this class must call {@link Drawable#setBounds} on the top-level drawable
 * of the DraweeHierarchy. Otherwise the drawable will not be drawn.
 *
 * 在使用这个类的时候必须要调用top-level Drawable的setBounds方法，不然不会被画出来
 *
 * <p>The containing view must also call {@link #onDetach()} from its
 * {@link View#onStartTemporaryDetach()} and {@link View#onDetachedFromWindow()} methods. It must
 * call {@link #onAttach} from its {@link View#onFinishTemporaryDetach()} and
 * {@link View#onAttachedToWindow()} methods.
 *
 * 注意在View的detach和attach的时候，调用holder对应的方法
 *
 */
public class DraweeHolder<DH extends DraweeHierarchy>
    implements VisibilityCallback, MemoryUiTrimmable {

  private boolean mIsControllerAttached = false;
  private boolean mIsHolderAttached = false;
  private boolean mIsVisible = true;
  private boolean mTrimmed = false;

  // holder管理的俩实例，hierarchy主要用来管理drawable层级，controller主要用来
  private DH mHierarchy;
  private DraweeController mController = null;

  /**
   * 用来统计drawee使用过程中特殊事件的日志工具
   * 特殊事件包括设置hierarchy等等
   */
  private final DraweeEventTracker mEventTracker = DraweeEventTracker.newInstance();

  /**
   * Creates a new instance of DraweeHolder that detaches / attaches controller whenever context
   * notifies it about activity's onStop and onStart callbacks.
   *
   * 暂时不太清楚为啥要把context传进来，说是为了在onStart和onStop的时候回调。没懂
   *
   * <p>It is recommended to pass a {@link ListenableActivity} as context. This will help in a future release.
   */
  public static <DH extends DraweeHierarchy> DraweeHolder<DH> create(
      @Nullable DH hierarchy,
      Context context) {
    DraweeHolder<DH> holder = new DraweeHolder<DH>(hierarchy);
    holder.registerWithContext(context);

    // 并没有看到有什么用，只是在test上面测试了一波
    MemoryUiTrimmableRegistry.registerUiTrimmable(holder);

    return holder;
  }

  /** For future use. */
  public void registerWithContext(Context context) {
  }

  /**
   * Creates a new instance of DraweeHolder.
   * @param hierarchy
   */
  public DraweeHolder(@Nullable DH hierarchy) {
    if (hierarchy != null) {
      setHierarchy(hierarchy);
    }
  }

  /**
   * Gets the controller ready to display the image.
   *
   * 在View检测到attach到屏幕之后会调用此方法
   * 记录日志、设置attached变量以及通知DraweeController关于attach的事件
   *
   * <p>The containing view must call this method from both {@link View#onFinishTemporaryDetach()}
   * and {@link View#onAttachedToWindow()}.
   *
   *
   */
  public void onAttach() {
    mEventTracker.recordEvent(Event.ON_HOLDER_ATTACH);
    mIsHolderAttached = true;
    attachOrDetachController();
  }

  /**
   * Checks whether the view that uses this holder is currently attached to a window.
   *
   * {@see #onAttach()}
   * {@see #onDetach()}
   *
   * @return true if the holder is currently attached
   */
  public boolean isAttached() {
    return mIsHolderAttached;
  }

  /**
   * Releases resources used to display the image.
   *
   * <p>The containing view must call this method from both {@link View#onStartTemporaryDetach()}
   * and {@link View#onDetachedFromWindow()}.
   */
  public void onDetach() {
    mEventTracker.recordEvent(Event.ON_HOLDER_DETACH);
    mIsHolderAttached = false;
    attachOrDetachController();
  }

  @Override
  public void trim() {
    mEventTracker.recordEvent(Event.ON_HOLDER_TRIM);
    mTrimmed = true;
    attachOrDetachController();
  }

  @Override
  public void untrim() {
    mEventTracker.recordEvent(Event.ON_HOLDER_UNTRIM);
    mTrimmed = false;
    attachOrDetachController();
  }

  /**
   * Forwards the touch event to the controller.
   * @param event touch event to handle
   * @return whether the event was handled or not
   */
  public boolean onTouchEvent(MotionEvent event) {
    if (mController == null) {
      return false;
    }
    return mController.onTouchEvent(event);
  }

  /**
   * Callback used to notify about top-level-drawable's visibility changes.
   *
   * 根据hierarchy里面top-level的drawable是否可见来改变属性
   * 在{@link com.facebook.drawee.generic.RootDrawable}里面有调用到,在top-level-drawable可见属性有变动的情况下就会调用
   *
   *
   * 基本上有情况变动都会调用attachOrDetachController()这个方法
   * 包括上面的trim()以及untrim()
   *
   */
  @Override
  public void onVisibilityChange(boolean isVisible) {
    if (mIsVisible == isVisible) {
      return;
    }
    mEventTracker.recordEvent(isVisible ? Event.ON_DRAWABLE_SHOW : Event.ON_DRAWABLE_HIDE);
    mIsVisible = isVisible;
    attachOrDetachController();
  }

  /**
   * Callback used to notify about top-level-drawable being drawn.
   *
   * 用来提示holder，top-level-drawable正在被draw到屏幕上
   * 在{@link com.facebook.drawee.generic.RootDrawable}里面有调用到,在drawable正在被draw的时候回调
   *
   */
  @Override
  public void onDraw() {
    // draw is only expected if the controller is attached
    if (mIsControllerAttached) {
      return;
    }
    // trimming events are not guaranteed to arrive before the draw
    if (!mTrimmed) {
      // something went wrong here; controller is not attached, yet the hierarchy has to be drawn
      // log error and attach the controller
      FLog.wtf(
          DraweeEventTracker.class,
          "%x: Draw requested for a non-attached controller %x. %s",
          System.identityHashCode(this),
          System.identityHashCode(mController),
          toString());
    }
    mTrimmed = false;
    mIsHolderAttached = true;
    mIsVisible = true;
    attachOrDetachController();
  }

  /**
   * Sets the visibility callback to the current top-level-drawable.
   */
  private void setVisibilityCallback(@Nullable VisibilityCallback visibilityCallback) {
    Drawable drawable = getTopLevelDrawable();
    if (drawable instanceof VisibilityAwareDrawable) {
      ((VisibilityAwareDrawable) drawable).setVisibilityCallback(visibilityCallback);
    }
  }

  /**
   * Sets a new controller.
   */
  public void setController(@Nullable DraweeController draweeController) {
    boolean wasAttached = mIsControllerAttached;
    if (wasAttached) {
      detachController();
    }

    // Clear the old controller
    if (mController != null) {
      mEventTracker.recordEvent(Event.ON_CLEAR_OLD_CONTROLLER);
      mController.setHierarchy(null);
    }
    mController = draweeController;
    if (mController != null) {
      mEventTracker.recordEvent(Event.ON_SET_CONTROLLER);
      mController.setHierarchy(mHierarchy);
    } else {
      mEventTracker.recordEvent(Event.ON_CLEAR_CONTROLLER);
    }

    if (wasAttached) {
      attachController();
    }
  }

  /**
   * Gets the controller if set, null otherwise.
   */
  @Nullable public DraweeController getController() {
    return mController;
  }

  /**
   * Sets the drawee hierarchy.
   */
  public void setHierarchy(DH hierarchy) {
    mEventTracker.recordEvent(Event.ON_SET_HIERARCHY);
    setVisibilityCallback(null);
    mHierarchy = Preconditions.checkNotNull(hierarchy);
    Drawable drawable = mHierarchy.getTopLevelDrawable();
    onVisibilityChange(drawable == null || drawable.isVisible());
    setVisibilityCallback(this);
    if (mController != null) {
      mController.setHierarchy(hierarchy);
    }
  }

  /**
   * Gets the drawee hierarchy if set, throws NPE otherwise.
   */
  public DH getHierarchy() {
    return Preconditions.checkNotNull(mHierarchy);
  }

  /**
   * Returns whether the hierarchy is set or not.
   */
  public boolean hasHierarchy() {
    return mHierarchy != null;
  }

  /**
   * Gets the top-level drawable if hierarchy is set, null otherwise.
   */
  public Drawable getTopLevelDrawable() {
    return mHierarchy == null ? null : mHierarchy.getTopLevelDrawable();
  }

  protected DraweeEventTracker getDraweeEventTracker() {
    return mEventTracker;
  }

  /**
   * 通知controller进行attach的相关工作
   * 可能是开始加载图片等等工作，看具体实现的情况
   *
   */
  private void attachController() {
    if (mIsControllerAttached) {
      return;
    }
    mEventTracker.recordEvent(Event.ON_ATTACH_CONTROLLER);
    mIsControllerAttached = true;
    if (mController != null &&
        mController.getHierarchy() != null) {
      mController.onAttach();
    }
  }

  /**
   * 通知controller进行detach工作
   * 撤销发出去的请求等等
   *
   */
  private void detachController() {
    if (!mIsControllerAttached) {
      return;
    }
    mEventTracker.recordEvent(Event.ON_DETACH_CONTROLLER);
    mIsControllerAttached = false;
    if (mController != null) {
      mController.onDetach();
    }
  }

  /**
   * 根据各种条件以及变量来判断是应该attach还是detach
   * 根据打的log来看，该方法在detach以及attach的时候，还有在图片加载过程中会被多次的调用
   * 在设置hierarchy的时候会给drawable设置callback，drawable发生变动也会影响controller的行为
   *
   */
  private void attachOrDetachController() {
    if (mIsHolderAttached && mIsVisible && !mTrimmed) {
      attachController();
    } else {
      detachController();
    }
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("controllerAttached", mIsControllerAttached)
        .add("holderAttached", mIsHolderAttached)
        .add("drawableVisible", mIsVisible)
        .add("trimmed", mTrimmed)
        .add("events", mEventTracker.toString())
        .toString();
  }
}
