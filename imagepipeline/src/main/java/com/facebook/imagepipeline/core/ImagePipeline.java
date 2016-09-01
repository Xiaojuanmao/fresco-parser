/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.core;

import javax.annotation.concurrent.ThreadSafe;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import android.net.Uri;

import com.facebook.cache.common.CacheKey;
import com.facebook.common.internal.Objects;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.Supplier;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.UriUtil;
import com.facebook.datasource.DataSource;
import com.facebook.datasource.DataSources;
import com.facebook.datasource.SimpleDataSource;
import com.facebook.imagepipeline.cache.BufferedDiskCache;
import com.facebook.imagepipeline.cache.CacheKeyFactory;
import com.facebook.imagepipeline.cache.MemoryCache;
import com.facebook.imagepipeline.common.Priority;
import com.facebook.imagepipeline.datasource.CloseableProducerToDataSourceAdapter;
import com.facebook.imagepipeline.datasource.ProducerToDataSourceAdapter;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.listener.ForwardingRequestListener;
import com.facebook.imagepipeline.listener.RequestListener;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.imagepipeline.producers.Producer;
import com.facebook.imagepipeline.producers.SettableProducerContext;
import com.facebook.imagepipeline.producers.ThreadHandoffProducerQueue;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;

import bolts.Continuation;
import bolts.Task;
import com.android.internal.util.Predicate;

/**
 * The entry point for the image pipeline.
 *
 * 图像流水线的入口
 * 多条功能流水线主要在{@link ProducerSequenceFactory}类里面,由各种Producer组成
 *
 * 在这里基本上能够将整个ImagePipeline工作原理理清了
 *
 * 首先需要弄明白的是，整个ImagePipeline模拟了工厂中流水线的一套模式
 * 可以理解为通过{@link ProducerSequenceFactory} 以及{@link ProducerFactory}这两个类生产出一系列首尾相连(其实是包含的关系)
 * 的Producer，最后由最后一道工序的Producer充当“头节点”
 *
 * 当整条流水线启动的时候，这个头Producer会调用自己的produceResults()方法，并创建了属于自己这一道工序的{@link com.facebook.imagepipeline.producers.Consumer}(对即将到来的结果进行处理
 * 每一道工序会将自己创建的consumer传给前一道工序，让前一道工序得到结果之后，方便通知自己进行处理
 *
 * 整条流水线启动是从顶向下要结果的，这种设计和MotionEvent的消费方式以及ReactiveX的思想都有点类似，从最后一道工序做好自己的准备，向前面一道工序询问结果
 * 一直询问到第一道工序，这时候就开始了正式的工作。
 *
 * 如果这时候是加载网络请求的图片，{@link com.facebook.imagepipeline.producers.NetworkFetchProducer}就会开始请求网络，将图片的源数据取回来，并通知上一道工序的
 */

@ThreadSafe
public class ImagePipeline {
  private static final CancellationException PREFETCH_EXCEPTION =
      new CancellationException("Prefetching is not enabled");

  private final ProducerSequenceFactory mProducerSequenceFactory; // 包含着多条生产流水线(Producer)的工厂类
  private final RequestListener mRequestListener; // 用来监听流水线上的ImageReuqest进度以及反馈结果进行监测
  private final Supplier<Boolean> mIsPrefetchEnabledSupplier; // 是否允许预解析的工作
  private final MemoryCache<CacheKey, CloseableImage> mBitmapMemoryCache; // 有关bitmap的内存缓存
  private final MemoryCache<CacheKey, PooledByteBuffer> mEncodedMemoryCache; // 有关图片原始字节码的内存缓存
  private final BufferedDiskCache mMainBufferedDiskCache; // 提供对磁盘文件进行读写管理的类
  private final BufferedDiskCache mSmallImageBufferedDiskCache;
  private final CacheKeyFactory mCacheKeyFactory; // 构造CacheKey的工厂类
  private final ThreadHandoffProducerQueue mThreadHandoffProducerQueue; // 一个存放Runnable队列
  private AtomicLong mIdCounter; // 具有原子操作性质的long

