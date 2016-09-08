/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.drawee.controller;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import java.util.concurrent.Executor;

import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import com.facebook.common.internal.Objects;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.logging.FLog;
import com.facebook.drawee.components.DeferredReleaser;
import com.facebook.drawee.components.DraweeEventTracker;
import com.facebook.drawee.components.RetryManager;
import com.facebook.drawee.gestures.GestureDetector;
import com.facebook.drawee.interfaces.DraweeHierarchy;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.interfaces.SettableDraweeHierarchy;
import com.facebook.datasource.BaseDataSubscriber;
import com.facebook.datasource.DataSource;
import com.facebook.datasource.DataSubscriber;

import static com.facebook.drawee.components.DraweeEventTracker.Event;

/**
 * Abstract Drawee controller that implements common functionality
 * regardless of the backend used to fetch the image.
 * <p/>
 * All methods should be called on the main UI thread.
 *
 * @param <T>    image type (e.g. Bitmap)
 * @param <INFO> image info type (can be same as T)
 */
@NotThreadSafe
public abstract class AbstractDraweeController<T, INFO> implements
        DraweeController,
        DeferredReleaser.Releasable,
        GestureDetector.ClickListener {

    /**
     * This class is used to allow an optimization of not creating a ForwardingControllerListener
     * when there is only a single controller listener.
     */
    private static class InternalForwardingListener<INFO> extends ForwardingControllerListener<INFO> {
        public static <INFO> InternalForwardingListener<INFO> createInternal(
                ControllerListener<? super INFO> listener1,
                ControllerListener<? super INFO> listener2) {
            InternalForwardingListener<INFO> forwarder = new InternalForwardingListener<INFO>();
            forwarder.addListener(listener1);
            forwarder.addListener(listener2);
            return forwarder;
        }
    }

    private static final Class<?> TAG = AbstractDraweeController.class;

    // Components
    private final DraweeEventTracker mEventTracker = DraweeEventTracker.newInstance(); // 用来统计并追踪在加载图片过程中发生的一些特殊事件
    private final DeferredReleaser mDeferredReleaser; // 用来延迟释放资源，针对onDetach和onAttach事件频繁发生而导致的资源快速释放以及新建
    private final Executor mUiThreadImmediateExecutor; // 在主线程执行

    // Optional components
    private
    @Nullable
    RetryManager mRetryManager; // 用来管理加载失败后的点击重新加载的逻辑
    private
    @Nullable
    GestureDetector mGestureDetector; // 用来进行手势判断的组件, fresco自己定义的
    private
    @Nullable
    ControllerListener<INFO> mControllerListener; // 暂时不清楚用来干啥

    // Hierarchy
    private
    @Nullable
    SettableDraweeHierarchy mSettableDraweeHierarchy; // 用来处理占位图以及失败图的hierarchy
    private
    @Nullable
    Drawable mControllerOverlay;

    // Constant state (non-final because controllers can be reused)
    private String mId;
    private Object mCallerContext;

    // Mutable state
    private boolean mIsAttached; // 当前controller是否处于attach状态，只有在attach的时候才会开始工作，这个也是fresco对于view的detach和attach事件作出的回应
    private boolean mIsRequestSubmitted; // request是否已经处于提交状态,即需要加载的图片正在被处理
    private boolean mHasFetchFailed; // 加载失败
    private boolean mRetainImageOnFailure;
    private
    @Nullable
    String mContentDescription;
    private
    @Nullable
    DataSource<T> mDataSource;
    private
    @Nullable
    T mFetchedImage;
    private
    @Nullable
    Drawable mDrawable;

    public AbstractDraweeController(
            DeferredReleaser deferredReleaser,
            Executor uiThreadImmediateExecutor,
            String id,
            Object callerContext) {
        mDeferredReleaser = deferredReleaser;
        mUiThreadImmediateExecutor = uiThreadImmediateExecutor;
        init(id, callerContext, true);
    }

    /**
     * Initializes this controller with the new id and caller context.
     * This allows for reusing of the existing controller instead of instantiating a new one.
     * This method should be called when the controller is in detached state.
     * <p/>
     * 重新初始化当前的controller
     * 方便重用,这个方法应该在controller处于detach的时候调用
     *
     * @param id            unique id for this controller
     * @param callerContext tag and context for this controller
     */
    protected void initialize(String id, Object callerContext) {
        init(id, callerContext, false);
    }

    /**
     * 用来初始化controller
     * 有两个入口
     * - 复用
     * - 新建
     *
     * @param id
     * @param callerContext
     * @param justConstructed
     */
    private void init(String id, Object callerContext, boolean justConstructed) {

        // 告诉记录的组件，controller初始化操作
        mEventTracker.recordEvent(Event.ON_INIT_CONTROLLER);

        /**
         * 对于new出来的controller，不需要取消DeferredReleaser的操作
         * 如果是重用的controller，需要取消延时释放资源的组件
         */
        if (!justConstructed && mDeferredReleaser != null) {
            mDeferredReleaser.cancelDeferredRelease(this);
        }

        // reinitialize mutable state (fetch state)
        /**
         * 将controller状态置为detach状态
         */
        mIsAttached = false;

        /**
         * 通知controller释放解析过的资源
         */
        releaseFetch();
        mRetainImageOnFailure = false;
        // reinitialize optional components

        /**
         * 下面对一些组件进行初始化
         * 管理重试的组件、处理手势的组件
         */
        if (mRetryManager != null) {
            mRetryManager.init();
        }
        if (mGestureDetector != null) {
            mGestureDetector.init();
            mGestureDetector.setClickListener(this);
        }
        if (mControllerListener instanceof InternalForwardingListener) {
            ((InternalForwardingListener) mControllerListener).clearListeners();
        } else {
            mControllerListener = null;
        }
        // clear hierarchy and controller overlay
        if (mSettableDraweeHierarchy != null) {
            mSettableDraweeHierarchy.reset();
            mSettableDraweeHierarchy.setControllerOverlay(null);
            mSettableDraweeHierarchy = null;
        }
        mControllerOverlay = null;
        // reinitialize constant state
        if (FLog.isLoggable(FLog.VERBOSE)) {
            FLog.v(TAG, "controller %x %s -> %s: initialize", System.identityHashCode(this), mId, id);
        }
        mId = id;
        mCallerContext = callerContext;
    }

    /**
     * 这个方法是{@link DeferredReleaser}来调用的
     * 当controller收到了detach事件之后，DeferredReleaser会延时调用这个release方法
     */
    @Override
    public void release() {
        mEventTracker.recordEvent(Event.ON_RELEASE_CONTROLLER);
        if (mRetryManager != null) {
            mRetryManager.reset();
        }
        if (mGestureDetector != null) {
            mGestureDetector.reset();
        }
        if (mSettableDraweeHierarchy != null) {
            mSettableDraweeHierarchy.reset();
        }
        releaseFetch();
    }

    /**
     * 释放解析过的drawable
     * 重置一些属性等等
     * 该放的放，该关的关
     */
    private void releaseFetch() {
        boolean wasRequestSubmitted = mIsRequestSubmitted;
        mIsRequestSubmitted = false;
        mHasFetchFailed = false;
        if (mDataSource != null) {
            mDataSource.close();
            mDataSource = null;
        }
        if (mDrawable != null) {
            releaseDrawable(mDrawable);
        }
        if (mContentDescription != null) {
            mContentDescription = null;
        }
        mDrawable = null;
        if (mFetchedImage != null) {
            logMessageAndImage("release", mFetchedImage);
            releaseImage(mFetchedImage);
            mFetchedImage = null;
        }
        if (wasRequestSubmitted) {
            getControllerListener().onRelease(mId);
        }
    }

    /**
     * Gets the controller id.
     */
    public String getId() {
        return mId;
    }

    /**
     * Gets the analytic tag & caller context
     */
    public Object getCallerContext() {
        return mCallerContext;
    }

    /**
     * Gets retry manager.
     */
    protected
    @Nullable
    RetryManager getRetryManager() {
        return mRetryManager;
    }

    /**
     * Sets retry manager.
     */
    protected void setRetryManager(@Nullable RetryManager retryManager) {
        mRetryManager = retryManager;
    }

    /**
     * Gets gesture detector.
     */
    protected
    @Nullable
    GestureDetector getGestureDetector() {
        return mGestureDetector;
    }

    /**
     * Sets gesture detector.
     */
    protected void setGestureDetector(@Nullable GestureDetector gestureDetector) {
        mGestureDetector = gestureDetector;
        if (mGestureDetector != null) {
            mGestureDetector.setClickListener(this);
        }
    }

    /**
     * Sets whether to display last available image in case of failure.
     */
    protected void setRetainImageOnFailure(boolean enabled) {
        mRetainImageOnFailure = enabled;
    }

    /**
     * Gets accessibility content description.
     */
    @Override
    public
    @Nullable
    String getContentDescription() {
        return mContentDescription;
    }

    /**
     * Sets accessibility content description.
     */
    @Override
    public void setContentDescription(@Nullable String contentDescription) {
        mContentDescription = contentDescription;
    }

    /**
     * Adds controller listener.
     */
    public void addControllerListener(ControllerListener<? super INFO> controllerListener) {
        Preconditions.checkNotNull(controllerListener);
        if (mControllerListener instanceof InternalForwardingListener) {
            ((InternalForwardingListener<INFO>) mControllerListener).addListener(controllerListener);
            return;
        }
        if (mControllerListener != null) {
            mControllerListener = InternalForwardingListener.createInternal(
                    mControllerListener,
                    controllerListener);
            return;
        }
        // Listener only receives <INFO>, it never produces one.
        // That means if it can accept <? super INFO>, it can very well accept <INFO>.
        mControllerListener = (ControllerListener<INFO>) controllerListener;
    }

    /**
     * Removes controller listener.
     */
    public void removeControllerListener(ControllerListener<? super INFO> controllerListener) {
        Preconditions.checkNotNull(controllerListener);
        if (mControllerListener instanceof InternalForwardingListener) {
            ((InternalForwardingListener<INFO>) mControllerListener).removeListener(controllerListener);
            return;
        }
        if (mControllerListener == controllerListener) {
            mControllerListener = null;
        }
    }

    /**
     * Gets controller listener for internal use.
     */
    protected ControllerListener<INFO> getControllerListener() {
        if (mControllerListener == null) {
            return BaseControllerListener.getNoOpListener();
        }
        return mControllerListener;
    }

    /**
     * Gets the hierarchy
     */
    @Override
    public
    @Nullable
    DraweeHierarchy getHierarchy() {
        return mSettableDraweeHierarchy;
    }

    /**
     * Sets the hierarchy.
     * 设置hierarchy的时候，需要将前一个释放，并将有关信息绑定到这个新的hierarchy上
     *
     * <p/>
     * <p>The controller should be detached when this method is called.
     *
     * @param hierarchy This must be an instance of {@link SettableDraweeHierarchy}
     */
    @Override
    public void setHierarchy(@Nullable DraweeHierarchy hierarchy) {
        if (FLog.isLoggable(FLog.VERBOSE)) {
            FLog.v(
                    TAG,
                    "controller %x %s: setHierarchy: %s",
                    System.identityHashCode(this),
                    mId,
                    hierarchy);
        }
        mEventTracker.recordEvent(
                (hierarchy != null) ? Event.ON_SET_HIERARCHY : Event.ON_CLEAR_HIERARCHY);
        // force release in case request was submitted
        if (mIsRequestSubmitted) {
            mDeferredReleaser.cancelDeferredRelease(this);
            release();
        }
        // clear the existing hierarchy
        if (mSettableDraweeHierarchy != null) {
            mSettableDraweeHierarchy.setControllerOverlay(null);
            mSettableDraweeHierarchy = null;
        }
        // set the new hierarchy
        if (hierarchy != null) {
            Preconditions.checkArgument(hierarchy instanceof SettableDraweeHierarchy);
            mSettableDraweeHierarchy = (SettableDraweeHierarchy) hierarchy;
            mSettableDraweeHierarchy.setControllerOverlay(mControllerOverlay);
        }
    }

    /**
     * Sets the controller overlay
     */
    protected void setControllerOverlay(@Nullable Drawable controllerOverlay) {
        mControllerOverlay = controllerOverlay;
        if (mSettableDraweeHierarchy != null) {
            mSettableDraweeHierarchy.setControllerOverlay(mControllerOverlay);
        }
    }

    /**
     * Gets the controller overlay
     */
    protected
    @Nullable
    Drawable getControllerOverlay() {
        return mControllerOverlay;
    }

    /**
     * 用来控制controller开始加载request中的图片
     * 由DraweeHolder统一管理
     */
    @Override
    public void onAttach() {
        if (FLog.isLoggable(FLog.VERBOSE)) {
            FLog.v(
                    TAG,
                    "controller %x %s: onAttach: %s",
                    System.identityHashCode(this),
                    mId,
                    mIsRequestSubmitted ? "request already submitted" : "request needs submit");
        }
        mEventTracker.recordEvent(Event.ON_ATTACH_CONTROLLER);
        Preconditions.checkNotNull(mSettableDraweeHierarchy);
        mDeferredReleaser.cancelDeferredRelease(this);
        mIsAttached = true;
        if (!mIsRequestSubmitted) {
            submitRequest();
        }
    }

    /**
     * 同上
     * 用来通知controller停止加载图片
     * 并准备释放资源
     */
    @Override
    public void onDetach() {
        if (FLog.isLoggable(FLog.VERBOSE)) {
            FLog.v(TAG, "controller %x %s: onDetach", System.identityHashCode(this), mId);
        }
        mEventTracker.recordEvent(Event.ON_DETACH_CONTROLLER);
        mIsAttached = false;
        mDeferredReleaser.scheduleDeferredRelease(this);
    }

    /**
     * 有些地方可能需要hierarchy处理点击事件
     *
     * @param event
     * @return
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (FLog.isLoggable(FLog.VERBOSE)) {
            FLog.v(TAG, "controller %x %s: onTouchEvent %s", System.identityHashCode(this), mId, event);
        }
        if (mGestureDetector == null) {
            return false;
        }
        if (mGestureDetector.isCapturingGesture() || shouldHandleGesture()) {
            mGestureDetector.onTouchEvent(event);
            return true;
        }
        return false;
    }

    /**
     * Returns whether the gesture should be handled by the controller
     */
    protected boolean shouldHandleGesture() {
        return shouldRetryOnTap();
    }

    private boolean shouldRetryOnTap() {
        // We should only handle touch event if we are expecting some gesture.
        // For example, we expect click when fetch fails and tap-to-retry is enabled.
        return mHasFetchFailed && mRetryManager != null && mRetryManager.shouldRetryOnTap();
    }

    /**
     * 点击重新加载
     *
     * @return
     */
    @Override
    public boolean onClick() {
        if (FLog.isLoggable(FLog.VERBOSE)) {
            FLog.v(TAG, "controller %x %s: onClick", System.identityHashCode(this), mId);
        }
        if (shouldRetryOnTap()) {
            mRetryManager.notifyTapToRetry();
            mSettableDraweeHierarchy.reset();
            submitRequest();
            return true;
        }
        return false;
    }

    /**
     * 提交当前ImageRequest，开始加载图片
     */
    protected void submitRequest() {
        /**
         * 先查找是否存在cache的图片
         */
        final T closeableImage = getCachedImage();
        if (closeableImage != null) {
            /**
             * cache中有目标图片
             * 命中缓存
             */
            mDataSource = null;
            mIsRequestSubmitted = true;
            mHasFetchFailed = false;
            mEventTracker.recordEvent(Event.ON_SUBMIT_CACHE_HIT);
            getControllerListener().onSubmit(mId, mCallerContext);
            /**
             * 提交结果
             */
            onNewResultInternal(mId, mDataSource, closeableImage, 1.0f, true, true);
            return;
        }
        mEventTracker.recordEvent(Event.ON_DATASOURCE_SUBMIT);
        getControllerListener().onSubmit(mId, mCallerContext);
        mSettableDraweeHierarchy.setProgress(0, true);
        mIsRequestSubmitted = true;
        mHasFetchFailed = false;

        /**
         * 拿到数据源的包装类
         * 检查是否已经有了结果
         * 新建一个带有观察者性质的实例，订阅数据源的动向
         * 当有了结果之后再调用{@link #onNewResultInternal(String, DataSource, Object, float, boolean, boolean)}等函数
         *
         * 也就是说加载数据貌似并不是从这里才开始
         * 有可能还是有结果的
         */
        mDataSource = getDataSource();
        if (FLog.isLoggable(FLog.VERBOSE)) {
            FLog.v(
                    TAG,
                    "controller %x %s: submitRequest: dataSource: %x",
                    System.identityHashCode(this),
                    mId,
                    System.identityHashCode(mDataSource));
        }
        final String id = mId;
        final boolean wasImmediate = mDataSource.hasResult();
        final DataSubscriber<T> dataSubscriber =
                new BaseDataSubscriber<T>() {
                    @Override
                    public void onNewResultImpl(DataSource<T> dataSource) {
                        // isFinished must be obtained before image, otherwise we might set intermediate result
                        // as final image.
                        boolean isFinished = dataSource.isFinished();
                        float progress = dataSource.getProgress();
                        T image = dataSource.getResult();
                        if (image != null) {
                            onNewResultInternal(id, dataSource, image, progress, isFinished, wasImmediate);
                        } else if (isFinished) {
                            onFailureInternal(id, dataSource, new NullPointerException(), /* isFinished */ true);
                        }
                    }

                    @Override
                    public void onFailureImpl(DataSource<T> dataSource) {
                        onFailureInternal(id, dataSource, dataSource.getFailureCause(), /* isFinished */ true);
                    }

                    @Override
                    public void onProgressUpdate(DataSource<T> dataSource) {
                        boolean isFinished = dataSource.isFinished();
                        float progress = dataSource.getProgress();
                        onProgressUpdateInternal(id, dataSource, progress, isFinished);
                    }
                };
        mDataSource.subscribe(dataSubscriber, mUiThreadImmediateExecutor);
    }

    /**
     * 通知controller有新的结果来了
     *
     * @param id
     * @param dataSource
     * @param image
     * @param progress
     * @param isFinished
     * @param wasImmediate
     */
    private void onNewResultInternal(
            String id,
            DataSource<T> dataSource,
            @Nullable T image,
            float progress,
            boolean isFinished,
            boolean wasImmediate) {
        // ignore late callbacks (data source that returned the new result is not the one we expected)
        /**
         * 检测到当前的结果并不是想要的结果
         * 释放图片资源
         */
        if (!isExpectedDataSource(id, dataSource)) {
            logMessageAndImage("ignore_old_datasource @ onNewResult", image);
            releaseImage(image);
            dataSource.close();
            return;
        }
        mEventTracker.recordEvent(
                isFinished ? Event.ON_DATASOURCE_RESULT : Event.ON_DATASOURCE_RESULT_INT);
        // create drawable
        /**
         * 创建一个Drawable
         * 根据新来的image的类型来创建drawable
         * 创建过程交给子类实现，大致分为static和dynamic两种
         * 也就是普通图片和webp、gif等动图
         */
        Drawable drawable;
        try {
            /**
             * 创建即将递交给hierarchy的drawable对象
             */
            drawable = createDrawable(image);

        } catch (Exception exception) {
            /**
             * 在创建drawable的过程中
             */
            logMessageAndImage("drawable_failed @ onNewResult", image);
            releaseImage(image);
            onFailureInternal(id, dataSource, exception, isFinished);
            return;
        }
        T previousImage = mFetchedImage;
        Drawable previousDrawable = mDrawable;
        mFetchedImage = image;
        mDrawable = drawable;
        try {
            // set the new image
            if (isFinished) {
                logMessageAndImage("set_final_result @ onNewResult", image);
                mDataSource = null;
                mSettableDraweeHierarchy.setImage(drawable, 1f, wasImmediate);
                getControllerListener().onFinalImageSet(id, getImageInfo(image), getAnimatable());
                // IMPORTANT: do not execute any instance-specific code after this point
            } else {
                logMessageAndImage("set_intermediate_result @ onNewResult", image);
                mSettableDraweeHierarchy.setImage(drawable, progress, wasImmediate);
                getControllerListener().onIntermediateImageSet(id, getImageInfo(image));
                // IMPORTANT: do not execute any instance-specific code after this point
            }
        } finally {
            if (previousDrawable != null && previousDrawable != drawable) {
                releaseDrawable(previousDrawable);
            }
            if (previousImage != null && previousImage != image) {
                logMessageAndImage("release_previous_result @ onNewResult", previousImage);
                releaseImage(previousImage);
            }
        }
    }

    private void onFailureInternal(
            String id,
            DataSource<T> dataSource,
            Throwable throwable,
            boolean isFinished) {
        // ignore late callbacks (data source that failed is not the one we expected)
        if (!isExpectedDataSource(id, dataSource)) {
            logMessageAndFailure("ignore_old_datasource @ onFailure", throwable);
            dataSource.close();
            return;
        }
        mEventTracker.recordEvent(
                isFinished ? Event.ON_DATASOURCE_FAILURE : Event.ON_DATASOURCE_FAILURE_INT);
        // fail only if the data source is finished
        if (isFinished) {
            logMessageAndFailure("final_failed @ onFailure", throwable);
            mDataSource = null;
            mHasFetchFailed = true;
            // Set the previously available image if available.
            if (mRetainImageOnFailure && mDrawable != null) {
                mSettableDraweeHierarchy.setImage(mDrawable, 1f, true);
            } else if (shouldRetryOnTap()) {
                mSettableDraweeHierarchy.setRetry(throwable);
            } else {
                mSettableDraweeHierarchy.setFailure(throwable);
            }
            getControllerListener().onFailure(mId, throwable);
            // IMPORTANT: do not execute any instance-specific code after this point
        } else {
            logMessageAndFailure("intermediate_failed @ onFailure", throwable);
            getControllerListener().onIntermediateImageFailed(mId, throwable);
            // IMPORTANT: do not execute any instance-specific code after this point
        }
    }

    private void onProgressUpdateInternal(
            String id,
            DataSource<T> dataSource,
            float progress,
            boolean isFinished) {
        // ignore late callbacks (data source that failed is not the one we expected)
        if (!isExpectedDataSource(id, dataSource)) {
            logMessageAndFailure("ignore_old_datasource @ onProgress", null);
            dataSource.close();
            return;
        }
        if (!isFinished) {
            mSettableDraweeHierarchy.setProgress(progress, false);
        }
    }

    private boolean isExpectedDataSource(String id, DataSource<T> dataSource) {
        if (dataSource == null && mDataSource == null) {
            // DataSource is null when we use directly the Bitmap from the MemoryCache. In this case
            // we don't have to close the DataSource.
            return true;
        }
        // There are several situations in which an old data source might return a result that we are no
        // longer interested in. To verify that the result is indeed expected, we check several things:
        return id.equals(mId) && dataSource == mDataSource && mIsRequestSubmitted;
    }

    private void logMessageAndImage(String messageAndMethod, T image) {
        if (FLog.isLoggable(FLog.VERBOSE)) {
            FLog.v(
                    TAG,
                    "controller %x %s: %s: image: %s %x",
                    System.identityHashCode(this),
                    mId,
                    messageAndMethod,
                    getImageClass(image),
                    getImageHash(image));
        }
    }

    private void logMessageAndFailure(String messageAndMethod, Throwable throwable) {
        if (FLog.isLoggable(FLog.VERBOSE)) {
            FLog.v(
                    TAG,
                    "controller %x %s: %s: failure: %s",
                    System.identityHashCode(this),
                    mId,
                    messageAndMethod,
                    throwable);
        }
    }

    @Override
    public
    @Nullable
    Animatable getAnimatable() {
        return (mDrawable instanceof Animatable) ? (Animatable) mDrawable : null;
    }

    protected abstract DataSource<T> getDataSource();

    protected abstract Drawable createDrawable(T image);

    protected abstract
    @Nullable
    INFO getImageInfo(T image);

    protected String getImageClass(@Nullable T image) {
        return (image != null) ? image.getClass().getSimpleName() : "<null>";
    }

    protected int getImageHash(@Nullable T image) {
        return System.identityHashCode(image);
    }

    protected abstract void releaseImage(@Nullable T image);

    protected abstract void releaseDrawable(@Nullable Drawable drawable);

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("isAttached", mIsAttached)
                .add("isRequestSubmitted", mIsRequestSubmitted)
                .add("hasFetchFailed", mHasFetchFailed)
                .add("fetchedImage", getImageHash(mFetchedImage))
                .add("events", mEventTracker.toString())
                .toString();
    }

    protected T getCachedImage() {
        return null;
    }
}
