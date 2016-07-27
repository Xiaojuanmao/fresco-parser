/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.drawee.view;

import javax.annotation.Nullable;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ImageView;

import com.facebook.common.internal.Objects;
import com.facebook.drawee.interfaces.DraweeHierarchy;
import com.facebook.drawee.interfaces.DraweeController;

/**
 * View that displays a {@link DraweeHierarchy}.
 * 这个类直接继承自ImageView，应该说是fresco里面比较基类的一个View了
 * 用来展示DraweeHierarchy里的内容
 *
 *
 * <p> Hierarchy should be set prior to using this view. See {@code setHierarchy}. Because creating
 * a hierarchy is an expensive operation, it is recommended this be done once per view, typically
 * near creation time.
 * 在使用DraweeView以及其衍生View之前就需要设置好Hierarchy，因为创建hierarchy是一个比较耗资源的操作
 * 在View创建的时候就new出来
 *
 * <p> In order to display an image, controller has to be set. See {@code setController}.
 *
 * 注意必须要设置好controller，controller以及Hierarchy都托管在了DraweeHolder中，只需要和DraweeHolder交互就好了
 *
 * <p> Although ImageView is subclassed instead of subclassing View directly, this class does not
 * support ImageView's setImageXxx, setScaleType and similar methods. Extending ImageView is a short
 * term solution in order to inherit some of its implementation (padding calculations, etc.).
 * This class is likely to be converted to extend View directly in the future, so avoid using
 * ImageView's methods and properties.
 *
 * 尽管继承自ImageView，但是{@link ImageView#setImageResource(int)}等等一系列方式都不可用，包括{@link ImageView#setScaleType(ScaleType)}
 * 继承ImageView只是一种为了沿用其部分已实现的功能（例如padding的计算等等）的捷径
 * 后面可能回换成继承自View,因此在使用的时候不要以来于ImageView的一些属性，以免造成版本不兼容的问题
 *
 */
public class DraweeView<DH extends DraweeHierarchy> extends ImageView {

  // Spec类是一个保存着Height和Width的holder， 用来存储draweeView的宽高
  private final AspectRatioMeasure.Spec mMeasureSpec = new AspectRatioMeasure.Spec();

  // View的宽高比例
  private float mAspectRatio = 0;

  // 这个类管理着从外界传入的Controller以及Hierarchy
  private DraweeHolder<DH> mDraweeHolder;

  // 标志着View是否已经被初始化过了
  private boolean mInitialised = false;

  /**
   * 这里我一直不明白为啥不写成下面这种方式
   * {@code this(context, null)}
   * 这样init(context)这行代码救治需要在第三个构造方法里面写了
   */
  public DraweeView(Context context) {
    super(context);
    init(context);
  }

