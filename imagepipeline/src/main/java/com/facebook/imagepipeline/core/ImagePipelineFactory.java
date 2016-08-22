/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.core;

import javax.annotation.concurrent.NotThreadSafe;

import android.content.Context;
import android.os.Build;
import android.support.v4.util.Pools;

import com.facebook.cache.common.CacheKey;
import com.facebook.cache.disk.DiskCacheConfig;
import com.facebook.cache.disk.DiskStorage;
import com.facebook.cache.disk.DiskStorageCache;
import com.facebook.cache.disk.FileCache;
import com.facebook.common.internal.AndroidPredicates;
import com.facebook.common.internal.Preconditions;
import com.facebook.imagepipeline.animated.factory.AnimatedFactory;
import com.facebook.imagepipeline.animated.factory.AnimatedFactoryProvider;
import com.facebook.imagepipeline.animated.factory.AnimatedImageFactory;
import com.facebook.imagepipeline.bitmaps.ArtBitmapFactory;
import com.facebook.imagepipeline.bitmaps.EmptyJpegGenerator;
import com.facebook.imagepipeline.bitmaps.GingerbreadBitmapFactory;
import com.facebook.imagepipeline.bitmaps.HoneycombBitmapFactory;
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory;
import com.facebook.imagepipeline.cache.BitmapCountingMemoryCacheFactory;
import com.facebook.imagepipeline.cache.BitmapMemoryCacheFactory;
import com.facebook.imagepipeline.cache.BufferedDiskCache;
import com.facebook.imagepipeline.cache.CountingMemoryCache;
import com.facebook.imagepipeline.cache.EncodedCountingMemoryCacheFactory;
import com.facebook.imagepipeline.cache.EncodedMemoryCacheFactory;
import com.facebook.imagepipeline.cache.MemoryCache;
import com.facebook.imagepipeline.decoder.ImageDecoder;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.memory.PoolFactory;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.imagepipeline.platform.ArtDecoder;
import com.facebook.imagepipeline.platform.GingerbreadPurgeableDecoder;
import com.facebook.imagepipeline.platform.KitKatPurgeableDecoder;
import com.facebook.imagepipeline.platform.PlatformDecoder;
import com.facebook.imagepipeline.producers.ThreadHandoffProducerQueue;

import java.util.concurrent.Executors;

/**
 * Factory class for the image pipeline.
 * 一个用来生成ImagePipeline对象的工厂类
 *
 * <p>This class constructs the pipeline and its dependencies from other libraries.
 *
 * <p>As the pipeline object can be quite expensive to create, it is strongly
 * recommended that applications create just one instance of this class
 * and of the pipeline.
 *
 * ImagePipeline对象的创建会消耗挺多资源的，建议整个app就共用一个imagePipline对象
 */
@NotThreadSafe
public class ImagePipelineFactory {

  // 单例
  private static ImagePipelineFactory sInstance = null;

  // 一个双端队列，能够控制进入队列是否排队或者是立刻执行，需要传入executor来具体的执行
  private final ThreadHandoffProducerQueue mThreadHandoffProducerQueue;

  /** Gets the instance of {@link ImagePipelineFactory}. */
  public static ImagePipelineFactory getInstance() {
    return Preconditions.checkNotNull(sInstance, "ImagePipelineFactory was not initialized!");
  }

  /**
   * Initializes {@link ImagePipelineFactory} with default config.
   *
   * 用默认的一些config来初始化ImagePipelineFactory
   */
  public static void initialize(Context context) {
    initialize(ImagePipelineConfig.newBuilder(context).build());
  }

  /** Initializes {@link ImagePipelineFactory} with the specified config. */
  public static void initialize(ImagePipelineConfig imagePipelineConfig) {
    sInstance = new ImagePipelineFactory(imagePipelineConfig);
  }

  /** Shuts {@link ImagePipelineFactory} down. */
  public static void shutDown() {
    if (sInstance != null) {
      sInstance.getBitmapMemoryCache().removeAll(AndroidPredicates.<CacheKey>True());
      sInstance.getEncodedMemoryCache().removeAll(AndroidPredicates.<CacheKey>True());
      sInstance = null;
    }
  }

