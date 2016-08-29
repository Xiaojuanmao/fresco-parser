/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.core;

import java.util.HashMap;
import java.util.Map;

import android.net.Uri;
import android.os.Build;

import com.facebook.common.internal.Preconditions;
import com.facebook.common.internal.VisibleForTesting;
import com.facebook.common.media.MediaUtils;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.UriUtil;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.imagepipeline.producers.BitmapMemoryCacheKeyMultiplexProducer;
import com.facebook.imagepipeline.producers.BitmapMemoryCacheProducer;
import com.facebook.imagepipeline.producers.DecodeProducer;
import com.facebook.imagepipeline.producers.EncodedMemoryCacheProducer;
import com.facebook.imagepipeline.producers.LocalAssetFetchProducer;
import com.facebook.imagepipeline.producers.LocalContentUriFetchProducer;
import com.facebook.imagepipeline.producers.LocalFileFetchProducer;
import com.facebook.imagepipeline.producers.LocalResourceFetchProducer;
import com.facebook.imagepipeline.producers.LocalVideoThumbnailProducer;
import com.facebook.imagepipeline.producers.NetworkFetcher;
import com.facebook.imagepipeline.producers.PostprocessedBitmapMemoryCacheProducer;
import com.facebook.imagepipeline.producers.PostprocessorProducer;
import com.facebook.imagepipeline.producers.Producer;
import com.facebook.imagepipeline.producers.RemoveImageTransformMetaDataProducer;
import com.facebook.imagepipeline.producers.SwallowResultProducer;
import com.facebook.imagepipeline.producers.ThreadHandoffProducer;
import com.facebook.imagepipeline.producers.ThreadHandoffProducerQueue;
import com.facebook.imagepipeline.producers.ThrottlingProducer;
import com.facebook.imagepipeline.producers.ThumbnailBranchProducer;
import com.facebook.imagepipeline.producers.ThumbnailProducer;
import com.facebook.imagepipeline.request.ImageRequest;

/**
 * 此类实际上是用ProducerFactory进行了一个流水线式的组装
 * ProducerFactory能够构造出多样化的producer，这些producer能够对输入的(也就是统一输入类型Producer<T>)
 * 类型进行处理，并返回另一个与输入类型相同的Producer<T>
 * 也就是类似于工厂里面流水线一样，从原始的result，经过每一种做不同操作的producer的操作，最后在整条流水线上产出一个产品
 * 这些producer仅仅只是预先包装好了，并没有实际的执行，和reactive一套思想有点类似，在最后一刻才会被触发，完成整个生产工作
 *
 * 生产流水线工厂
 */
public class ProducerSequenceFactory {

  private final ProducerFactory mProducerFactory; // 提供了构造各种producer的接口
  private final NetworkFetcher mNetworkFetcher; // 用来进行网络请求的
  private final boolean mResizeAndRotateEnabledForNetwork;
  private final boolean mWebpSupportEnabled;
  private final boolean mDownsampleEnabled;
  private final ThreadHandoffProducerQueue mThreadHandoffProducerQueue; // 一个runnable队列
  private final int mThrottlingMaxSimultaneousRequests;