  public ImagePipeline(
      ProducerSequenceFactory producerSequenceFactory,
      Set<RequestListener> requestListeners,
      Supplier<Boolean> isPrefetchEnabledSupplier,
      MemoryCache<CacheKey, CloseableImage> bitmapMemoryCache,
      MemoryCache<CacheKey, PooledByteBuffer> encodedMemoryCache,
      BufferedDiskCache mainBufferedDiskCache,
      BufferedDiskCache smallImageBufferedDiskCache,
      CacheKeyFactory cacheKeyFactory,
      ThreadHandoffProducerQueue threadHandoffProducerQueue) {
    mIdCounter = new AtomicLong();
    mProducerSequenceFactory = producerSequenceFactory;
    mRequestListener = new ForwardingRequestListener(requestListeners);
    mIsPrefetchEnabledSupplier = isPrefetchEnabledSupplier;
    mBitmapMemoryCache = bitmapMemoryCache;
    mEncodedMemoryCache = encodedMemoryCache;
    mMainBufferedDiskCache = mainBufferedDiskCache;
    mSmallImageBufferedDiskCache = smallImageBufferedDiskCache;
    mCacheKeyFactory = cacheKeyFactory;
    mThreadHandoffProducerQueue = threadHandoffProducerQueue;
  }

  /**
   * Generates unique id for RequestFuture.
   *
   * 利用带有原子操作性质的类来生成一个自然增长的独一无二的id
   * @return unique id
   */
  private String generateUniqueFutureId() {
    return String.valueOf(mIdCounter.getAndIncrement());
  }

  /**
   * Returns a DataSource supplier that will on get submit the request for execution and return a
   * DataSource representing the pending results of the task.
   *
   * 构造出一个包含了能够得到数据源的Supplier
   * DataSource的获得通过传入的ImageRequest来构造出一套产出目标图片的流水线
   * 并通过方法{@link #submitFetchRequest(Producer, ImageRequest, ImageRequest.RequestLevel, Object)}方法开始请求的工作
   *
   * @param imageRequest the request to submit (what to execute).
   * @param bitmapCacheOnly whether to only look for the image in the bitmap cache
   * @return a DataSource representing pending results and completion of the request
   */
  public Supplier<DataSource<CloseableReference<CloseableImage>>> getDataSourceSupplier(
      final ImageRequest imageRequest,
      final Object callerContext,
      final boolean bitmapCacheOnly) {
    return new Supplier<DataSource<CloseableReference<CloseableImage>>>() {
      @Override
      public DataSource<CloseableReference<CloseableImage>> get() {
        if (bitmapCacheOnly) {
          // 只允许从bitmap缓存中获取图片
          return fetchImageFromBitmapCache(imageRequest, callerContext);
        } else {
          // 优先从decodedImage中获取图片
          return fetchDecodedImage(imageRequest, callerContext);
        }
      }

      @Override
      public String toString() {
        return Objects.toStringHelper(this)
            .add("uri", imageRequest.getSourceUri())
            .toString();
      }
    };
  }

  /**
   * Returns a DataSource supplier that will on get submit the request for execution and return a
   * DataSource representing the pending results of the task.
   *
   * 和上面的方法类似，只不过请求返回的结果是PooledByteBuffer，也就是存在byte数组池中的字节码
   *
   * 通过这个方法能够在使用Fresco的时候直接拿到图片的字节码
   * 只需要构造一个ImageRequest即可
   *
   * @param imageRequest the request to submit (what to execute).
   * @return a DataSource representing pending results and completion of the request
   */
  public Supplier<DataSource<CloseableReference<PooledByteBuffer>>>
      getEncodedImageDataSourceSupplier(
          final ImageRequest imageRequest,
          final Object callerContext) {
    return new Supplier<DataSource<CloseableReference<PooledByteBuffer>>>() {
      @Override
      public DataSource<CloseableReference<PooledByteBuffer>> get() {
        return fetchEncodedImage(imageRequest, callerContext);
      }

      @Override
      public String toString() {
        return Objects.toStringHelper(this)
            .add("uri", imageRequest.getSourceUri())
            .toString();
      }
    };
  }

