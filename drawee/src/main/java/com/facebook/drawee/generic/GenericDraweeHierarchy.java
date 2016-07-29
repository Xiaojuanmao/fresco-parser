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

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import com.facebook.common.internal.Preconditions;
import com.facebook.drawee.drawable.DrawableParent;
import com.facebook.drawee.drawable.FadeDrawable;
import com.facebook.drawee.drawable.ForwardingDrawable;
import com.facebook.drawee.drawable.MatrixDrawable;
import com.facebook.drawee.drawable.ScaleTypeDrawable;
import com.facebook.drawee.interfaces.SettableDraweeHierarchy;

import static com.facebook.drawee.drawable.ScalingUtils.ScaleType;

/**
 * A SettableDraweeHierarchy that displays placeholder image until the actual image is set.
 * If provided, failure image will be used in case of failure (placeholder otherwise).
 * If provided, retry image will be used in case of failure when retrying is enabled.
 * If provided, progressbar will be displayed until fully loaded.
 * Each image can be displayed with a different scale type (or no scaling at all).
 * Fading between the layers is supported. Rounding is supported.
 *
 * 一个可设置在需要加载的图片出现之前的展位图的hierarchy
 * 如果提供了啥failure image、retry image、progressbar等等东西都会显示上去
 * 常用的SimpleDraweeView显示多个drawable，主要逻辑就在这个类里面
 * 也支持消失动画以及圆角
 *
 * <p>
 * Example hierarchy with a placeholder, retry, failure and the actual image:
 *
 * 下面是一个设置了各种占位图的hierarchy里面，drawable的层级树
 *
 *  <pre>
 *  o RootDrawable (top level drawable)
 *  |
 *  +--o FadeDrawable
 *     |
 *     +--o ScaleTypeDrawable (placeholder branch, optional)
 *     |  |
 *     |  +--o Drawable (placeholder image)
 *     |
 *     +--o ScaleTypeDrawable (actual image branch)
 *     |  |
 *     |  +--o ForwardingDrawable (actual image wrapper)
 *     |     |
 *     |     +--o Drawable (actual image)
 *     |
 *     +--o null (progress bar branch, optional)
 *     |
 *     +--o Drawable (retry image branch, optional)
 *     |
 *     +--o ScaleTypeDrawable (failure image branch, optional)
 *        |
 *        +--o Drawable (failure image)
 *  </pre>
 *
 *
 * <p>
 * Note:
 * <ul>
 * <li> RootDrawable and FadeDrawable are always created.
 *
 * RootDrawable和FadeDrawable这两个drawable总是会被创建
 *
 * <li> All branches except the actual image branch are optional (placeholder, failure, retry,
 * progress bar). If some branch is not specified it won't be created. Index in FadeDrawable will
 * still be reserved though.
 *
 * 除了actual image分支外，所有的drawable层级分支都是可选的，配置则有，不配则无
 *
 * <li> If overlays and/or background are specified, they are added to the same fade drawable, and
 * are always being displayed.
 *
 * 如果Overlays不懂 = = 或者设置了background，则会被加到fade drawable层级上，会一直被显示出来
 *
 * <li> ScaleType and Matrix transformations will be added only if specified. If both are
 * unspecified, then the branch for that image is attached to FadeDrawable directly. Matrix
 * transformation is only supported for the actual image, and it is not recommended to be used.
 *
 * <li> Rounding, if specified, is applied to all layers. Rounded drawable can either wrap
 * FadeDrawable, or if leaf rounding is specified, each leaf drawable will be rounded separately.
 *
 * 圆角会对所有层面的drawable生效
 *
 * <li> A particular drawable instance should be used by only one DH. If more than one DH is being
 * built with the same builder, different drawable instances must be specified for each DH.
 *
 * 每个传入的drawable应该唯一属于一个DraweeHolder
 *
 * </ul>
 */
public class GenericDraweeHierarchy implements SettableDraweeHierarchy {

  // 背景drawable index
  private static final int BACKGROUND_IMAGE_INDEX = 0;