  // Saved sequences
  // 暂时还不清楚下面这一系列producer有什么用
  /**
   * 看到后面算是理解了
   * 下面这些Producer能够理解成本工厂类中的一条条流水线
   * 每条流水线的构造是不同的，也负责了不同的工序
   * 例如第一条就负责从网络获取图片、磁盘缓存、内存缓存等一系列的操作
   * 其他的流水线也是类似
   */
  @VisibleForTesting Producer<CloseableReference<CloseableImage>> mNetworkFetchSequence; // 从网络获取image的流水线
  @VisibleForTesting Producer<EncodedImage> mBackgroundNetworkFetchToEncodedMemorySequence; // 从内存缓存中解析出image的流水线
  @VisibleForTesting Producer<CloseableReference<PooledByteBuffer>> mEncodedImageProducerSequence;
  @VisibleForTesting Producer<Void> mNetworkFetchToEncodedMemoryPrefetchSequence;
  private Producer<EncodedImage> mCommonNetworkFetchToEncodedMemorySequence;
  @VisibleForTesting Producer<CloseableReference<CloseableImage>> mLocalImageFileFetchSequence;
  @VisibleForTesting Producer<CloseableReference<CloseableImage>> mLocalVideoFileFetchSequence;
  @VisibleForTesting Producer<CloseableReference<CloseableImage>> mLocalContentUriFetchSequence;
  @VisibleForTesting Producer<CloseableReference<CloseableImage>> mLocalResourceFetchSequence;
  @VisibleForTesting Producer<CloseableReference<CloseableImage>> mLocalAssetFetchSequence;
  @VisibleForTesting Producer<CloseableReference<CloseableImage>> mDataFetchSequence;
  @VisibleForTesting Map<
      Producer<CloseableReference<CloseableImage>>,
      Producer<CloseableReference<CloseableImage>>>
      mPostprocessorSequences;
  @VisibleForTesting Map<Producer<CloseableReference<CloseableImage>>, Producer<Void>>
      mCloseableImagePrefetchSequences;

  public ProducerSequenceFactory(
      ProducerFactory producerFactory,
      NetworkFetcher networkFetcher,
      boolean resizeAndRotateEnabledForNetwork,
      boolean downsampleEnabled,
      boolean webpSupportEnabled,
      ThreadHandoffProducerQueue threadHandoffProducerQueue,
      int throttlingMaxSimultaneousRequests) {
    mProducerFactory = producerFactory;
    mNetworkFetcher = networkFetcher;
    mResizeAndRotateEnabledForNetwork = resizeAndRotateEnabledForNetwork;
    mDownsampleEnabled = downsampleEnabled;
    mWebpSupportEnabled = webpSupportEnabled;
    mPostprocessorSequences = new HashMap<>();
    mCloseableImagePrefetchSequences = new HashMap<>();
    mThreadHandoffProducerQueue = threadHandoffProducerQueue;
    mThrottlingMaxSimultaneousRequests = throttlingMaxSimultaneousRequests;
  }

  /**
   * Returns a sequence that can be used for a request for an encoded image.
   *
   * 返回一个在内部构造好了consumer的producer
   *
   * @param imageRequest the request that will be submitted
   * @return the sequence that should be used to process the request
   */
  public Producer<CloseableReference<PooledByteBuffer>> getEncodedImageProducerSequence(
      ImageRequest imageRequest) {
    validateEncodedImageRequest(imageRequest);
    synchronized (this) {
      if (mEncodedImageProducerSequence == null) {
        mEncodedImageProducerSequence = new RemoveImageTransformMetaDataProducer(
            getBackgroundNetworkFetchToEncodedMemorySequence());
      }
    }
    return mEncodedImageProducerSequence;
  }

  /**
   * Returns a sequence that can be used for a prefetch request for an encoded image.
   *
   * 返回一个能够用来进行图片请求预处理的producer
   * 可能是和bitmap的inJustDecodeBounds类似的环节
   *
   * <p>Guaranteed to return the same sequence as
   * {@code getEncodedImageProducerSequence(request)}, except that it is pre-pended with a
   * {@link SwallowResultProducer}.
   * @param imageRequest the request that will be submitted
   * @return the sequence that should be used to process the request
   */
  public Producer<Void> getEncodedImagePrefetchProducerSequence(ImageRequest imageRequest) {
    validateEncodedImageRequest(imageRequest);
    return getNetworkFetchToEncodedMemoryPrefetchSequence();
  }