  /**
   * Submits a request for bitmap cache lookup.
   *
   * 和下面那个方法没啥大的区别
   * 都是同一条流水线了，只不过在{@link com.facebook.imagepipeline.request.ImageRequest.RequestLevel}上有所不同
   *
   * @param imageRequest the request to submit
   * @return a DataSource representing the image
   */
  public DataSource<CloseableReference<CloseableImage>> fetchImageFromBitmapCache(
      ImageRequest imageRequest,
      Object callerContext) {
    try {
      Producer<CloseableReference<CloseableImage>> producerSequence =
          mProducerSequenceFactory.getDecodedImageProducerSequence(imageRequest);
      return submitFetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.BITMAP_MEMORY_CACHE,
          callerContext);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Submits a request for execution and returns a DataSource representing the pending decoded
   * image(s).
   *
   * 同楼上的那个方法
   *
   * <p>The returned DataSource must be closed once the client has finished with it.
   * @param imageRequest the request to submit
   * @return a DataSource representing the pending decoded image(s)
   */
  public DataSource<CloseableReference<CloseableImage>> fetchDecodedImage(
      ImageRequest imageRequest,
      Object callerContext) {
    try {
      Producer<CloseableReference<CloseableImage>> producerSequence =
          mProducerSequenceFactory.getDecodedImageProducerSequence(imageRequest);
      return submitFetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.FULL_FETCH,
          callerContext);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Submits a request for execution and returns a DataSource representing the pending encoded
   * image(s).
   *
   * 返回一个有待解码的byte数组封装类
   * 貌似看到了更多花式玩图的希望0.0
   *
   * <p> The ResizeOptions in the imageRequest will be ignored for this fetch
   *
   * <p>The returned DataSource must be closed once the client has finished with it.
   *
   * @param imageRequest the request to submit
   * @return a DataSource representing the pending encoded image(s)
   */
  public DataSource<CloseableReference<PooledByteBuffer>> fetchEncodedImage(
      ImageRequest imageRequest,
      Object callerContext) {
    Preconditions.checkNotNull(imageRequest.getSourceUri());
    try {
      Producer<CloseableReference<PooledByteBuffer>> producerSequence =
          mProducerSequenceFactory.getEncodedImageProducerSequence(imageRequest);
      // The resize options are used to determine whether images are going to be downsampled during
      // decode or not. For the case where the image has to be downsampled and it's a local image it
      // will be kept as a FileInputStream until decoding instead of reading it in memory. Since
      // this method returns an encoded image, it should always be read into memory. Therefore, the
      // resize options are ignored to avoid treating the image as if it was to be downsampled
      // during decode.
      /**
       * 需要忽略传入的imageRequest的ResizeOptions
       */
      if (imageRequest.getResizeOptions() != null) {
        imageRequest = ImageRequestBuilder.fromRequest(imageRequest)
            .setResizeOptions(null)
            .build();
      }
      return submitFetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.FULL_FETCH,
          callerContext);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Submits a request for prefetching to the bitmap cache.
   * @param imageRequest the request to submit
   * @return a DataSource that can safely be ignored.
   */
  public DataSource<Void> prefetchToBitmapCache(
      ImageRequest imageRequest,
      Object callerContext) {
    if (!mIsPrefetchEnabledSupplier.get()) {
      return DataSources.immediateFailedDataSource(PREFETCH_EXCEPTION);
    }
    try {
      Producer<Void> producerSequence =
          mProducerSequenceFactory.getDecodedImagePrefetchProducerSequence(imageRequest);
      return submitPrefetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.FULL_FETCH,
          callerContext,
          Priority.MEDIUM);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Submits a request for prefetching to the disk cache with a default priority
   * @param imageRequest the request to submit
   * @return a DataSource that can safely be ignored.
   */
  public DataSource<Void> prefetchToDiskCache(
      ImageRequest imageRequest,
      Object callerContext) {
    return prefetchToDiskCache(imageRequest, callerContext, Priority.MEDIUM);
  }

  /**
   * Submits a request for prefetching to the disk cache.
   * @param imageRequest the request to submit
   * @param priority custom priority for the fetch
   * @return a DataSource that can safely be ignored.
   */
  public DataSource<Void> prefetchToDiskCache(
      ImageRequest imageRequest,
      Object callerContext,
      Priority priority) {
    if (!mIsPrefetchEnabledSupplier.get()) {
      return DataSources.immediateFailedDataSource(PREFETCH_EXCEPTION);
    }
    try {
      Producer<Void> producerSequence =
          mProducerSequenceFactory.getEncodedImagePrefetchProducerSequence(imageRequest);
      return submitPrefetchRequest(
          producerSequence,
          imageRequest,
          ImageRequest.RequestLevel.FULL_FETCH,
          callerContext,
          priority);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  /**
   * Removes all images with the specified {@link Uri} from memory cache.
   *
   * 将所有和uri有关的image从memoryCache中回收掉
   *
   * @param uri The uri of the image to evict
   */
  public void evictFromMemoryCache(final Uri uri) {
    // 创建一个关于uri的断言条件
    Predicate<CacheKey> predicate = predicateForUri(uri);

    // 将符合predicate条件的缓存都移除
    mBitmapMemoryCache.removeAll(predicate);
    mEncodedMemoryCache.removeAll(predicate);
  }

  /**
   * <p>If you have supplied your own cache key factory when configuring the pipeline, this method
   * may not work correctly. It will only work if the custom factory builds the cache key entirely
   * from the URI. If that is not the case, use {@link #evictFromDiskCache(ImageRequest)}.
   *
   * 如果在构造pipeline的时候用户提供了自己构建CacheKey的一套规则，那么这个方法可能会不管用
   * 由于DiskCache和MemoryCache是两套不同的缓存结构，所以在清除缓存的时候会不同
   *
   * @param uri The uri of the image to evict
   */
  public void evictFromDiskCache(final Uri uri) {
    evictFromDiskCache(ImageRequest.fromUri(uri));
  }

  /**
   * Removes all images with the specified {@link Uri} from disk cache.
   *
   * @param imageRequest The imageRequest for the image to evict from disk cache
   */
  public void evictFromDiskCache(final ImageRequest imageRequest) {
    CacheKey cacheKey = mCacheKeyFactory.getEncodedCacheKey(imageRequest, null);
    mMainBufferedDiskCache.remove(cacheKey);
    mSmallImageBufferedDiskCache.remove(cacheKey);
  }

  /**
   * <p>If you have supplied your own cache key factory when configuring the pipeline, this method
   * may not work correctly. It will only work if the custom factory builds the cache key entirely
   * from the URI. If that is not the case, use {@link #evictFromMemoryCache(Uri)} and
   * {@link #evictFromDiskCache(ImageRequest)} separately.
   * @param uri The uri of the image to evict
   */
  public void evictFromCache(final Uri uri) {
    evictFromMemoryCache(uri);
    evictFromDiskCache(uri);
  }

  /**
   * Clear the memory caches
   */
  public void clearMemoryCaches() {
    Predicate<CacheKey> allPredicate =
        new Predicate<CacheKey>() {
          @Override
          public boolean apply(CacheKey key) {
            return true;
          }
        };
    mBitmapMemoryCache.removeAll(allPredicate);
    mEncodedMemoryCache.removeAll(allPredicate);
  }

  /**
   * Clear disk caches
   */
  public void clearDiskCaches() {
    mMainBufferedDiskCache.clearAll();
    mSmallImageBufferedDiskCache.clearAll();
  }

  /**
   * Clear all the caches (memory and disk)
   */
  public void clearCaches() {
    clearMemoryCaches();
    clearDiskCaches();
  }

  /**
   * Returns whether the image is stored in the bitmap memory cache.
   *
   * 用来检测uri对应的图片是否存在bitmap的缓存对象
   *
   * @param uri the uri for the image to be looked up.
   * @return true if the image was found in the bitmap memory cache, false otherwise
   */
  public boolean isInBitmapMemoryCache(final Uri uri) {
    if (uri == null) {
      return false;
    }
    Predicate<CacheKey> bitmapCachePredicate = predicateForUri(uri);
    return mBitmapMemoryCache.contains(bitmapCachePredicate);
 }

  /**
   * @return The Bitmap MemoryCache
   */
  public MemoryCache<CacheKey, CloseableImage> getBitmapMemoryCache() {
    return mBitmapMemoryCache;
  }

  /**
   * Returns whether the image is stored in the bitmap memory cache.
   *
   * 同上
   *
   * @param imageRequest the imageRequest for the image to be looked up.
   * @return true if the image was found in the bitmap memory cache, false otherwise.
   */
  public boolean isInBitmapMemoryCache(final ImageRequest imageRequest) {
    if (imageRequest == null) {
      return false;
    }
    final CacheKey cacheKey = mCacheKeyFactory.getBitmapCacheKey(imageRequest, null);
    CloseableReference<CloseableImage> ref = mBitmapMemoryCache.get(cacheKey);
    try {
      return CloseableReference.isValid(ref);
    } finally {
      CloseableReference.closeSafely(ref);
    }
  }

  /**
   * Returns whether the image is stored in the disk cache.
   * Performs disk cache check synchronously. It is not recommended to use this
   * unless you know what exactly you are doing. Disk cache check is a costly operation,
   * the call will block the caller thread until the cache check is completed.
   *
   * 用来检测是否存在磁盘缓存
   * 尽量不要调用这个方法，同步的去检查磁盘缓存是开销非常大的
   *
   * @param uri the uri for the image to be looked up.
   * @return true if the image was found in the disk cache, false otherwise.
   */
  public boolean isInDiskCacheSync(final Uri uri) {
    return isInDiskCacheSync(ImageRequest.fromUri(uri));
  }

  /**
   * Performs disk cache check synchronously. It is not recommended to use this
   * unless you know what exactly you are doing. Disk cache check is a costly operation,
   * the call will block the caller thread until the cache check is completed.
   *
   * 同上
   *
   * @param imageRequest the imageRequest for the image to be looked up.
   * @return true if the image was found in the disk cache, false otherwise.
   */
  public boolean isInDiskCacheSync(final ImageRequest imageRequest) {
    final CacheKey cacheKey = mCacheKeyFactory.getEncodedCacheKey(imageRequest, null);
    return mMainBufferedDiskCache.diskCheckSync(cacheKey);
  }

  /**
   * Returns whether the image is stored in the disk cache.
   *
   * <p>If you have supplied your own cache key factory when configuring the pipeline, this method
   * may not work correctly. It will only work if the custom factory builds the cache key entirely
   * from the URI. If that is not the case, use {@link #isInDiskCache(ImageRequest)}.
   *
   * 这个方法是上面同步版本的非同步版
   *
   * @param uri the uri for the image to be looked up.
   * @return true if the image was found in the disk cache, false otherwise.
   */
  public DataSource<Boolean> isInDiskCache(final Uri uri) {
    return isInDiskCache(ImageRequest.fromUri(uri));
  }

  /**
   * Returns whether the image is stored in the disk cache.
   *
   * 同上咯
   *
   * @param imageRequest the imageRequest for the image to be looked up.
   * @return true if the image was found in the disk cache, false otherwise.
   */
  public DataSource<Boolean> isInDiskCache(final ImageRequest imageRequest) {
    final CacheKey cacheKey = mCacheKeyFactory.getEncodedCacheKey(imageRequest, null);
    final SimpleDataSource<Boolean> dataSource = SimpleDataSource.create();
    mMainBufferedDiskCache.contains(cacheKey)
        .continueWithTask(
            new Continuation<Boolean, Task<Boolean>>() {
              @Override
              public Task<Boolean> then(Task<Boolean> task) throws Exception {
                if (!task.isCancelled() && !task.isFaulted() && task.getResult()) {
                  return Task.forResult(true);
                }
                return mSmallImageBufferedDiskCache.contains(cacheKey);
              }
            })
        .continueWith(
            new Continuation<Boolean, Void>() {
              @Override
              public Void then(Task<Boolean> task) throws Exception {
                dataSource.setResult(!task.isCancelled() && !task.isFaulted() && task.getResult());
                return null;
              }
            });
    return dataSource;
  }

  /**
   * 用来将构造好的流水线开启
   *
   *
   * @param producerSequence 构造好的生产流水线
   * @param imageRequest 关于需要得到的图片所有信息以及调用者对于图片的一些要求
   * @param lowestPermittedRequestLevelOnSubmit
   * @param callerContext
   * @param <T>
   * @return
   */
  private <T> DataSource<CloseableReference<T>> submitFetchRequest(
      Producer<CloseableReference<T>> producerSequence,
      ImageRequest imageRequest,
      ImageRequest.RequestLevel lowestPermittedRequestLevelOnSubmit,
      Object callerContext) {
    try {
      /**
       * 再次确定最低允许的RequestLevel
       * 将request中的取出并和传入的level进行对比，取最大值
       * 这个值暂时还是不太清除有什么用
       */
      ImageRequest.RequestLevel lowestPermittedRequestLevel =
          ImageRequest.RequestLevel.getMax(
              imageRequest.getLowestPermittedRequestLevel(),
              lowestPermittedRequestLevelOnSubmit);

        /**
         * 用来监控整条流水线上各种状态的context
         * 流水线上发生的各种事情都通过这个来进行通知
         * 例如流水线产出失败、被cancel等等
         */
      SettableProducerContext settableProducerContext = new SettableProducerContext(
          imageRequest,
          generateUniqueFutureId(),
          mRequestListener,
          callerContext,
          lowestPermittedRequestLevel,
        /* isPrefetch */ false,
          imageRequest.getProgressiveRenderingEnabled() ||
              !UriUtil.isNetworkUri(imageRequest.getSourceUri()),
          imageRequest.getPriority());

        /**
         * 返回了一个CloseableProducerToDataSourceAdapter实例对象
         * 该对象是实现了DataSource接口的一个类
         * 在create方法执行的时候，{@link com.facebook.imagepipeline.datasource.AbstractProducerToDataSourceAdapter}构造方法中启动了流水线
         * 并通知了mRequestListener，流水线已经启动
         */
      return CloseableProducerToDataSourceAdapter.create(
          producerSequence,
          settableProducerContext,
          mRequestListener);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  private DataSource<Void> submitPrefetchRequest(
      Producer<Void> producerSequence,
      ImageRequest imageRequest,
      ImageRequest.RequestLevel lowestPermittedRequestLevelOnSubmit,
      Object callerContext,
      Priority priority) {
    try {
      ImageRequest.RequestLevel lowestPermittedRequestLevel =
          ImageRequest.RequestLevel.getMax(
              imageRequest.getLowestPermittedRequestLevel(),
              lowestPermittedRequestLevelOnSubmit);
      SettableProducerContext settableProducerContext = new SettableProducerContext(
          imageRequest,
          generateUniqueFutureId(),
          mRequestListener,
          callerContext,
          lowestPermittedRequestLevel,
        /* isPrefetch */ true,
        /* isIntermediateResultExpected */ false,
          priority);
      return ProducerToDataSourceAdapter.create(
          producerSequence,
          settableProducerContext,
          mRequestListener);
    } catch (Exception exception) {
      return DataSources.immediateFailedDataSource(exception);
    }
  }

  private Predicate<CacheKey> predicateForUri(final Uri uri) {
    return new Predicate<CacheKey>() {
          @Override
          public boolean apply(CacheKey key) {
            return key.containsUri(uri);
          }
        };
  }

  public void pause() {
    mThreadHandoffProducerQueue.startQueueing();
  }

  public void resume() {
    mThreadHandoffProducerQueue.stopQueuing();
  }

  public boolean isPaused() {
    return mThreadHandoffProducerQueue.isQueueing();
  }

  /**
   * @return The CacheKeyFactory implementation used by ImagePipeline
   */
  public CacheKeyFactory getCacheKeyFactory() {
    return mCacheKeyFactory;
  }
}
