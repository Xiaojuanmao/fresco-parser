/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.common.memory;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Used to make available a list of all UI objects in the system that can
 * be trimmed on background. Currently stores DraweeHolder objects, both
 * inside and outside views.
 *
 * 将在background可调的ui对象都存在一个list中，随时能够访问
 * 目前也就仅仅将holder存在了这里，使得从外界通过这个类也能够访问到holder
 *
 */
public class MemoryUiTrimmableRegistry {

  /**
   * 用一个WeakHashMap来存储,对key的保存使用的弱引用
   *
   * MemoryUiTrimmable
   */

  private static final Set<MemoryUiTrimmable> sUiTrimmables =
      Collections.newSetFromMap(new WeakHashMap<MemoryUiTrimmable, Boolean>());

  public static void registerUiTrimmable(MemoryUiTrimmable uiTrimmable) {
    sUiTrimmables.add(uiTrimmable);
  }

  public static Iterable<MemoryUiTrimmable> iterable() {
    return sUiTrimmables;
  }

  // There is no unregister! The trimmables are stored in a weak-hash set,
  // so the GC will take care of that.

}
