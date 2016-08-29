/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.memory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.VisibleForTesting;

/**
 * Helper class for interacting with java streams, similar to guava's ByteSteams.
 * To prevent numerous allocations of temp buffers pool of byte arrays is used.
 *
 * 为了防止大量的字节数组的临时缓冲池的分配
 * 用来与java中的流进行交互的一个辅助类
 *
 * 也就是对在is和os中间的copy过程做一个优化,提高了读写的效率
 * 不同一个字节一个字节的读取，也不用不停的创建byte数组，而是用ByteArrayPool预先存在的字节数租池来进行空间的节省
 */
public class PooledByteStreams {
  /**
   * Size of temporary buffer to use for copying (16 kb)
   * 临时用来作为拷贝的一个缓冲区默认大小
   * 在bitmap中的inTempStorage也建议将临时缓冲去大小设置成16kb，不知道为啥= =
   */
  private static final int DEFAULT_TEMP_BUF_SIZE = 16 * 1024;

  private final int mTempBufSize;
  private final ByteArrayPool mByteArrayPool;

  public PooledByteStreams(ByteArrayPool byteArrayPool) {
    this(byteArrayPool, DEFAULT_TEMP_BUF_SIZE);
  }

  @VisibleForTesting
  PooledByteStreams(ByteArrayPool byteArrayPool, int tempBufSize) {
    Preconditions.checkArgument(tempBufSize > 0);
    mTempBufSize = tempBufSize;
    mByteArrayPool = byteArrayPool;
  }

  /**
   * Copy all bytes from InputStream to OutputStream.
   * @param from InputStream
   * @param to OutputStream
   * @return number of copied bytes
   * @throws IOException
   */
  public long copy(final InputStream from, final OutputStream to) throws IOException {
    long count = 0;
    byte[] tmp = mByteArrayPool.get(mTempBufSize);

    try {
      while (true) {
        int read = from.read(tmp, 0, mTempBufSize);
        if (read == -1) {
          return count;
        }
        to.write(tmp, 0, read);
        count += read;
      }
    } finally {
      mByteArrayPool.release(tmp);
    }
  }

  /**
   * Copy at most number of bytes from InputStream to OutputStream.
   * @param from InputStream
   * @param to OutputStream
   * @param bytesToCopy bytes to copy
   * @return number of copied bytes
   * @throws IOException
   */
  public long copy(
      final InputStream from,
      final OutputStream to,
      final long bytesToCopy) throws IOException {
    Preconditions.checkState(bytesToCopy > 0);
    long copied = 0;
    byte[] tmp = mByteArrayPool.get(mTempBufSize);

    try {
      while (copied < bytesToCopy) {
        int read = from.read(tmp, 0, (int) Math.min(mTempBufSize, bytesToCopy - copied));
        if (read == -1) {
          return copied;
        }
        to.write(tmp, 0, read);
        copied += read;
      }
      return copied;
    } finally {
      mByteArrayPool.release(tmp);
    }
  }
}