  // 占位图drawable index
  private static final int PLACEHOLDER_IMAGE_INDEX = 1;

  // 真实显示图片的drawable index
  private static final int ACTUAL_IMAGE_INDEX = 2;

  // 进度条 index
  private static final int PROGRESS_BAR_IMAGE_INDEX = 3;

  // 重试drawable index
  private static final int RETRY_IMAGE_INDEX = 4;

  // 失败占位图 index
  private static final int FAILURE_IMAGE_INDEX = 5;

  // Overlay index 不明白是干啥的
  private static final int OVERLAY_IMAGES_INDEX = 6;

  // 一张final 类型的透明drawable, 啥也显示不出来的时候顶上去替位
  private final Drawable mEmptyActualImageDrawable = new ColorDrawable(Color.TRANSPARENT);

  private final Resources mResources;

  // 关于圆角的一些参数
  private @Nullable RoundingParams mRoundingParams;

  /**
   * 应该要不停刷新这个drawable, 每次需要attach上windows的时候就显示这个mTopLevelDrawable, 这个rootdrawable代表了要显示的drawable
   *
   * RootDrawable 里面
   */
  private final RootDrawable mTopLevelDrawable;

  private final FadeDrawable mFadeDrawable;
  private final ForwardingDrawable mActualImageWrapper;

  GenericDraweeHierarchy(GenericDraweeHierarchyBuilder builder) {
    mResources = builder.getResources();
    mRoundingParams = builder.getRoundingParams();

    mActualImageWrapper = new ForwardingDrawable(mEmptyActualImageDrawable);

    int numOverlays = (builder.getOverlays() != null) ? builder.getOverlays().size() : 1;
    numOverlays += (builder.getPressedStateOverlay() != null) ? 1 : 0;

    // layer indices and count
    int numLayers = OVERLAY_IMAGES_INDEX + numOverlays;

    // array of layers
    Drawable[] layers = new Drawable[numLayers];
    layers[BACKGROUND_IMAGE_INDEX] = buildBranch(builder.getBackground(), null);
    layers[PLACEHOLDER_IMAGE_INDEX] = buildBranch(
        builder.getPlaceholderImage(),
        builder.getPlaceholderImageScaleType());
    layers[ACTUAL_IMAGE_INDEX] = buildActualImageBranch(
        mActualImageWrapper,
        builder.getActualImageScaleType(),
        builder.getActualImageFocusPoint(),
        builder.getActualImageMatrix(),
        builder.getActualImageColorFilter());
    layers[PROGRESS_BAR_IMAGE_INDEX] = buildBranch(
        builder.getProgressBarImage(),
        builder.getProgressBarImageScaleType());
    layers[RETRY_IMAGE_INDEX] = buildBranch(
        builder.getRetryImage(),
        builder.getRetryImageScaleType());
    layers[FAILURE_IMAGE_INDEX] = buildBranch(
        builder.getFailureImage(),
        builder.getFailureImageScaleType());
    if (numOverlays > 0) {
      int index = 0;
      if (builder.getOverlays() != null) {
        for (Drawable overlay : builder.getOverlays()) {
          layers[OVERLAY_IMAGES_INDEX + index++] = buildBranch(overlay, null);
        }
      } else {
        index = 1; // reserve space for one overlay
      }
      if (builder.getPressedStateOverlay() != null) {
        layers[OVERLAY_IMAGES_INDEX + index] = buildBranch(builder.getPressedStateOverlay(), null);
      }
    }

    // fade drawable composed of layers
    mFadeDrawable = new FadeDrawable(layers);
    mFadeDrawable.setTransitionDuration(builder.getFadeDuration());

    // rounded corners drawable (optional)
    Drawable maybeRoundedDrawable =
        WrappingUtils.maybeWrapWithRoundedOverlayColor(mFadeDrawable, mRoundingParams);

    // top-level drawable
    mTopLevelDrawable = new RootDrawable(maybeRoundedDrawable);
    mTopLevelDrawable.mutate();

    resetFade();
  }