  private final ImagePipelineConfig mConfig; //　一个ImagePipeline需要使用的到一些辅助类集合
  private CountingMemoryCache<CacheKey, CloseableImage>
      mBitmapCountingMemoryCache; //　
  private MemoryCache<CacheKey, CloseableImage> mBitmapMemoryCache;
  private CountingMemoryCache<CacheKey, PooledByteBuffer> mEncodedCountingMemoryCache;
  private MemoryCache<CacheKey, PooledByteBuffer> mEncodedMemoryCache;
  private BufferedDiskCache mMainBufferedDiskCache;
  private FileCache mMainFileCache;
  private ImageDecoder mImageDecoder;
  private ImagePipeline mImagePipeline;
  private ProducerFactory mProducerFactory;
  private ProducerSequenceFactory mProducerSequenceFactory;
  private BufferedDiskCache mSmallImageBufferedDiskCache;
  private FileCache mSmallImageFileCache;

  private PlatformBitmapFactory mPlatformBitmapFactory;
  private PlatformDecoder mPlatformDecoder;

  private AnimatedFactory mAnimatedFactory;

  /**
   * 构造方法，通过传入的config来设置工厂产出的ImagePipeline所带的特性
   * 还从config里面得到了一个executor给一个能够装载runnable的双端队列设置上
   *
   * config中的executorsupplier是一个提供了多种get方法的接口，具体实现类在于{@link DefaultExecutorSupplier}
   * 通过android提供的{@link Executors}创建了一系列的线程池，在需要使用的地方直接get
   * 根据需要执行任务的不同来get不同属性的线程池,可以说这个executorsupplier是一个线程池的集合
   *
   * @param config
   */
  public ImagePipelineFactory(ImagePipelineConfig config) {
    mConfig = Preconditions.checkNotNull(config);
    mThreadHandoffProducerQueue = new ThreadHandoffProducerQueue(
        config.getExecutorSupplier().forLightweightBackgroundTasks());
  }

  /**
   * 懒加载一个动画工厂
   * 传入了一个{@link PlatformBitmapFactory}, 这个类专门用来创建bitmap的实例对象
   * 上面这个类仅仅提供了两个抽象方法，类下面引申出了三个子类，对于不同版本的android系统有不同的实现方式
   * 对HoneyComb、gingerbread以及art版本的系统进行了不同的处理
   *
   * 还把一个线程池集合executorsupplier传入了
   *
   * @return
   */
  public AnimatedFactory getAnimatedFactory() {
    if (mAnimatedFactory == null) {
      mAnimatedFactory = AnimatedFactoryProvider.getAnimatedFactory(
          getPlatformBitmapFactory(),
          mConfig.getExecutorSupplier());
    }
    return mAnimatedFactory;
  }

  public CountingMemoryCache<CacheKey, CloseableImage>
      getBitmapCountingMemoryCache() {
    if (mBitmapCountingMemoryCache == null) {
      mBitmapCountingMemoryCache =
          BitmapCountingMemoryCacheFactory.get(
              mConfig.getBitmapMemoryCacheParamsSupplier(),
              mConfig.getMemoryTrimmableRegistry());
    }
    return mBitmapCountingMemoryCache;
  }

  public MemoryCache<CacheKey, CloseableImage> getBitmapMemoryCache() {
    if (mBitmapMemoryCache == null) {
      mBitmapMemoryCache =
          BitmapMemoryCacheFactory.get(
              getBitmapCountingMemoryCache(),
              mConfig.getImageCacheStatsTracker());
    }
    return mBitmapMemoryCache;
  }

  /**
   * Creates a new {@link DiskStorageCache} from the given {@link DiskCacheConfig}
   *
   * @deprecated use {@link DiskStorageCacheFactory.buildDiskStorageCache}
   */
  @Deprecated
  public static DiskStorageCache buildDiskStorageCache(
      DiskCacheConfig diskCacheConfig,
      DiskStorage diskStorage) {
    return DiskStorageCacheFactory.buildDiskStorageCache(diskCacheConfig, diskStorage);
  }

  public CountingMemoryCache<CacheKey, PooledByteBuffer> getEncodedCountingMemoryCache() {
    if (mEncodedCountingMemoryCache == null) {
      mEncodedCountingMemoryCache =
          EncodedCountingMemoryCacheFactory.get(
              mConfig.getEncodedMemoryCacheParamsSupplier(),
              mConfig.getMemoryTrimmableRegistry());
    }
    return mEncodedCountingMemoryCache;
  }

