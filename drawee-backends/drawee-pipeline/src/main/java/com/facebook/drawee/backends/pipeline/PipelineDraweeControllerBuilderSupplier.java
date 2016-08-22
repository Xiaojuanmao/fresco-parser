/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.drawee.backends.pipeline;

import android.content.Context;

import com.facebook.common.executors.UiThreadImmediateExecutorService;
import com.facebook.common.internal.Supplier;
import com.facebook.drawee.components.DeferredReleaser;
import com.facebook.drawee.controller.ControllerListener;
import com.facebook.imagepipeline.core.ImagePipeline;
import com.facebook.imagepipeline.core.ImagePipelineFactory;
import com.facebook.imagepipeline.animated.factory.AnimatedDrawableFactory;
import com.facebook.imagepipeline.animated.factory.AnimatedFactory;

import java.util.Set;

/**
 * 在Fresco初始化的时候就创建了此类的一个实例
 * 实例里面包含了一些final类型的实例
 * SimpleDraweeView用的DraweeController也是从这里拿出去的builder构造出来的
 *
 * 之前在看DraweeHolder、DraweeHierarchy以及各种RootDrawable的时候
 * 一直都没有看到关于ImagePipeline、DraweeController的影子
 * 在最初就分割开了 = =，这耦合我服
 * 简直就是逻辑一边、显示一边
 *
 */
public class PipelineDraweeControllerBuilderSupplier implements
    Supplier<PipelineDraweeControllerBuilder> {

  // 初始化时保留applicationcontext的一个引用
  private final Context mContext;

  // 框架介绍里面说是用来处理图片加载的类
  private final ImagePipeline mImagePipeline;

  // 方便创建PipelineDraweController的工具类
  private final PipelineDraweeControllerFactory mPipelineDraweeControllerFactory;

  // 一个监听器的集合，暂时不知道有什么用处
  private final Set<ControllerListener> mBoundControllerListeners;

  public PipelineDraweeControllerBuilderSupplier(Context context) {
    this(context, ImagePipelineFactory.getInstance());
  }

  public PipelineDraweeControllerBuilderSupplier(
      Context context,
      ImagePipelineFactory imagePipelineFactory) {
    this(context, imagePipelineFactory, null);
  }

  public PipelineDraweeControllerBuilderSupplier(
      Context context,
      ImagePipelineFactory imagePipelineFactory,
      Set<ControllerListener> boundControllerListeners) {
    mContext = context;

    // 通过ImagePipeline工厂的单例创建了一个ImagePipeline实例,整个fresco都用这个imagepipeline
    mImagePipeline = imagePipelineFactory.getImagePipeline();

    //
    final AnimatedFactory animatedFactory = imagePipelineFactory.getAnimatedFactory();
    AnimatedDrawableFactory animatedDrawableFactory = null;
    if (animatedFactory != null) {
      animatedDrawableFactory = animatedFactory.getAnimatedDrawableFactory(context);
    }

    mPipelineDraweeControllerFactory = new PipelineDraweeControllerFactory(
        context.getResources(),
        DeferredReleaser.getInstance(),
        animatedDrawableFactory,
        UiThreadImmediateExecutorService.getInstance(),
        mImagePipeline.getBitmapMemoryCache());
    mBoundControllerListeners = boundControllerListeners;
  }

  /**
   * 在最开始Fresco初始化的时候就保留了一个此类的引用
   * 传给了SimpleDraweeView等类中
   * 需要draweecontrollerbuilder地方的时候直接get()方法，就能直接新建一个Builder
   *
   * @return
   */
  @Override
  public PipelineDraweeControllerBuilder get() {
    return new PipelineDraweeControllerBuilder(
        mContext,
        mPipelineDraweeControllerFactory,
        mImagePipeline,
        mBoundControllerListeners);
  }
}