  @Nullable
  private Drawable buildActualImageBranch(
      Drawable drawable,
      @Nullable ScaleType scaleType,
      @Nullable PointF focusPoint,
      @Nullable Matrix matrix,
      @Nullable ColorFilter colorFilter) {
    drawable.setColorFilter(colorFilter);
    drawable = WrappingUtils.maybeWrapWithScaleType(drawable, scaleType, focusPoint);
    drawable = WrappingUtils.maybeWrapWithMatrix(drawable, matrix);
    return drawable;
  }

  /** Applies scale type and rounding (both if specified). */
  @Nullable
  private Drawable buildBranch(@Nullable Drawable drawable, @Nullable ScaleType scaleType) {
    drawable = WrappingUtils.maybeApplyLeafRounding(drawable, mRoundingParams, mResources);
    drawable = WrappingUtils.maybeWrapWithScaleType(drawable, scaleType);
    return drawable;
  }

  private void resetActualImages() {
    mActualImageWrapper.setDrawable(mEmptyActualImageDrawable);
  }

  private void resetFade() {
    if (mFadeDrawable != null) {
      mFadeDrawable.beginBatchMode();
      // turn on all layers (backgrounds, branches, overlays)
      mFadeDrawable.fadeInAllLayers();
      // turn off branches (leaving backgrounds and overlays on)
      fadeOutBranches();
      // turn on placeholder
      fadeInLayer(PLACEHOLDER_IMAGE_INDEX);
      mFadeDrawable.finishTransitionImmediately();
      mFadeDrawable.endBatchMode();
    }
  }

  private void fadeOutBranches() {
    fadeOutLayer(PLACEHOLDER_IMAGE_INDEX);
    fadeOutLayer(ACTUAL_IMAGE_INDEX);
    fadeOutLayer(PROGRESS_BAR_IMAGE_INDEX);
    fadeOutLayer(RETRY_IMAGE_INDEX);
    fadeOutLayer(FAILURE_IMAGE_INDEX);
  }

  private void fadeInLayer(int index) {
    if (index >= 0) {
      mFadeDrawable.fadeInLayer(index);
    }
  }

  private void fadeOutLayer(int index) {
    if (index >= 0) {
      mFadeDrawable.fadeOutLayer(index);
    }
  }

  private void setProgress(float progress) {
    Drawable progressBarDrawable = getParentDrawableAtIndex(PROGRESS_BAR_IMAGE_INDEX).getDrawable();
    if (progressBarDrawable == null) {
      return;
    }

    // display progressbar when not fully loaded, hide otherwise
    if (progress >= 0.999f) {
      if (progressBarDrawable instanceof Animatable) {
        ((Animatable) progressBarDrawable).stop();
      }
      fadeOutLayer(PROGRESS_BAR_IMAGE_INDEX);
    } else {
      if (progressBarDrawable instanceof Animatable) {
        ((Animatable) progressBarDrawable).start();
      }
      fadeInLayer(PROGRESS_BAR_IMAGE_INDEX);
    }
    // set drawable level, scaled to [0, 10000] per drawable specification
    progressBarDrawable.setLevel(Math.round(progress * 10000));
  }

  // SettableDraweeHierarchy interface

  @Override
  public Drawable getTopLevelDrawable() {
    return mTopLevelDrawable;
  }

  @Override
  public void reset() {
    resetActualImages();
    resetFade();
  }

  @Override
  public void setImage(Drawable drawable, float progress, boolean immediate) {
    drawable = WrappingUtils.maybeApplyLeafRounding(drawable, mRoundingParams, mResources);
    drawable.mutate();
    mActualImageWrapper.setDrawable(drawable);
    mFadeDrawable.beginBatchMode();
    fadeOutBranches();
    fadeInLayer(ACTUAL_IMAGE_INDEX);
    setProgress(progress);
    if (immediate) {
      mFadeDrawable.finishTransitionImmediately();
    }
    mFadeDrawable.endBatchMode();
  }