  public MemoryCache<CacheKey, PooledByteBuffer> getEncodedMemoryCache() {
    if (mEncodedMemoryCache == null) {
      mEncodedMemoryCache =
          EncodedMemoryCacheFactory.get(
              getEncodedCountingMemoryCache(),
              mConfig.getImageCacheStatsTracker());
    }
    return mEncodedMemoryCache;
  }

  private ImageDecoder getImageDecoder() {
    if (mImageDecoder == null) {
      if (mConfig.getImageDecoder() != null) {
        mImageDecoder = mConfig.getImageDecoder();
      } else {
        final AnimatedFactory animatedFactory = getAnimatedFactory();
        final AnimatedImageFactory animatedImageFactory;
        if (animatedFactory != null) {
          animatedImageFactory = getAnimatedFactory().getAnimatedImageFactory();
        } else {
          animatedImageFactory = null;
        }
        mImageDecoder = new ImageDecoder(
            animatedImageFactory,
            getPlatformDecoder(),
            mConfig.getBitmapConfig());
      }
    }
    return mImageDecoder;
  }

  private BufferedDiskCache getMainBufferedDiskCache() {
    if (mMainBufferedDiskCache == null) {
      mMainBufferedDiskCache =
          new BufferedDiskCache(
              getMainFileCache(),
              mConfig.getPoolFactory().getPooledByteBufferFactory(),
              mConfig.getPoolFactory().getPooledByteStreams(),
              mConfig.getExecutorSupplier().forLocalStorageRead(),
              mConfig.getExecutorSupplier().forLocalStorageWrite(),
              mConfig.getImageCacheStatsTracker());
    }
    return mMainBufferedDiskCache;
  }

  /**
   * @deprecated use {@link ImagePipelineFactory.getMainFileCache}
   */
  @Deprecated
  public FileCache getMainDiskStorageCache() {
    return getMainFileCache();
  }

  public FileCache getMainFileCache() {
    if (mMainFileCache == null) {
      DiskCacheConfig diskCacheConfig = mConfig.getMainDiskCacheConfig();
      mMainFileCache = mConfig.getFileCacheFactory().get(diskCacheConfig);
    }
    return mMainFileCache;
  }

  public ImagePipeline getImagePipeline() {
    if (mImagePipeline == null) {
      mImagePipeline =
          new ImagePipeline(
              getProducerSequenceFactory(),
              mConfig.getRequestListeners(),
              mConfig.getIsPrefetchEnabledSupplier(),
              getBitmapMemoryCache(),
              getEncodedMemoryCache(),
              getMainBufferedDiskCache(),
              getSmallImageBufferedDiskCache(),
              mConfig.getCacheKeyFactory(),
              mThreadHandoffProducerQueue);
    }
    return mImagePipeline;
  }