  /**
   * 验证ImageRequest是否有效
   *
   * @param imageRequest
   */
  private static void validateEncodedImageRequest(ImageRequest imageRequest) {
    Preconditions.checkNotNull(imageRequest);
    Preconditions.checkArgument(UriUtil.isNetworkUri(imageRequest.getSourceUri()));
    Preconditions.checkArgument(
        imageRequest.getLowestPermittedRequestLevel().getValue() <=
            ImageRequest.RequestLevel.ENCODED_MEMORY_CACHE.getValue());
  }

  /**
   * Returns a sequence that can be used for a request for a decoded image.
   *
   * @param imageRequest the request that will be submitted
   * @return the sequence that should be used to process the request
   */
  public Producer<CloseableReference<CloseableImage>> getDecodedImageProducerSequence(
      ImageRequest imageRequest) {
    Producer<CloseableReference<CloseableImage>> pipelineSequence =
        getBasicDecodedImageSequence(imageRequest);
    if (imageRequest.getPostprocessor() != null) {
      return getPostprocessorSequence(pipelineSequence);
    } else {
      return pipelineSequence;
    }
  }

  /**
   * Returns a sequence that can be used for a prefetch request for a decoded image.
   *
   * @param imageRequest the request that will be submitted
   * @return the sequence that should be used to process the request
   */
  public Producer<Void> getDecodedImagePrefetchProducerSequence(
      ImageRequest imageRequest) {
    return getDecodedImagePrefetchSequence(getBasicDecodedImageSequence(imageRequest));
  }

  private Producer<CloseableReference<CloseableImage>> getBasicDecodedImageSequence(
      ImageRequest imageRequest) {
    Preconditions.checkNotNull(imageRequest);

    Uri uri = imageRequest.getSourceUri();
    Preconditions.checkNotNull(uri, "Uri is null.");
    if (UriUtil.isNetworkUri(uri)) {
      return getNetworkFetchSequence();
    } else if (UriUtil.isLocalFileUri(uri)) {
      if (MediaUtils.isVideo(MediaUtils.extractMime(uri.getPath()))) {
        return getLocalVideoFileFetchSequence();
      } else {
        return getLocalImageFileFetchSequence();
      }
    } else if (UriUtil.isLocalContentUri(uri)) {
      return getLocalContentUriFetchSequence();
    } else if (UriUtil.isLocalAssetUri(uri)) {
      return getLocalAssetFetchSequence();
    } else if (UriUtil.isLocalResourceUri(uri)) {
      return getLocalResourceFetchSequence();
    } else if (UriUtil.isDataUri(uri)) {
      return getDataFetchSequence();
    } else {
      String uriString = uri.toString();
      if (uriString.length() > 30) {
        uriString = uriString.substring(0, 30) + "...";
      }
      throw new RuntimeException("Unsupported uri scheme! Uri is: " + uriString);
    }
  }

  /**
   * swallow result if prefetch -> bitmap cache get ->
   * background thread hand-off -> multiplex -> bitmap cache -> decode -> multiplex ->
   * encoded cache -> disk cache -> (webp transcode) -> network fetch.
   */
  private synchronized Producer<CloseableReference<CloseableImage>> getNetworkFetchSequence() {
    if (mNetworkFetchSequence == null) {
      mNetworkFetchSequence =
          newBitmapCacheGetToDecodeSequence(getCommonNetworkFetchToEncodedMemorySequence());
    }
    return mNetworkFetchSequence;
  }

  /**
   * background-thread hand-off -> multiplex -> encoded cache ->
   * disk cache -> (webp transcode) -> network fetch.
   *
   * 通过producerFactory构造出一个producer，该producer负责构造出一个runnable并将其放入了queue中
   * 让其所有的工作都在executor提供的线程中执行
   *
   * 这里包装的是流水线的最后一个步骤，规范了producer执行过程中在一个线程池中被执行
   */
  private synchronized Producer<EncodedImage>
  getBackgroundNetworkFetchToEncodedMemorySequence() {
    if (mBackgroundNetworkFetchToEncodedMemorySequence == null) {
      // Use hand-off producer to ensure that we don't do any unnecessary work on the UI thread.
      mBackgroundNetworkFetchToEncodedMemorySequence =
          mProducerFactory.newBackgroundThreadHandoffProducer(
              getCommonNetworkFetchToEncodedMemorySequence(),
              mThreadHandoffProducerQueue);
    }
    return mBackgroundNetworkFetchToEncodedMemorySequence;
  }