  @Override
  public void setProgress(float progress, boolean immediate) {
    mFadeDrawable.beginBatchMode();
    setProgress(progress);
    if (immediate) {
      mFadeDrawable.finishTransitionImmediately();
    }
    mFadeDrawable.endBatchMode();
  }

  @Override
  public void setFailure(Throwable throwable) {
    mFadeDrawable.beginBatchMode();
    fadeOutBranches();
    if (mFadeDrawable.getDrawable(FAILURE_IMAGE_INDEX) != null) {
      fadeInLayer(FAILURE_IMAGE_INDEX);
    } else {
      fadeInLayer(PLACEHOLDER_IMAGE_INDEX);
    }
    mFadeDrawable.endBatchMode();
  }

  @Override
  public void setRetry(Throwable throwable) {
    mFadeDrawable.beginBatchMode();
    fadeOutBranches();
    if (mFadeDrawable.getDrawable(RETRY_IMAGE_INDEX) != null) {
      fadeInLayer(RETRY_IMAGE_INDEX);
    } else {
      fadeInLayer(PLACEHOLDER_IMAGE_INDEX);
    }
    mFadeDrawable.endBatchMode();
  }

  @Override
  public void setControllerOverlay(@Nullable Drawable drawable) {
    mTopLevelDrawable.setControllerOverlay(drawable);
  }

  // Helper methods for accessing layers

  /**
   * Gets the lowest parent drawable for the layer at the specified index.
   *
   * Following drawables are considered as parents: FadeDrawable, MatrixDrawable, ScaleTypeDrawable.
   * This is because those drawables are added automatically by the hierarchy (if specified),
   * whereas their children are created externally by the client code. When we need to change the
   * previously set drawable this is the parent whose child needs to be replaced.
   */
  private DrawableParent getParentDrawableAtIndex(int index) {
    DrawableParent parent = mFadeDrawable.getDrawableParentForIndex(index);
    if (parent.getDrawable() instanceof MatrixDrawable) {
      parent = (MatrixDrawable) parent.getDrawable();
    }
    if (parent.getDrawable() instanceof ScaleTypeDrawable) {
      parent = (ScaleTypeDrawable) parent.getDrawable();
    }
    return parent;
  }

  /**
   * Sets the drawable at the specified index while keeping the old scale type and rounding.
   * In case the given drawable is null, scale type gets cleared too.
   */
  private void setChildDrawableAtIndex(int index, @Nullable Drawable drawable) {
    if (drawable == null) {
      mFadeDrawable.setDrawable(index, null);
      return;
    }
    drawable = WrappingUtils.maybeApplyLeafRounding(drawable, mRoundingParams, mResources);
    getParentDrawableAtIndex(index).setDrawable(drawable);
  }

  /**
   * Gets the ScaleTypeDrawable at the specified index.
   * In case there is no child at the specified index, a NullPointerException is thrown.
   * In case there is a child, but the ScaleTypeDrawable does not exist,
   * the child will be wrapped with a new ScaleTypeDrawable.
   */
  private ScaleTypeDrawable getScaleTypeDrawableAtIndex(int index) {
    DrawableParent parent = getParentDrawableAtIndex(index);
    if (parent instanceof ScaleTypeDrawable) {
      return (ScaleTypeDrawable) parent;
    } else {
      return WrappingUtils.wrapChildWithScaleType(parent, ScaleType.FIT_XY);
    }
  }

  /**
   * Returns whether the given layer has a scale type drawable.
   */
  private boolean hasScaleTypeDrawableAtIndex(int index) {
    DrawableParent parent = getParentDrawableAtIndex(index);
    return (parent instanceof ScaleTypeDrawable);
  }

  // Mutability

  /** Sets the fade duration. */
  public void setFadeDuration(int durationMs) {
    mFadeDrawable.setTransitionDuration(durationMs);
  }

  /** Gets the fade duration. */
  public int getFadeDuration() {
    return mFadeDrawable.getTransitionDuration();
  }