  public DraweeView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init(context);
  }

  public DraweeView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    init(context);
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  public DraweeView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context,attrs,defStyleAttr,defStyleRes);
    init(context);
  }

  /**
   * This method is idempotent so it only has effect the first time it's called
   *
   * 这个方法只有在第一次调用的时候才会生效
   * 后面的调用都会被{@link DraweeView#mInitialised}参数return掉
   *
   */
  private void init(Context context) {
    if (mInitialised) {
      return;
    }
    mInitialised = true;

    // 创建view的时候holder就被初始化了，为后面接入controller和hierarchy做准备
    mDraweeHolder = DraweeHolder.create(null, context);

    // 如果系统版本在萝莉炮之上，从imageview那边拿到用xml写的drawable，给set上
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      ColorStateList imageTintList = getImageTintList();
      if (imageTintList == null) {
        return;
      }
      setColorFilter(imageTintList.getDefaultColor());
    }
  }

  /**
   * Sets the hierarchy.
   *
   * 此方法可以在运行时动态的设置hierarchy来改变draweeview的行为以及状态
   *
   */
  public void setHierarchy(DH hierarchy) {
    mDraweeHolder.setHierarchy(hierarchy);
    super.setImageDrawable(mDraweeHolder.getTopLevelDrawable());
  }

  /** Gets the hierarchy if set, throws NPE otherwise. */
  public DH getHierarchy() {
    return mDraweeHolder.getHierarchy();
  }

  /** Returns whether the hierarchy is set or not. */
  public boolean hasHierarchy() {
    return mDraweeHolder.hasHierarchy();
  }

  /**
   *  Gets the top-level drawable if hierarchy is set, null otherwise.
   *
   *  draweeholder开放的接口，实际上是从hierarchy里面get到drawable
   *  至于topLevelDrawable是个什么东西后面再说，这么一进去好象就很深了= =
   *
   */
  @Nullable public Drawable getTopLevelDrawable() {
    return mDraweeHolder.getTopLevelDrawable();
  }

  /**
   *  Sets the controller.
   *  将传入的Controller递给DraweeHolder来托管
   */
  public void setController(@Nullable DraweeController draweeController) {
    mDraweeHolder.setController(draweeController);
    super.setImageDrawable(mDraweeHolder.getTopLevelDrawable());
  }

  /** Gets the controller if set, null otherwise. */
  @Nullable public DraweeController getController() {
    return mDraweeHolder.getController();
  }

  /** Returns whether the controller is set or not.  */
  public boolean hasController() {
    return mDraweeHolder.getController() != null;
  }

  /**
   * 下面这一连串的attach以及detached方法，都会去触发draweeholder里面的方法
   * draweeholder里面又会调用一些其他的逻辑来响应系统发出的信号（例如：recyclerview的item滑出了屏幕等等
   * 自定义的时候需要处理好这些函数调用的时机，避免各种内存泄漏等等
   *
   */

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    onAttach();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    onDetach();
  }

  @Override
  public void onStartTemporaryDetach() {
    super.onStartTemporaryDetach();
    onDetach();
  }

  @Override
  public void onFinishTemporaryDetach() {
    super.onFinishTemporaryDetach();
    onAttach();
  }

  /** Called by the system to attach. Subclasses may override. */
  protected void onAttach() {
    doAttach();
  }

  /**  Called by the system to detach. Subclasses may override. */
  protected void onDetach() {
    doDetach();
  }

  /**
   * Does the actual work of attaching.
   *
   * Non-test subclasses should NOT override. Use onAttach for custom code.
   */
  protected void doAttach() {
    mDraweeHolder.onAttach();
  }

  /**
   * Does the actual work of detaching.
   *
   * Non-test subclasses should NOT override. Use onDetach for custom code.
   */
  protected void doDetach() {
    mDraweeHolder.onDetach();
  }

  /**
   * 处理触摸事件的时候会交给draweeholder，holder会交给controller来先处理
   *
   * @param event
   * @return
     */
  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (mDraweeHolder.onTouchEvent(event)) {
      return true;
    }
    return super.onTouchEvent(event);
  }

  /**
   * Use this method only when using this class as an ordinary ImageView.
   * @deprecated Use {@link #setController(DraweeController)} instead.
   *
   * 这个方法和下面那几个被丢掉的方法，官方不建议使用，依赖ImageView可能会撤掉，所以= =你懂的
   *
   */
  @Override
  @Deprecated
  public void setImageDrawable(Drawable drawable) {
    init(getContext());
    mDraweeHolder.setController(null);
    super.setImageDrawable(drawable);
  }

  /**
   * Use this method only when using this class as an ordinary ImageView.
   * @deprecated Use {@link #setController(DraweeController)} instead.
   */
  @Override
  @Deprecated
  public void setImageBitmap(Bitmap bm) {
    init(getContext());
    mDraweeHolder.setController(null);
    super.setImageBitmap(bm);
  }

  /**
   * Use this method only when using this class as an ordinary ImageView.
   * @deprecated Use {@link #setController(DraweeController)} instead.
   */
  @Override
  @Deprecated
  public void setImageResource(int resId) {
    init(getContext());
    mDraweeHolder.setController(null);
    super.setImageResource(resId);
  }

  /**
   * Use this method only when using this class as an ordinary ImageView.
   * @deprecated Use {@link #setController(DraweeController)} instead.
   */
  @Override
  @Deprecated
  public void setImageURI(Uri uri) {
    init(getContext());
    mDraweeHolder.setController(null);
    super.setImageURI(uri);
  }

  /**
   * Sets the desired aspect ratio (w/h).
   *
   * 设置view长宽的比例
   */
  public void setAspectRatio(float aspectRatio) {
    if (aspectRatio == mAspectRatio) {
      return;
    }
    mAspectRatio = aspectRatio;
    requestLayout();
  }

  /**
   * Gets the desired aspect ratio (w/h).
   */
  public float getAspectRatio() {
    return mAspectRatio;
  }

  /**
   *
   * 将计算宽高的步骤交给了AspectRatioMeasure里的静态方法
   * 结合宽高的比例aspectRatio，将计算的结果存在mMeasureSpec中
   *
   * @param widthMeasureSpec
   * @param heightMeasureSpec
   */
  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    mMeasureSpec.width = widthMeasureSpec;
    mMeasureSpec.height = heightMeasureSpec;
    AspectRatioMeasure.updateMeasureSpec(
        mMeasureSpec,
        mAspectRatio,
        getLayoutParams(),
        getPaddingLeft() + getPaddingRight(),
        getPaddingTop() + getPaddingBottom());
    super.onMeasure(mMeasureSpec.width, mMeasureSpec.height);
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("holder", mDraweeHolder != null ? mDraweeHolder.toString(): "<no holder set>")
        .toString();
  }
}