  /**
   * swallow-result -> background-thread hand-off -> multiplex -> encoded cache ->
   * disk cache -> (webp transcode) -> network fetch.
   *
   * 构造另外一条生产流水线,只不过在最后一道工序的时候不会将结果返回，而是swallow掉
   * 生产线后面的拼接与之前对于EncodedImage的流水线是一样的
   */
  private synchronized Producer<Void> getNetworkFetchToEncodedMemoryPrefetchSequence() {
    if (mNetworkFetchToEncodedMemoryPrefetchSequence == null) {
      mNetworkFetchToEncodedMemoryPrefetchSequence =
          mProducerFactory.newSwallowResultProducer(
              getBackgroundNetworkFetchToEncodedMemorySequence());
    }
    return mNetworkFetchToEncodedMemoryPrefetchSequence;
  }

  /**
   * multiplex -> encoded cache -> disk cache -> (webp transcode) -> network fetch.
   *
   * 关于EncodedImage流水线上的其他的工序
   */
  private synchronized Producer<EncodedImage> getCommonNetworkFetchToEncodedMemorySequence() {
    if (mCommonNetworkFetchToEncodedMemorySequence == null) {
      /**
       * 先创建了一个网络请求的producer
       * 获取图片的源数据后，在第一道工序后面
       * 又添加了磁盘缓存的工序以及内存缓存的工序
       */
      Producer<EncodedImage> inputProducer =
          newEncodedCacheMultiplexToTranscodeSequence(
              mProducerFactory.newNetworkFetchProducer(mNetworkFetcher));
      mCommonNetworkFetchToEncodedMemorySequence =
          ProducerFactory.newAddImageTransformMetaDataProducer(inputProducer);

      /**
       * 根据是否支持图片的旋转以及resize
       * 来决定是否需要加上这道工序
       */
      if (mResizeAndRotateEnabledForNetwork && !mDownsampleEnabled) {
        mCommonNetworkFetchToEncodedMemorySequence =
            mProducerFactory.newResizeAndRotateProducer(
                mCommonNetworkFetchToEncodedMemorySequence);
      }
    }
    return mCommonNetworkFetchToEncodedMemorySequence;
  }

  /**
   * bitmap cache get ->
   * background thread hand-off -> multiplex -> bitmap cache -> decode ->
   * branch on separate images
   *   -> exif resize and rotate -> exif thumbnail creation
   *   -> local image resize and rotate -> add meta data producer -> multiplex -> encoded cache ->
   *   (webp transcode) -> local file fetch.
   *
   *   这条流水线好长啊= =，从本地文件中获取image
   */
  private synchronized Producer<CloseableReference<CloseableImage>>
  getLocalImageFileFetchSequence() {
    if (mLocalImageFileFetchSequence == null) {
      LocalFileFetchProducer localFileFetchProducer =
          mProducerFactory.newLocalFileFetchProducer();
      mLocalImageFileFetchSequence =
          newBitmapCacheGetToLocalTransformSequence(localFileFetchProducer);
    }
    return mLocalImageFileFetchSequence;
  }

  /**
   * Bitmap cache get -> thread hand off -> multiplex -> bitmap cache ->
   * local video thumbnail
   *
   * 用来获取本地视频第一帧缩略图的流水线
   */
  private synchronized Producer<CloseableReference<CloseableImage>>
  getLocalVideoFileFetchSequence() {
    if (mLocalVideoFileFetchSequence == null) {
      LocalVideoThumbnailProducer localVideoThumbnailProducer =
          mProducerFactory.newLocalVideoThumbnailProducer();
      mLocalVideoFileFetchSequence =
          newBitmapCacheGetToBitmapCacheSequence(localVideoThumbnailProducer);
    }
    return mLocalVideoFileFetchSequence;
  }