  /** Sets the actual image focus point. */
  public void setActualImageFocusPoint(PointF focusPoint) {
    Preconditions.checkNotNull(focusPoint);
    getScaleTypeDrawableAtIndex(ACTUAL_IMAGE_INDEX).setFocusPoint(focusPoint);
  }

  /** Sets the actual image scale type. */
  public void setActualImageScaleType(ScaleType scaleType) {
    Preconditions.checkNotNull(scaleType);
    getScaleTypeDrawableAtIndex(ACTUAL_IMAGE_INDEX).setScaleType(scaleType);
  }

  public @Nullable ScaleType getActualImageScaleType() {
    if (!hasScaleTypeDrawableAtIndex(ACTUAL_IMAGE_INDEX)) {
      return null;
    }
    return getScaleTypeDrawableAtIndex(ACTUAL_IMAGE_INDEX).getScaleType();
  }

  /** Sets the color filter to be applied on the actual image. */
  public void setActualImageColorFilter(ColorFilter colorfilter) {
    mActualImageWrapper.setColorFilter(colorfilter);
  }

  /** Gets the non-cropped post-scaling bounds of the actual image. */
  public void getActualImageBounds(RectF outBounds) {
    mActualImageWrapper.getTransformedBounds(outBounds);
  }

  /** Sets a new placeholder drawable with old scale type. */
  public void setPlaceholderImage(@Nullable Drawable drawable) {
    setChildDrawableAtIndex(PLACEHOLDER_IMAGE_INDEX, drawable);
  }

  /** Sets a new placeholder drawable with scale type. */
  public void setPlaceholderImage(Drawable drawable, ScaleType scaleType) {
    setChildDrawableAtIndex(PLACEHOLDER_IMAGE_INDEX, drawable);
    getScaleTypeDrawableAtIndex(PLACEHOLDER_IMAGE_INDEX).setScaleType(scaleType);
  }

  /**
   * @return true if there is a placeholder image set.
   */
  public boolean hasPlaceholderImage() {
    return getParentDrawableAtIndex(PLACEHOLDER_IMAGE_INDEX) != null;
  }

  /** Sets the placeholder image focus point. */
  public void setPlaceholderImageFocusPoint(PointF focusPoint) {
    Preconditions.checkNotNull(focusPoint);
    getScaleTypeDrawableAtIndex(PLACEHOLDER_IMAGE_INDEX).setFocusPoint(focusPoint);
  }

  /**
   * Sets a new placeholder drawable with old scale type.
   *
   * @param resourceId an identifier of an Android drawable or color resource.
   */
  public void setPlaceholderImage(int resourceId) {
    setPlaceholderImage(mResources.getDrawable(resourceId));
  }

  /**
   * Sets a new placeholder drawable with scale type.
   *
   * @param resourceId an identifier of an Android drawable or color resource.
   * @param scaleType a new scale type.
   */
  public void setPlaceholderImage(int resourceId, ScaleType scaleType) {
    setPlaceholderImage(mResources.getDrawable(resourceId), scaleType);
  }

  /** Sets a new failure drawable with old scale type. */
  public void setFailureImage(@Nullable Drawable drawable) {
    setChildDrawableAtIndex(FAILURE_IMAGE_INDEX, drawable);
  }

  /** Sets a new failure drawable with scale type. */
  public void setFailureImage(Drawable drawable, ScaleType scaleType) {
    setChildDrawableAtIndex(FAILURE_IMAGE_INDEX, drawable);
    getScaleTypeDrawableAtIndex(FAILURE_IMAGE_INDEX).setScaleType(scaleType);
  }
  
  /**
   * Sets a new failure drawable with old scale type.
   *
   * @param resourceId an identifier of an Android drawable or color resource.
   */
  public void setFailureImage(int resourceId) {
    setFailureImage(mResources.getDrawable(resourceId));
  }
  
  /**
   * Sets a new failure drawable with scale type.
   *
   * @param resourceId an identifier of an Android drawable or color resource.
   * @param scaleType a new scale type.
   */
  public void setFailureImage(int resourceId, ScaleType scaleType) {
    setFailureImage(mResources.getDrawable(resourceId), scaleType);
  }