  /**
   * Provide the implementation of the PlatformBitmapFactory for the current platform
   * using the provided PoolFactory
   *
   * 判断当前android版本，返回不同的实现类
   *
   * @param poolFactory The PoolFactory 从mConfig里面拿出的一个池子集合，{@link PoolFactory}里面好多pool,对bitmap的、对byte数组的等等
   * @param platformDecoder The PlatformDecoder 此接口的实现类有俩方法，支持普通图片解码以及JPEG的部分解码, 对该实现类的构造在{@link ImagePipelineFactory#getPlatformDecoder()}
   * @return The PlatformBitmapFactory implementation 构造bitmap的具体工厂类
   */
  public static PlatformBitmapFactory buildPlatformBitmapFactory(
      PoolFactory poolFactory,
      PlatformDecoder platformDecoder) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // 5.0以上，使用bitmap池
      return new ArtBitmapFactory(poolFactory.getBitmapPool());
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      // 3.1到2.3 使用byte数组那啥
      return new HoneycombBitmapFactory(
          new EmptyJpegGenerator(poolFactory.getPooledByteBufferFactory()),
          platformDecoder);
    } else {
      // 2.3以下直接交给android处理,貌似直接给native处理了
      return new GingerbreadBitmapFactory();
    }
  }

  /**
   * 在这里创建具体的bitmap工厂类
   * 具体的过程交给了楼上的那个方法
   *
   * @return
   */
  public PlatformBitmapFactory getPlatformBitmapFactory() {
    if (mPlatformBitmapFactory == null) {
      mPlatformBitmapFactory = buildPlatformBitmapFactory(
          mConfig.getPoolFactory(),
          getPlatformDecoder());
    }
    return mPlatformBitmapFactory;
  }

  /**
   * Provide the implementation of the PlatformDecoder for the current platform using the
   * provided PoolFactory
   * 和创建bitmap的实现类一样
   * 对不同版本的系统也有不同的分类实现
   *
   * @param poolFactory The PoolFactory
   * @return The PlatformDecoder implementation
   */
  public static PlatformDecoder buildPlatformDecoder(
      PoolFactory poolFactory,
      boolean decodeMemoryFileEnabled,
      boolean webpSupportEnabled) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      int maxNumThreads = poolFactory.getFlexByteArrayPoolMaxNumThreads();
      return new ArtDecoder(
          poolFactory.getBitmapPool(),
          maxNumThreads,
          new Pools.SynchronizedPool<>(maxNumThreads));
    } else {
      if (decodeMemoryFileEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
        return new GingerbreadPurgeableDecoder(webpSupportEnabled);
      } else {
        return new KitKatPurgeableDecoder(poolFactory.getFlexByteArrayPool());
      }
    }
  }

  /**
   * 创建一个专门用来decode的实现类实例
   * 具体build过程在楼上
   *
   * @return
   */
  public PlatformDecoder getPlatformDecoder() {
    if (mPlatformDecoder == null) {
      mPlatformDecoder = buildPlatformDecoder(
          mConfig.getPoolFactory(),
          mConfig.isDecodeMemoryFileEnabled(),
          mConfig.getExperiments().isWebpSupportEnabled());
    }
    return mPlatformDecoder;
  }

  private ProducerFactory getProducerFactory() {
    if (mProducerFactory == null) {
      mProducerFactory =
          new ProducerFactory(
              mConfig.getContext(),
              mConfig.getPoolFactory().getSmallByteArrayPool(),
              getImageDecoder(),
              mConfig.getProgressiveJpegConfig(),
              mConfig.isDownsampleEnabled(),
              mConfig.isResizeAndRotateEnabledForNetwork(),
              mConfig.getExecutorSupplier(),
              mConfig.getPoolFactory().getPooledByteBufferFactory(),
              getBitmapMemoryCache(),
              getEncodedMemoryCache(),
              getMainBufferedDiskCache(),
              getSmallImageBufferedDiskCache(),
              mConfig.getCacheKeyFactory(),
              getPlatformBitmapFactory(),
              mConfig.getExperiments().isDecodeFileDescriptorEnabled(),
              mConfig.getExperiments().getForceSmallCacheThresholdBytes());
    }
    return mProducerFactory;
  }

  private ProducerSequenceFactory getProducerSequenceFactory() {
    if (mProducerSequenceFactory == null) {
      mProducerSequenceFactory =
          new ProducerSequenceFactory(
              getProducerFactory(),
              mConfig.getNetworkFetcher(),
              mConfig.isResizeAndRotateEnabledForNetwork(),
              mConfig.isDownsampleEnabled(),
              mConfig.getExperiments().isWebpSupportEnabled(),
              mThreadHandoffProducerQueue,
              mConfig.getExperiments().getThrottlingMaxSimultaneousRequests());
    }
    return mProducerSequenceFactory;
  }

  /**
   * @deprecated use {@link ImagePipelineFactory.getSmallImageFileCache}
   */
  @Deprecated
  public FileCache getSmallImageDiskStorageCache() {
    return getSmallImageFileCache();
  }

  public FileCache getSmallImageFileCache() {
    if (mSmallImageFileCache == null) {
      DiskCacheConfig diskCacheConfig = mConfig.getSmallImageDiskCacheConfig();
      mSmallImageFileCache = mConfig.getFileCacheFactory().get(diskCacheConfig);
    }
    return mSmallImageFileCache;
  }

  private BufferedDiskCache getSmallImageBufferedDiskCache() {
    if (mSmallImageBufferedDiskCache == null) {
      mSmallImageBufferedDiskCache =
          new BufferedDiskCache(
              getSmallImageFileCache(),
              mConfig.getPoolFactory().getPooledByteBufferFactory(),
              mConfig.getPoolFactory().getPooledByteStreams(),
              mConfig.getExecutorSupplier().forLocalStorageRead(),
              mConfig.getExecutorSupplier().forLocalStorageWrite(),
              mConfig.getImageCacheStatsTracker());
    }
    return mSmallImageBufferedDiskCache;
  }
}
