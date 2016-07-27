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
import android.os.Build;
import android.util.AttributeSet;

import com.facebook.drawee.generic.GenericDraweeHierarchy;
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder;
import com.facebook.drawee.generic.GenericDraweeHierarchyInflater;

/**
 * DraweeView that uses GenericDraweeHierarchy.
 *
 * 继承自DraweeView，使用的是GenericDraweeHierarchy
 * 解析xml属性交给了GenericDraweeHierarchyInflater
 *
 * The hierarchy can be set either programmatically or inflated from XML.
 * See {@link GenericDraweeHierarchyInflater} for supported XML attributes.
 */
public class GenericDraweeView extends DraweeView<GenericDraweeHierarchy> {

  public GenericDraweeView(Context context, GenericDraweeHierarchy hierarchy) {
    super(context);
    setHierarchy(hierarchy);
  }

  public GenericDraweeView(Context context) {
    super(context);
    inflateHierarchy(context, null);
  }

  public GenericDraweeView(Context context, AttributeSet attrs) {
    super(context, attrs);
    inflateHierarchy(context, attrs);
  }

  public GenericDraweeView(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    inflateHierarchy(context, attrs);
  }

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  public GenericDraweeView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    inflateHierarchy(context, attrs);
  }

  /**
   * 该方法对xml里面设置的各种属性进行了解析
   *
   * @param context
   * @param attrs
     */
  protected void inflateHierarchy(Context context, @Nullable AttributeSet attrs) {
    // 静态方法，调用之后返回一个
    GenericDraweeHierarchyBuilder builder = GenericDraweeHierarchyInflater.inflateBuilder(context, attrs);

    // 给DraweeView设置好了长宽比例，方法里面调用了requestLayout()，请求重新布局
    setAspectRatio(builder.getDesiredAspectRatio());

    // 将前面构造好的hierarchy给DraweeView设置上，DraweeView将其又传递给了DraweeHolder保管，DraweeHolder在DraweeView中应该扮演的角色挺重要的
    setHierarchy(builder.build());
  }
}