  /** Sets a new retry drawable with old scale type. */
  public void setRetryImage(@Nullable Drawable drawable) {
    setChildDrawableAtIndex(RETRY_IMAGE_INDEX, drawable);
  }

  /** Sets a new retry drawable with scale type. */
  public void setRetryImage(Drawable drawable, ScaleType scaleType) {
    setChildDrawableAtIndex(RETRY_IMAGE_INDEX, drawable);
    getScaleTypeDrawableAtIndex(RETRY_IMAGE_INDEX).setScaleType(scaleType);
  }
  
  /**
   * Sets a new retry drawable with old scale type.
   *
   * @param resourceId an identifier of an Android drawable or color resource.
   */
  public void setRetryImage(int resourceId) {
    setRetryImage(mResources.getDrawable(resourceId));
  }
  
  /**
   * Sets a new retry drawable with scale type.
   *
   * @param resourceId an identifier of an Android drawable or color resource.
   * @param scaleType a new scale type.
   */
  public void setRetryImage(int resourceId, ScaleType scaleType) {
    setRetryImage(mResources.getDrawable(resourceId), scaleType);
  }

  /** Sets a new progress bar drawable with old scale type. */
  public void setProgressBarImage(@Nullable Drawable drawable) {
    setChildDrawableAtIndex(PROGRESS_BAR_IMAGE_INDEX, drawable);
  }

  /** Sets a new progress bar drawable with scale type. */
  public void setProgressBarImage(Drawable drawable, ScaleType scaleType) {
    setChildDrawableAtIndex(PROGRESS_BAR_IMAGE_INDEX, drawable);
    getScaleTypeDrawableAtIndex(PROGRESS_BAR_IMAGE_INDEX).setScaleType(scaleType);
  }
  
  /**
   * Sets a new progress bar drawable with old scale type.
   *
   * @param resourceId an identifier of an Android drawable or color resource.
   */
  public void setProgressBarImage(int resourceId) {
    setProgressBarImage(mResources.getDrawable(resourceId));
  }
  
  /**
   * Sets a new progress bar drawable with scale type.
   *
   * @param resourceId an identifier of an Android drawable or color resource.
   * @param scaleType a new scale type.
   */
  public void setProgressBarImage(int resourceId, ScaleType scaleType) {
    setProgressBarImage(mResources.getDrawable(resourceId), scaleType);
  }

  /** Sets the background image if allowed. */
  public void setBackgroundImage(@Nullable Drawable drawable) {
    setChildDrawableAtIndex(BACKGROUND_IMAGE_INDEX, drawable);
  }

  /**
   * Sets a new overlay image at the specified index.
   *
   * This method will throw if the given index is out of bounds.
   *
   * @param drawable background image
   */
  public void setOverlayImage(int index, @Nullable Drawable drawable) {
    // Note that overlays are by definition top-most and therefore the last elements in the array.
    Preconditions.checkArgument(
        index >= 0 && OVERLAY_IMAGES_INDEX + index < mFadeDrawable.getNumberOfLayers(),
        "The given index does not correspond to an overlay image.");
    setChildDrawableAtIndex(OVERLAY_IMAGES_INDEX + index, drawable);
  }

  /** Sets the overlay image if allowed. */
  public void setOverlayImage(@Nullable Drawable drawable) {
    setOverlayImage(0, drawable);
  }

  /** Sets the rounding params. */
  public void setRoundingParams(@Nullable RoundingParams roundingParams) {
    mRoundingParams = roundingParams;
    WrappingUtils.updateOverlayColorRounding(mTopLevelDrawable, mRoundingParams);
    for (int i = 0; i < mFadeDrawable.getNumberOfLayers(); i++) {
      WrappingUtils.updateLeafRounding(getParentDrawableAtIndex(i), mRoundingParams, mResources);
    }
  }

  /** Gets the rounding params. */
  @Nullable
  public RoundingParams getRoundingParams() {
    return mRoundingParams;
  }
}