  /**
   * bitmap cache get ->
   * background thread hand-off -> multiplex -> bitmap cache -> decode ->
   * branch on separate images
   *   -> thumbnail resize and rotate -> thumbnail branch
   *     -> local content thumbnail creation
   *     -> exif thumbnail creation
   *   -> local image resize and rotate -> add meta data producer -> multiplex -> encoded cache ->
   *   (webp transcode) -> local content uri fetch.
   *
   *   根据提供的本地Uri来进行解析并返回image的流水线
   */
  private synchronized Producer<CloseableReference<CloseableImage>>
  getLocalContentUriFetchSequence() {
    if (mLocalContentUriFetchSequence == null) {
      LocalContentUriFetchProducer localContentUriFetchProducer =
          mProducerFactory.newLocalContentUriFetchProducer();

      ThumbnailProducer<EncodedImage>[] thumbnailProducers = new ThumbnailProducer[2];
      thumbnailProducers[0] = mProducerFactory.newLocalContentUriThumbnailFetchProducer();
      thumbnailProducers[1] = mProducerFactory.newLocalExifThumbnailProducer();

      mLocalContentUriFetchSequence = newBitmapCacheGetToLocalTransformSequence(
          localContentUriFetchProducer,
          thumbnailProducers);
    }
    return mLocalContentUriFetchSequence;
  }

  /**
   * bitmap cache get ->
   * background thread hand-off -> multiplex -> bitmap cache -> decode ->
   * branch on separate images
   *   -> exif resize and rotate -> exif thumbnail creation
   *   -> local image resize and rotate -> add meta data producer -> multiplex -> encoded cache ->
   *   (webp transcode) -> local resource fetch.
   *
   *   从本地资源获取image的流水线
   */
  private synchronized Producer<CloseableReference<CloseableImage>>
  getLocalResourceFetchSequence() {
    if (mLocalResourceFetchSequence == null) {
      LocalResourceFetchProducer localResourceFetchProducer =
          mProducerFactory.newLocalResourceFetchProducer();
      mLocalResourceFetchSequence =
          newBitmapCacheGetToLocalTransformSequence(localResourceFetchProducer);
    }
    return mLocalResourceFetchSequence;
  }

  /**
   * bitmap cache get ->
   * background thread hand-off -> multiplex -> bitmap cache -> decode ->
   * branch on separate images
   *   -> exif resize and rotate -> exif thumbnail creation
   *   -> local image resize and rotate -> add meta data producer -> multiplex -> encoded cache ->
   *   (webp transcode) -> local asset fetch.
   */
  private synchronized Producer<CloseableReference<CloseableImage>> getLocalAssetFetchSequence() {
    if (mLocalAssetFetchSequence == null) {
      LocalAssetFetchProducer localAssetFetchProducer =
          mProducerFactory.newLocalAssetFetchProducer();
      mLocalAssetFetchSequence =
          newBitmapCacheGetToLocalTransformSequence(localAssetFetchProducer);
    }
    return mLocalAssetFetchSequence;
  }

