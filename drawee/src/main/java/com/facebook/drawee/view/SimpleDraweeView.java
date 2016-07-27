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
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;

import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.Supplier;
import com.facebook.drawee.R;
import com.facebook.drawee.generic.GenericDraweeHierarchy;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.interfaces.SimpleDraweeControllerBuilder;

/**
 * This view takes a uri as input and internally builds and sets a controller.
 *
 * SimpleDraweeView，使用Fresco基本上会用的一个View，使用的时候在Xml里面配置好行为样式之后，将uri设置给该view
 * View会自动创建并set一个controller,完成后续工作
 * 
 * <p>This class must be statically initialized in order to be used. If you are using the Fresco
 * image pipeline, use {@link com.facebook.drawee.backends.pipeline.Fresco#initialize} to do this.
 */
public class SimpleDraweeView extends GenericDraweeView {

  /**
   * Supplier是一个接口，接口里面有一个{@link Supplier#get()}方法
   * 方法返回一个T类型的值，具体返回情况看接口实现方式
   *
   * 这里传入的是一个通配符，{@link SimpleDraweeControllerBuilder}或者其子类
   *
   * 注意这里是一个静态的引用，所有的SimpleDraweeView使用的同一个sDraweeControllerBuilderSupplier
   */
  private static Supplier<? extends SimpleDraweeControllerBuilder> sDraweeControllerBuilderSupplier;

  /**
   * Initializes {@link SimpleDraweeView} with supplier of Drawee controller builders.
   *
   * 对SimpleDraweeView这个类进行初始化
   * 传入了一个{@link SimpleDraweeControllerBuilder}参数并用引用指向
   *
   * 调用此方法只有两个地方：
   * 1. {@link Fresco#initializeDrawee}有调用到，此次调用的时机是在{@link Fresco#initialize}时被调用，也就是在使用Fresco之前需要进行初始化，顺便把这个地方初始化了
   * 2. {@link VolleyDraweeAdapter}有用到，这个时候是自定义DraweeView时候用到的，可以不用管
   */
  public static void initialize(
      Supplier<? extends SimpleDraweeControllerBuilder> draweeControllerBuilderSupplier) {
    sDraweeControllerBuilderSupplier = draweeControllerBuilderSupplier;
  }

  /**
   * Shuts {@link SimpleDraweeView} down.
   * 手动释放引用
   *
   * 只有在{@link Fresco#shutDown}才调用到了，也可以在适当的时候手动调用
   */
  public static void shutDown() {
    sDraweeControllerBuilderSupplier = null;
  }

  /**
   * 一个定义了很多构造{@link DraweeController}的接口
   */
  private SimpleDraweeControllerBuilder mSimpleDraweeControllerBuilder;

  public SimpleDraweeView(Context context, GenericDraweeHierarchy hierarchy) {
    super(context, hierarchy);
    init(context, null);
  }

  public SimpleDraweeView(Context context) {
    super(context);
    init(context, null);
  }

  public SimpleDraweeView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init(context, attrs);
  }

  public SimpleDraweeView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    init(context, attrs);
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  public SimpleDraweeView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    init(context, attrs);
  }

  /**
   * 初始化方法，在构造方法中调用
   *
   *
   * @param context
   * @param attrs
     */
  private void init(Context context, @Nullable AttributeSet attrs) {

    // View中的方法，官方的解释是用来解决可视化工具(类似于preview)中自定义View报错的问题
    if (isInEditMode()) {
      return;
    }

    /**
     * {@link Preconditions}一个工具类，译为前提条件，里面有多种检测方法，例如判空,检测各种变量的状态
     * 以此来决定是否满足逻辑继续进行下去的条件
     * 这里用来检测sDraweeControllerBuilderSupplier变量是否为null
     */
    Preconditions.checkNotNull(
        sDraweeControllerBuilderSupplier,
        "SimpleDraweeView was not initialized!");
    mSimpleDraweeControllerBuilder = sDraweeControllerBuilderSupplier.get();

    if (attrs != null) {
      TypedArray gdhAttrs = context.obtainStyledAttributes(
          attrs,
          R.styleable.SimpleDraweeView);
      try {
        // 如果在xml里面指定了actualImageUri，通过解析Uri来构造一个controller并给view设置好
        if (gdhAttrs.hasValue(R.styleable.SimpleDraweeView_actualImageUri)) {
          setImageURI(Uri.parse(gdhAttrs.getString(R.styleable.SimpleDraweeView_actualImageUri)), null);
        }
      } finally {
        gdhAttrs.recycle();
      }
    }
  }

  /**
   * 对private变量的get方法?
   * protected访问限制的？没弄懂
   *
   * @return
   */
  protected SimpleDraweeControllerBuilder getControllerBuilder() {
    return mSimpleDraweeControllerBuilder;
  }

  /**
   * Displays an image given by the uri.
   *
   * ImageView对外提供的接口，从DraweeView一直Override下来
   * 该方法被遗弃了= =，推荐使用下面的两个方法
   *
   * @param uri uri of the image
   * @undeprecate
   */
  @Override
  public void setImageURI(Uri uri) {
    setImageURI(uri, null);
  }

  /**
   * Displays an image given by the uri string.
   * 传入一个Uri的String，判断是否符合格式规范并解析
   *
   * @param uriString uri string of the image
   */
  public void setImageURI(@Nullable String uriString) {
    setImageURI(uriString, null);
  }

  /**
   * Displays an image given by the uri.
   * 此方法除了传入一个Uri之外，还传入了一个Object对象，代表着调用者的context
   * 具体有啥作用暂时不明了
   *
   * 方法里面用到了之前初始化的时候从Fresco初始化方法传入builder，所有的SimpleDraweeView初始化之后都共用这个builder实例
   *
   * @param uri uri of the image
   * @param callerContext caller context
   */
  public void setImageURI(Uri uri, @Nullable Object callerContext) {
    DraweeController controller = mSimpleDraweeControllerBuilder
        .setCallerContext(callerContext)
        .setUri(uri)
        .setOldController(getController())
        .build();
    // DraweeView中的方法，将DraweeController接口实现类传入，继而交给了DraweeHolder,在DraweeView中controller是由DraweeHolder管理的
    setController(controller);
  }

  /**
   * Displays an image given by the uri string.
   *
   * @param uriString uri string of the image
   * @param callerContext caller context
   */
  public void setImageURI(@Nullable String uriString, @Nullable Object callerContext) {
    Uri uri = (uriString != null) ? Uri.parse(uriString) : null;
    setImageURI(uri, callerContext);
  }
}