  /**
   * bitmap cache get ->
   * background thread hand-off -> bitmap cache -> decode -> resize and rotate -> (webp transcode)
   * -> data fetch.
   */
  private synchronized Producer<CloseableReference<CloseableImage>> getDataFetchSequence() {
    if (mDataFetchSequence == null) {
      Producer<EncodedImage> inputProducer = mProducerFactory.newDataFetchProducer();
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2 && !mWebpSupportEnabled) {
        inputProducer = mProducerFactory.newWebpTranscodeProducer(inputProducer);
      }
      inputProducer = mProducerFactory.newAddImageTransformMetaDataProducer(inputProducer);
      if (!mDownsampleEnabled) {
        inputProducer = mProducerFactory.newResizeAndRotateProducer(inputProducer);
      }
      mDataFetchSequence = newBitmapCacheGetToDecodeSequence(inputProducer);
    }
    return mDataFetchSequence;
  }

  /**
   * Creates a new fetch sequence that just needs the source producer.
   * @param inputProducer the source producer
   * @return the new sequence
   */
  private Producer<CloseableReference<CloseableImage>> newBitmapCacheGetToLocalTransformSequence(
      Producer<EncodedImage> inputProducer) {
    ThumbnailProducer<EncodedImage>[] defaultThumbnailProducers = new ThumbnailProducer[1];
    defaultThumbnailProducers[0] = mProducerFactory.newLocalExifThumbnailProducer();
    return newBitmapCacheGetToLocalTransformSequence(inputProducer, defaultThumbnailProducers);
  }

  /**
   * Creates a new fetch sequence that just needs the source producer.
   * @param inputProducer the source producer
   * @param thumbnailProducers the thumbnail producers from which to request the image before
   * falling back to the full image producer sequence
   * @return the new sequence
   */
  private Producer<CloseableReference<CloseableImage>> newBitmapCacheGetToLocalTransformSequence(
      Producer<EncodedImage> inputProducer,
      ThumbnailProducer<EncodedImage>[] thumbnailProducers) {
    inputProducer = newEncodedCacheMultiplexToTranscodeSequence(inputProducer);
    Producer<EncodedImage> inputProducerAfterDecode =
        newLocalTransformationsSequence(inputProducer, thumbnailProducers);
    return newBitmapCacheGetToDecodeSequence(inputProducerAfterDecode);
  }

  /**
   * Same as {@code newBitmapCacheGetToBitmapCacheSequence} but with an extra DecodeProducer.
   * @param inputProducer producer providing the input to the decode
   * @return bitmap cache get to decode sequence
   */
  private Producer<CloseableReference<CloseableImage>> newBitmapCacheGetToDecodeSequence(
      Producer<EncodedImage> inputProducer) {
    DecodeProducer decodeProducer = mProducerFactory.newDecodeProducer(inputProducer);
    return newBitmapCacheGetToBitmapCacheSequence(decodeProducer);
  }

  /**
   * encoded cache multiplex -> encoded cache -> (disk cache) -> (webp transcode)
   *
   * 有关EncodedImage的中间工序
   * 根据系统是否支持webp来进行生产线的构造
   * diskCache和encodedMemoryCache也一并包含在了这次流水线中
   *
   * @param inputProducer producer providing the input to the transcode
   * @return encoded cache multiplex to webp transcode sequence
   */
  private Producer<EncodedImage> newEncodedCacheMultiplexToTranscodeSequence(
      Producer<EncodedImage> inputProducer) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2 && !mWebpSupportEnabled) {
      inputProducer = mProducerFactory.newWebpTranscodeProducer(inputProducer);
    }
    inputProducer = mProducerFactory.newDiskCacheProducer(inputProducer);
    EncodedMemoryCacheProducer encodedMemoryCacheProducer =
        mProducerFactory.newEncodedMemoryCacheProducer(inputProducer);
    return mProducerFactory.newEncodedCacheKeyMultiplexProducer(encodedMemoryCacheProducer);
  }

  /**
   * Bitmap cache get -> thread hand off -> multiplex -> bitmap cache
   * @param inputProducer producer providing the input to the bitmap cache
   * @return bitmap cache get to bitmap cache sequence
   */
  private Producer<CloseableReference<CloseableImage>> newBitmapCacheGetToBitmapCacheSequence(
      Producer<CloseableReference<CloseableImage>> inputProducer) {
    BitmapMemoryCacheProducer bitmapMemoryCacheProducer =
        mProducerFactory.newBitmapMemoryCacheProducer(inputProducer);
    BitmapMemoryCacheKeyMultiplexProducer bitmapKeyMultiplexProducer =
        mProducerFactory.newBitmapMemoryCacheKeyMultiplexProducer(bitmapMemoryCacheProducer);
    ThreadHandoffProducer<CloseableReference<CloseableImage>> threadHandoffProducer =
        mProducerFactory.newBackgroundThreadHandoffProducer(
            bitmapKeyMultiplexProducer,
            mThreadHandoffProducerQueue);
    return mProducerFactory.newBitmapMemoryCacheGetProducer(threadHandoffProducer);
  }

  /**
   * Branch on separate images
   *   -> thumbnail resize and rotate -> thumbnail producers as provided
   *   -> local image resize and rotate -> add meta data producer
   * @param inputProducer producer providing the input to add meta data producer
   * @param thumbnailProducers the thumbnail producers from which to request the image before
   * falling back to the full image producer sequence
   * @return local transformations sequence
   */
  private Producer<EncodedImage> newLocalTransformationsSequence(
      Producer<EncodedImage> inputProducer,
      ThumbnailProducer<EncodedImage>[] thumbnailProducers) {
    Producer<EncodedImage> localImageProducer =
        ProducerFactory.newAddImageTransformMetaDataProducer(inputProducer);
    if (!mDownsampleEnabled) {
      localImageProducer =
          mProducerFactory.newResizeAndRotateProducer(localImageProducer);
    }
    ThrottlingProducer<EncodedImage>
        localImageThrottlingProducer =
        mProducerFactory.newThrottlingProducer(
            mThrottlingMaxSimultaneousRequests,
            localImageProducer);
    return mProducerFactory.newBranchOnSeparateImagesProducer(
        newLocalThumbnailProducer(thumbnailProducers),
        localImageThrottlingProducer);
  }

  private Producer<EncodedImage> newLocalThumbnailProducer(
      ThumbnailProducer<EncodedImage>[] thumbnailProducers) {
    ThumbnailBranchProducer thumbnailBranchProducer =
        mProducerFactory.newThumbnailBranchProducer(thumbnailProducers);

    if (mDownsampleEnabled) {
      return thumbnailBranchProducer;
    } else {
      return mProducerFactory.newResizeAndRotateProducer(thumbnailBranchProducer);
    }
  }

  /**
   * post-processor producer -> copy producer -> inputProducer
   */
  private synchronized Producer<CloseableReference<CloseableImage>> getPostprocessorSequence(
      Producer<CloseableReference<CloseableImage>> inputProducer) {
    if (!mPostprocessorSequences.containsKey(inputProducer)) {
      PostprocessorProducer postprocessorProducer =
          mProducerFactory.newPostprocessorProducer(inputProducer);
      PostprocessedBitmapMemoryCacheProducer postprocessedBitmapMemoryCacheProducer =
          mProducerFactory.newPostprocessorBitmapMemoryCacheProducer(postprocessorProducer);
      mPostprocessorSequences.put(inputProducer, postprocessedBitmapMemoryCacheProducer);
    }
    return mPostprocessorSequences.get(inputProducer);
  }

  /**
   * swallow result producer -> inputProducer
   */
  private synchronized Producer<Void> getDecodedImagePrefetchSequence(
      Producer<CloseableReference<CloseableImage>> inputProducer) {
    if (!mCloseableImagePrefetchSequences.containsKey(inputProducer)) {
      SwallowResultProducer<CloseableReference<CloseableImage>> swallowResultProducer =
          mProducerFactory.newSwallowResultProducer(inputProducer);
      mCloseableImagePrefetchSequences.put(inputProducer, swallowResultProducer);
    }
    return mCloseableImagePrefetchSequences.get(inputProducer);
  }
}
