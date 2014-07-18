/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package android.support.v7.widget;

import android.content.Context;
import android.database.Observable;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.Nullable;
import android.support.v4.util.ArrayMap;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.VelocityTrackerCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.support.v4.widget.ScrollerCompat;
import static android.support.v7.widget.AdapterHelper.UpdateOp;
import static android.support.v7.widget.AdapterHelper.Callback;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.FocusFinder;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Interpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A flexible view for providing a limited window into a large data set.
 *
 * <h3>Glossary of terms:</h3>
 *
 * <ul>
 *     <li><em>Adapter:</em> A subclass of {@link Adapter} responsible for providing views
 *     that represent items in a data set.</li>
 *     <li><em>Position:</em> The position of a data item within an <em>Adapter</em>.</li>
 *     <li><em>Index:</em> The index of an attached child view as used in a call to
 *     {@link ViewGroup#getChildAt}. Contrast with <em>Position.</em></li>
 *     <li><em>Binding:</em> The process of preparing a child view to display data corresponding
 *     to a <em>position</em> within the adapter.</li>
 *     <li><em>Recycle (view):</em> A view previously used to display data for a specific adapter
 *     position may be placed in a cache for later reuse to display the same type of data again
 *     later. This can drastically improve performance by skipping initial layout inflation
 *     or construction.</li>
 *     <li><em>Scrap (view):</em> A child view that has entered into a temporarily detached
 *     state during layout. Scrap views may be reused without becoming fully detached
 *     from the parent RecyclerView, either unmodified if no rebinding is required or modified
 *     by the adapter if the view was considered <em>dirty</em>.</li>
 *     <li><em>Dirty (view):</em> A child view that must be rebound by the adapter before
 *     being displayed.</li>
 * </ul>
 */
public class RecyclerView extends ViewGroup {
    private static final String TAG = "RecyclerView";

    private static final boolean DEBUG = false;

    private static final boolean DISPATCH_TEMP_DETACH = false;
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public static final int NO_POSITION = -1;
    public static final long NO_ID = -1;
    public static final int INVALID_TYPE = -1;

    private static final int MAX_SCROLL_DURATION = 2000;

    private final RecyclerViewDataObserver mObserver = new RecyclerViewDataObserver();

    final Recycler mRecycler = new Recycler();

    private SavedState mPendingSavedState;

    AdapterHelper mAdapterHelper;

    ChildHelper mChildHelper;

    final List<View> mDisappearingViewsInLayoutPass = new ArrayList<View>();


    /**
     * Prior to L, there is no way to query this variable which is why we override the setter and
     * track it here.
     */
    private boolean mClipToPadding;

    /**
     * Note: this Runnable is only ever posted if:
     * 1) We've been through first layout
     * 2) We know we have a fixed size (mHasFixedSize)
     * 3) We're attached
     */
    private final Runnable mUpdateChildViewsRunnable = new Runnable() {
        public void run() {
            if (!mAdapterHelper.hasPendingUpdates()) {
                return;
            }
            if (!mFirstLayoutComplete) {
                // a layout request will happen, we should not do layout here.
                return;
            }
            if (mDataSetHasChangedAfterLayout) {
                dispatchLayout();
            } else {
                eatRequestLayout();
                mAdapterHelper.preProcess();
                if (!mLayoutRequestEaten) {
                    // We run this after pre-processing is complete so that ViewHolders have their
                    // final adapter positions. No need to run it if a layout is already requested.
                    rebindUpdatedViewHolders();
                }
                resumeRequestLayout(true);
            }
        }
    };

    private final Rect mTempRect = new Rect();
    private Adapter mAdapter;
    private LayoutManager mLayout;
    private RecyclerListener mRecyclerListener;
    private final ArrayList<ItemDecoration> mItemDecorations = new ArrayList<ItemDecoration>();
    private final ArrayList<OnItemTouchListener> mOnItemTouchListeners =
            new ArrayList<OnItemTouchListener>();
    private OnItemTouchListener mActiveOnItemTouchListener;
    private boolean mIsAttached;
    private boolean mHasFixedSize;
    private boolean mFirstLayoutComplete;
    private boolean mEatRequestLayout;
    private boolean mLayoutRequestEaten;
    private boolean mAdapterUpdateDuringMeasure;
    private final boolean mPostUpdatesOnAnimation;

    /**
     * Set to true when an adapter data set changed notification is received.
     * In that case, we cannot run any animations since we don't know what happened.
     */
    private boolean mDataSetHasChangedAfterLayout = false;

    /**
     * This variable is set to true during a dispatchLayout and/or scroll.
     * Some methods should not be called during these periods (e.g. adapter data change).
     * Doing so will create hard to find bugs so we better check it and throw an exception.
     *
     * @see #assertInLayoutOrScroll(String)
     * @see #assertNotInLayoutOrScroll(String)
     */
    private boolean mRunningLayoutOrScroll = false;

    private EdgeEffectCompat mLeftGlow, mTopGlow, mRightGlow, mBottomGlow;

    ItemAnimator mItemAnimator = new DefaultItemAnimator();

    private static final int INVALID_POINTER = -1;

    /**
     * The RecyclerView is not currently scrolling.
     * @see #getScrollState()
     */
    public static final int SCROLL_STATE_IDLE = 0;

    /**
     * The RecyclerView is currently being dragged by outside input such as user touch input.
     * @see #getScrollState()
     */
    public static final int SCROLL_STATE_DRAGGING = 1;

    /**
     * The RecyclerView is currently animating to a final position while not under
     * outside control.
     * @see #getScrollState()
     */
    public static final int SCROLL_STATE_SETTLING = 2;

    // Touch/scrolling handling

    private int mScrollState = SCROLL_STATE_IDLE;
    private int mScrollPointerId = INVALID_POINTER;
    private VelocityTracker mVelocityTracker;
    private int mInitialTouchX;
    private int mInitialTouchY;
    private int mLastTouchX;
    private int mLastTouchY;
    private final int mTouchSlop;
    private final int mMinFlingVelocity;
    private final int mMaxFlingVelocity;

    private final ViewFlinger mViewFlinger = new ViewFlinger();

    final State mState = new State();

    private OnScrollListener mScrollListener;

    // For use in item animations
    boolean mItemsAddedOrRemoved = false;
    boolean mItemsChanged = false;
    boolean mInPreLayout = false;
    private ItemAnimator.ItemAnimatorListener mItemAnimatorListener =
            new ItemAnimatorRestoreListener();
    private boolean mPostedAnimatorRunner = false;
    private Runnable mItemAnimatorRunner = new Runnable() {
        @Override
        public void run() {
            if (mItemAnimator != null) {
                mItemAnimator.runPendingAnimations();
            }
            mPostedAnimatorRunner = false;
        }
    };

    private static final Interpolator sQuinticInterpolator = new Interpolator() {
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };

    public RecyclerView(Context context) {
        this(context, null);
    }

    public RecyclerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final int version = Build.VERSION.SDK_INT;
        mPostUpdatesOnAnimation = version >= 16;

        final ViewConfiguration vc = ViewConfiguration.get(context);
        mTouchSlop = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        setWillNotDraw(ViewCompat.getOverScrollMode(this) == ViewCompat.OVER_SCROLL_NEVER);

        mItemAnimator.setListener(mItemAnimatorListener);
        initAdapterManager();
        initChildrenHelper();
    }

    private void initChildrenHelper() {
        mChildHelper = new ChildHelper(new ChildHelper.Callback() {
            @Override
            public int getChildCount() {
                return RecyclerView.this.getChildCount();
            }

            @Override
            public void addView(View child, int index) {
                RecyclerView.this.addView(child, index);
            }

            @Override
            public int indexOfChild(View view) {
                return RecyclerView.this.indexOfChild(view);
            }

            @Override
            public void removeViewAt(int index) {
                RecyclerView.this.removeViewAt(index);
            }

            @Override
            public View getChildAt(int offset) {
                return RecyclerView.this.getChildAt(offset);
            }

            @Override
            public void removeAllViews() {
                RecyclerView.this.removeAllViews();
            }

            @Override
            public ViewHolder getChildViewHolder(View view) {
                return getChildViewHolderInt(view);
            }

            @Override
            public void attachViewToParent(View child, int index,
                    ViewGroup.LayoutParams layoutParams) {
                RecyclerView.this.attachViewToParent(child, index, layoutParams);
            }

            @Override
            public void detachViewFromParent(int offset) {
                RecyclerView.this.detachViewFromParent(offset);
            }
        });
    }

    void initAdapterManager() {
        mAdapterHelper = new AdapterHelper(new Callback() {
            @Override
            public ViewHolder findViewHolder(int position) {
                return findViewHolderForPosition(position, true);
            }

            @Override
            public void offsetPositionsForRemovingInvisible(int start, int count) {
                offsetPositionRecordsForRemove(start, count, true);
                mItemsAddedOrRemoved = true;
                mState.mDeletedInvisibleItemCountSincePreviousLayout += count;
            }

            @Override
            public void offsetPositionsForRemovingLaidOutOrNewView(int positionStart, int itemCount) {
                offsetPositionRecordsForRemove(positionStart, itemCount, false);
                mItemsAddedOrRemoved = true;
            }

            @Override
            public void markViewHoldersUpdated(int positionStart, int itemCount) {
                viewRangeUpdate(positionStart, itemCount);
                mItemsChanged = true;
            }

            @Override
            public void onDispatchFirstPass(UpdateOp op) {
                dispatchUpdate(op);
            }

            void dispatchUpdate(UpdateOp op) {
                switch (op.cmd) {
                    case UpdateOp.ADD:
                        mLayout.onItemsAdded(RecyclerView.this, op.positionStart, op.itemCount);
                        break;
                    case UpdateOp.REMOVE:
                        mLayout.onItemsRemoved(RecyclerView.this, op.positionStart, op.itemCount);
                        break;
                    case UpdateOp.UPDATE:
                        mLayout.onItemsUpdated(RecyclerView.this, op.positionStart, op.itemCount);
                        break;
                    case UpdateOp.MOVE:
                        mLayout.onItemsMoved(RecyclerView.this, op.positionStart, op.itemCount, 1);
                        break;
                }
            }

            @Override
            public void onDispatchSecondPass(UpdateOp op) {
                dispatchUpdate(op);
            }

            @Override
            public void offsetPositionsForAdd(int positionStart, int itemCount) {
                offsetPositionRecordsForInsert(positionStart, itemCount);
                mItemsAddedOrRemoved = true;
            }

            @Override
            public void offsetPositionsForMove(int from, int to) {
                offsetPositionRecordsForMove(from, to);
                // should we create mItemsMoved ?
                mItemsAddedOrRemoved = true;
            }
        });
    }

    /**
     * RecyclerView can perform several optimizations if it can know in advance that changes in
     * adapter content cannot change the size of the RecyclerView itself.
     * If your use of RecyclerView falls into this category, set this to true.
     *
     * @param hasFixedSize true if adapter changes cannot affect the size of the RecyclerView.
     */
    public void setHasFixedSize(boolean hasFixedSize) {
        mHasFixedSize = hasFixedSize;
    }

    /**
     * @return true if the app has specified that changes in adapter content cannot change
     * the size of the RecyclerView itself.
     */
    public boolean hasFixedSize() {
        return mHasFixedSize;
    }

    @Override
    public void setClipToPadding(boolean clipToPadding) {
        if (clipToPadding != mClipToPadding) {
            invalidateGlows();
        }
        mClipToPadding = clipToPadding;
        super.setClipToPadding(clipToPadding);
        if (mFirstLayoutComplete) {
            requestLayout();
        }
    }

    /**
     * Set a new adapter to provide child views on demand.
     *
     * @param adapter The new adapter to set, or null to set no adapter.
     */
    public void setAdapter(Adapter adapter) {
        if (mAdapter != null) {
            mAdapter.unregisterAdapterDataObserver(mObserver);
        }
        // end all running animations
        if (mItemAnimator != null) {
            mItemAnimator.endAnimations();
        }
        // Since animations are ended, mLayout.children should be equal to recyclerView.children.
        // This may not be true if item animator's end does not work as expected. (e.g. not release
        // children instantly). It is safer to use mLayout's child count.
        if (mLayout != null) {
            mLayout.removeAndRecycleAllViews(mRecycler);
            mLayout.removeAndRecycleScrapInt(mRecycler, true);
        }
        mAdapterHelper.reset();
        final Adapter oldAdapter = mAdapter;
        mAdapter = adapter;
        if (adapter != null) {
            adapter.registerAdapterDataObserver(mObserver);
        }
        if (mLayout != null) {
            mLayout.onAdapterChanged(oldAdapter, mAdapter);
        }
        mRecycler.onAdapterChanged(oldAdapter, mAdapter);
        mState.mStructureChanged = true;
        markKnownViewsInvalid();
        requestLayout();
    }

    /**
     * Retrieves the previously set adapter or null if no adapter is set.
     *
     * @return The previously set adapter
     * @see #setAdapter(Adapter)
     */
    public Adapter getAdapter() {
        return mAdapter;
    }

    /**
     * Register a listener that will be notified whenever a child view is recycled.
     *
     * <p>This listener will be called when a LayoutManager or the RecyclerView decides
     * that a child view is no longer needed. If an application associates expensive
     * or heavyweight data with item views, this may be a good place to release
     * or free those resources.</p>
     *
     * @param listener Listener to register, or null to clear
     */
    public void setRecyclerListener(RecyclerListener listener) {
        mRecyclerListener = listener;
    }

    /**
     * Set the {@link LayoutManager} that this RecyclerView will use.
     *
     * <p>In contrast to other adapter-backed views such as {@link android.widget.ListView}
     * or {@link android.widget.GridView}, RecyclerView allows client code to provide custom
     * layout arrangements for child views. These arrangements are controlled by the
     * {@link LayoutManager}. A LayoutManager must be provided for RecyclerView to function.</p>
     *
     * <p>Several default strategies are provided for common uses such as lists and grids.</p>
     *
     * @param layout LayoutManager to use
     */
    public void setLayoutManager(LayoutManager layout) {
        if (layout == mLayout) {
            return;
        }
        // TODO We should do this switch a dispachLayout pass and animate children. There is a good
        // chance that LayoutManagers will re-use views.
        if (mLayout != null) {
            if (mIsAttached) {
                mLayout.onDetachedFromWindow(this, mRecycler);
            }
            mLayout.setRecyclerView(null);
        }
        mRecycler.clear();
        mChildHelper.removeAllViewsUnfiltered();
        mLayout = layout;
        if (layout != null) {
            if (layout.mRecyclerView != null) {
                throw new IllegalArgumentException("LayoutManager " + layout +
                        " is already attached to a RecyclerView: " + layout.mRecyclerView);
            }
            mLayout.setRecyclerView(this);
            if (mIsAttached) {
                mLayout.onAttachedToWindow(this);
            }
        }
        requestLayout();
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        SavedState state = new SavedState(super.onSaveInstanceState());
        if (mPendingSavedState != null) {
            state.copyFrom(mPendingSavedState);
        } else if (mLayout != null) {
            state.mLayoutState = mLayout.onSaveInstanceState();
        } else {
            state.mLayoutState = null;
        }

        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        mPendingSavedState = (SavedState) state;
        super.onRestoreInstanceState(mPendingSavedState.getSuperState());
        if (mLayout != null && mPendingSavedState.mLayoutState != null) {
            mLayout.onRestoreInstanceState(mPendingSavedState.mLayoutState);
        }
    }

    /**
     * Adds a view to the animatingViews list.
     * mAnimatingViews holds the child views that are currently being kept around
     * purely for the purpose of being animated out of view. They are drawn as a regular
     * part of the child list of the RecyclerView, but they are invisible to the LayoutManager
     * as they are managed separately from the regular child views.
     * @param view The view to be removed
     */
    private void addAnimatingView(View view) {
        final boolean alreadyParented = view.getParent() == this;
        mRecycler.unscrapView(getChildViewHolder(view));
        if (!alreadyParented) {
            mChildHelper.addView(view, true);
        } else {
            mChildHelper.hide(view);
        }
    }

    /**
     * Removes a view from the animatingViews list.
     * @param view The view to be removed
     * @see #addAnimatingView(View)
     */
    private void removeAnimatingView(View view) {
        eatRequestLayout();
        if (mChildHelper.removeViewIfHidden(view)) {
            mRecycler.unscrapView(getChildViewHolderInt(view));
            mRecycler.recycleView(view);
            if (DEBUG) {
                Log.d(TAG, "after removing animated view: " + view + ", " + this);
            }
        }
        resumeRequestLayout(false);
    }

    /**
     * Return the {@link LayoutManager} currently responsible for
     * layout policy for this RecyclerView.
     *
     * @return The currently bound LayoutManager
     */
    public LayoutManager getLayoutManager() {
        return mLayout;
    }

    /**
     * Retrieve this RecyclerView's {@link RecycledViewPool}. This method will never return null;
     * if no pool is set for this view a new one will be created. See
     * {@link #setRecycledViewPool(RecycledViewPool) setRecycledViewPool} for more information.
     *
     * @return The pool used to store recycled item views for reuse.
     * @see #setRecycledViewPool(RecycledViewPool)
     */
    public RecycledViewPool getRecycledViewPool() {
        return mRecycler.getRecycledViewPool();
    }

    /**
     * Recycled view pools allow multiple RecyclerViews to share a common pool of scrap views.
     * This can be useful if you have multiple RecyclerViews with adapters that use the same
     * view types, for example if you have several data sets with the same kinds of item views
     * displayed by a {@link android.support.v4.view.ViewPager ViewPager}.
     *
     * @param pool Pool to set. If this parameter is null a new pool will be created and used.
     */
    public void setRecycledViewPool(RecycledViewPool pool) {
        mRecycler.setRecycledViewPool(pool);
    }

    /**
     * Sets a new {@link ViewCacheExtension} to be used by the Recycler.
     *
     * @param extension ViewCacheExtension to be used or null if you want to clear the existing one.
     *
     * @see {@link ViewCacheExtension#getViewForPositionAndType(Recycler, int, int)}
     */
    public void setViewCacheExtension(ViewCacheExtension extension) {
        mRecycler.setViewCacheExtension(extension);
    }

    /**
     * Set the number of offscreen views to retain before adding them to the potentially shared
     * {@link #getRecycledViewPool() recycled view pool}.
     *
     * <p>The offscreen view cache stays aware of changes in the attached adapter, allowing
     * a LayoutManager to reuse those views unmodified without needing to return to the adapter
     * to rebind them.</p>
     *
     * @param size Number of views to cache offscreen before returning them to the general
     *             recycled view pool
     */
    public void setItemViewCacheSize(int size) {
        mRecycler.setViewCacheSize(size);
    }

    /**
     * Return the current scrolling state of the RecyclerView.
     *
     * @return {@link #SCROLL_STATE_IDLE}, {@link #SCROLL_STATE_DRAGGING} or
     * {@link #SCROLL_STATE_SETTLING}
     */
    public int getScrollState() {
        return mScrollState;
    }

    private void setScrollState(int state) {
        if (state == mScrollState) {
            return;
        }
        mScrollState = state;
        if (state != SCROLL_STATE_SETTLING) {
            stopScroll();
        }
        if (mScrollListener != null) {
            mScrollListener.onScrollStateChanged(state);
        }
        mLayout.onScrollStateChanged(state);
    }

    /**
     * Add an {@link ItemDecoration} to this RecyclerView. Item decorations can
     * affect both measurement and drawing of individual item views.
     *
     * <p>Item decorations are ordered. Decorations placed earlier in the list will
     * be run/queried/drawn first for their effects on item views. Padding added to views
     * will be nested; a padding added by an earlier decoration will mean further
     * item decorations in the list will be asked to draw/pad within the previous decoration's
     * given area.</p>
     *
     * @param decor Decoration to add
     * @param index Position in the decoration chain to insert this decoration at. If this value
     *              is negative the decoration will be added at the end.
     */
    public void addItemDecoration(ItemDecoration decor, int index) {
        if (mItemDecorations.isEmpty()) {
            setWillNotDraw(false);
        }
        if (index < 0) {
            mItemDecorations.add(decor);
        } else {
            mItemDecorations.add(index, decor);
        }
        markItemDecorInsetsDirty();
        requestLayout();
    }

    /**
     * Add an {@link ItemDecoration} to this RecyclerView. Item decorations can
     * affect both measurement and drawing of individual item views.
     *
     * <p>Item decorations are ordered. Decorations placed earlier in the list will
     * be run/queried/drawn first for their effects on item views. Padding added to views
     * will be nested; a padding added by an earlier decoration will mean further
     * item decorations in the list will be asked to draw/pad within the previous decoration's
     * given area.</p>
     *
     * @param decor Decoration to add
     */
    public void addItemDecoration(ItemDecoration decor) {
        addItemDecoration(decor, -1);
    }

    /**
     * Remove an {@link ItemDecoration} from this RecyclerView.
     *
     * <p>The given decoration will no longer impact the measurement and drawing of
     * item views.</p>
     *
     * @param decor Decoration to remove
     * @see #addItemDecoration(ItemDecoration)
     */
    public void removeItemDecoration(ItemDecoration decor) {
        mItemDecorations.remove(decor);
        if (mItemDecorations.isEmpty()) {
            setWillNotDraw(ViewCompat.getOverScrollMode(this) == ViewCompat.OVER_SCROLL_NEVER);
        }
        markItemDecorInsetsDirty();
        requestLayout();
    }

    /**
     * Set a listener that will be notified of any changes in scroll state or position.
     *
     * @param listener Listener to set or null to clear
     */
    public void setOnScrollListener(OnScrollListener listener) {
        mScrollListener = listener;
    }

    /**
     * Convenience method to scroll to a certain position.
     *
     * RecyclerView does not implement scrolling logic, rather forwards the call to
     * {@link android.support.v7.widget.RecyclerView.LayoutManager#scrollToPosition(int)}
     * @param position Scroll to this adapter position
     * @see android.support.v7.widget.RecyclerView.LayoutManager#scrollToPosition(int)
     */
    public void scrollToPosition(int position) {
        stopScroll();
        mLayout.scrollToPosition(position);
        awakenScrollBars();
    }

    /**
     * Starts a smooth scroll to an adapter position.
     * <p>
     * To support smooth scrolling, you must override
     * {@link LayoutManager#smoothScrollToPosition(RecyclerView, State, int)} and create a
     * {@link SmoothScroller}.
     * <p>
     * {@link LayoutManager} is responsible for creating the actual scroll action. If you want to
     * provide a custom smooth scroll logic, override
     * {@link LayoutManager#smoothScrollToPosition(RecyclerView, State, int)} in your
     * LayoutManager.
     *
     * @param position The adapter position to scroll to
     * @see LayoutManager#smoothScrollToPosition(RecyclerView, State, int)
     */
    public void smoothScrollToPosition(int position) {
        mLayout.smoothScrollToPosition(this, mState, position);
    }

    @Override
    public void scrollTo(int x, int y) {
        throw new UnsupportedOperationException(
                "RecyclerView does not support scrolling to an absolute position.");
    }

    @Override
    public void scrollBy(int x, int y) {
        if (mLayout == null) {
            throw new IllegalStateException("Cannot scroll without a LayoutManager set. " +
                    "Call setLayoutManager with a non-null argument.");
        }
        final boolean canScrollHorizontal = mLayout.canScrollHorizontally();
        final boolean canScrollVertical = mLayout.canScrollVertically();
        if (canScrollHorizontal || canScrollVertical) {
            scrollByInternal(canScrollHorizontal ? x : 0, canScrollVertical ? y : 0);
        }
    }

    /**
     * Helper method reflect data changes to the state.
     * <p>
     * Adapter changes during a scroll may trigger a crash because scroll assumes no data change
     * but data actually changed.
     * <p>
     * This method consumes all deferred changes to avoid that case.
     */
    private void consumePendingUpdateOperations() {
        if (mAdapterHelper.hasPendingUpdates()) {
            mUpdateChildViewsRunnable.run();
        }
    }

    /**
     * Does not perform bounds checking. Used by internal methods that have already validated input.
     */
    void scrollByInternal(int x, int y) {
        int overscrollX = 0, overscrollY = 0;
        consumePendingUpdateOperations();
        if (mAdapter != null) {
            eatRequestLayout();
            mRunningLayoutOrScroll = true;
            if (x != 0) {
                final int hresult = mLayout.scrollHorizontallyBy(x, mRecycler, mState);
                overscrollX = x - hresult;
            }
            if (y != 0) {
                final int vresult = mLayout.scrollVerticallyBy(y, mRecycler, mState);
                overscrollY = y - vresult;
            }
            if (supportsChangeAnimations()) {
                // Fix up shadow views used by changing animations
                int count = mChildHelper.getChildCount();
                for (int i = 0; i < count; i++) {
                    View view = mChildHelper.getChildAt(i);
                    ViewHolder holder = getChildViewHolder(view);
                    if (holder != null && holder.mShadowingHolder != null) {
                        ViewHolder shadowingHolder = holder.mShadowingHolder;
                        View shadowingView = shadowingHolder != null ? shadowingHolder.itemView : null;
                        if (shadowingView != null) {
                            int left = view.getLeft();
                            int top = view.getTop();
                            if (left != shadowingView.getLeft() || top != shadowingView.getTop()) {
                                shadowingView.layout(left, top,
                                        left + shadowingView.getWidth(),
                                        top + shadowingView.getHeight());
                            }
                        }
                    }
                }
            }
            mRunningLayoutOrScroll = false;
            resumeRequestLayout(false);
        }
        if (!mItemDecorations.isEmpty()) {
            invalidate();
        }
        if (ViewCompat.getOverScrollMode(this) != ViewCompat.OVER_SCROLL_NEVER) {
            considerReleasingGlowsOnScroll(x, y);
            pullGlows(overscrollX, overscrollY);
        }
        if (mScrollListener != null && (x != 0 || y != 0)) {
            mScrollListener.onScrolled(x, y);
        }
        if (!awakenScrollBars()) {
            invalidate();
        }
    }

    /**
     * <p>Compute the horizontal offset of the horizontal scrollbar's thumb within the horizontal
     * range. This value is used to compute the length of the thumb within the scrollbar's track.
     * </p>
     *
     * <p>The range is expressed in arbitrary units that must be the same as the units used by
     * {@link #computeHorizontalScrollRange()} and {@link #computeHorizontalScrollExtent()}.</p>
     *
     * <p>Default implementation returns 0.</p>
     *
     * <p>If you want to support scroll bars, override
     * {@link RecyclerView.LayoutManager#computeHorizontalScrollOffset(RecyclerView.State)} in your
     * LayoutManager. </p>
     *
     * @return The horizontal offset of the scrollbar's thumb
     * @see android.support.v7.widget.RecyclerView.LayoutManager#computeHorizontalScrollOffset
     * (RecyclerView.Adapter)
     */
    @Override
    protected int computeHorizontalScrollOffset() {
        return mLayout.canScrollHorizontally() ? mLayout.computeHorizontalScrollOffset(mState)
                : 0;
    }

    /**
     * <p>Compute the horizontal extent of the horizontal scrollbar's thumb within the
     * horizontal range. This value is used to compute the length of the thumb within the
     * scrollbar's track.</p>
     *
     * <p>The range is expressed in arbitrary units that must be the same as the units used by
     * {@link #computeHorizontalScrollRange()} and {@link #computeHorizontalScrollOffset()}.</p>
     *
     * <p>Default implementation returns 0.</p>
     *
     * <p>If you want to support scroll bars, override
     * {@link RecyclerView.LayoutManager#computeHorizontalScrollExtent(RecyclerView.State)} in your
     * LayoutManager.</p>
     *
     * @return The horizontal extent of the scrollbar's thumb
     * @see RecyclerView.LayoutManager#computeHorizontalScrollExtent(RecyclerView.State)
     */
    @Override
    protected int computeHorizontalScrollExtent() {
        return mLayout.canScrollHorizontally() ? mLayout.computeHorizontalScrollExtent(mState) : 0;
    }

    /**
     * <p>Compute the horizontal range that the horizontal scrollbar represents.</p>
     *
     * <p>The range is expressed in arbitrary units that must be the same as the units used by
     * {@link #computeHorizontalScrollExtent()} and {@link #computeHorizontalScrollOffset()}.</p>
     *
     * <p>Default implementation returns 0.</p>
     *
     * <p>If you want to support scroll bars, override
     * {@link RecyclerView.LayoutManager#computeHorizontalScrollRange(RecyclerView.State)} in your
     * LayoutManager.</p>
     *
     * @return The total horizontal range represented by the vertical scrollbar
     * @see RecyclerView.LayoutManager#computeHorizontalScrollRange(RecyclerView.State)
     */
    @Override
    protected int computeHorizontalScrollRange() {
        return mLayout.canScrollHorizontally() ? mLayout.computeHorizontalScrollRange(mState) : 0;
    }

    /**
     * <p>Compute the vertical offset of the vertical scrollbar's thumb within the vertical range.
     * This value is used to compute the length of the thumb within the scrollbar's track. </p>
     *
     * <p>The range is expressed in arbitrary units that must be the same as the units used by
     * {@link #computeVerticalScrollRange()} and {@link #computeVerticalScrollExtent()}.</p>
     *
     * <p>Default implementation returns 0.</p>
     *
     * <p>If you want to support scroll bars, override
     * {@link RecyclerView.LayoutManager#computeVerticalScrollOffset(RecyclerView.State)} in your
     * LayoutManager.</p>
     *
     * @return The vertical offset of the scrollbar's thumb
     * @see android.support.v7.widget.RecyclerView.LayoutManager#computeVerticalScrollOffset
     * (RecyclerView.Adapter)
     */
    @Override
    protected int computeVerticalScrollOffset() {
        return mLayout.canScrollVertically() ? mLayout.computeVerticalScrollOffset(mState) : 0;
    }

    /**
     * <p>Compute the vertical extent of the vertical scrollbar's thumb within the vertical range.
     * This value is used to compute the length of the thumb within the scrollbar's track.</p>
     *
     * <p>The range is expressed in arbitrary units that must be the same as the units used by
     * {@link #computeVerticalScrollRange()} and {@link #computeVerticalScrollOffset()}.</p>
     *
     * <p>Default implementation returns 0.</p>
     *
     * <p>If you want to support scroll bars, override
     * {@link RecyclerView.LayoutManager#computeVerticalScrollExtent(RecyclerView.State)} in your
     * LayoutManager.</p>
     *
     * @return The vertical extent of the scrollbar's thumb
     * @see RecyclerView.LayoutManager#computeVerticalScrollExtent(RecyclerView.State)
     */
    @Override
    protected int computeVerticalScrollExtent() {
        return mLayout.canScrollVertically() ? mLayout.computeVerticalScrollExtent(mState) : 0;
    }

    /**
     * <p>Compute the vertical range that the vertical scrollbar represents.</p>
     *
     * <p>The range is expressed in arbitrary units that must be the same as the units used by
     * {@link #computeVerticalScrollExtent()} and {@link #computeVerticalScrollOffset()}.</p>
     *
     * <p>Default implementation returns 0.</p>
     *
     * <p>If you want to support scroll bars, override
     * {@link RecyclerView.LayoutManager#computeVerticalScrollRange(RecyclerView.State)} in your
     * LayoutManager.</p>
     *
     * @return The total vertical range represented by the vertical scrollbar
     * @see RecyclerView.LayoutManager#computeVerticalScrollRange(RecyclerView.State)
     */
    @Override
    protected int computeVerticalScrollRange() {
        return mLayout.canScrollVertically() ? mLayout.computeVerticalScrollRange(mState) : 0;
    }


    void eatRequestLayout() {
        if (!mEatRequestLayout) {
            mEatRequestLayout = true;
            mLayoutRequestEaten = false;
        }
    }

    void resumeRequestLayout(boolean performLayoutChildren) {
        if (mEatRequestLayout) {
            if (performLayoutChildren && mLayoutRequestEaten &&
                    mLayout != null && mAdapter != null) {
                dispatchLayout();
            }
            mEatRequestLayout = false;
            mLayoutRequestEaten = false;
        }
    }

    /**
     * Animate a scroll by the given amount of pixels along either axis.
     *
     * @param dx Pixels to scroll horizontally
     * @param dy Pixels to scroll vertically
     */
    public void smoothScrollBy(int dx, int dy) {
        if (dx != 0 || dy != 0) {
            mViewFlinger.smoothScrollBy(dx, dy);
        }
    }

    /**
     * Begin a standard fling with an initial velocity along each axis in pixels per second.
     * If the velocity given is below the system-defined minimum this method will return false
     * and no fling will occur.
     *
     * @param velocityX Initial horizontal velocity in pixels per second
     * @param velocityY Initial vertical velocity in pixels per second
     * @return true if the fling was started, false if the velocity was too low to fling
     */
    public boolean fling(int velocityX, int velocityY) {
        if (Math.abs(velocityX) < mMinFlingVelocity) {
            velocityX = 0;
        }
        if (Math.abs(velocityY) < mMinFlingVelocity) {
            velocityY = 0;
        }
        velocityX = Math.max(-mMaxFlingVelocity, Math.min(velocityX, mMaxFlingVelocity));
        velocityY = Math.max(-mMaxFlingVelocity, Math.min(velocityY, mMaxFlingVelocity));
        if (velocityX != 0 || velocityY != 0) {
            mViewFlinger.fling(velocityX, velocityY);
            return true;
        }
        return false;
    }

    /**
     * Stop any current scroll in progress, such as one started by
     * {@link #smoothScrollBy(int, int)}, {@link #fling(int, int)} or a touch-initiated fling.
     */
    public void stopScroll() {
        mViewFlinger.stop();
        mLayout.stopSmoothScroller();
    }

    /**
     * Apply a pull to relevant overscroll glow effects
     */
    private void pullGlows(int overscrollX, int overscrollY) {
        if (overscrollX < 0) {
            ensureLeftGlow();
            mLeftGlow.onPull(-overscrollX / (float) getWidth());
        } else if (overscrollX > 0) {
            ensureRightGlow();
            mRightGlow.onPull(overscrollX / (float) getWidth());
        }

        if (overscrollY < 0) {
            ensureTopGlow();
            mTopGlow.onPull(-overscrollY / (float) getHeight());
        } else if (overscrollY > 0) {
            ensureBottomGlow();
            mBottomGlow.onPull(overscrollY / (float) getHeight());
        }

        if (overscrollX != 0 || overscrollY != 0) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    private void releaseGlows() {
        boolean needsInvalidate = false;
        if (mLeftGlow != null) needsInvalidate = mLeftGlow.onRelease();
        if (mTopGlow != null) needsInvalidate |= mTopGlow.onRelease();
        if (mRightGlow != null) needsInvalidate |= mRightGlow.onRelease();
        if (mBottomGlow != null) needsInvalidate |= mBottomGlow.onRelease();
        if (needsInvalidate) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    private void considerReleasingGlowsOnScroll(int dx, int dy) {
        boolean needsInvalidate = false;
        if (mLeftGlow != null && !mLeftGlow.isFinished() && dx > 0) {
            needsInvalidate = mLeftGlow.onRelease();
        }
        if (mRightGlow != null && !mRightGlow.isFinished() && dx < 0) {
            needsInvalidate |= mRightGlow.onRelease();
        }
        if (mTopGlow != null && !mTopGlow.isFinished() && dy > 0) {
            needsInvalidate |= mTopGlow.onRelease();
        }
        if (mBottomGlow != null && !mBottomGlow.isFinished() && dy < 0) {
            needsInvalidate |= mBottomGlow.onRelease();
        }
        if (needsInvalidate) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    void absorbGlows(int velocityX, int velocityY) {
        if (velocityX < 0) {
            ensureLeftGlow();
            mLeftGlow.onAbsorb(-velocityX);
        } else if (velocityX > 0) {
            ensureRightGlow();
            mRightGlow.onAbsorb(velocityX);
        }

        if (velocityY < 0) {
            ensureTopGlow();
            mTopGlow.onAbsorb(-velocityY);
        } else if (velocityY > 0) {
            ensureBottomGlow();
            mBottomGlow.onAbsorb(velocityY);
        }

        if (velocityX != 0 || velocityY != 0) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    void ensureLeftGlow() {
        if (mLeftGlow != null) {
            return;
        }
        mLeftGlow = new EdgeEffectCompat(getContext());
        if (mClipToPadding) {
            mLeftGlow.setSize(getMeasuredHeight() - getPaddingTop() - getPaddingBottom(),
                    getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
        } else {
            mLeftGlow.setSize(getMeasuredHeight(), getMeasuredWidth());
        }
    }

    void ensureRightGlow() {
        if (mRightGlow != null) {
            return;
        }
        mRightGlow = new EdgeEffectCompat(getContext());
        if (mClipToPadding) {
            mRightGlow.setSize(getMeasuredHeight() - getPaddingTop() - getPaddingBottom(),
                    getMeasuredWidth() - getPaddingLeft() - getPaddingRight());
        } else {
            mRightGlow.setSize(getMeasuredHeight(), getMeasuredWidth());
        }
    }

    void ensureTopGlow() {
        if (mTopGlow != null) {
            return;
        }
        mTopGlow = new EdgeEffectCompat(getContext());
        if (mClipToPadding) {
            mTopGlow.setSize(getMeasuredWidth() - getPaddingLeft() - getPaddingRight(),
                    getMeasuredHeight() - getPaddingTop() - getPaddingBottom());
        } else {
            mTopGlow.setSize(getMeasuredWidth(), getMeasuredHeight());
        }

    }

    void ensureBottomGlow() {
        if (mBottomGlow != null) {
            return;
        }
        mBottomGlow = new EdgeEffectCompat(getContext());
        if (mClipToPadding) {
            mBottomGlow.setSize(getMeasuredWidth() - getPaddingLeft() - getPaddingRight(),
                    getMeasuredHeight() - getPaddingTop() - getPaddingBottom());
        } else {
            mBottomGlow.setSize(getMeasuredWidth(), getMeasuredHeight());
        }
    }

    void invalidateGlows() {
        mLeftGlow = mRightGlow = mTopGlow = mBottomGlow = null;
    }

    // Focus handling

    @Override
    public View focusSearch(View focused, int direction) {
        View result = mLayout.onInterceptFocusSearch(focused, direction);
        if (result != null) {
            return result;
        }
        final FocusFinder ff = FocusFinder.getInstance();
        result = ff.findNextFocus(this, focused, direction);
        if (result == null && mAdapter != null) {
            eatRequestLayout();
            result = mLayout.onFocusSearchFailed(focused, direction, mRecycler, mState);
            resumeRequestLayout(false);
        }
        return result != null ? result : super.focusSearch(focused, direction);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        if (!mLayout.onRequestChildFocus(this, mState, child, focused)) {
            mTempRect.set(0, 0, focused.getWidth(), focused.getHeight());
            offsetDescendantRectToMyCoords(focused, mTempRect);
            offsetRectIntoDescendantCoords(child, mTempRect);
            requestChildRectangleOnScreen(child, mTempRect, !mFirstLayoutComplete);
        }
        super.requestChildFocus(child, focused);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
        return mLayout.requestChildRectangleOnScreen(this, child, rect, immediate);
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        if (!mLayout.onAddFocusables(this, views, direction, focusableMode)) {
            super.addFocusables(views, direction, focusableMode);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mIsAttached = true;
        mFirstLayoutComplete = false;
        if (mLayout != null) {
            mLayout.onAttachedToWindow(this);
        }
        mPostedAnimatorRunner = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mFirstLayoutComplete = false;

        stopScroll();
        mIsAttached = false;
        if (mLayout != null) {
            mLayout.onDetachedFromWindow(this, mRecycler);
        }
        removeCallbacks(mItemAnimatorRunner);
    }

    /**
     * Checks if RecyclerView is in the middle of a layout or scroll and throws an
     * {@link IllegalStateException} if it <b>is not</b>.
     *
     * @param message The message for the exception. Can be null.
     * @see #assertNotInLayoutOrScroll(String)
     */
    void assertInLayoutOrScroll(String message) {
        if (!mRunningLayoutOrScroll) {
            if (message == null) {
                throw new IllegalStateException("Cannot call this method unless RecyclerView is "
                        + "computing a layout or scrolling");
            }
            throw new IllegalStateException(message);

        }
    }

    /**
     * Checks if RecyclerView is in the middle of a layout or scroll and throws an
     * {@link IllegalStateException} if it <b>is</b>.
     *
     * @param message The message for the exception. Can be null.
     * @see #assertInLayoutOrScroll(String)
     */
    void assertNotInLayoutOrScroll(String message) {
        if (mRunningLayoutOrScroll) {
            if (message == null) {
                throw new IllegalStateException("Cannot call this method while RecyclerView is "
                        + "computing a layout or scrolling");
            }
            throw new IllegalStateException(message);
        }
    }

    /**
     * Add an {@link OnItemTouchListener} to intercept touch events before they are dispatched
     * to child views or this view's standard scrolling behavior.
     *
     * <p>Client code may use listeners to implement item manipulation behavior. Once a listener
     * returns true from
     * {@link OnItemTouchListener#onInterceptTouchEvent(RecyclerView, MotionEvent)} its
     * {@link OnItemTouchListener#onTouchEvent(RecyclerView, MotionEvent)} method will be called
     * for each incoming MotionEvent until the end of the gesture.</p>
     *
     * @param listener Listener to add
     */
    public void addOnItemTouchListener(OnItemTouchListener listener) {
        mOnItemTouchListeners.add(listener);
    }

    /**
     * Remove an {@link OnItemTouchListener}. It will no longer be able to intercept touch events.
     *
     * @param listener Listener to remove
     */
    public void removeOnItemTouchListener(OnItemTouchListener listener) {
        mOnItemTouchListeners.remove(listener);
        if (mActiveOnItemTouchListener == listener) {
            mActiveOnItemTouchListener = null;
        }
    }

    private boolean dispatchOnItemTouchIntercept(MotionEvent e) {
        final int action = e.getAction();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_DOWN) {
            mActiveOnItemTouchListener = null;
        }

        final int listenerCount = mOnItemTouchListeners.size();
        for (int i = 0; i < listenerCount; i++) {
            final OnItemTouchListener listener = mOnItemTouchListeners.get(i);
            if (listener.onInterceptTouchEvent(this, e) && action != MotionEvent.ACTION_CANCEL) {
                mActiveOnItemTouchListener = listener;
                return true;
            }
        }
        return false;
    }

    private boolean dispatchOnItemTouch(MotionEvent e) {
        final int action = e.getAction();
        if (mActiveOnItemTouchListener != null) {
            if (action == MotionEvent.ACTION_DOWN) {
                // Stale state from a previous gesture, we're starting a new one. Clear it.
                mActiveOnItemTouchListener = null;
            } else {
                mActiveOnItemTouchListener.onTouchEvent(this, e);
                if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                    // Clean up for the next gesture.
                    mActiveOnItemTouchListener = null;
                }
                return true;
            }
        }

        // Listeners will have already received the ACTION_DOWN via dispatchOnItemTouchIntercept
        // as called from onInterceptTouchEvent; skip it.
        if (action != MotionEvent.ACTION_DOWN) {
            final int listenerCount = mOnItemTouchListeners.size();
            for (int i = 0; i < listenerCount; i++) {
                final OnItemTouchListener listener = mOnItemTouchListeners.get(i);
                if (listener.onInterceptTouchEvent(this, e)) {
                    mActiveOnItemTouchListener = listener;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (dispatchOnItemTouchIntercept(e)) {
            cancelTouch();
            return true;
        }

        final boolean canScrollHorizontally = mLayout.canScrollHorizontally();
        final boolean canScrollVertically = mLayout.canScrollVertically();

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(e);

        final int action = MotionEventCompat.getActionMasked(e);
        final int actionIndex = MotionEventCompat.getActionIndex(e);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mScrollPointerId = MotionEventCompat.getPointerId(e, 0);
                mInitialTouchX = mLastTouchX = (int) (e.getX() + 0.5f);
                mInitialTouchY = mLastTouchY = (int) (e.getY() + 0.5f);

                if (mScrollState == SCROLL_STATE_SETTLING) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    setScrollState(SCROLL_STATE_DRAGGING);
                }
                break;

            case MotionEventCompat.ACTION_POINTER_DOWN:
                mScrollPointerId = MotionEventCompat.getPointerId(e, actionIndex);
                mInitialTouchX = mLastTouchX = (int) (MotionEventCompat.getX(e, actionIndex) + 0.5f);
                mInitialTouchY = mLastTouchY = (int) (MotionEventCompat.getY(e, actionIndex) + 0.5f);
                break;

            case MotionEvent.ACTION_MOVE: {
                final int index = MotionEventCompat.findPointerIndex(e, mScrollPointerId);
                if (index < 0) {
                    Log.e(TAG, "Error processing scroll; pointer index for id " +
                            mScrollPointerId + " not found. Did any MotionEvents get skipped?");
                    return false;
                }

                final int x = (int) (MotionEventCompat.getX(e, index) + 0.5f);
                final int y = (int) (MotionEventCompat.getY(e, index) + 0.5f);
                if (mScrollState != SCROLL_STATE_DRAGGING) {
                    final int dx = x - mInitialTouchX;
                    final int dy = y - mInitialTouchY;
                    boolean startScroll = false;
                    if (canScrollHorizontally && Math.abs(dx) > mTouchSlop) {
                        mLastTouchX = mInitialTouchX + mTouchSlop * (dx < 0 ? -1 : 1);
                        startScroll = true;
                    }
                    if (canScrollVertically && Math.abs(dy) > mTouchSlop) {
                        mLastTouchY = mInitialTouchY + mTouchSlop * (dy < 0 ? -1 : 1);
                        startScroll = true;
                    }
                    if (startScroll) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        setScrollState(SCROLL_STATE_DRAGGING);
                    }
                }
            } break;

            case MotionEventCompat.ACTION_POINTER_UP: {
                onPointerUp(e);
            } break;

            case MotionEvent.ACTION_UP: {
                mVelocityTracker.clear();
            } break;

            case MotionEvent.ACTION_CANCEL: {
                cancelTouch();
            }
        }
        return mScrollState == SCROLL_STATE_DRAGGING;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (dispatchOnItemTouch(e)) {
            cancelTouch();
            return true;
        }

        final boolean canScrollHorizontally = mLayout.canScrollHorizontally();
        final boolean canScrollVertically = mLayout.canScrollVertically();

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(e);

        final int action = MotionEventCompat.getActionMasked(e);
        final int actionIndex = MotionEventCompat.getActionIndex(e);

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mScrollPointerId = MotionEventCompat.getPointerId(e, 0);
                mInitialTouchX = mLastTouchX = (int) (e.getX() + 0.5f);
                mInitialTouchY = mLastTouchY = (int) (e.getY() + 0.5f);
            } break;

            case MotionEventCompat.ACTION_POINTER_DOWN: {
                mScrollPointerId = MotionEventCompat.getPointerId(e, actionIndex);
                mInitialTouchX = mLastTouchX = (int) (MotionEventCompat.getX(e, actionIndex) + 0.5f);
                mInitialTouchY = mLastTouchY = (int) (MotionEventCompat.getY(e, actionIndex) + 0.5f);
            } break;

            case MotionEvent.ACTION_MOVE: {
                final int index = MotionEventCompat.findPointerIndex(e, mScrollPointerId);
                if (index < 0) {
                    Log.e(TAG, "Error processing scroll; pointer index for id " +
                            mScrollPointerId + " not found. Did any MotionEvents get skipped?");
                    return false;
                }

                final int x = (int) (MotionEventCompat.getX(e, index) + 0.5f);
                final int y = (int) (MotionEventCompat.getY(e, index) + 0.5f);
                if (mScrollState != SCROLL_STATE_DRAGGING) {
                    final int dx = x - mInitialTouchX;
                    final int dy = y - mInitialTouchY;
                    boolean startScroll = false;
                    if (canScrollHorizontally && Math.abs(dx) > mTouchSlop) {
                        mLastTouchX = mInitialTouchX + mTouchSlop * (dx < 0 ? -1 : 1);
                        startScroll = true;
                    }
                    if (canScrollVertically && Math.abs(dy) > mTouchSlop) {
                        mLastTouchY = mInitialTouchY + mTouchSlop * (dy < 0 ? -1 : 1);
                        startScroll = true;
                    }
                    if (startScroll) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        setScrollState(SCROLL_STATE_DRAGGING);
                    }
                }
                if (mScrollState == SCROLL_STATE_DRAGGING) {
                    final int dx = x - mLastTouchX;
                    final int dy = y - mLastTouchY;
                    scrollByInternal(canScrollHorizontally ? -dx : 0,
                            canScrollVertically ? -dy : 0);
                }
                mLastTouchX = x;
                mLastTouchY = y;
            } break;

            case MotionEventCompat.ACTION_POINTER_UP: {
                onPointerUp(e);
            } break;

            case MotionEvent.ACTION_UP: {
                mVelocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity);
                final float xvel = canScrollHorizontally ?
                        -VelocityTrackerCompat.getXVelocity(mVelocityTracker, mScrollPointerId) : 0;
                final float yvel = canScrollVertically ?
                        -VelocityTrackerCompat.getYVelocity(mVelocityTracker, mScrollPointerId) : 0;
                if (!((xvel != 0 || yvel != 0) && fling((int) xvel, (int) yvel))) {
                    setScrollState(SCROLL_STATE_IDLE);
                }
                mVelocityTracker.clear();
                releaseGlows();
            } break;

            case MotionEvent.ACTION_CANCEL: {
                cancelTouch();
            } break;
        }

        return true;
    }

    private void cancelTouch() {
        mVelocityTracker.clear();
        releaseGlows();
        setScrollState(SCROLL_STATE_IDLE);
    }

    private void onPointerUp(MotionEvent e) {
        final int actionIndex = MotionEventCompat.getActionIndex(e);
        if (MotionEventCompat.getPointerId(e, actionIndex) == mScrollPointerId) {
            // Pick a new pointer to pick up the slack.
            final int newIndex = actionIndex == 0 ? 1 : 0;
            mScrollPointerId = MotionEventCompat.getPointerId(e, newIndex);
            mInitialTouchX = mLastTouchX = (int) (MotionEventCompat.getX(e, newIndex) + 0.5f);
            mInitialTouchY = mLastTouchY = (int) (MotionEventCompat.getY(e, newIndex) + 0.5f);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (mAdapterUpdateDuringMeasure) {
            eatRequestLayout();
            mAdapterHelper.preProcess();
            mAdapterUpdateDuringMeasure = false;
            resumeRequestLayout(false);
        }

        if (mAdapter != null) {
            mState.mItemCount = mAdapter.getItemCount();
        }
        mLayout.onMeasure(mRecycler, mState, widthSpec, heightSpec);

        final int widthSize = getMeasuredWidth();
        final int heightSize = getMeasuredHeight();

        if (mLeftGlow != null) mLeftGlow.setSize(heightSize, widthSize);
        if (mTopGlow != null) mTopGlow.setSize(widthSize, heightSize);
        if (mRightGlow != null) mRightGlow.setSize(heightSize, widthSize);
        if (mBottomGlow != null) mBottomGlow.setSize(widthSize, heightSize);
    }

    /**
     * Sets the {@link ItemAnimator} that will handle animations involving changes
     * to the items in this RecyclerView. By default, RecyclerView instantiates and
     * uses an instance of {@link DefaultItemAnimator}. Whether item animations are
     * enabled for the RecyclerView depends on the ItemAnimator and whether
     * the LayoutManager {@link LayoutManager#supportsPredictiveItemAnimations()
     * supports item animations}.
     *
     * @param animator The ItemAnimator being set. If null, no animations will occur
     * when changes occur to the items in this RecyclerView.
     */
    public void setItemAnimator(ItemAnimator animator) {
        if (mItemAnimator != null) {
            mItemAnimator.endAnimations();
            mItemAnimator.setListener(null);
        }
        mItemAnimator = animator;
        if (mItemAnimator != null) {
            mItemAnimator.setListener(mItemAnimatorListener);
        }
    }

    /**
     * Gets the current ItemAnimator for this RecyclerView. A null return value
     * indicates that there is no animator and that item changes will happen without
     * any animations. By default, RecyclerView instantiates and
     * uses an instance of {@link DefaultItemAnimator}.
     *
     * @return ItemAnimator The current ItemAnimator. If null, no animations will occur
     * when changes occur to the items in this RecyclerView.
     */
    public ItemAnimator getItemAnimator() {
        return mItemAnimator;
    }

    private boolean supportsChangeAnimations() {
        return mItemAnimator != null && mItemAnimator.getSupportsChangeAnimations();
    }

    /**
     * Post a runnable to the next frame to run pending item animations. Only the first such
     * request will be posted, governed by the mPostedAnimatorRunner flag.
     */
    private void postAnimationRunner() {
        if (!mPostedAnimatorRunner && mIsAttached) {
            ViewCompat.postOnAnimation(this, mItemAnimatorRunner);
            mPostedAnimatorRunner = true;
        }
    }

    private boolean predictiveItemAnimationsEnabled() {
        return (mItemAnimator != null && mLayout.supportsPredictiveItemAnimations());
    }

    /**
     * Wrapper around layoutChildren() that handles animating changes caused by layout.
     * Animations work on the assumption that there are five different kinds of items
     * in play:
     * PERSISTENT: items are visible before and after layout
     * REMOVED: items were visible before layout and were removed by the app
     * ADDED: items did not exist before layout and were added by the app
     * DISAPPEARING: items exist in the data set before/after, but changed from
     * visible to non-visible in the process of layout (they were moved off
     * screen as a side-effect of other changes)
     * APPEARING: items exist in the data set before/after, but changed from
     * non-visible to visible in the process of layout (they were moved on
     * screen as a side-effect of other changes)
     * The overall approach figures out what items exist before/after layout and
     * infers one of the five above states for each of the items. Then the animations
     * are set up accordingly:
     * PERSISTENT views are moved ({@link ItemAnimator#animateMove(ViewHolder, int, int, int, int)})
     * REMOVED views are removed ({@link ItemAnimator#animateRemove(ViewHolder)})
     * ADDED views are added ({@link ItemAnimator#animateAdd(ViewHolder)})
     * DISAPPEARING views are moved off screen
     * APPEARING views are moved on screen
     */
    void dispatchLayout() {
        if (mAdapter == null) {
            Log.e(TAG, "No adapter attached; skipping layout");
            return;
        }
        mDisappearingViewsInLayoutPass.clear();
        eatRequestLayout();
        mRunningLayoutOrScroll = true;
        // simple animations are a subset of advanced animations (which will cause a
        // prelayout step)
        boolean animationTypeSupported = (mItemsAddedOrRemoved && !mItemsChanged) ||
                (mItemsAddedOrRemoved || (mItemsChanged && supportsChangeAnimations()));
        mState.mRunSimpleAnimations = mFirstLayoutComplete && mItemAnimator != null &&
                (mDataSetHasChangedAfterLayout || animationTypeSupported ||
                        mLayout.mRequestedSimpleAnimations) &&
                (!mDataSetHasChangedAfterLayout || mAdapter.hasStableIds());
        mState.mRunPredictiveAnimations = mState.mRunSimpleAnimations &&
                animationTypeSupported && !mDataSetHasChangedAfterLayout &&
                predictiveItemAnimationsEnabled();
        ArrayMap<Long, ViewHolder> oldChangedHolders =
                mState.mRunSimpleAnimations && mItemsChanged && supportsChangeAnimations() ?
                        new ArrayMap<Long, ViewHolder>() : null;
        mItemsAddedOrRemoved = mItemsChanged = false;
        ArrayMap<View, Rect> appearingViewInitialBounds = null;
        mState.mInPreLayout = mState.mRunPredictiveAnimations;
        mState.mItemCount = mAdapter.getItemCount();

        if (mDataSetHasChangedAfterLayout) {
            // Processing these items have no value since data set changed unexpectedly.
            // Instead, we just reset it.
            mAdapterHelper.reset();
            markKnownViewsInvalid();
            mLayout.onItemsChanged(this);
        }

        if (mState.mRunSimpleAnimations) {
            // Step 0: Find out where all non-removed items are, pre-layout
            mState.mPreLayoutHolderMap.clear();
            mState.mPostLayoutHolderMap.clear();
            int count = mChildHelper.getChildCount();
            for (int i = 0; i < count; ++i) {
                final ViewHolder holder = getChildViewHolderInt(mChildHelper.getChildAt(i));
                if (holder.shouldIgnore() || (holder.isInvalid() && !mAdapter.hasStableIds())) {
                    continue;
                }
                final View view = holder.itemView;
                mState.mPreLayoutHolderMap.put(holder, new ItemHolderInfo(holder,
                        view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
            }
        }
        if (mState.mRunPredictiveAnimations) {
            // Step 1: run prelayout: This will use the old positions of items. The layout manager
            // is expected to layout everything, even removed items (though not to add removed
            // items back to the container). This gives the pre-layout position of APPEARING views
            // which come into existence as part of the real layout.

            // Save old positions so that LayoutManager can run its mapping logic.
            saveOldPositions();
            // Make sure any pending data updates are flushed before laying out.
            mAdapterHelper.preProcess();
            if (oldChangedHolders != null) {
                int count = mChildHelper.getChildCount();
                for (int i = 0; i < count; ++i) {
                    final ViewHolder holder = getChildViewHolderInt(mChildHelper.getChildAt(i));
                    if (holder.isChanged() && !holder.isRemoved() && !holder.shouldIgnore()) {
                        long key = mAdapter.hasStableIds() ? holder.getItemId() :
                                (long) holder.mPosition;
                        oldChangedHolders.put(key, holder);
                        mState.mPreLayoutHolderMap.remove(holder);
                    }
                }
            }

                mInPreLayout = true;
            final boolean didStructureChange = mState.mStructureChanged;
            mState.mStructureChanged = false;
            // temporarily disable flag because we are asking for previous layout
            mLayout.onLayoutChildren(mRecycler, mState);
            mState.mStructureChanged = didStructureChange;
            mInPreLayout = false;

            appearingViewInitialBounds = new ArrayMap<View, Rect>();
            for (int i = 0; i < mChildHelper.getChildCount(); ++i) {
                boolean found = false;
                View child = mChildHelper.getChildAt(i);
                if (getChildViewHolderInt(child).shouldIgnore()) {
                    continue;
                }
                for (int j = 0; j < mState.mPreLayoutHolderMap.size(); ++j) {
                    ViewHolder holder = mState.mPreLayoutHolderMap.keyAt(j);
                    if (holder.itemView == child) {
                        found = true;
                        continue;
                    }
                }
                if (!found) {
                    appearingViewInitialBounds.put(child, new Rect(child.getLeft(), child.getTop(),
                            child.getRight(), child.getBottom()));
                }
            }
            // we don't process disappearing list because they may re-appear in post layout pass.
            clearOldPositions();
            mAdapterHelper.consumePostponedUpdates();
        } else {
            clearOldPositions();
            mAdapterHelper.consumeUpdatesInOnePass();
            if (oldChangedHolders != null) {
                int count = mChildHelper.getChildCount();
                for (int i = 0; i < count; ++i) {
                    final ViewHolder holder = getChildViewHolderInt(mChildHelper.getChildAt(i));
                    if (holder.isChanged() && !holder.isRemoved() && !holder.shouldIgnore()) {
                        long key = mAdapter.hasStableIds() ? holder.getItemId() :
                                (long) holder.mPosition;
                        oldChangedHolders.put(key, holder);
                        mState.mPreLayoutHolderMap.remove(holder);
                    }
                }
            }
        }
        mState.mItemCount = mAdapter.getItemCount();
        mState.mDeletedInvisibleItemCountSincePreviousLayout = 0;

        // Step 2: Run layout
        mState.mInPreLayout = false;
        mLayout.onLayoutChildren(mRecycler, mState);

        mState.mStructureChanged = false;
        mPendingSavedState = null;

        // onLayoutChildren may have caused client code to disable item animations; re-check
        mState.mRunSimpleAnimations = mState.mRunSimpleAnimations && mItemAnimator != null;

        if (mState.mRunSimpleAnimations) {
            // Step 3: Find out where things are now, post-layout
            ArrayMap<Long, ViewHolder> newChangedHolders = oldChangedHolders != null ?
                    new ArrayMap<Long, ViewHolder>() : null;
            int count = mChildHelper.getChildCount();
            for (int i = 0; i < count; ++i) {
                ViewHolder holder = getChildViewHolderInt(mChildHelper.getChildAt(i));
                if (holder.shouldIgnore()) {
                    continue;
                }
                final View view = holder.itemView;
                long key = mAdapter.hasStableIds() ? holder.getItemId() :
                        (long) holder.mPosition;
                if (newChangedHolders != null && oldChangedHolders.get(key) != null) {
                    newChangedHolders.put(key, holder);
                } else {
                    mState.mPostLayoutHolderMap.put(holder, new ItemHolderInfo(holder,
                            view.getLeft(), view.getTop(), view.getRight(), view.getBottom()));
                }
            }
            processDisappearingList();
            // Step 4: Animate DISAPPEARING and REMOVED items
            int preLayoutCount = mState.mPreLayoutHolderMap.size();
            for (int i = preLayoutCount - 1; i >= 0; i--) {
                ViewHolder itemHolder = mState.mPreLayoutHolderMap.keyAt(i);
                if (!mState.mPostLayoutHolderMap.containsKey(itemHolder)) {
                    ItemHolderInfo disappearingItem = mState.mPreLayoutHolderMap.valueAt(i);
                    mState.mPreLayoutHolderMap.removeAt(i);

                    View disappearingItemView = disappearingItem.holder.itemView;
                    removeDetachedView(disappearingItemView, false);
                    mRecycler.unscrapView(disappearingItem.holder);

                    animateDisappearance(disappearingItem);
                }
            }
            // Step 5: Animate APPEARING and ADDED items
            int postLayoutCount = mState.mPostLayoutHolderMap.size();
            if (postLayoutCount > 0) {
                for (int i = postLayoutCount - 1; i >= 0; i--) {
                    ViewHolder itemHolder = mState.mPostLayoutHolderMap.keyAt(i);
                    ItemHolderInfo info = mState.mPostLayoutHolderMap.valueAt(i);
                    if ((mState.mPreLayoutHolderMap.isEmpty() ||
                            !mState.mPreLayoutHolderMap.containsKey(itemHolder))) {
                        mState.mPostLayoutHolderMap.removeAt(i);
                        Rect initialBounds = (appearingViewInitialBounds != null) ?
                                appearingViewInitialBounds.get(itemHolder.itemView) : null;
                        animateAppearance(itemHolder, initialBounds,
                                info.left, info.top);
                    }
                }
            }
            // Step 6: Animate PERSISTENT items
            count = mState.mPostLayoutHolderMap.size();
            for (int i = 0; i < count; ++i) {
                ViewHolder postHolder = mState.mPostLayoutHolderMap.keyAt(i);
                ItemHolderInfo postInfo = mState.mPostLayoutHolderMap.valueAt(i);
                ItemHolderInfo preInfo = mState.mPreLayoutHolderMap.get(postHolder);
                if (preInfo != null && postInfo != null) {
                    if (preInfo.left != postInfo.left || preInfo.top != postInfo.top) {
                        postHolder.setIsRecyclable(false);
                        if (DEBUG) {
                            Log.d(TAG, "PERSISTENT: " + postHolder +
                                    " with view " + postHolder.itemView);
                        }
                        if (mItemAnimator.animateMove(postHolder,
                                preInfo.left, preInfo.top, postInfo.left, postInfo.top)) {
                            postAnimationRunner();
                        }
                    }
                }
            }
            // Step 7: Animate CHANGING items
            count = oldChangedHolders != null ? oldChangedHolders.size() : 0;
            for (int i = 0; i < count; ++i) {
                long key = oldChangedHolders.keyAt(i);
                ViewHolder oldHolder = oldChangedHolders.get(key);
                View oldView = oldHolder.itemView;
                if (!oldHolder.shouldIgnore() &&
                        mRecycler.mChangedScrap.contains(oldHolder)) {
                    oldHolder.setIsRecyclable(false);
                    removeDetachedView(oldView, false);
                    addAnimatingView(oldView);
                    mRecycler.unscrapView(oldHolder);
                    if (DEBUG) {
                        Log.d(TAG, "CHANGED: " + oldHolder + " with view " + oldView);
                    }
                    ViewHolder newHolder = newChangedHolders.get(key);
                    oldHolder.mShadowedHolder = newHolder;
                    if (newHolder != null && !newHolder.shouldIgnore()) {
                        newHolder.setIsRecyclable(false);
                        newHolder.mShadowingHolder = oldHolder;
                    }
                    if (mItemAnimator.animateChange(oldHolder, newHolder)) {
                        postAnimationRunner();
                    }
                }
            }
        }
        resumeRequestLayout(false);
        mLayout.removeAndRecycleScrapInt(mRecycler, !mState.mRunPredictiveAnimations);
        mState.mPreviousLayoutItemCount = mState.mItemCount;
        mDataSetHasChangedAfterLayout = false;
        mState.mRunSimpleAnimations = false;
        mState.mRunPredictiveAnimations = false;
        mRunningLayoutOrScroll = false;
        mLayout.mRequestedSimpleAnimations = false;
        if (mRecycler.mChangedScrap != null) {
            mRecycler.mChangedScrap.clear();
        }
    }

    /**
     * A LayoutManager may want to layout a view just to animate disappearance.
     * This method handles those views and triggers remove animation on them.
     */
    private void processDisappearingList() {
        final int count = mDisappearingViewsInLayoutPass.size();
        for (int i = 0; i < count; i ++) {
            View view = mDisappearingViewsInLayoutPass.get(i);
            ViewHolder vh = getChildViewHolderInt(view);
            final ItemHolderInfo info = mState.mPreLayoutHolderMap.remove(vh);
            if (!mState.isPreLayout()) {
                mState.mPostLayoutHolderMap.remove(vh);
            }
            if (info != null) {
                animateDisappearance(info);
            } else {
                // let it disappear from the position it becomes visible
                animateDisappearance(new ItemHolderInfo(vh, view.getLeft(), view.getTop(),
                        view.getRight(), view.getBottom()));
            }
        }
        mDisappearingViewsInLayoutPass.clear();
    }

    private void animateAppearance(ViewHolder itemHolder, Rect beforeBounds, int afterLeft,
            int afterTop) {
        View newItemView = itemHolder.itemView;
        if (beforeBounds != null &&
                (beforeBounds.left != afterLeft || beforeBounds.top != afterTop)) {
            // slide items in if before/after locations differ
            itemHolder.setIsRecyclable(false);
            if (DEBUG) {
                Log.d(TAG, "APPEARING: " + itemHolder + " with view " + newItemView);
            }
            if (mItemAnimator.animateMove(itemHolder,
                    beforeBounds.left, beforeBounds.top,
                    afterLeft, afterTop)) {
                postAnimationRunner();
            }
        } else {
            if (DEBUG) {
                Log.d(TAG, "ADDED: " + itemHolder + " with view " + newItemView);
            }
            itemHolder.setIsRecyclable(false);
            if (mItemAnimator.animateAdd(itemHolder)) {
                postAnimationRunner();
            }
        }
    }

    private void animateDisappearance(ItemHolderInfo disappearingItem) {
        View disappearingItemView = disappearingItem.holder.itemView;
        addAnimatingView(disappearingItemView);
        int oldLeft = disappearingItem.left;
        int oldTop = disappearingItem.top;
        int newLeft = disappearingItemView.getLeft();
        int newTop = disappearingItemView.getTop();
        if (oldLeft != newLeft || oldTop != newTop) {
            disappearingItem.holder.setIsRecyclable(false);
            disappearingItemView.layout(newLeft, newTop,
                    newLeft + disappearingItemView.getWidth(),
                    newTop + disappearingItemView.getHeight());
            if (DEBUG) {
                Log.d(TAG, "DISAPPEARING: " + disappearingItem.holder +
                        " with view " + disappearingItemView);
            }
            if (mItemAnimator.animateMove(disappearingItem.holder, oldLeft, oldTop,
                    newLeft, newTop)) {
                postAnimationRunner();
            }
        } else {
            if (DEBUG) {
                Log.d(TAG, "REMOVED: " + disappearingItem.holder +
                        " with view " + disappearingItemView);
            }
            disappearingItem.holder.setIsRecyclable(false);
            if (mItemAnimator.animateRemove(disappearingItem.holder)) {
                postAnimationRunner();
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        eatRequestLayout();
        dispatchLayout();
        resumeRequestLayout(false);
        mFirstLayoutComplete = true;
    }

    @Override
    public void requestLayout() {
        if (!mEatRequestLayout) {
            super.requestLayout();
        } else {
            mLayoutRequestEaten = true;
        }
    }

    void markItemDecorInsetsDirty() {
        final int childCount = mChildHelper.getUnfilteredChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = mChildHelper.getUnfilteredChildAt(i);
            ((LayoutParams) child.getLayoutParams()).mInsetsDirty = true;
        }
    }

    @Override
    public void draw(Canvas c) {
        super.draw(c);

        final int count = mItemDecorations.size();
        for (int i = 0; i < count; i++) {
            mItemDecorations.get(i).onDrawOver(c, this, mState);
        }
        // TODO If padding is not 0 and chilChildrenToPadding is false, to draw glows properly, we
        // need find children closest to edges. Not sure if it is worth the effort.
        boolean needsInvalidate = false;
        if (mLeftGlow != null && !mLeftGlow.isFinished()) {
            final int restore = c.save();
            final int padding = mClipToPadding ? getPaddingBottom() : 0;
            c.rotate(270);
            c.translate(-getHeight() + padding, 0);
            needsInvalidate = mLeftGlow != null && mLeftGlow.draw(c);
            c.restoreToCount(restore);
        }
        if (mTopGlow != null && !mTopGlow.isFinished()) {
            final int restore = c.save();
            if (mClipToPadding) {
                c.translate(getPaddingLeft(), getPaddingTop());
            }
            needsInvalidate |= mTopGlow != null && mTopGlow.draw(c);
            c.restoreToCount(restore);
        }
        if (mRightGlow != null && !mRightGlow.isFinished()) {
            final int restore = c.save();
            final int width = getWidth();
            final int padding = mClipToPadding ? getPaddingTop() : 0;
            c.rotate(90);
            c.translate(-padding, -width);
            needsInvalidate |= mRightGlow != null && mRightGlow.draw(c);
            c.restoreToCount(restore);
        }
        if (mBottomGlow != null && !mBottomGlow.isFinished()) {
            final int restore = c.save();
            c.rotate(180);
            if (mClipToPadding) {
                c.translate(-getWidth() + getPaddingRight(), -getHeight() + getPaddingBottom());
            } else {
                c.translate(-getWidth(), -getHeight());
            }
            needsInvalidate |= mBottomGlow != null && mBottomGlow.draw(c);
            c.restoreToCount(restore);
        }

        if (needsInvalidate) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    @Override
    public void onDraw(Canvas c) {
        super.onDraw(c);

        final int count = mItemDecorations.size();
        for (int i = 0; i < count; i++) {
            mItemDecorations.get(i).onDraw(c, this, mState);
        }
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams && mLayout.checkLayoutParams((LayoutParams) p);
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        if (mLayout == null) {
            throw new IllegalStateException("RecyclerView has no LayoutManager");
        }
        return mLayout.generateDefaultLayoutParams();
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        if (mLayout == null) {
            throw new IllegalStateException("RecyclerView has no LayoutManager");
        }
        return mLayout.generateLayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        if (mLayout == null) {
            throw new IllegalStateException("RecyclerView has no LayoutManager");
        }
        return mLayout.generateLayoutParams(p);
    }

    void saveOldPositions() {
        final int childCount = mChildHelper.getUnfilteredChildCount();
        for (int i = 0; i < childCount; i++) {
            final ViewHolder holder = getChildViewHolderInt(mChildHelper.getUnfilteredChildAt(i));
            if (DEBUG && holder.mPosition == -1 && !holder.isRemoved()) {
                throw new IllegalStateException("view holder cannot have position -1 unless it"
                        + " is removed");
            }
            if (!holder.shouldIgnore()) {
                holder.saveOldPosition();
            }
        }
    }

    void clearOldPositions() {
        final int childCount = mChildHelper.getUnfilteredChildCount();
        for (int i = 0; i < childCount; i++) {
            final ViewHolder holder = getChildViewHolderInt(mChildHelper.getUnfilteredChildAt(i));
            if (!holder.shouldIgnore()) {
                holder.clearOldPosition();
            }
        }
        mRecycler.clearOldPositions();
    }

    void offsetPositionRecordsForMove(int from, int to) {
        final int childCount = mChildHelper.getUnfilteredChildCount();
        final int start, end, inBetweenOffset;
        if (from < to) {
            start = from;
            end = to;
            inBetweenOffset = -1;
        } else {
            start = to;
            end = from;
            inBetweenOffset = 1;
        }

        for (int i = 0; i < childCount; i++) {
            final ViewHolder holder = getChildViewHolderInt(mChildHelper.getUnfilteredChildAt(i));
            if (holder == null || holder.mPosition < start || holder.mPosition > end) {
                continue;
            }
            if (DEBUG) {
                Log.d(TAG, "offsetPositionRecordsForMove attached child " + i + " holder " +
                        holder);
            }
            if (holder.mPosition == from) {
                holder.offsetPosition(to - from, false);
            } else {
                holder.offsetPosition(inBetweenOffset, false);
            }

            mState.mStructureChanged = true;
        }
        mRecycler.offsetPositionRecordsForMove(from, to);
        requestLayout();
    }

    void offsetPositionRecordsForInsert(int positionStart, int itemCount) {
        final int childCount = mChildHelper.getUnfilteredChildCount();
        for (int i = 0; i < childCount; i++) {
            final ViewHolder holder = getChildViewHolderInt(mChildHelper.getUnfilteredChildAt(i));
            if (holder != null && !holder.shouldIgnore() && holder.mPosition >= positionStart) {
                if (DEBUG) {
                    Log.d(TAG, "offsetPositionRecordsForInsert attached child " + i + " holder " +
                            holder + " now at position " + (holder.mPosition + itemCount));
                }
                holder.offsetPosition(itemCount, false);
                mState.mStructureChanged = true;
            }
        }
        mRecycler.offsetPositionRecordsForInsert(positionStart, itemCount);
        requestLayout();
    }

    void offsetPositionRecordsForRemove(int positionStart, int itemCount,
            boolean applyToPreLayout) {
        final int positionEnd = positionStart + itemCount;
        final int childCount = mChildHelper.getUnfilteredChildCount();
        for (int i = 0; i < childCount; i++) {
            final ViewHolder holder = getChildViewHolderInt(mChildHelper.getUnfilteredChildAt(i));
            if (holder != null && !holder.shouldIgnore()) {
                if (holder.mPosition >= positionEnd) {
                    if (DEBUG) {
                        Log.d(TAG, "offsetPositionRecordsForRemove attached child " + i +
                                " holder " + holder + " now at position " +
                                (holder.mPosition - itemCount));
                    }
                    holder.offsetPosition(-itemCount, applyToPreLayout);
                    mState.mStructureChanged = true;
                } else if (holder.mPosition >= positionStart) {
                    if (DEBUG) {
                        Log.d(TAG, "offsetPositionRecordsForRemove attached child " + i +
                                " holder " + holder + " now REMOVED");
                    }
                    holder.addFlags(ViewHolder.FLAG_REMOVED);
                    mState.mStructureChanged = true;
                    holder.offsetPosition(-itemCount, applyToPreLayout);
                }
            }
        }
        mRecycler.offsetPositionRecordsForRemove(positionStart, itemCount, applyToPreLayout);
        requestLayout();
    }

    /**
     * Rebind existing views for the given range, or create as needed.
     *
     * @param positionStart Adapter position to start at
     * @param itemCount Number of views that must explicitly be rebound
     */
    void viewRangeUpdate(int positionStart, int itemCount) {
        final int childCount = mChildHelper.getUnfilteredChildCount();
        final int positionEnd = positionStart + itemCount;

        for (int i = 0; i < childCount; i++) {
            final View child = mChildHelper.getUnfilteredChildAt(i);
            final ViewHolder holder = getChildViewHolderInt(child);
            if (holder == null || holder.shouldIgnore()) {
                continue;
            }
            if (holder.mPosition >= positionStart && holder.mPosition < positionEnd) {
                // We re-bind these view holders after pre-processing is complete so that
                // ViewHolders have their final positions assigned.
                holder.addFlags(ViewHolder.FLAG_UPDATE);
                if (supportsChangeAnimations()) {
                    holder.addFlags(ViewHolder.FLAG_CHANGED);
                }
                // lp cannot be null since we get ViewHolder from it.
                ((LayoutParams) child.getLayoutParams()).mInsetsDirty = true;
            }
        }
        mRecycler.viewRangeUpdate(positionStart, itemCount);
    }

    void rebindUpdatedViewHolders() {
        final int childCount = mChildHelper.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final ViewHolder holder = getChildViewHolderInt(mChildHelper.getChildAt(i));
            // validate type is correct
            if (holder == null || holder.shouldIgnore()) {
                continue;
            }
            if (holder.isRemoved() || holder.isInvalid()) {
                requestLayout();
            } else if (holder.needsUpdate()) {
                final int type = mAdapter.getItemViewType(holder.mPosition);
                if (holder.getItemViewType() == type) {
                    // Binding an attached view will request a layout if needed.
                    if (!holder.isChanged() || !supportsChangeAnimations()) {
                        mAdapter.bindViewHolder(holder, holder.mPosition);
                    } else {
                        // Don't rebind changed holders if change animations are enabled.
                        // We want the old contents for the animation and will get a new
                        // holder for the new contents.
                        requestLayout();
                    }
                } else {
                    // binding to a new view will need re-layout anyways. We can as well trigger
                    // it here so that it happens during layout
                    holder.addFlags(ViewHolder.FLAG_INVALID);
                    requestLayout();
                }
            }
        }
    }

    /**
     * Mark all known views as invalid. Used in response to a, "the whole world might have changed"
     * data change event.
     */
    void markKnownViewsInvalid() {
        final int childCount = mChildHelper.getUnfilteredChildCount();
        for (int i = 0; i < childCount; i++) {
            final ViewHolder holder = getChildViewHolderInt(mChildHelper.getUnfilteredChildAt(i));
            if (holder != null && !holder.shouldIgnore()) {
                holder.addFlags(ViewHolder.FLAG_UPDATE | ViewHolder.FLAG_INVALID);
            }
        }
        markItemDecorInsetsDirty();
        mRecycler.markKnownViewsInvalid();
    }

    /**
     * Retrieve the {@link ViewHolder} for the given child view.
     *
     * @param child Child of this RecyclerView to query for its ViewHolder
     * @return The child view's ViewHolder
     */
    public ViewHolder getChildViewHolder(View child) {
        final ViewParent parent = child.getParent();
        if (parent != null && parent != this) {
            throw new IllegalArgumentException("View " + child + " is not a direct child of " +
                    this);
        }
        return getChildViewHolderInt(child);
    }

    static ViewHolder getChildViewHolderInt(View child) {
        if (child == null) {
            return null;
        }
        return ((LayoutParams) child.getLayoutParams()).mViewHolder;
    }

    /**
     * Return the adapter position that the given child view corresponds to.
     *
     * @param child Child View to query
     * @return Adapter position corresponding to the given view or {@link #NO_POSITION}
     */
    public int getChildPosition(View child) {
        final ViewHolder holder = getChildViewHolderInt(child);
        return holder != null ? holder.getPosition() : NO_POSITION;
    }

    /**
     * Return the stable item id that the given child view corresponds to.
     *
     * @param child Child View to query
     * @return Item id corresponding to the given view or {@link #NO_ID}
     */
    public long getChildItemId(View child) {
        if (mAdapter == null || !mAdapter.hasStableIds()) {
            return NO_ID;
        }
        final ViewHolder holder = getChildViewHolderInt(child);
        return holder != null ? holder.getItemId() : NO_ID;
    }

    /**
     * Return the ViewHolder for the item in the given position of the data set.
     *
     * @param position The position of the item in the data set of the adapter
     * @return The ViewHolder at <code>position</code>
     */
    public ViewHolder findViewHolderForPosition(int position) {
        return findViewHolderForPosition(position, false);
    }

    ViewHolder findViewHolderForPosition(int position, boolean checkNewPosition) {
        final int childCount = mChildHelper.getUnfilteredChildCount();
        for (int i = 0; i < childCount; i++) {
            final ViewHolder holder = getChildViewHolderInt(mChildHelper.getUnfilteredChildAt(i));
            if (holder != null && !holder.isRemoved()) {
                if (checkNewPosition) {
                    if (holder.mPosition == position) {
                        return holder;
                    }
                } else if (holder.getPosition() == position) {
                    return holder;
                }
            }
        }
        // This method should not query cached views. It creates a problem during adapter updates
        // when we are dealing with already laid out views. Also, for the public method, it is more
        // reasonable to return null if position is not laid out.
        return null;
    }

    /**
     * Return the ViewHolder for the item with the given id. The RecyclerView must
     * use an Adapter with {@link Adapter#setHasStableIds(boolean) stableIds} to
     * return a non-null value.
     *
     * @param id The id for the requested item
     * @return The ViewHolder with the given <code>id</code>, of null if there
     * is no such item.
     */
    public ViewHolder findViewHolderForItemId(long id) {
        final int childCount = mChildHelper.getUnfilteredChildCount();
        for (int i = 0; i < childCount; i++) {
            final ViewHolder holder = getChildViewHolderInt(mChildHelper.getUnfilteredChildAt(i));
            if (holder != null && holder.getItemId() == id) {
                return holder;
            }
        }
        // this method should not query cached views. They are not children so they
        // should not be returned in this public method
        return null;
    }

    /**
     * Find the topmost view under the given point.
     *
     * @param x Horizontal position in pixels to search
     * @param y Vertical position in pixels to search
     * @return The child view under (x, y) or null if no matching child is found
     */
    public View findChildViewUnder(float x, float y) {
        final int count = mChildHelper.getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            final View child = mChildHelper.getChildAt(i);
            final float translationX = ViewCompat.getTranslationX(child);
            final float translationY = ViewCompat.getTranslationY(child);
            if (x >= child.getLeft() + translationX &&
                    x <= child.getRight() + translationX &&
                    y >= child.getTop() + translationY &&
                    y <= child.getBottom() + translationY) {
                return child;
            }
        }
        return null;
    }

    /**
     * Offset the bounds of all child views by <code>dy</code> pixels.
     * Useful for implementing simple scrolling in {@link LayoutManager LayoutManagers}.
     *
     * @param dy Vertical pixel offset to apply to the bounds of all child views
     */
    public void offsetChildrenVertical(int dy) {
        final int childCount = mChildHelper.getChildCount();
        for (int i = 0; i < childCount; i++) {
            mChildHelper.getChildAt(i).offsetTopAndBottom(dy);
        }
    }

    /**
     * Called when an item view is attached to this RecyclerView.
     *
     * <p>Subclasses of RecyclerView may want to perform extra bookkeeping or modifications
     * of child views as they become attached. This will be called before a
     * {@link LayoutManager} measures or lays out the view and is a good time to perform these
     * changes.</p>
     *
     * @param child Child view that is now attached to this RecyclerView and its associated window
     */
    public void onChildAttachedToWindow(View child) {
    }

    /**
     * Called when an item view is detached from this RecyclerView.
     *
     * <p>Subclasses of RecyclerView may want to perform extra bookkeeping or modifications
     * of child views as they become detached. This will be called as a
     * {@link LayoutManager} fully detaches the child view from the parent and its window.</p>
     *
     * @param child Child view that is now detached from this RecyclerView and its associated window
     */
    public void onChildDetachedFromWindow(View child) {
    }

    /**
     * Offset the bounds of all child views by <code>dx</code> pixels.
     * Useful for implementing simple scrolling in {@link LayoutManager LayoutManagers}.
     *
     * @param dx Horizontal pixel offset to apply to the bounds of all child views
     */
    public void offsetChildrenHorizontal(int dx) {
        final int childCount = mChildHelper.getChildCount();
        for (int i = 0; i < childCount; i++) {
            mChildHelper.getChildAt(i).offsetLeftAndRight(dx);
        }
    }

    Rect getItemDecorInsetsForChild(View child) {
        final LayoutParams lp = (LayoutParams) child.getLayoutParams();
        if (!lp.mInsetsDirty) {
            return lp.mDecorInsets;
        }

        final Rect insets = lp.mDecorInsets;
        insets.set(0, 0, 0, 0);
        final int decorCount = mItemDecorations.size();
        for (int i = 0; i < decorCount; i++) {
            mTempRect.set(0, 0, 0, 0);
            mItemDecorations.get(i).getItemOffsets(mTempRect, child, this, mState);
            insets.left += mTempRect.left;
            insets.top += mTempRect.top;
            insets.right += mTempRect.right;
            insets.bottom += mTempRect.bottom;
        }
        lp.mInsetsDirty = false;
        return insets;
    }

    private class ViewFlinger implements Runnable {
        private int mLastFlingX;
        private int mLastFlingY;
        private ScrollerCompat mScroller;
        private Interpolator mInterpolator = sQuinticInterpolator;


        // When set to true, postOnAnimation callbacks are delayed until the run method completes
        private boolean mEatRunOnAnimationRequest = false;

        // Tracks if postAnimationCallback should be re-attached when it is done
        private boolean mReSchedulePostAnimationCallback = false;

        public ViewFlinger() {
            mScroller = ScrollerCompat.create(getContext(), sQuinticInterpolator);
        }

        @Override
        public void run() {
            disableRunOnAnimationRequests();
            consumePendingUpdateOperations();
            // keep a local reference so that if it is changed during onAnimation method, it won't
            // cause unexpected behaviors
            final ScrollerCompat scroller = mScroller;
            final SmoothScroller smoothScroller = mLayout.mSmoothScroller;
            if (scroller.computeScrollOffset()) {
                final int x = scroller.getCurrX();
                final int y = scroller.getCurrY();
                final int dx = x - mLastFlingX;
                final int dy = y - mLastFlingY;
                mLastFlingX = x;
                mLastFlingY = y;
                int overscrollX = 0, overscrollY = 0;
                if (mAdapter != null) {
                    eatRequestLayout();
                    mRunningLayoutOrScroll = true;
                    if (dx != 0) {
                        final int hresult = mLayout.scrollHorizontallyBy(dx, mRecycler, mState);
                        overscrollX = dx - hresult;
                    }
                    if (dy != 0) {
                        final int vresult = mLayout.scrollVerticallyBy(dy, mRecycler, mState);
                        overscrollY = dy - vresult;
                    }
                    if (supportsChangeAnimations()) {
                        // Fix up shadow views used by changing animations
                        int count = mChildHelper.getChildCount();
                        for (int i = 0; i < count; i++) {
                            View view = mChildHelper.getChildAt(i);
                            ViewHolder holder = getChildViewHolder(view);
                            if (holder != null && holder.mShadowingHolder != null) {
                                View shadowingView = holder.mShadowingHolder != null ?
                                        holder.mShadowingHolder.itemView : null;
                                if (shadowingView != null) {
                                    int left = view.getLeft();
                                    int top = view.getTop();
                                    if (left != shadowingView.getLeft() ||
                                            top != shadowingView.getTop()) {
                                        shadowingView.layout(left, top,
                                                left + shadowingView.getWidth(),
                                                top + shadowingView.getHeight());
                                    }
                                }
                            }
                        }
                    }

                    if (smoothScroller != null && !smoothScroller.isPendingInitialRun() &&
                            smoothScroller.isRunning()) {
                        smoothScroller.onAnimation(dx - overscrollX, dy - overscrollY);
                    }
                    mRunningLayoutOrScroll = false;
                    resumeRequestLayout(false);
                }
                if (!mItemDecorations.isEmpty()) {
                    invalidate();
                }
                if (ViewCompat.getOverScrollMode(RecyclerView.this) !=
                        ViewCompat.OVER_SCROLL_NEVER) {
                    considerReleasingGlowsOnScroll(dx, dy);
                }
                if (overscrollX != 0 || overscrollY != 0) {
                    final int vel = (int) scroller.getCurrVelocity();

                    int velX = 0;
                    if (overscrollX != x) {
                        velX = overscrollX < 0 ? -vel : overscrollX > 0 ? vel : 0;
                    }

                    int velY = 0;
                    if (overscrollY != y) {
                        velY = overscrollY < 0 ? -vel : overscrollY > 0 ? vel : 0;
                    }

                    if (ViewCompat.getOverScrollMode(RecyclerView.this) !=
                            ViewCompat.OVER_SCROLL_NEVER) {
                        absorbGlows(velX, velY);
                    }
                    if ((velX != 0 || overscrollX == x || scroller.getFinalX() == 0) &&
                            (velY != 0 || overscrollY == y || scroller.getFinalY() == 0)) {
                        scroller.abortAnimation();
                    }
                }

                if (mScrollListener != null && (x != 0 || y != 0)) {
                    mScrollListener.onScrolled(dx, dy);
                }

                if (!awakenScrollBars()) {
                    invalidate();
                }

                if (scroller.isFinished()) {
                    setScrollState(SCROLL_STATE_IDLE);
                } else {
                    postOnAnimation();
                }
            }
            // call this after the onAnimation is complete not to have inconsistent callbacks etc.
            if (smoothScroller != null && smoothScroller.isPendingInitialRun()) {
                smoothScroller.onAnimation(0, 0);
            }
            enableRunOnAnimationRequests();
        }

        private void disableRunOnAnimationRequests() {
            mReSchedulePostAnimationCallback = false;
            mEatRunOnAnimationRequest = true;
        }

        private void enableRunOnAnimationRequests() {
            mEatRunOnAnimationRequest = false;
            if (mReSchedulePostAnimationCallback) {
                postOnAnimation();
            }
        }

        void postOnAnimation() {
            if (mEatRunOnAnimationRequest) {
                mReSchedulePostAnimationCallback = true;
            } else {
                ViewCompat.postOnAnimation(RecyclerView.this, this);
            }
        }

        public void fling(int velocityX, int velocityY) {
            setScrollState(SCROLL_STATE_SETTLING);
            mLastFlingX = mLastFlingY = 0;
            mScroller.fling(0, 0, velocityX, velocityY,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
            postOnAnimation();
        }

        public void smoothScrollBy(int dx, int dy) {
            smoothScrollBy(dx, dy, 0, 0);
        }

        public void smoothScrollBy(int dx, int dy, int vx, int vy) {
            smoothScrollBy(dx, dy, computeScrollDuration(dx, dy, vx, vy));
        }

        private float distanceInfluenceForSnapDuration(float f) {
            f -= 0.5f; // center the values about 0.
            f *= 0.3f * Math.PI / 2.0f;
            return (float) Math.sin(f);
        }

        private int computeScrollDuration(int dx, int dy, int vx, int vy) {
            final int absDx = Math.abs(dx);
            final int absDy = Math.abs(dy);
            final boolean horizontal = absDx > absDy;
            final int velocity = (int) Math.sqrt(vx * vx + vy * vy);
            final int delta = (int) Math.sqrt(dx * dx + dy * dy);
            final int containerSize = horizontal ? getWidth() : getHeight();
            final int halfContainerSize = containerSize / 2;
            final float distanceRatio = Math.min(1.f, 1.f * delta / containerSize);
            final float distance = halfContainerSize + halfContainerSize *
                    distanceInfluenceForSnapDuration(distanceRatio);

            final int duration;
            if (velocity > 0) {
                duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
            } else {
                float absDelta = (float) (horizontal ? absDx : absDy);
                duration = (int) (((absDelta / containerSize) + 1) * 300);
            }
            return Math.min(duration, MAX_SCROLL_DURATION);
        }

        public void smoothScrollBy(int dx, int dy, int duration) {
            smoothScrollBy(dx, dy, duration, sQuinticInterpolator);
        }

        public void smoothScrollBy(int dx, int dy, int duration, Interpolator interpolator) {
            if (mInterpolator != interpolator) {
                mInterpolator = interpolator;
                mScroller = ScrollerCompat.create(getContext(), interpolator);
            }
            setScrollState(SCROLL_STATE_SETTLING);
            mLastFlingX = mLastFlingY = 0;
            mScroller.startScroll(0, 0, dx, dy, duration);
            postOnAnimation();
        }

        public void stop() {
            removeCallbacks(this);
            mScroller.abortAnimation();
        }

    }

    private class RecyclerViewDataObserver extends AdapterDataObserver {
        @Override
        public void onChanged() {
            assertNotInLayoutOrScroll(null);
            if (mAdapter.hasStableIds()) {
                // TODO Determine what actually changed.
                // This is more important to implement now since this callback will disable all
                // animations because we cannot rely on positions.
                mState.mStructureChanged = true;
                mDataSetHasChangedAfterLayout = true;
            } else {
                mState.mStructureChanged = true;
                mDataSetHasChangedAfterLayout = true;
            }
            if (!mAdapterHelper.hasPendingUpdates()) {
                requestLayout();
            }
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            assertNotInLayoutOrScroll(null);
            if (mAdapterHelper.onItemRangeChanged(positionStart, itemCount)) {
                triggerUpdateProcessor();
            }
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            assertNotInLayoutOrScroll(null);
            if (mAdapterHelper.onItemRangeInserted(positionStart, itemCount)) {
                triggerUpdateProcessor();
            }
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            assertNotInLayoutOrScroll(null);
            if (mAdapterHelper.onItemRangeRemoved(positionStart, itemCount)) {
                triggerUpdateProcessor();
            }
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            assertNotInLayoutOrScroll(null);
            if (mAdapterHelper.onItemRangeMoved(fromPosition, toPosition, itemCount)) {
                triggerUpdateProcessor();
            }
        }

        void triggerUpdateProcessor() {
            if (mPostUpdatesOnAnimation && mHasFixedSize && mIsAttached) {
                ViewCompat.postOnAnimation(RecyclerView.this, mUpdateChildViewsRunnable);
            } else {
                mAdapterUpdateDuringMeasure = true;
                requestLayout();
            }
        }
    }

    public static class RecycledViewPool {
        private SparseArray<ArrayList<ViewHolder>> mScrap =
                new SparseArray<ArrayList<ViewHolder>>();
        private SparseIntArray mMaxScrap = new SparseIntArray();
        private int mAttachCount = 0;

        private static final int DEFAULT_MAX_SCRAP = 5;

        public void clear() {
            mScrap.clear();
        }

        public void setMaxRecycledViews(int viewType, int max) {
            mMaxScrap.put(viewType, max);
            final ArrayList<ViewHolder> scrapHeap = mScrap.get(viewType);
            if (scrapHeap != null) {
                while (scrapHeap.size() > max) {
                    scrapHeap.remove(scrapHeap.size() - 1);
                }
            }
        }

        public ViewHolder getRecycledView(int viewType) {
            final ArrayList<ViewHolder> scrapHeap = mScrap.get(viewType);
            if (scrapHeap != null && !scrapHeap.isEmpty()) {
                final int index = scrapHeap.size() - 1;
                final ViewHolder scrap = scrapHeap.get(index);
                scrapHeap.remove(index);
                return scrap;
            }
            return null;
        }

        int size() {
            int count = 0;
            for (int i = 0; i < mScrap.size(); i ++) {
                ArrayList<ViewHolder> viewHolders = mScrap.valueAt(i);
                if (viewHolders != null) {
                    count += viewHolders.size();
                }
            }
            return count;
        }

        public void putRecycledView(ViewHolder scrap) {
            final int viewType = scrap.getItemViewType();
            final ArrayList scrapHeap = getScrapHeapForType(viewType);
            if (mMaxScrap.get(viewType) <= scrapHeap.size()) {
                return;
            }
            scrap.resetInternal();
            scrapHeap.add(scrap);
        }

        void attach(Adapter adapter) {
            mAttachCount++;
        }

        void detach() {
            mAttachCount--;
        }


        void onAdapterChanged(Adapter oldAdapter, Adapter newAdapter) {
            if (mAttachCount == 1) {
                clear();
            }
        }

        private ArrayList<ViewHolder> getScrapHeapForType(int viewType) {
            ArrayList<ViewHolder> scrap = mScrap.get(viewType);
            if (scrap == null) {
                scrap = new ArrayList<ViewHolder>();
                mScrap.put(viewType, scrap);
                if (mMaxScrap.indexOfKey(viewType) < 0) {
                    mMaxScrap.put(viewType, DEFAULT_MAX_SCRAP);
                }
            }
            return scrap;
        }
    }

    /**
     * A Recycler is responsible for managing scrapped or detached item views for reuse.
     *
     * <p>A "scrapped" view is a view that is still attached to its parent RecyclerView but
     * that has been marked for removal or reuse.</p>
     *
     * <p>Typical use of a Recycler by a {@link LayoutManager} will be to obtain views for
     * an adapter's data set representing the data at a given position or item ID.
     * If the view to be reused is considered "dirty" the adapter will be asked to rebind it.
     * If not, the view can be quickly reused by the LayoutManager with no further work.
     * Clean views that have not {@link android.view.View#isLayoutRequested() requested layout}
     * may be repositioned by a LayoutManager without remeasurement.</p>
     */
    public final class Recycler {
        private final ArrayList<ViewHolder> mAttachedScrap = new ArrayList<ViewHolder>();
        private ArrayList<ViewHolder> mChangedScrap = null;

        final ArrayList<ViewHolder> mCachedViews = new ArrayList<ViewHolder>();

        private final List<ViewHolder>
                mUnmodifiableAttachedScrap = Collections.unmodifiableList(mAttachedScrap);

        private int mViewCacheMax = DEFAULT_CACHE_SIZE;

        private RecycledViewPool mRecyclerPool;

        private ViewCacheExtension mViewCacheExtension;

        private static final int DEFAULT_CACHE_SIZE = 2;

        /**
         * Clear scrap views out of this recycler. Detached views contained within a
         * recycled view pool will remain.
         */
        public void clear() {
            mAttachedScrap.clear();
            recycleAndClearCachedViews();
        }

        /**
         * Set the maximum number of detached, valid views we should retain for later use.
         *
         * @param viewCount Number of views to keep before sending views to the shared pool
         */
        public void setViewCacheSize(int viewCount) {
            mViewCacheMax = viewCount;
            // first, try the views that can be recycled
            for (int i = mCachedViews.size() - 1; i >= 0 && mCachedViews.size() > viewCount; i--) {
                tryToRecycleCachedViewAt(i);
            }
            // if we could not recycle enough of them, remove some.
            while (mCachedViews.size() > viewCount) {
                mCachedViews.remove(mCachedViews.size() - 1);
            }
        }

        /**
         * Returns an unmodifiable list of ViewHolders that are currently in the scrap list.
         *
         * @return List of ViewHolders in the scrap list.
         */
        public List<ViewHolder> getScrapList() {
            return mUnmodifiableAttachedScrap;
        }

        /**
         * Helper method for getViewForPosition.
         * <p>
         * Checks whether a given view holder can be used for the provided position.
         *
         * @param holder ViewHolder
         * @return true if ViewHolder matches the provided position, false otherwise
         */
        boolean validateViewHolderForOffsetPosition(ViewHolder holder) {
            // if it is a removed holder, nothing to verify since we cannot ask adapter anymore
            // if it is not removed, verify the type and id.
            if (holder.isRemoved()) {
                return true;
            }
            if (holder.mPosition < 0 || holder.mPosition >= mAdapter.getItemCount()) {
                throw new IndexOutOfBoundsException("Inconsistency detected. Invalid view holder "
                        + "adapter position" + holder);
            }
            final int type = mAdapter.getItemViewType(holder.mPosition);
            if (type != holder.getItemViewType()) {
                return false;
            }
            if (mAdapter.hasStableIds()) {
                return holder.getItemId() == mAdapter.getItemId(holder.mPosition);
            }
            return true;
        }

        /**
         * Binds the given View to the position. The View can be a View previously retrieved via
         * {@link #getViewForPosition(int)} or created by
         * {@link Adapter#onCreateViewHolder(ViewGroup, int)}.
         * <p>
         * Generally, a LayoutManager should acquire its views via {@link #getViewForPosition(int)}
         * and let the RecyclerView handle caching. This is a helper method for LayoutManager who
         * wants to handle its own recycling logic.
         * <p>
         * Note that, {@link #getViewForPosition(int)} already binds the View to the position so
         * you don't need to call this method unless you want to bind this View to another position.
         *
         * @param view The view to update.
         * @param position The position of the item to bind to this View.
         */
        public void bindViewToPosition(View view, int position) {
            ViewHolder holder = getChildViewHolderInt(view);
            if (holder == null) {
                throw new IllegalArgumentException("The view does not have a ViewHolder. You cannot"
                        + " pass arbitrary views to this method, they should be created by the "
                        + "Adapter");
            }
            final int offsetPosition = mAdapterHelper.findPositionOffset(position);
            if (offsetPosition < 0 || offsetPosition >= mAdapter.getItemCount()) {
                throw new IndexOutOfBoundsException("Inconsistency detected. Invalid item "
                        + "position " + position + "(offset:" + offsetPosition + ")."
                        + "state:" + mState.getItemCount());
            }
            mAdapter.bindViewHolder(holder, offsetPosition);
            if (mState.isPreLayout()) {
                holder.mPreLayoutPosition = position;
            }

            ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            if (lp == null) {
                lp = generateDefaultLayoutParams();
                holder.itemView.setLayoutParams(lp);
            } else if (!checkLayoutParams(lp)) {
                lp = generateLayoutParams(lp);
                holder.itemView.setLayoutParams(lp);
            }
            ((LayoutParams) lp).mInsetsDirty = true;
            ((LayoutParams) lp).mViewHolder = holder;
        }

        /**
         * Obtain a view initialized for the given position.
         *
         * This method should be used by {@link LayoutManager} implementations to obtain
         * views to represent data from an {@link Adapter}.
         * <p>
         * The Recycler may reuse a scrap or detached view from a shared pool if one is
         * available for the correct view type. If the adapter has not indicated that the
         * data at the given position has changed, the Recycler will attempt to hand back
         * a scrap view that was previously initialized for that data without rebinding.
         *
         * @param position Position to obtain a view for
         * @return A view representing the data at <code>position</code> from <code>adapter</code>
         */
        public View getViewForPosition(int position) {
            return getViewForPosition(position, false);
        }

        View getViewForPosition(int position, boolean dryRun) {
            if (position < 0 || position >= mState.getItemCount()) {
                throw new IndexOutOfBoundsException("Invalid item position " + position
                        + "(" + position + "). Item count:" + mState.getItemCount());
            }
            ViewHolder holder;
            // 1) Find from scrap by position
            holder = getScrapViewForPosition(position, INVALID_TYPE, dryRun);
            if (holder != null) {
                if (!validateViewHolderForOffsetPosition(holder)) {
                    // recycle this scrap
                    if (!dryRun) {
                        // we would like to recycle this but need to make sure it is not used by
                        // animation logic etc.
                        holder.addFlags(ViewHolder.FLAG_INVALID);
                        if (holder.isScrap()) {
                            removeDetachedView(holder.itemView, false);
                            holder.unScrap();
                        } else if (holder.wasReturnedFromScrap()) {
                            holder.clearReturnedFromScrapFlag();
                        }
                        recycleViewHolder(holder);
                    }
                    holder = null;
                }
            }
            if (holder == null) {
                final int offsetPosition = mAdapterHelper.findPositionOffset(position);
                if (offsetPosition < 0 || offsetPosition >= mAdapter.getItemCount()) {
                    throw new IndexOutOfBoundsException("Inconsistency detected. Invalid item "
                            + "position " + position + "(offset:" + offsetPosition + ")."
                            + "state:" + mState.getItemCount());
                }

                final int type = mAdapter.getItemViewType(offsetPosition);
                // 2) Find from scrap via stable ids, if exists
                if (mAdapter.hasStableIds()) {
                    holder = getScrapViewForId(mAdapter.getItemId(offsetPosition), type, dryRun);
                    if (holder != null) {
                        // update position
                        holder.mPosition = offsetPosition;
                    }
                }
                if (holder == null && mViewCacheExtension != null) {
                    // We are NOT sending the offsetPosition because LayoutManager does not
                    // know it.
                    final View view = mViewCacheExtension
                            .getViewForPositionAndType(this, position, type);
                    if (view != null) {
                        holder = getChildViewHolder(view);
                        if (holder == null) {
                            throw new IllegalArgumentException("getViewForPositionAndType returned"
                                    + " a view which does not have a ViewHolder");
                        } else if (holder.shouldIgnore()) {
                            throw new IllegalArgumentException("getViewForPositionAndType returned"
                                    + " a view that is ignored. You must call stopIgnoring before"
                                    + " returning this view.");
                        }
                    }
                }
                if (holder == null) { // fallback to recycler
                    // try recycler.
                    // Head to the shared pool.
                    if (DEBUG) {
                        Log.d(TAG, "getViewForPosition(" + position + ") fetching from shared "
                                + "pool");
                    }
                    holder = getRecycledViewPool()
                            .getRecycledView(mAdapter.getItemViewType(offsetPosition));
                    if (holder != null) {
                        holder.resetInternal();
                    }
                }
                if (holder == null) {
                    holder = mAdapter.createViewHolder(RecyclerView.this,
                            mAdapter.getItemViewType(offsetPosition));
                    if (DEBUG) {
                        Log.d(TAG, "getViewForPosition created new ViewHolder");
                    }
                }
            }

            if (!holder.isRemoved() && (!holder.isBound() || holder.needsUpdate() ||
                    holder.isInvalid())) {
                final int offsetPosition = mAdapterHelper.findPositionOffset(position);
                mAdapter.bindViewHolder(holder, offsetPosition);
                if (mState.isPreLayout()) {
                    holder.mPreLayoutPosition = position;
                }
            }

            ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            if (lp == null) {
                lp = generateDefaultLayoutParams();
                holder.itemView.setLayoutParams(lp);
            } else if (!checkLayoutParams(lp)) {
                lp = generateLayoutParams(lp);
                holder.itemView.setLayoutParams(lp);
            }
            ((LayoutParams) lp).mViewHolder = holder;

            return holder.itemView;
        }

        /**
         * Recycle a detached view. The specified view will be added to a pool of views
         * for later rebinding and reuse.
         *
         * <p>A view must be fully detached before it may be recycled.</p>
         *
         * @param view Removed view for recycling
         */
        public void recycleView(View view) {
            recycleViewHolder(getChildViewHolderInt(view));
        }

        void recycleAndClearCachedViews() {
            final int count = mCachedViews.size();
            for (int i = count - 1; i >= 0; i--) {
                tryToRecycleCachedViewAt(i);
            }
            mCachedViews.clear();
        }

        /**
         * Tries to recyle a cached view and removes the view from the list if and only if it
         * is recycled.
         *
         * @param cachedViewIndex The index of the view in cached views list
         * @return True if item is recycled
         */
        boolean tryToRecycleCachedViewAt(int cachedViewIndex) {
            if (DEBUG) {
                Log.d(TAG, "Recycling cached view at index " + cachedViewIndex);
            }
            ViewHolder viewHolder = mCachedViews.get(cachedViewIndex);
            if (DEBUG) {
                Log.d(TAG, "CachedViewHolder to be recycled(if recycleable): " + viewHolder);
            }
            if (viewHolder.isRecyclable()) {
                getRecycledViewPool().putRecycledView(viewHolder);
                dispatchViewRecycled(viewHolder);
                mCachedViews.remove(cachedViewIndex);
                return true;
            }
            return false;
        }

        void recycleViewHolder(ViewHolder holder) {
            if (holder.isScrap() || holder.itemView.getParent() != null) {
                throw new IllegalArgumentException(
                        "Scrapped or attached views may not be recycled. isScrap:"
                                + holder.isScrap() + " isAttached:"
                                + (holder.itemView.getParent() != null));
            }

            if (holder.shouldIgnore()) {
                throw new IllegalArgumentException("Trying to recycle an ignored view holder");
            }
            if (holder.isRecyclable()) {
                boolean cached = false;
                if (!holder.isInvalid() && (mInPreLayout || !holder.isRemoved()) &&
                        !holder.isChanged()) {
                    // Retire oldest cached views first
                    if (mCachedViews.size() == mViewCacheMax && !mCachedViews.isEmpty()) {
                        for (int i = 0; i < mCachedViews.size(); i++) {
                            if (tryToRecycleCachedViewAt(i)) {
                                break;
                            }
                        }
                    }
                    if (mCachedViews.size() < mViewCacheMax) {
                        mCachedViews.add(holder);
                        cached = true;
                    }
                }
                if (!cached) {
                    getRecycledViewPool().putRecycledView(holder);
                    dispatchViewRecycled(holder);
                }
            } else if (DEBUG) {
                Log.d(TAG, "trying to recycle a non-recycleable holder. Hopefully, it will "
                        + "re-visit here. We are stil removing it from animation lists");
            }
            // Remove from pre/post maps that are used to animate items; a recycled holder
            // should not be animated
            mState.mPreLayoutHolderMap.remove(holder);
            mState.mPostLayoutHolderMap.remove(holder);
        }

        /**
         * Used as a fast path for unscrapping and recycling a view during a bulk operation.
         * The caller must call {@link #clearScrap()} when it's done to update the recycler's
         * internal bookkeeping.
         */
        void quickRecycleScrapView(View view) {
            final ViewHolder holder = getChildViewHolderInt(view);
            holder.mScrapContainer = null;
            holder.clearReturnedFromScrapFlag();
            recycleViewHolder(holder);
        }

        /**
         * Mark an attached view as scrap.
         *
         * <p>"Scrap" views are still attached to their parent RecyclerView but are eligible
         * for rebinding and reuse. Requests for a view for a given position may return a
         * reused or rebound scrap view instance.</p>
         *
         * @param view View to scrap
         */
        void scrapView(View view) {
            final ViewHolder holder = getChildViewHolderInt(view);
            if (holder.isInvalid() && !holder.isRemoved() && !mAdapter.hasStableIds()) {
                throw new IllegalArgumentException("Called scrap view with an invalid view."
                        + " Invalid views cannot be reused from scrap, they should rebound from"
                        + " recycler pool.");
            }
            holder.setScrapContainer(this);
            if (!holder.isChanged() || !supportsChangeAnimations()) {
                mAttachedScrap.add(holder);
            } else {
                if (mChangedScrap == null) {
                    mChangedScrap = new ArrayList<ViewHolder>();
                }
                mChangedScrap.add(holder);
            }
        }

        /**
         * Remove a previously scrapped view from the pool of eligible scrap.
         *
         * <p>This view will no longer be eligible for reuse until re-scrapped or
         * until it is explicitly removed and recycled.</p>
         */
        void unscrapView(ViewHolder holder) {
            if (!holder.isChanged() || !supportsChangeAnimations() || mChangedScrap == null) {
                mAttachedScrap.remove(holder);
            } else {
                mChangedScrap.remove(holder);
            }
            holder.mScrapContainer = null;
            holder.clearReturnedFromScrapFlag();
        }

        int getScrapCount() {
            return mAttachedScrap.size();
        }

        View getScrapViewAt(int index) {
            return mAttachedScrap.get(index).itemView;
        }

        void clearScrap() {
            mAttachedScrap.clear();
        }

        /**
         * Returns a scrap view for the position. If type is not INVALID_TYPE, it also checks if
         * ViewHolder's type matches the provided type.
         *
         * @param position Item position
         * @param type View type
         * @param dryRun  Does a dry run, finds the ViewHolder but does not remove
         * @return a ViewHolder that can be re-used for this position.
         */
        ViewHolder getScrapViewForPosition(int position, int type, boolean dryRun) {
            final int scrapCount = mAttachedScrap.size();

            // Try first for an exact, non-invalid match from scrap.
            for (int i = 0; i < scrapCount; i++) {
                final ViewHolder holder = mAttachedScrap.get(i);
                if (!holder.wasReturnedFromScrap() && holder.getPosition() == position
                        && !holder.isInvalid() && (mInPreLayout || !holder.isRemoved())) {
                    if (type != INVALID_TYPE && holder.getItemViewType() != type) {
                        Log.e(TAG, "Scrap view for position " + position + " isn't dirty but has" +
                                " wrong view type! (found " + holder.getItemViewType() +
                                " but expected " + type + ")");
                        break;
                    }
                    holder.addFlags(ViewHolder.FLAG_RETURNED_FROM_SCRAP);
                    return holder;
                }
            }

            if (!dryRun) {
                View view = mChildHelper.findHiddenNonRemovedView(position, type);
                if (view != null) {
                    // ending the animation should cause it to get recycled before we reuse it
                    mItemAnimator.endAnimation(getChildViewHolder(view));
                }
            }

            // Search in our first-level recycled view cache.
            final int cacheSize = mCachedViews.size();
            for (int i = 0; i < cacheSize; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                // invalid view holders may be in cache if adapter has stable ids as they can be
                // retrieved via getScrapViewForId
                if (!holder.isInvalid() && holder.getPosition() == position) {
                    if (!dryRun) {
                        mCachedViews.remove(i);
                    }
                    if (DEBUG) {
                        Log.d(TAG, "getScrapViewForPosition(" + position + ", " + type +
                                ") found match in cache: " + holder);
                    }
                    return holder;
                }
            }
            return null;
        }

        ViewHolder getScrapViewForId(long id, int type, boolean dryRun) {
            // Look in our attached views first
            final int count = mAttachedScrap.size();
            for (int i = count - 1; i >= 0; i--) {
                final ViewHolder holder = mAttachedScrap.get(i);
                if (holder.getItemId() == id && !holder.wasReturnedFromScrap()) {
                    if (type == holder.getItemViewType()) {
                        holder.addFlags(ViewHolder.FLAG_RETURNED_FROM_SCRAP);
                        if (holder.isRemoved()) {
                            // this might be valid in two cases:
                            // > item is removed but we are in pre-layout pass
                            // >> do nothing. return as is. make sure we don't rebind
                            // > item is removed then added to another position and we are in
                            // post layout.
                            // >> remove removed and invalid flags, add update flag to rebind
                            // because item was invisible to us and we don't know what happened in
                            // between.
                            if (!mState.isPreLayout()) {
                                holder.setFlags(ViewHolder.FLAG_UPDATE, ViewHolder.FLAG_UPDATE |
                                        ViewHolder.FLAG_INVALID | ViewHolder.FLAG_REMOVED);
                            }
                        }
                        return holder;
                    } else if (!dryRun) {
                        // Recycle this scrap. Type mismatch.
                        mAttachedScrap.remove(i);
                        removeDetachedView(holder.itemView, false);
                        quickRecycleScrapView(holder.itemView);
                    }
                }
            }

            // Search the first-level cache
            final int cacheSize = mCachedViews.size();
            for (int i = cacheSize - 1; i >= 0; i--) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder.getItemId() == id) {
                    if (type == holder.getItemViewType()) {
                        if (!dryRun) {
                            mCachedViews.remove(i);
                        }
                        return holder;
                    } else if (!dryRun) {
                        tryToRecycleCachedViewAt(i);
                    }
                }
            }
            return null;
        }

        void dispatchViewRecycled(ViewHolder holder) {
            if (mRecyclerListener != null) {
                mRecyclerListener.onViewRecycled(holder);
            }
            if (mAdapter != null) {
                mAdapter.onViewRecycled(holder);
            }
            if (mState != null) {
                mState.onViewRecycled(holder);
            }
            if (DEBUG) Log.d(TAG, "dispatchViewRecycled: " + holder);
        }

        void onAdapterChanged(Adapter oldAdapter, Adapter newAdapter) {
            clear();
            getRecycledViewPool().onAdapterChanged(oldAdapter, newAdapter);
        }

        void offsetPositionRecordsForMove(int from, int to) {
            final int start, end, inBetweenOffset;
            if (from < to) {
                start = from;
                end = to;
                inBetweenOffset = -1;
            } else {
                start = to;
                end = from;
                inBetweenOffset = 1;
            }
            final int cachedCount = mCachedViews.size();
            for (int i = 0; i < cachedCount; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder == null || holder.mPosition < start || holder.mPosition > end) {
                    continue;
                }
                if (holder.mPosition == from) {
                    holder.offsetPosition(to - from, false);
                } else {
                    holder.offsetPosition(inBetweenOffset, false);
                }
                if (DEBUG) {
                    Log.d(TAG, "offsetPositionRecordsForMove cached child " + i + " holder " +
                            holder);
                }
            }
        }

        void offsetPositionRecordsForInsert(int insertedAt, int count) {
            final int cachedCount = mCachedViews.size();
            for (int i = 0; i < cachedCount; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder != null && holder.getPosition() >= insertedAt) {
                    if (DEBUG) {
                        Log.d(TAG, "offsetPositionRecordsForInsert cached " + i + " holder " +
                                holder + " now at position " + (holder.mPosition + count));
                    }
                    holder.offsetPosition(count, true);
                }
            }
        }

        /**
         * @param removedFrom Remove start index
         * @param count Remove count
         * @param applyToPreLayout If true, changes will affect ViewHolder's pre-layout position, if
         *                         false, they'll be applied before the second layout pass
         */
        void offsetPositionRecordsForRemove(int removedFrom, int count, boolean applyToPreLayout) {
            final int removedEnd = removedFrom + count;
            final int cachedCount = mCachedViews.size();
            for (int i = cachedCount - 1; i >= 0; i--) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder != null) {
                    if (holder.getPosition() >= removedEnd) {
                        if (DEBUG) {
                            Log.d(TAG, "offsetPositionRecordsForRemove cached " + i +
                                    " holder " + holder + " now at position " +
                                    (holder.mPosition - count));
                        }
                        holder.offsetPosition(-count, applyToPreLayout);
                    } else if (holder.getPosition() >= removedFrom) {
                        // Item for this view was removed. Dump it from the cache.
                        if (!tryToRecycleCachedViewAt(i)) {
                            // if we cannot recycle it, at least invalidate so that we won't return
                            // it by position.
                            holder.addFlags(ViewHolder.FLAG_INVALID);
                            if (DEBUG) {
                                Log.d(TAG, "offsetPositionRecordsForRemove cached " + i +
                                        " holder " + holder + " now flagged as invalid because it "
                                        + "could not be recycled");
                            }
                        } else if (DEBUG) {
                            Log.d(TAG, "offsetPositionRecordsForRemove cached " + i +
                                    " holder " + holder + " now placed in pool");
                        }
                    }
                }
            }
        }

        void setViewCacheExtension(ViewCacheExtension extension) {
            mViewCacheExtension = extension;
        }

        void setRecycledViewPool(RecycledViewPool pool) {
            if (mRecyclerPool != null) {
                mRecyclerPool.detach();
            }
            mRecyclerPool = pool;
            if (pool != null) {
                mRecyclerPool.attach(getAdapter());
            }
        }

        RecycledViewPool getRecycledViewPool() {
            if (mRecyclerPool == null) {
                mRecyclerPool = new RecycledViewPool();
            }
            return mRecyclerPool;
        }

        void viewRangeUpdate(int positionStart, int itemCount) {
            final int positionEnd = positionStart + itemCount;
            final int cachedCount = mCachedViews.size();
            for (int i = 0; i < cachedCount; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                if (holder == null) {
                    continue;
                }

                final int pos = holder.getPosition();
                if (pos >= positionStart && pos < positionEnd) {
                    holder.addFlags(ViewHolder.FLAG_UPDATE);
                    if (supportsChangeAnimations()) {
                        holder.addFlags(ViewHolder.FLAG_CHANGED);
                    }
                }
            }
        }

        void markKnownViewsInvalid() {
            if (mAdapter != null && mAdapter.hasStableIds()) {
                final int cachedCount = mCachedViews.size();
                for (int i = 0; i < cachedCount; i++) {
                    final ViewHolder holder = mCachedViews.get(i);
                    if (holder != null) {
                        holder.addFlags(ViewHolder.FLAG_UPDATE | ViewHolder.FLAG_INVALID);
                    }
                }
            } else {
                // we cannot re-use cached views in this case. Recycle the ones we can and flag
                // the remaining as invalid so that they can be recycled later on (when their
                // animations end.)
                for (int i = mCachedViews.size() - 1; i >= 0; i--) {
                    if (!tryToRecycleCachedViewAt(i)) {
                        final ViewHolder holder = mCachedViews.get(i);
                        holder.addFlags(ViewHolder.FLAG_UPDATE | ViewHolder.FLAG_INVALID);
                    }
                }
            }

        }

        void clearOldPositions() {
            final int cachedCount = mCachedViews.size();
            for (int i = 0; i < cachedCount; i++) {
                final ViewHolder holder = mCachedViews.get(i);
                holder.clearOldPosition();
            }
        }
    }

    /**
     * ViewCacheExtension is a helper class to provide an additional layer of view caching that can
     * ben controlled by the developer.
     * <p>
     * When {@link Recycler#getViewForPosition(int)} is called, Recycler checks attached scrap and
     * first level cache to find a matching View. If it cannot find a suitable View, Recycler will
     * call the {@link #getViewForPositionAndType(Recycler, int, int)} before checking
     * {@link RecycledViewPool}.
     * <p>
     * Note that, Recycler never sends Views to this method to be cached. It is developers
     * responsibility to decide whether they want to keep their Views in this custom cache or let
     * the default recycling policy handle it.
     */
    public abstract static class ViewCacheExtension {

        /**
         * Returns a View that can be binded to the given Adapter position.
         * <p>
         * This method should <b>not</b> create a new View. Instead, it is expected to return
         * an already created View that can be re-used for the given type and position.
         * If the View is marked as ignored, it should first call
         * {@link LayoutManager#stopIgnoringView(View)} before returning the View.
         * <p>
         * RecyclerView will re-bind the returned View to the position if necessary.
         *
         * @param recycler The Recycler that can be used to bind the View
         * @param position The adapter position
         * @param type     The type of the View, defined by adapter
         * @return A View that is bound to the given position or NULL if there is no View to re-use
         * @see LayoutManager#ignoreView(View)
         */
        abstract public View getViewForPositionAndType(Recycler recycler, int position, int type);
    }

    /**
     * Base class for an Adapter
     *
     * <p>Adapters provide a binding from an app-specific data set to views that are displayed
     * within a {@link RecyclerView}.</p>
     */
    public static abstract class Adapter<VH extends ViewHolder> {
        private final AdapterDataObservable mObservable = new AdapterDataObservable();
        private boolean mHasStableIds = false;

        public abstract VH onCreateViewHolder(ViewGroup parent, int viewType);
        public abstract void onBindViewHolder(VH holder, int position);

        public final VH createViewHolder(ViewGroup parent, int viewType) {
            final VH holder = onCreateViewHolder(parent, viewType);
            holder.mItemViewType = viewType;
            return holder;
        }

        public final void bindViewHolder(VH holder, int position) {
            holder.mPosition = position;
            if (hasStableIds()) {
                holder.mItemId = getItemId(position);
            }
            onBindViewHolder(holder, position);
            holder.setFlags(ViewHolder.FLAG_BOUND,
                    ViewHolder.FLAG_BOUND | ViewHolder.FLAG_UPDATE | ViewHolder.FLAG_INVALID);
        }

        /**
         * Return the view type of the item at <code>position</code> for the purposes
         * of view recycling.
         *
         * <p>The default implementation of this method returns 0, making the assumption of
         * a single view type for the adapter. Unlike ListView adapters, types need not
         * be contiguous. Consider using id resources to uniquely identify item view types.
         *
         * @param position position to query
         * @return integer value identifying the type of the view needed to represent the item at
         *                 <code>position</code>. Type codes need not be contiguous.
         */
        public int getItemViewType(int position) {
            return 0;
        }

        public void setHasStableIds(boolean hasStableIds) {
            if (hasObservers()) {
                throw new IllegalStateException("Cannot change whether this adapter has " +
                        "stable IDs while the adapter has registered observers.");
            }
            mHasStableIds = hasStableIds;
        }

        /**
         * Return the stable ID for the item at <code>position</code>. If {@link #hasStableIds()}
         * would return false this method should return {@link #NO_ID}. The default implementation
         * of this method returns {@link #NO_ID}.
         *
         * @param position Adapter position to query
         * @return the stable ID of the item at position
         */
        public long getItemId(int position) {
            return NO_ID;
        }

        public abstract int getItemCount();

        /**
         * Returns true if this adapter publishes a unique <code>long</code> value that can
         * act as a key for the item at a given position in the data set. If that item is relocated
         * in the data set, the ID returned for that item should be the same.
         *
         * @return true if this adapter's items have stable IDs
         */
        public final boolean hasStableIds() {
            return mHasStableIds;
        }

        /**
         * Called when a view created by this adapter has been recycled.
         *
         * <p>A view is recycled when a {@link LayoutManager} decides that it no longer
         * needs to be attached to its parent {@link RecyclerView}. This can be because it has
         * fallen out of visibility or a set of cached views represented by views still
         * attached to the parent RecyclerView. If an item view has large or expensive data
         * bound to it such as large bitmaps, this may be a good place to release those
         * resources.</p>
         *
         * @param holder The ViewHolder for the view being recycled
         */
        public void onViewRecycled(VH holder) {
        }

        /**
         * Called when a view created by this adapter has been attached to a window.
         *
         * <p>This can be used as a reasonable signal that the view is about to be seen
         * by the user. If the adapter previously freed any resources in
         * {@link #onViewDetachedFromWindow(RecyclerView.ViewHolder) onViewDetachedFromWindow}
         * those resources should be restored here.</p>
         *
         * @param holder Holder of the view being attached
         */
        public void onViewAttachedToWindow(VH holder) {
        }

        /**
         * Called when a view created by this adapter has been detached from its window.
         *
         * <p>Becoming detached from the window is not necessarily a permanent condition;
         * the consumer of an Adapter's views may choose to cache views offscreen while they
         * are not visible, attaching an detaching them as appropriate.</p>
         *
         * @param holder Holder of the view being detached
         */
        public void onViewDetachedFromWindow(VH holder) {
        }

        /**
         * Returns true if one or more observers are attached to this adapter.
         * @return true if this adapter has observers
         */
        public final boolean hasObservers() {
            return mObservable.hasObservers();
        }

        /**
         * Register a new observer to listen for data changes.
         *
         * <p>The adapter may publish a variety of events describing specific changes.
         * Not all adapters may support all change types and some may fall back to a generic
         * {@link android.support.v7.widget.RecyclerView.AdapterDataObserver#onChanged()
         * "something changed"} event if more specific data is not available.</p>
         *
         * <p>Components registering observers with an adapter are responsible for
         * {@link #unregisterAdapterDataObserver(android.support.v7.widget.RecyclerView.AdapterDataObserver)
         * unregistering} those observers when finished.</p>
         *
         * @param observer Observer to register
         *
         * @see #unregisterAdapterDataObserver(android.support.v7.widget.RecyclerView.AdapterDataObserver)
         */
        public void registerAdapterDataObserver(AdapterDataObserver observer) {
            mObservable.registerObserver(observer);
        }

        /**
         * Unregister an observer currently listening for data changes.
         *
         * <p>The unregistered observer will no longer receive events about changes
         * to the adapter.</p>
         *
         * @param observer Observer to unregister
         *
         * @see #registerAdapterDataObserver(android.support.v7.widget.RecyclerView.AdapterDataObserver)
         */
        public void unregisterAdapterDataObserver(AdapterDataObserver observer) {
            mObservable.unregisterObserver(observer);
        }

        /**
         * Notify any registered observers that the data set has changed.
         *
         * <p>There are two different classes of data change events, item changes and structural
         * changes. Item changes are when a single item has its data updated but no positional
         * changes have occurred. Structural changes are when items are inserted, removed or moved
         * within the data set.</p>
         *
         * <p>This event does not specify what about the data set has changed, forcing
         * any observers to assume that all existing items and structure may no longer be valid.
         * LayoutManagers will be forced to fully rebind and relayout all visible views.</p>
         *
         * <p><code>RecyclerView</code> will attempt to synthesize visible structural change events
         * for adapters that report that they have {@link #hasStableIds() stable IDs} when
         * this method is used. This can help for the purposes of animation and visual
         * object persistence but individual item views will still need to be rebound
         * and relaid out.</p>
         *
         * <p>If you are writing an adapter it will always be more efficient to use the more
         * specific change events if you can. Rely on <code>notifyDataSetChanged()</code>
         * as a last resort.</p>
         *
         * @see #notifyItemChanged(int)
         * @see #notifyItemInserted(int)
         * @see #notifyItemRemoved(int)
         * @see #notifyItemRangeChanged(int, int)
         * @see #notifyItemRangeInserted(int, int)
         * @see #notifyItemRangeRemoved(int, int)
         */
        public final void notifyDataSetChanged() {
            mObservable.notifyChanged();
        }

        /**
         * Notify any registered observers that the item at <code>position</code> has changed.
         *
         * <p>This is an item change event, not a structural change event. It indicates that any
         * reflection of the data at <code>position</code> is out of date and should be updated.
         * The item at <code>position</code> retains the same identity.</p>
         *
         * @param position Position of the item that has changed
         *
         * @see #notifyItemRangeChanged(int, int)
         */
        public final void notifyItemChanged(int position) {
            mObservable.notifyItemRangeChanged(position, 1);
        }

        /**
         * Notify any registered observers that the <code>itemCount</code> items starting at
         * position <code>positionStart</code> have changed.
         *
         * <p>This is an item change event, not a structural change event. It indicates that
         * any reflection of the data in the given position range is out of date and should
         * be updated. The items in the given range retain the same identity.</p>
         *
         * @param positionStart Position of the first item that has changed
         * @param itemCount Number of items that have changed
         *
         * @see #notifyItemChanged(int)
         */
        public final void notifyItemRangeChanged(int positionStart, int itemCount) {
            mObservable.notifyItemRangeChanged(positionStart, itemCount);
        }

        /**
         * Notify any registered observers that the item reflected at <code>position</code>
         * has been newly inserted. The item previously at <code>position</code> is now at
         * position <code>position + 1</code>.
         *
         * <p>This is a structural change event. Representations of other existing items in the
         * data set are still considered up to date and will not be rebound, though their
         * positions may be altered.</p>
         *
         * @param position Position of the newly inserted item in the data set
         *
         * @see #notifyItemRangeInserted(int, int)
         */
        public final void notifyItemInserted(int position) {
            mObservable.notifyItemRangeInserted(position, 1);
        }

        /**
         * Notify any registered observers that the item reflected at <code>fromPosition</code>
         * has been moved to <code>toPosition</code>.
         *
         * <p>This is a structural change event. Representations of other existing items in the
         * data set are still considered up to date and will not be rebound, though their
         * positions may be altered.</p>
         *
         * @param fromPosition Previous position of the item.
         * @param toPosition New position of the item.
         */
        public final void notifyItemMoved(int fromPosition, int toPosition) {
            mObservable.notifyItemMoved(fromPosition, toPosition);
        }

        /**
         * Notify any registered observers that the currently reflected <code>itemCount</code>
         * items starting at <code>positionStart</code> have been newly inserted. The items
         * previously located at <code>positionStart</code> and beyond can now be found starting
         * at position <code>positionStart + itemCount</code>.
         *
         * <p>This is a structural change event. Representations of other existing items in the
         * data set are still considered up to date and will not be rebound, though their positions
         * may be altered.</p>
         *
         * @param positionStart Position of the first item that was inserted
         * @param itemCount Number of items inserted
         *
         * @see #notifyItemInserted(int)
         */
        public final void notifyItemRangeInserted(int positionStart, int itemCount) {
            mObservable.notifyItemRangeInserted(positionStart, itemCount);
        }

        /**
         * Notify any registered observers that the item previously located at <code>position</code>
         * has been removed from the data set. The items previously located at and after
         * <code>position</code> may now be found at <code>oldPosition - 1</code>.
         *
         * <p>This is a structural change event. Representations of other existing items in the
         * data set are still considered up to date and will not be rebound, though their positions
         * may be altered.</p>
         *
         * @param position Position of the item that has now been removed
         *
         * @see #notifyItemRangeRemoved(int, int)
         */
        public final void notifyItemRemoved(int position) {
            mObservable.notifyItemRangeRemoved(position, 1);
        }

        /**
         * Notify any registered observers that the <code>itemCount</code> items previously
         * located at <code>positionStart</code> have been removed from the data set. The items
         * previously located at and after <code>positionStart + itemCount</code> may now be found
         * at <code>oldPosition - itemCount</code>.
         *
         * <p>This is a structural change event. Representations of other existing items in the data
         * set are still considered up to date and will not be rebound, though their positions
         * may be altered.</p>
         *
         * @param positionStart Previous position of the first item that was removed
         * @param itemCount Number of items removed from the data set
         */
        public final void notifyItemRangeRemoved(int positionStart, int itemCount) {
            mObservable.notifyItemRangeRemoved(positionStart, itemCount);
        }
    }

    /**
     * A <code>LayoutManager</code> is responsible for measuring and positioning item views
     * within a <code>RecyclerView</code> as well as determining the policy for when to recycle
     * item views that are no longer visible to the user. By changing the <code>LayoutManager</code>
     * a <code>RecyclerView</code> can be used to implement a standard vertically scrolling list,
     * a uniform grid, staggered grids, horizontally scrolling collections and more. Several stock
     * layout managers are provided for general use.
     */
    public static abstract class LayoutManager {
        ChildHelper mChildHelper;
        RecyclerView mRecyclerView;

        @Nullable
        SmoothScroller mSmoothScroller;

        private boolean mRequestedSimpleAnimations = false;

        void setRecyclerView(RecyclerView recyclerView) {
            if (recyclerView == null) {
                mRecyclerView = null;
                mChildHelper = null;
            } else {
                mRecyclerView = recyclerView;
                mChildHelper = recyclerView.mChildHelper;
            }

        }

        /**
         * Calls {@code RecyclerView#requestLayout} on the underlying RecyclerView
         */
        public void requestLayout() {
            if(mRecyclerView != null) {
                mRecyclerView.requestLayout();
            }
        }

        /**
         * Checks if RecyclerView is in the middle of a layout or scroll and throws an
         * {@link IllegalStateException} if it <b>is not</b>.
         *
         * @param message The message for the exception. Can be null.
         * @see #assertNotInLayoutOrScroll(String)
         */
        public void assertInLayoutOrScroll(String message) {
            if (mRecyclerView != null) {
                mRecyclerView.assertInLayoutOrScroll(message);
            }
        }

        /**
         * Checks if RecyclerView is in the middle of a layout or scroll and throws an
         * {@link IllegalStateException} if it <b>is</b>.
         *
         * @param message The message for the exception. Can be null.
         * @see #assertInLayoutOrScroll(String)
         */
        public void assertNotInLayoutOrScroll(String message) {
            if (mRecyclerView != null) {
                mRecyclerView.assertNotInLayoutOrScroll(message);
            }
        }

        /**
         * Returns whether this LayoutManager supports automatic item animations.
         * A LayoutManager wishing to support item animations should obey certain
         * rules as outlined in {@link #onLayoutChildren(Recycler, State)}.
         * The default return value is <code>false</code>, so subclasses of LayoutManager
         * will not get predictive item animations by default.
         *
         * <p>Whether item animations are enabled in a RecyclerView is determined both
         * by the return value from this method and the
         * {@link RecyclerView#setItemAnimator(ItemAnimator) ItemAnimator} set on the
         * RecyclerView itself. If the RecyclerView has a non-null ItemAnimator but this
         * method returns false, then simple item animations will be enabled, in which
         * views that are moving onto or off of the screen are simply faded in/out. If
         * the RecyclerView has a non-null ItemAnimator and this method returns true,
         * then there will be two calls to {@link #onLayoutChildren(Recycler, State)} to
         * setup up the information needed to more intelligently predict where appearing
         * and disappearing views should be animated from/to.</p>
         *
         * @return true if predictive item animations should be enabled, false otherwise
         */
        public boolean supportsPredictiveItemAnimations() {
            return false;
        }

        /**
         * Called when this LayoutManager is both attached to a RecyclerView and that RecyclerView
         * is attached to a window.
         *
         * <p>Subclass implementations should always call through to the superclass implementation.
         * </p>
         *
         * @param view The RecyclerView this LayoutManager is bound to
         */
        public void onAttachedToWindow(RecyclerView view) {
        }

        /**
         * @deprecated
         * override {@link #onDetachedFromWindow(RecyclerView, Recycler)}
         */
        @Deprecated
        public void onDetachedFromWindow(RecyclerView view) {

        }

        /**
         * Called when this LayoutManager is detached from its parent RecyclerView or when
         * its parent RecyclerView is detached from its window.
         *
         * <p>Subclass implementations should always call through to the superclass implementation.
         * </p>
         *
         * @param view The RecyclerView this LayoutManager is bound to
         * @param recycler The recycler to use if you prefer to recycle your children instead of
         *                 keeping them around.
         */
        public void onDetachedFromWindow(RecyclerView view, Recycler recycler) {
            onDetachedFromWindow(view);
        }

        /**
         * Check if the RecyclerView is configured to clip child views to its padding.
         *
         * @return true if this RecyclerView clips children to its padding, false otherwise
         */
        public boolean getClipToPadding() {
            return mRecyclerView != null && mRecyclerView.mClipToPadding;
        }

        /**
         * Lay out all relevant child views from the given adapter.
         *
         * The LayoutManager is in charge of the behavior of item animations. By default,
         * RecyclerView has a non-null {@link #getItemAnimator() ItemAnimator}, and simple
         * item animations are enabled. This means that add/remove operations on the
         * adapter will result in animations to add new or appearing items, removed or
         * disappearing items, and moved items. If a LayoutManager returns false from
         * {@link #supportsPredictiveItemAnimations()}, which is the default, and runs a
         * normal layout operation during {@link #onLayoutChildren(Recycler, State)}, the
         * RecyclerView will have enough information to run those animations in a simple
         * way. For example, the default ItemAnimator, {@link DefaultItemAnimator}, will
         * simple fade views in and out, whether they are actuall added/removed or whether
         * they are moved on or off the screen due to other add/remove operations.
         *
         * <p>A LayoutManager wanting a better item animation experience, where items can be
         * animated onto and off of the screen according to where the items exist when they
         * are not on screen, then the LayoutManager should return true from
         * {@link #supportsPredictiveItemAnimations()} and add additional logic to
         * {@link #onLayoutChildren(Recycler, State)}. Supporting predictive animations
         * means that {@link #onLayoutChildren(Recycler, State)} will be called twice;
         * once as a "pre" layout step to determine where items would have been prior to
         * a real layout, and again to do the "real" layout. In the pre-layout phase,
         * items will remember their pre-layout positions to allow them to be laid out
         * appropriately. Also, {@link LayoutParams#isItemRemoved() removed} items will
         * be returned from the scrap to help determine correct placement of other items.
         * These removed items should not be added to the child list, but should be used
         * to help calculate correct positioning of other views, including views that
         * were not previously onscreen (referred to as APPEARING views), but whose
         * pre-layout offscreen position can be determined given the extra
         * information about the pre-layout removed views.</p>
         *
         * <p>The second layout pass is the real layout in which only non-removed views
         * will be used. The only additional requirement during this pass is, if
         * {@link #supportsPredictiveItemAnimations()} returns true, to note which
         * views exist in the child list prior to layout and which are not there after
         * layout (referred to as DISAPPEARING views), and to position/layout those views
         * appropriately, without regard to the actual bounds of the RecyclerView. This allows
         * the animation system to know the location to which to animate these disappearing
         * views.</p>
         *
         * <p>The default LayoutManager implementations for RecyclerView handle all of these
         * requirements for animations already. Clients of RecyclerView can either use one
         * of these layout managers directly or look at their implementations of
         * onLayoutChildren() to see how they account for the APPEARING and
         * DISAPPEARING views.</p>
         *
         * @param recycler         Recycler to use for fetching potentially cached views for a
         *                         position
         * @param state            Transient state of RecyclerView
         */
        public void onLayoutChildren(Recycler recycler, State state) {
            Log.e(TAG, "You must override onLayoutChildren(Recycler recycler, State state) ");
        }

        /**
         * Create a default <code>LayoutParams</code> object for a child of the RecyclerView.
         *
         * <p>LayoutManagers will often want to use a custom <code>LayoutParams</code> type
         * to store extra information specific to the layout. Client code should subclass
         * {@link RecyclerView.LayoutParams} for this purpose.</p>
         *
         * <p><em>Important:</em> if you use your own custom <code>LayoutParams</code> type
         * you must also override
         * {@link #checkLayoutParams(LayoutParams)},
         * {@link #generateLayoutParams(android.view.ViewGroup.LayoutParams)} and
         * {@link #generateLayoutParams(android.content.Context, android.util.AttributeSet)}.</p>
         *
         * @return A new LayoutParams for a child view
         */
        public abstract LayoutParams generateDefaultLayoutParams();

        /**
         * Determines the validity of the supplied LayoutParams object.
         *
         * <p>This should check to make sure that the object is of the correct type
         * and all values are within acceptable ranges. The default implementation
         * returns <code>true</code> for non-null params.</p>
         *
         * @param lp LayoutParams object to check
         * @return true if this LayoutParams object is valid, false otherwise
         */
        public boolean checkLayoutParams(LayoutParams lp) {
            return lp != null;
        }

        /**
         * Create a LayoutParams object suitable for this LayoutManager, copying relevant
         * values from the supplied LayoutParams object if possible.
         *
         * <p><em>Important:</em> if you use your own custom <code>LayoutParams</code> type
         * you must also override
         * {@link #checkLayoutParams(LayoutParams)},
         * {@link #generateLayoutParams(android.view.ViewGroup.LayoutParams)} and
         * {@link #generateLayoutParams(android.content.Context, android.util.AttributeSet)}.</p>
         *
         * @param lp Source LayoutParams object to copy values from
         * @return a new LayoutParams object
         */
        public LayoutParams generateLayoutParams(ViewGroup.LayoutParams lp) {
            if (lp instanceof LayoutParams) {
                return new LayoutParams((LayoutParams) lp);
            } else if (lp instanceof MarginLayoutParams) {
                return new LayoutParams((MarginLayoutParams) lp);
            } else {
                return new LayoutParams(lp);
            }
        }

        /**
         * Create a LayoutParams object suitable for this LayoutManager from
         * an inflated layout resource.
         *
         * <p><em>Important:</em> if you use your own custom <code>LayoutParams</code> type
         * you must also override
         * {@link #checkLayoutParams(LayoutParams)},
         * {@link #generateLayoutParams(android.view.ViewGroup.LayoutParams)} and
         * {@link #generateLayoutParams(android.content.Context, android.util.AttributeSet)}.</p>
         *
         * @param c Context for obtaining styled attributes
         * @param attrs AttributeSet describing the supplied arguments
         * @return a new LayoutParams object
         */
        public LayoutParams generateLayoutParams(Context c, AttributeSet attrs) {
            return new LayoutParams(c, attrs);
        }

        /**
         * Scroll horizontally by dx pixels in screen coordinates and return the distance traveled.
         * The default implementation does nothing and returns 0.
         *
         * @param dx            distance to scroll by in pixels. X increases as scroll position
         *                      approaches the right.
         * @param recycler      Recycler to use for fetching potentially cached views for a
         *                      position
         * @param state         Transient state of RecyclerView
         * @return The actual distance scrolled. The return value will be negative if dx was
         * negative and scrolling proceeeded in that direction.
         * <code>Math.abs(result)</code> may be less than dx if a boundary was reached.
         */
        public int scrollHorizontallyBy(int dx, Recycler recycler, State state) {
            return 0;
        }

        /**
         * Scroll vertically by dy pixels in screen coordinates and return the distance traveled.
         * The default implementation does nothing and returns 0.
         *
         * @param dy            distance to scroll in pixels. Y increases as scroll position
         *                      approaches the bottom.
         * @param recycler      Recycler to use for fetching potentially cached views for a
         *                      position
         * @param state         Transient state of RecyclerView
         * @return The actual distance scrolled. The return value will be negative if dy was
         * negative and scrolling proceeeded in that direction.
         * <code>Math.abs(result)</code> may be less than dy if a boundary was reached.
         */
        public int scrollVerticallyBy(int dy, Recycler recycler, State state) {
            return 0;
        }

        /**
         * Query if horizontal scrolling is currently supported. The default implementation
         * returns false.
         *
         * @return True if this LayoutManager can scroll the current contents horizontally
         */
        public boolean canScrollHorizontally() {
            return false;
        }

        /**
         * Query if vertical scrolling is currently supported. The default implementation
         * returns false.
         *
         * @return True if this LayoutManager can scroll the current contents vertically
         */
        public boolean canScrollVertically() {
            return false;
        }

        /**
         * Scroll to the specified adapter position.
         *
         * Actual position of the item on the screen depends on the LayoutManager implementation.
         * @param position Scroll to this adapter position.
         */
        public void scrollToPosition(int position) {
            if (DEBUG) {
                Log.e(TAG, "You MUST implement scrollToPosition. It will soon become abstract");
            }
        }

        /**
         * <p>Smooth scroll to the specified adapter position.</p>
         * <p>To support smooth scrolling, override this method, create your {@link SmoothScroller}
         * instance and call {@link #startSmoothScroll(SmoothScroller)}.
         * </p>
         * @param recyclerView The RecyclerView to which this layout manager is attached
         * @param state    Current State of RecyclerView
         * @param position Scroll to this adapter position.
         */
        public void smoothScrollToPosition(RecyclerView recyclerView, State state,
                int position) {
            Log.e(TAG, "You must override smoothScrollToPosition to support smooth scrolling");
        }

        /**
         * <p>Starts a smooth scroll using the provided SmoothScroller.</p>
         * <p>Calling this method will cancel any previous smooth scroll request.</p>
         * @param smoothScroller Unstance which defines how smooth scroll should be animated
         */
        public void startSmoothScroll(SmoothScroller smoothScroller) {
            if (mSmoothScroller != null && smoothScroller != mSmoothScroller
                    && mSmoothScroller.isRunning()) {
                mSmoothScroller.stop();
            }
            mSmoothScroller = smoothScroller;
            mSmoothScroller.start(mRecyclerView, this);
        }

        /**
         * @return true if RecycylerView is currently in the state of smooth scrolling.
         */
        public boolean isSmoothScrolling() {
            return mSmoothScroller != null && mSmoothScroller.isRunning();
        }


        /**
         * Returns the resolved layout direction for this RecyclerView.
         *
         * @return {@link android.support.v4.view.ViewCompat#LAYOUT_DIRECTION_RTL} if the layout
         * direction is RTL or returns
         * {@link android.support.v4.view.ViewCompat#LAYOUT_DIRECTION_LTR} if the layout direction
         * is not RTL.
         */
        public int getLayoutDirection() {
            return ViewCompat.getLayoutDirection(mRecyclerView);
        }

        /**
         * Ends all animations on the view created by the {@link ItemAnimator}.
         *
         * @param view The View for which the animations should be ended.
         * @see RecyclerView.ItemAnimator#endAnimations()
         */
        public void endAnimation(View view) {
            if (mRecyclerView.mItemAnimator != null) {
                mRecyclerView.mItemAnimator.endAnimation(getChildViewHolderInt(view));
            }
        }

        /**
         * To be called only during {@link #onLayoutChildren(Recycler, State)} to add a view
         * to the layout that is known to be going away, either because it has been
         * {@link Adapter#notifyItemRemoved(int) removed} or because it is actually not in the
         * visible portion of the container but is being laid out in order to inform RecyclerView
         * in how to animate the item out of view.
         * <p>
         * Views added via this method are going to be invisible to LayoutManager after the
         * dispatchLayout pass is complete. They cannot be retrieved via {@link #getChildAt(int)}
         * or won't be included in {@link #getChildCount()} method.
         *
         * @param child View to add and then remove with animation.
         */
        public void addDisappearingView(View child) {
            addDisappearingView(child, -1);
        }

        /**
         * To be called only during {@link #onLayoutChildren(Recycler, State)} to add a view
         * to the layout that is known to be going away, either because it has been
         * {@link Adapter#notifyItemRemoved(int) removed} or because it is actually not in the
         * visible portion of the container but is being laid out in order to inform RecyclerView
         * in how to animate the item out of view.
         * <p>
         * Views added via this method are going to be invisible to LayoutManager after the
         * dispatchLayout pass is complete. They cannot be retrieved via {@link #getChildAt(int)}
         * or won't be included in {@link #getChildCount()} method.
         *
         * @param child View to add and then remove with animation.
         * @param index Index of the view.
         */
        public void addDisappearingView(View child, int index) {
            addViewInt(child, index, true);
        }

        /**
         * Add a view to the currently attached RecyclerView if needed. LayoutManagers should
         * use this method to add views obtained from a {@link Recycler} using
         * {@link Recycler#getViewForPosition(int)}.
         *
         * @param child View to add
         */
        public void addView(View child) {
            addView(child, -1);
        }

        /**
         * Add a view to the currently attached RecyclerView if needed. LayoutManagers should
         * use this method to add views obtained from a {@link Recycler} using
         * {@link Recycler#getViewForPosition(int)}.
         *
         * @param child View to add
         * @param index Index to add child at
         */
        public void addView(View child, int index) {
            addViewInt(child, index, false);
        }

        private void addViewInt(View child, int index, boolean disappearing) {
            final ViewHolder holder = getChildViewHolderInt(child);
            if (disappearing || holder.isRemoved()) {
                // these views will be hidden at the end of the layout pass.
                mRecyclerView.mDisappearingViewsInLayoutPass.add(child);
            } else {
                // This may look like unnecessary but may happen if layout manager supports
                // predictive layouts and adapter removed then re-added the same item.
                // In this case, added version will be visible in the post layout (because add is
                // deferred) but RV will still bind it to the same View.
                // So if a View re-appears in post layout pass, remove it from disappearing list.
                mRecyclerView.mDisappearingViewsInLayoutPass.remove(child);
            }

            if (holder.wasReturnedFromScrap() || holder.isScrap()) {
                if (holder.isScrap()) {
                    holder.unScrap();
                } else {
                    holder.clearReturnedFromScrapFlag();
                }
                mChildHelper.attachViewToParent(child, index, child.getLayoutParams(), false);
                if (DISPATCH_TEMP_DETACH) {
                    ViewCompat.dispatchFinishTemporaryDetach(child);
                }
            } else if (child.getParent() == mRecyclerView) { // it was not a scrap but a valid child
                // ensure in correct position
                int currentIndex = mChildHelper.indexOfChild(child);
                if (index == -1) {
                    index = mChildHelper.getChildCount();
                }
                if (currentIndex == -1) {
                    throw new IllegalStateException("Added View has RecyclerView as parent but"
                            + " view is not a real child. Unfiltered index:"
                            + mRecyclerView.indexOfChild(child));
                }
                if (currentIndex != index) {
                    mRecyclerView.mLayout.moveView(currentIndex, index);
                }
            } else {
                mChildHelper.addView(child, index, false);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                lp.mInsetsDirty = true;
                final Adapter adapter = mRecyclerView.getAdapter();
                if (adapter != null) {
                    adapter.onViewAttachedToWindow(getChildViewHolderInt(child));
                }
                mRecyclerView.onChildAttachedToWindow(child);
                if (mSmoothScroller != null && mSmoothScroller.isRunning()) {
                    mSmoothScroller.onChildAttachedToWindow(child);
                }
            }
        }

        /**
         * Remove a view from the currently attached RecyclerView if needed. LayoutManagers should
         * use this method to completely remove a child view that is no longer needed.
         * LayoutManagers should strongly consider recycling removed views using
         * {@link Recycler#recycleView(android.view.View)}.
         *
         * @param child View to remove
         */
        public void removeView(View child) {
            final Adapter adapter = mRecyclerView.getAdapter();
            if (adapter != null) {
                adapter.onViewDetachedFromWindow(getChildViewHolderInt(child));
            }
            mRecyclerView.onChildDetachedFromWindow(child);
            mChildHelper.removeView(child);
        }

        /**
         * Remove a view from the currently attached RecyclerView if needed. LayoutManagers should
         * use this method to completely remove a child view that is no longer needed.
         * LayoutManagers should strongly consider recycling removed views using
         * {@link Recycler#recycleView(android.view.View)}.
         *
         * @param index Index of the child view to remove
         */
        public void removeViewAt(int index) {
            final View child = getChildAt(index);
            if (child != null) {
                final Adapter adapter = mRecyclerView.getAdapter();
                if (adapter != null) {
                    adapter.onViewDetachedFromWindow(getChildViewHolderInt(child));
                }
                mRecyclerView.onChildDetachedFromWindow(child);
                mChildHelper.removeViewAt(index);
            }
        }

        /**
         * Remove all views from the currently attached RecyclerView. This will not recycle
         * any of the affected views; the LayoutManager is responsible for doing so if desired.
         */
        public void removeAllViews() {
            final Adapter adapter = mRecyclerView.getAdapter();
            // Only remove non-animating views
            final int childCount = getChildCount();

            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                if (adapter != null) {
                    adapter.onViewDetachedFromWindow(getChildViewHolderInt(child));
                }
                mRecyclerView.onChildDetachedFromWindow(child);
            }

            for (int i = childCount - 1; i >= 0; i--) {
                mChildHelper.removeViewAt(i);
            }
        }

        /**
         * Returns the adapter position of the item represented by the given View.
         *
         * @param view The view to query
         * @return The adapter position of the item which is rendered by this View.
         */
        public int getPosition(View view) {
            return ((RecyclerView.LayoutParams) view.getLayoutParams()).getViewPosition();
        }

        /**
         * Returns the View type defined by the adapter.
         *
         * @param view The view to query
         * @return The type of the view assigned by the adapter.
         */
        public int getItemViewType(View view) {
            return getChildViewHolderInt(view).getItemViewType();
        }

        /**
         * <p>Finds the view which represents the given adapter position.</p>
         * <p>This method traverses each child since it has no information about child order.
         * Override this method to improve performance if your LayoutManager keeps data about
         * child views.</p>
         *
         * @param position Position of the item in adapter
         * @return The child view that represents the given position or null if the position is not
         * visible
         */
        public View findViewByPosition(int position) {
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                ViewHolder vh = getChildViewHolderInt(child);
                if (vh == null) {
                    continue;
                }
                if (vh.getPosition() == position &&
                        (mRecyclerView.mState.isPreLayout() || !vh.isRemoved())) {
                    return child;
                }
            }
            return null;
        }

        /**
         * Temporarily detach a child view.
         *
         * <p>LayoutManagers may want to perform a lightweight detach operation to rearrange
         * views currently attached to the RecyclerView. Generally LayoutManager implementations
         * will want to use {@link #detachAndScrapView(android.view.View, RecyclerView.Recycler)}
         * so that the detached view may be rebound and reused.</p>
         *
         * <p>If a LayoutManager uses this method to detach a view, it <em>must</em>
         * {@link #attachView(android.view.View, int, RecyclerView.LayoutParams) reattach}
         * or {@link #removeDetachedView(android.view.View) fully remove} the detached view
         * before the LayoutManager entry point method called by RecyclerView returns.</p>
         *
         * @param child Child to detach
         */
        public void detachView(View child) {
            if (DISPATCH_TEMP_DETACH) {
                ViewCompat.dispatchStartTemporaryDetach(child);
            }
            mRecyclerView.detachViewFromParent(child);
        }

        /**
         * Temporarily detach a child view.
         *
         * <p>LayoutManagers may want to perform a lightweight detach operation to rearrange
         * views currently attached to the RecyclerView. Generally LayoutManager implementations
         * will want to use {@link #detachAndScrapView(android.view.View, RecyclerView.Recycler)}
         * so that the detached view may be rebound and reused.</p>
         *
         * <p>If a LayoutManager uses this method to detach a view, it <em>must</em>
         * {@link #attachView(android.view.View, int, RecyclerView.LayoutParams) reattach}
         * or {@link #removeDetachedView(android.view.View) fully remove} the detached view
         * before the LayoutManager entry point method called by RecyclerView returns.</p>
         *
         * @param index Index of the child to detach
         */
        public void detachViewAt(int index) {
            if (DISPATCH_TEMP_DETACH) {
                ViewCompat.dispatchStartTemporaryDetach(getChildAt(index));
            }
            mChildHelper.detachViewFromParent(index);
        }

        /**
         * Reattach a previously {@link #detachView(android.view.View) detached} view.
         * This method should not be used to reattach views that were previously
         * {@link #detachAndScrapView(android.view.View, RecyclerView.Recycler)}  scrapped}.
         *
         * @param child Child to reattach
         * @param index Intended child index for child
         * @param lp LayoutParams for child
         */
        public void attachView(View child, int index, LayoutParams lp) {
            ViewHolder vh = getChildViewHolderInt(child);
            if (vh.isRemoved()) {
                mRecyclerView.mDisappearingViewsInLayoutPass.add(child);
            } else {
                mRecyclerView.mDisappearingViewsInLayoutPass.remove(child);
            }
            mChildHelper.attachViewToParent(child, index, lp, vh.isRemoved());
            if (DISPATCH_TEMP_DETACH)  {
                ViewCompat.dispatchFinishTemporaryDetach(child);
            }
        }

        /**
         * Reattach a previously {@link #detachView(android.view.View) detached} view.
         * This method should not be used to reattach views that were previously
         * {@link #detachAndScrapView(android.view.View, RecyclerView.Recycler)}  scrapped}.
         *
         * @param child Child to reattach
         * @param index Intended child index for child
         */
        public void attachView(View child, int index) {
            attachView(child, index, (LayoutParams) child.getLayoutParams());
        }

        /**
         * Reattach a previously {@link #detachView(android.view.View) detached} view.
         * This method should not be used to reattach views that were previously
         * {@link #detachAndScrapView(android.view.View, RecyclerView.Recycler)}  scrapped}.
         *
         * @param child Child to reattach
         */
        public void attachView(View child) {
            attachView(child, -1);
        }

        /**
         * Finish removing a view that was previously temporarily
         * {@link #detachView(android.view.View) detached}.
         *
         * @param child Detached child to remove
         */
        public void removeDetachedView(View child) {
            mRecyclerView.removeDetachedView(child, false);
        }

        /**
         * Moves a View from one position to another.
         *
         * @param fromIndex The View's initial index
         * @param toIndex The View's target index
         */
        public void moveView(int fromIndex, int toIndex) {
            View view = getChildAt(fromIndex);
            if (view == null) {
                throw new IllegalArgumentException("Cannot move a child from non-existing index:"
                        + fromIndex);
            }
            detachViewAt(fromIndex);
            attachView(view, toIndex);
        }

        /**
         * Detach a child view and add it to a {@link Recycler Recycler's} scrap heap.
         *
         * <p>Scrapping a view allows it to be rebound and reused to show updated or
         * different data.</p>
         *
         * @param child Child to detach and scrap
         * @param recycler Recycler to deposit the new scrap view into
         */
        public void detachAndScrapView(View child, Recycler recycler) {
            int index = mChildHelper.indexOfChild(child);
            scrapOrRecycleView(recycler, index, child);
        }

        /**
         * Detach a child view and add it to a {@link Recycler Recycler's} scrap heap.
         *
         * <p>Scrapping a view allows it to be rebound and reused to show updated or
         * different data.</p>
         *
         * @param index Index of child to detach and scrap
         * @param recycler Recycler to deposit the new scrap view into
         */
        public void detachAndScrapViewAt(int index, Recycler recycler) {
            final View child = getChildAt(index);
            scrapOrRecycleView(recycler, index, child);
        }

        /**
         * Remove a child view and recycle it using the given Recycler.
         *
         * @param child Child to remove and recycle
         * @param recycler Recycler to use to recycle child
         */
        public void removeAndRecycleView(View child, Recycler recycler) {
            removeView(child);
            recycler.recycleView(child);
        }

        /**
         * Remove a child view and recycle it using the given Recycler.
         *
         * @param index Index of child to remove and recycle
         * @param recycler Recycler to use to recycle child
         */
        public void removeAndRecycleViewAt(int index, Recycler recycler) {
            final View view = getChildAt(index);
            removeViewAt(index);
            recycler.recycleView(view);
        }

        /**
         * Return the current number of child views attached to the parent RecyclerView.
         * This does not include child views that were temporarily detached and/or scrapped.
         *
         * @return Number of attached children
         */
        public int getChildCount() {
            return mChildHelper != null ? mChildHelper.getChildCount() : 0;
        }

        /**
         * Return the child view at the given index
         * @param index Index of child to return
         * @return Child view at index
         */
        public View getChildAt(int index) {
            return mChildHelper != null ? mChildHelper.getChildAt(index) : null;
        }

        /**
         * Return the width of the parent RecyclerView
         *
         * @return Width in pixels
         */
        public int getWidth() {
            return mRecyclerView != null ? mRecyclerView.getWidth() : 0;
        }

        /**
         * Return the height of the parent RecyclerView
         *
         * @return Height in pixels
         */
        public int getHeight() {
            return mRecyclerView != null ? mRecyclerView.getHeight() : 0;
        }

        /**
         * Return the left padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingLeft() {
            return mRecyclerView != null ? mRecyclerView.getPaddingLeft() : 0;
        }

        /**
         * Return the top padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingTop() {
            return mRecyclerView != null ? mRecyclerView.getPaddingTop() : 0;
        }

        /**
         * Return the right padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingRight() {
            return mRecyclerView != null ? mRecyclerView.getPaddingRight() : 0;
        }

        /**
         * Return the bottom padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingBottom() {
            return mRecyclerView != null ? mRecyclerView.getPaddingBottom() : 0;
        }

        /**
         * Return the start padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingStart() {
            return mRecyclerView != null ? ViewCompat.getPaddingStart(mRecyclerView) : 0;
        }

        /**
         * Return the end padding of the parent RecyclerView
         *
         * @return Padding in pixels
         */
        public int getPaddingEnd() {
            return mRecyclerView != null ? ViewCompat.getPaddingEnd(mRecyclerView) : 0;
        }

        /**
         * Returns true if the RecyclerView this LayoutManager is bound to has focus.
         *
         * @return True if the RecyclerView has focus, false otherwise.
         * @see View#isFocused()
         */
        public boolean isFocused() {
            return mRecyclerView != null && mRecyclerView.isFocused();
        }

        /**
         * Returns true if the RecyclerView this LayoutManager is bound to has or contains focus.
         *
         * @return true if the RecyclerView has or contains focus
         * @see View#hasFocus()
         */
        public boolean hasFocus() {
            return mRecyclerView != null && mRecyclerView.hasFocus();
        }

        /**
         * Return the number of items in the adapter bound to the parent RecyclerView
         *
         * @return Items in the bound adapter
         */
        public int getItemCount() {
            final Adapter a = mRecyclerView != null ? mRecyclerView.getAdapter() : null;
            return a != null ? a.getItemCount() : 0;
        }

        /**
         * Offset all child views attached to the parent RecyclerView by dx pixels along
         * the horizontal axis.
         *
         * @param dx Pixels to offset by
         */
        public void offsetChildrenHorizontal(int dx) {
            if (mRecyclerView != null) {
                mRecyclerView.offsetChildrenHorizontal(dx);
            }
        }

        /**
         * Offset all child views attached to the parent RecyclerView by dy pixels along
         * the vertical axis.
         *
         * @param dy Pixels to offset by
         */
        public void offsetChildrenVertical(int dy) {
            if (mRecyclerView != null) {
                mRecyclerView.offsetChildrenVertical(dy);
            }
        }

        /**
         * Flags a view so that it will not be scrapped or recycled.
         * <p>
         * Note that View is still a child of the ViewGroup. If LayoutManager calls methods like
         * {@link LayoutManager#offsetChildrenHorizontal(int)}, this View will be offset as well.
         *
         * @param view View to ignore.
         */
        public void ignoreView(View view) {
            if (view.getParent() != mRecyclerView || mRecyclerView.indexOfChild(view) == -1) {
                // checking this because calling this method on a recycled or detached view may
                // cause loss of state.
                throw new IllegalArgumentException("View should be fully attached to be ignored");
            }
            final ViewHolder vh = getChildViewHolderInt(view);
            vh.addFlags(ViewHolder.FLAG_IGNORE);
            mRecyclerView.mState.onViewIgnored(vh);
        }

        /**
         * View can be scrapped and recycled again.
         * <p>
         * Note that calling this method removes all information in the view holder.
         *
         * @param view View to ignore.
         */
        public void stopIgnoringView(View view) {
            final ViewHolder vh = getChildViewHolderInt(view);
            vh.stopIgnoring();
            vh.resetInternal();
            vh.addFlags(ViewHolder.FLAG_INVALID);
        }

        /**
         * Temporarily detach and scrap all currently attached child views. Views will be scrapped
         * into the given Recycler. The Recycler may prefer to reuse scrap views before
         * other views that were previously recycled.
         *
         * @param recycler Recycler to scrap views into
         */
        public void detachAndScrapAttachedViews(Recycler recycler) {
            final int childCount = getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                final View v = getChildAt(i);
                scrapOrRecycleView(recycler, i, v);
            }
        }

        private void scrapOrRecycleView(Recycler recycler, int index, View view) {
            final ViewHolder viewHolder = getChildViewHolderInt(view);
            if (viewHolder.shouldIgnore()) {
                if (DEBUG) {
                    Log.d(TAG, "ignoring view " + viewHolder);
                }
                return;
            }
            if (viewHolder.isInvalid() && !viewHolder.isRemoved() &&
                    !mRecyclerView.mAdapter.hasStableIds()) {
                removeViewAt(index);
                recycler.recycleView(view);
            } else {
                detachViewAt(index);
                recycler.scrapView(view);
            }
        }

        /**
         * Recycles the scrapped views.
         * <p>
         * When a view is detached and removed, it does not trigger a ViewGroup invalidate. This is
         * the expected behavior if scrapped views are used for animations. Otherwise, we need to
         * call remove and invalidate RecyclerView to ensure UI update.
         *
         * @param recycler Recycler
         * @param remove   Whether scrapped views should be removed from ViewGroup or not. This
         *                 method will invalidate RecyclerView if it removes any scrapped child.
         */
        void removeAndRecycleScrapInt(Recycler recycler, boolean remove) {
            final int scrapCount = recycler.getScrapCount();
            for (int i = 0; i < scrapCount; i++) {
                final View scrap = recycler.getScrapViewAt(i);
                if (getChildViewHolderInt(scrap).shouldIgnore()) {
                    continue;
                }
                if (remove) {
                    mRecyclerView.removeDetachedView(scrap, false);
                }
                recycler.quickRecycleScrapView(scrap);
            }
            recycler.clearScrap();
            if (remove && scrapCount > 0) {
                mRecyclerView.invalidate();
            }
        }


        /**
         * Measure a child view using standard measurement policy, taking the padding
         * of the parent RecyclerView and any added item decorations into account.
         *
         * <p>If the RecyclerView can be scrolled in either dimension the caller may
         * pass 0 as the widthUsed or heightUsed parameters as they will be irrelevant.</p>
         *
         * @param child Child view to measure
         * @param widthUsed Width in pixels currently consumed by other views, if relevant
         * @param heightUsed Height in pixels currently consumed by other views, if relevant
         */
        public void measureChild(View child, int widthUsed, int heightUsed) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            final Rect insets = mRecyclerView.getItemDecorInsetsForChild(child);
            widthUsed += insets.left + insets.right;
            heightUsed += insets.top + insets.bottom;

            final int widthSpec = getChildMeasureSpec(getWidth(),
                    getPaddingLeft() + getPaddingRight() + widthUsed, lp.width,
                    canScrollHorizontally());
            final int heightSpec = getChildMeasureSpec(getHeight(),
                    getPaddingTop() + getPaddingBottom() + heightUsed, lp.height,
                    canScrollVertically());
            child.measure(widthSpec, heightSpec);
        }

        /**
         * Measure a child view using standard measurement policy, taking the padding
         * of the parent RecyclerView, any added item decorations and the child margins
         * into account.
         *
         * <p>If the RecyclerView can be scrolled in either dimension the caller may
         * pass 0 as the widthUsed or heightUsed parameters as they will be irrelevant.</p>
         *
         * @param child Child view to measure
         * @param widthUsed Width in pixels currently consumed by other views, if relevant
         * @param heightUsed Height in pixels currently consumed by other views, if relevant
         */
        public void measureChildWithMargins(View child, int widthUsed, int heightUsed) {
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            final Rect insets = mRecyclerView.getItemDecorInsetsForChild(child);
            widthUsed += insets.left + insets.right;
            heightUsed += insets.top + insets.bottom;

            final int widthSpec = getChildMeasureSpec(getWidth(),
                    getPaddingLeft() + getPaddingRight() +
                            lp.leftMargin + lp.rightMargin + widthUsed, lp.width,
                    canScrollHorizontally());
            final int heightSpec = getChildMeasureSpec(getHeight(),
                    getPaddingTop() + getPaddingBottom() +
                            lp.topMargin + lp.bottomMargin + heightUsed, lp.height,
                    canScrollVertically());
            child.measure(widthSpec, heightSpec);
        }

        /**
         * Calculate a MeasureSpec value for measuring a child view in one dimension.
         *
         * @param parentSize Size of the parent view where the child will be placed
         * @param padding Total space currently consumed by other elements of parent
         * @param childDimension Desired size of the child view, or MATCH_PARENT/WRAP_CONTENT.
         *                       Generally obtained from the child view's LayoutParams
         * @param canScroll true if the parent RecyclerView can scroll in this dimension
         *
         * @return a MeasureSpec value for the child view
         */
        public static int getChildMeasureSpec(int parentSize, int padding, int childDimension,
                boolean canScroll) {
            int size = Math.max(0, parentSize - padding);
            int resultSize = 0;
            int resultMode = 0;

            if (canScroll) {
                if (childDimension >= 0) {
                    resultSize = childDimension;
                    resultMode = MeasureSpec.EXACTLY;
                } else {
                    // MATCH_PARENT can't be applied since we can scroll in this dimension, wrap
                    // instead using UNSPECIFIED.
                    resultSize = 0;
                    resultMode = MeasureSpec.UNSPECIFIED;
                }
            } else {
                if (childDimension >= 0) {
                    resultSize = childDimension;
                    resultMode = MeasureSpec.EXACTLY;
                } else if (childDimension == LayoutParams.FILL_PARENT) {
                    resultSize = size;
                    resultMode = MeasureSpec.EXACTLY;
                } else if (childDimension == LayoutParams.WRAP_CONTENT) {
                    resultSize = size;
                    resultMode = MeasureSpec.AT_MOST;
                }
            }
            return MeasureSpec.makeMeasureSpec(resultSize, resultMode);
        }

        /**
         * Returns the measured width of the given child, plus the additional size of
         * any insets applied by {@link ItemDecoration ItemDecorations}.
         *
         * @param child Child view to query
         * @return child's measured width plus <code>ItemDecoration</code> insets
         *
         * @see View#getMeasuredWidth()
         */
        public int getDecoratedMeasuredWidth(View child) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            return child.getMeasuredWidth() + insets.left + insets.right;
        }

        /**
         * Returns the measured height of the given child, plus the additional size of
         * any insets applied by {@link ItemDecoration ItemDecorations}.
         *
         * @param child Child view to query
         * @return child's measured height plus <code>ItemDecoration</code> insets
         *
         * @see View#getMeasuredHeight()
         */
        public int getDecoratedMeasuredHeight(View child) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            return child.getMeasuredHeight() + insets.top + insets.bottom;
        }

        /**
         * Lay out the given child view within the RecyclerView using coordinates that
         * include any current {@link ItemDecoration ItemDecorations}.
         *
         * <p>LayoutManagers should prefer working in sizes and coordinates that include
         * item decoration insets whenever possible. This allows the LayoutManager to effectively
         * ignore decoration insets within measurement and layout code. See the following
         * methods:</p>
         * <ul>
         *     <li>{@link #measureChild(View, int, int)}</li>
         *     <li>{@link #measureChildWithMargins(View, int, int)}</li>
         *     <li>{@link #getDecoratedLeft(View)}</li>
         *     <li>{@link #getDecoratedTop(View)}</li>
         *     <li>{@link #getDecoratedRight(View)}</li>
         *     <li>{@link #getDecoratedBottom(View)}</li>
         *     <li>{@link #getDecoratedMeasuredWidth(View)}</li>
         *     <li>{@link #getDecoratedMeasuredHeight(View)}</li>
         * </ul>
         *
         * @param child Child to lay out
         * @param left Left edge, with item decoration insets included
         * @param top Top edge, with item decoration insets included
         * @param right Right edge, with item decoration insets included
         * @param bottom Bottom edge, with item decoration insets included
         *
         * @see View#layout(int, int, int, int)
         */
        public void layoutDecorated(View child, int left, int top, int right, int bottom) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            child.layout(left + insets.left, top + insets.top, right - insets.right,
                    bottom - insets.bottom);
        }

        /**
         * Returns the left edge of the given child view within its parent, offset by any applied
         * {@link ItemDecoration ItemDecorations}.
         *
         * @param child Child to query
         * @return Child left edge with offsets applied
         */
        public int getDecoratedLeft(View child) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            return child.getLeft() - insets.left;
        }

        /**
         * Returns the top edge of the given child view within its parent, offset by any applied
         * {@link ItemDecoration ItemDecorations}.
         *
         * @param child Child to query
         * @return Child top edge with offsets applied
         */
        public int getDecoratedTop(View child) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            return child.getTop() - insets.top;
        }

        /**
         * Returns the right edge of the given child view within its parent, offset by any applied
         * {@link ItemDecoration ItemDecorations}.
         *
         * @param child Child to query
         * @return Child right edge with offsets applied
         */
        public int getDecoratedRight(View child) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            return child.getRight() + insets.right;
        }

        /**
         * Returns the bottom edge of the given child view within its parent, offset by any applied
         * {@link ItemDecoration ItemDecorations}.
         *
         * @param child Child to query
         * @return Child bottom edge with offsets applied
         */
        public int getDecoratedBottom(View child) {
            final Rect insets = ((LayoutParams) child.getLayoutParams()).mDecorInsets;
            return child.getBottom() + insets.bottom;
        }

        /**
         * Called when searching for a focusable view in the given direction has failed
         * for the current content of the RecyclerView.
         *
         * <p>This is the LayoutManager's opportunity to populate views in the given direction
         * to fulfill the request if it can. The LayoutManager should attach and return
         * the view to be focused. The default implementation returns null.</p>
         *
         * @param focused   The currently focused view
         * @param direction One of {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
         *                  {@link View#FOCUS_LEFT}, {@link View#FOCUS_RIGHT},
         *                  {@link View#FOCUS_BACKWARD}, {@link View#FOCUS_FORWARD}
         *                  or 0 for not applicable
         * @param recycler  The recycler to use for obtaining views for currently offscreen items
         * @param state     Transient state of RecyclerView
         * @return The chosen view to be focused
         */
        public View onFocusSearchFailed(View focused, int direction, Recycler recycler,
                State state) {
            return null;
        }

        /**
         * This method gives a LayoutManager an opportunity to intercept the initial focus search
         * before the default behavior of {@link FocusFinder} is used. If this method returns
         * null FocusFinder will attempt to find a focusable child view. If it fails
         * then {@link #onFocusSearchFailed(View, int, RecyclerView.Recycler, RecyclerView.State)}
         * will be called to give the LayoutManager an opportunity to add new views for items
         * that did not have attached views representing them. The LayoutManager should not add
         * or remove views from this method.
         *
         * @param focused The currently focused view
         * @param direction One of {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
         *                  {@link View#FOCUS_LEFT}, {@link View#FOCUS_RIGHT},
         *                  {@link View#FOCUS_BACKWARD}, {@link View#FOCUS_FORWARD}
         * @return A descendant view to focus or null to fall back to default behavior.
         *         The default implementation returns null.
         */
        public View onInterceptFocusSearch(View focused, int direction) {
            return null;
        }

        /**
         * Called when a child of the RecyclerView wants a particular rectangle to be positioned
         * onto the screen. See {@link ViewParent#requestChildRectangleOnScreen(android.view.View,
         * android.graphics.Rect, boolean)} for more details.
         *
         * <p>The base implementation will attempt to perform a standard programmatic scroll
         * to bring the given rect into view, within the padded area of the RecyclerView.</p>
         *
         * @param child The direct child making the request.
         * @param rect  The rectangle in the child's coordinates the child
         *              wishes to be on the screen.
         * @param immediate True to forbid animated or delayed scrolling,
         *                  false otherwise
         * @return Whether the group scrolled to handle the operation
         */
        public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect,
                boolean immediate) {
            final int parentLeft = getPaddingLeft();
            final int parentTop = getPaddingTop();
            final int parentRight = getWidth() - getPaddingRight();
            final int parentBottom = getHeight() - getPaddingBottom();
            final int childLeft = child.getLeft() + rect.left;
            final int childTop = child.getTop() + rect.top;
            final int childRight = childLeft + rect.right;
            final int childBottom = childTop + rect.bottom;

            final int offScreenLeft = Math.min(0, childLeft - parentLeft);
            final int offScreenTop = Math.min(0, childTop - parentTop);
            final int offScreenRight = Math.max(0, childRight - parentRight);
            final int offScreenBottom = Math.max(0, childBottom - parentBottom);

            // Favor the "start" layout direction over the end when bringing one side or the other
            // of a large rect into view.
            final int dx;
            if (ViewCompat.getLayoutDirection(parent) == ViewCompat.LAYOUT_DIRECTION_RTL) {
                dx = offScreenRight != 0 ? offScreenRight : offScreenLeft;
            } else {
                dx = offScreenLeft != 0 ? offScreenLeft : offScreenRight;
            }

            // Favor bringing the top into view over the bottom
            final int dy = offScreenTop != 0 ? offScreenTop : offScreenBottom;

            if (dx != 0 || dy != 0) {
                if (immediate) {
                    parent.scrollBy(dx, dy);
                } else {
                    parent.smoothScrollBy(dx, dy);
                }
                return true;
            }
            return false;
        }

        /**
         * @deprecated Use {@link #onRequestChildFocus(RecyclerView, State, View, View)}
         */
        @Deprecated
        public boolean onRequestChildFocus(RecyclerView parent, View child, View focused) {
            return false;
        }

        /**
         * Called when a descendant view of the RecyclerView requests focus.
         *
         * <p>A LayoutManager wishing to keep focused views aligned in a specific
         * portion of the view may implement that behavior in an override of this method.</p>
         *
         * <p>If the LayoutManager executes different behavior that should override the default
         * behavior of scrolling the focused child on screen instead of running alongside it,
         * this method should return true.</p>
         *
         * @param parent  The RecyclerView hosting this LayoutManager
         * @param state   Current state of RecyclerView
         * @param child   Direct child of the RecyclerView containing the newly focused view
         * @param focused The newly focused view. This may be the same view as child
         * @return true if the default scroll behavior should be suppressed
         */
        public boolean onRequestChildFocus(RecyclerView parent, State state, View child,
                View focused) {
            return onRequestChildFocus(parent, child, focused);
        }

        /**
         * Called if the RecyclerView this LayoutManager is bound to has a different adapter set.
         * The LayoutManager may use this opportunity to clear caches and configure state such
         * that it can relayout appropriately with the new data and potentially new view types.
         *
         * <p>The default implementation removes all currently attached views.</p>
         *
         * @param oldAdapter The previous adapter instance. Will be null if there was previously no
         *                   adapter.
         * @param newAdapter The new adapter instance. Might be null if
         *                   {@link #setAdapter(RecyclerView.Adapter)} is called with {@code null}.
         */
        public void onAdapterChanged(Adapter oldAdapter, Adapter newAdapter) {
        }

        /**
         * Called to populate focusable views within the RecyclerView.
         *
         * <p>The LayoutManager implementation should return <code>true</code> if the default
         * behavior of {@link ViewGroup#addFocusables(java.util.ArrayList, int)} should be
         * suppressed.</p>
         *
         * <p>The default implementation returns <code>false</code> to trigger RecyclerView
         * to fall back to the default ViewGroup behavior.</p>
         *
         * @param recyclerView The RecyclerView hosting this LayoutManager
         * @param views List of output views. This method should add valid focusable views
         *              to this list.
         * @param direction One of {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
         *                  {@link View#FOCUS_LEFT}, {@link View#FOCUS_RIGHT},
         *                  {@link View#FOCUS_BACKWARD}, {@link View#FOCUS_FORWARD}
         * @param focusableMode The type of focusables to be added.
         *
         * @return true to suppress the default behavior, false to add default focusables after
         *         this method returns.
         *
         * @see #FOCUSABLES_ALL
         * @see #FOCUSABLES_TOUCH_MODE
         */
        public boolean onAddFocusables(RecyclerView recyclerView, ArrayList<View> views,
                int direction, int focusableMode) {
            return false;
        }

        /**
         * Called when {@link Adapter#notifyDataSetChanged()} is triggered instead of giving
         * detailed information on what has actually changed.
         *
         * @param recyclerView
         */
        public void onItemsChanged(RecyclerView recyclerView) {
        }

        /**
         * Called when items have been added to the adapter. The LayoutManager may choose to
         * requestLayout if the inserted items would require refreshing the currently visible set
         * of child views. (e.g. currently empty space would be filled by appended items, etc.)
         *
         * @param recyclerView
         * @param positionStart
         * @param itemCount
         */
        public void onItemsAdded(RecyclerView recyclerView, int positionStart, int itemCount) {
        }

        /**
         * Called when items have been removed from the adapter.
         *
         * @param recyclerView
         * @param positionStart
         * @param itemCount
         */
        public void onItemsRemoved(RecyclerView recyclerView, int positionStart, int itemCount) {
        }

        /**
         * Called when items have been changed in the adapter.
         *
         * @param recyclerView
         * @param positionStart
         * @param itemCount
         */
        public void onItemsUpdated(RecyclerView recyclerView, int positionStart, int itemCount) {
        }

        /**
         * Called when an item is moved withing the adapter.
         * <p>
         * Note that, an item may also change position in response to another ADD/REMOVE/MOVE
         * operation. This callback is only called if and only if {@link Adapter#notifyItemMoved}
         * is called.
         *
         * @param recyclerView
         * @param from
         * @param to
         * @param itemCount
         */
        public void onItemsMoved(RecyclerView recyclerView, int from, int to, int itemCount) {

        }


        /**
         * <p>Override this method if you want to support scroll bars.</p>
         *
         * <p>Read {@link RecyclerView#computeHorizontalScrollExtent()} for details.</p>
         *
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current state of RecyclerView
         * @return The horizontal extent of the scrollbar's thumb
         * @see RecyclerView#computeHorizontalScrollExtent()
         */
        public int computeHorizontalScrollExtent(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         *
         * <p>Read {@link RecyclerView#computeHorizontalScrollOffset()} for details.</p>
         *
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current State of RecyclerView where you can find total item count
         * @return The horizontal offset of the scrollbar's thumb
         * @see RecyclerView#computeHorizontalScrollOffset()
         */
        public int computeHorizontalScrollOffset(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         *
         * <p>Read {@link RecyclerView#computeHorizontalScrollRange()} for details.</p>
         *
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current State of RecyclerView where you can find total item count
         * @return The total horizontal range represented by the vertical scrollbar
         * @see RecyclerView#computeHorizontalScrollRange()
         */
        public int computeHorizontalScrollRange(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         *
         * <p>Read {@link RecyclerView#computeVerticalScrollExtent()} for details.</p>
         *
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current state of RecyclerView
         * @return The vertical extent of the scrollbar's thumb
         * @see RecyclerView#computeVerticalScrollExtent()
         */
        public int computeVerticalScrollExtent(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         *
         * <p>Read {@link RecyclerView#computeVerticalScrollOffset()} for details.</p>
         *
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current State of RecyclerView where you can find total item count
         * @return The vertical offset of the scrollbar's thumb
         * @see RecyclerView#computeVerticalScrollOffset()
         */
        public int computeVerticalScrollOffset(State state) {
            return 0;
        }

        /**
         * <p>Override this method if you want to support scroll bars.</p>
         *
         * <p>Read {@link RecyclerView#computeVerticalScrollRange()} for details.</p>
         *
         * <p>Default implementation returns 0.</p>
         *
         * @param state Current State of RecyclerView where you can find total item count
         * @return The total vertical range represented by the vertical scrollbar
         * @see RecyclerView#computeVerticalScrollRange()
         */
        public int computeVerticalScrollRange(State state) {
            return 0;
        }

        /**
         * Measure the attached RecyclerView. Implementations must call
         * {@link #setMeasuredDimension(int, int)} before returning.
         *
         * <p>The default implementation will handle EXACTLY measurements and respect
         * the minimum width and height properties of the host RecyclerView if measured
         * as UNSPECIFIED. AT_MOST measurements will be treated as EXACTLY and the RecyclerView
         * will consume all available space.</p>
         *
         * @param recycler Recycler
         * @param state Transient state of RecyclerView
         * @param widthSpec Width {@link android.view.View.MeasureSpec}
         * @param heightSpec Height {@link android.view.View.MeasureSpec}
         */
        public void onMeasure(Recycler recycler, State state, int widthSpec, int heightSpec) {
            final int widthMode = MeasureSpec.getMode(widthSpec);
            final int heightMode = MeasureSpec.getMode(heightSpec);
            final int widthSize = MeasureSpec.getSize(widthSpec);
            final int heightSize = MeasureSpec.getSize(heightSpec);

            int width = 0;
            int height = 0;

            switch (widthMode) {
                case MeasureSpec.EXACTLY:
                case MeasureSpec.AT_MOST:
                    width = widthSize;
                    break;
                case MeasureSpec.UNSPECIFIED:
                default:
                    width = getMinimumWidth();
                    break;
            }

            switch (heightMode) {
                case MeasureSpec.EXACTLY:
                case MeasureSpec.AT_MOST:
                    height = heightSize;
                    break;
                case MeasureSpec.UNSPECIFIED:
                default:
                    height = getMinimumHeight();
                    break;
            }

            setMeasuredDimension(width, height);
        }

        /**
         * {@link View#setMeasuredDimension(int, int) Set the measured dimensions} of the
         * host RecyclerView.
         *
         * @param widthSize Measured width
         * @param heightSize Measured height
         */
        public void setMeasuredDimension(int widthSize, int heightSize) {
            mRecyclerView.setMeasuredDimension(widthSize, heightSize);
        }

        /**
         * @return The host RecyclerView's {@link View#getMinimumWidth()}
         */
        public int getMinimumWidth() {
            return ViewCompat.getMinimumWidth(mRecyclerView);
        }

        /**
         * @return The host RecyclerView's {@link View#getMinimumHeight()}
         */
        public int getMinimumHeight() {
            return ViewCompat.getMinimumHeight(mRecyclerView);
        }
        /**
         * <p>Called when the LayoutManager should save its state. This is a good time to save your
         * scroll position, configuration and anything else that may be required to restore the same
         * layout state if the LayoutManager is recreated.</p>
         * <p>RecyclerView does NOT verify if the LayoutManager has changed between state save and
         * restore. This will let you share information between your LayoutManagers but it is also
         * your responsibility to make sure they use the same parcelable class.</p>
         *
         * @return Necessary information for LayoutManager to be able to restore its state
         */
        public Parcelable onSaveInstanceState() {
            return null;
        }


        public void onRestoreInstanceState(Parcelable state) {

        }

        void stopSmoothScroller() {
            if (mSmoothScroller != null) {
                mSmoothScroller.stop();
            }
        }

        private void onSmoothScrollerStopped(SmoothScroller smoothScroller) {
            if (mSmoothScroller == smoothScroller) {
                mSmoothScroller = null;
            }
        }

        /**
         * RecyclerView calls this method to notify LayoutManager that scroll state has changed.
         *
         * @param state The new scroll state for RecyclerView
         */
        public void onScrollStateChanged(int state) {
        }

        /**
         * Removes all views and recycles them using the given recycler.
         * <p>
         * If you want to clean cached views as well, you should call {@link Recycler#clear()} too.
         *
         * @param recycler Recycler to use to recycle children
         * @see #removeAndRecycleView(View, Recycler)
         * @see #removeAndRecycleViewAt(int, Recycler)
         */
        public void removeAndRecycleAllViews(Recycler recycler) {
            for (int i = getChildCount() - 1; i >= 0; i--) {
                removeAndRecycleViewAt(i, recycler);
            }
        }

        /**
         * A LayoutManager can call this method to force RecyclerView to run simple animations in
         * the next layout pass, even if there is not any trigger to do so. (e.g. adapter data
         * change).
         * <p>
         * Note that, calling this method will not guarantee that RecyclerView will run animations
         * at all. For example, if there is not any {@link ItemAnimator} set, RecyclerView will
         * not run any animations but will still clear this flag after the layout is complete.
         *
         */
        public void requestSimpleAnimationsInNextLayout() {
            mRequestedSimpleAnimations = true;
        }
    }

    /**
     * An ItemDecoration allows the application to add a special drawing and layout offset
     * to specific item views from the adapter's data set. This can be useful for drawing dividers
     * between items, highlights, visual grouping boundaries and more.
     *
     * <p>All ItemDecorations are drawn in the order they were added, before the item
     * views (in {@link ItemDecoration#onDraw(Canvas, RecyclerView, RecyclerView.State) onDraw()}
     * and after the items (in {@link ItemDecoration#onDrawOver(Canvas, RecyclerView,
     * RecyclerView.State)}.</p>
     */
    public static abstract class ItemDecoration {
        /**
         * Draw any appropriate decorations into the Canvas supplied to the RecyclerView.
         * Any content drawn by this method will be drawn before the item views are drawn,
         * and will thus appear underneath the views.
         *
         * @param c Canvas to draw into
         * @param parent RecyclerView this ItemDecoration is drawing into
         * @param state The current state of RecyclerView
         */
        public void onDraw(Canvas c, RecyclerView parent, State state) {
            onDraw(c, parent);
        }

        /**
         * @deprecated
         * Override {@link #onDraw(Canvas, RecyclerView, RecyclerView.State)}
         */
        @Deprecated
        public void onDraw(Canvas c, RecyclerView parent) {
        }

        /**
         * Draw any appropriate decorations into the Canvas supplied to the RecyclerView.
         * Any content drawn by this method will be drawn after the item views are drawn
         * and will thus appear over the views.
         *
         * @param c Canvas to draw into
         * @param parent RecyclerView this ItemDecoration is drawing into
         * @param state The current state of RecyclerView.
         */
        public void onDrawOver(Canvas c, RecyclerView parent, State state) {
            onDrawOver(c, parent);
        }

        /**
         * @deprecated
         * Override {@link #onDrawOver(Canvas, RecyclerView, RecyclerView.State)}
         */
        @Deprecated
        public void onDrawOver(Canvas c, RecyclerView parent) {
        }


        /**
         * @deprecated
         * Use {@link #getItemOffsets(Rect, View, RecyclerView, State)}
         */
        @Deprecated
        public void getItemOffsets(Rect outRect, int itemPosition, RecyclerView parent) {
            outRect.set(0, 0, 0, 0);
        }

        /**
         * Retrieve any offsets for the given item. Each field of <code>outRect</code> specifies
         * the number of pixels that the item view should be inset by, similar to padding or margin.
         * The default implementation sets the bounds of outRect to 0 and returns.
         *
         * <p>If this ItemDecoration does not affect the positioning of item views it should set
         * all four fields of <code>outRect</code> (left, top, right, bottom) to zero
         * before returning.</p>
         *
         * @param outRect Rect to receive the output.
         * @param view    The child view to decorate
         * @param parent  RecyclerView this ItemDecoration is decorating
         * @param state   The current state of RecyclerView.
         */
        public void getItemOffsets(Rect outRect, View view, RecyclerView parent, State state) {
            getItemOffsets(outRect, ((LayoutParams) view.getLayoutParams()).getViewPosition(),
                    parent);
        }
    }

    /**
     * An OnItemTouchListener allows the application to intercept touch events in progress at the
     * view hierarchy level of the RecyclerView before those touch events are considered for
     * RecyclerView's own scrolling behavior.
     *
     * <p>This can be useful for applications that wish to implement various forms of gestural
     * manipulation of item views within the RecyclerView. OnItemTouchListeners may intercept
     * a touch interaction already in progress even if the RecyclerView is already handling that
     * gesture stream itself for the purposes of scrolling.</p>
     */
    public interface OnItemTouchListener {
        /**
         * Silently observe and/or take over touch events sent to the RecyclerView
         * before they are handled by either the RecyclerView itself or its child views.
         *
         * <p>The onInterceptTouchEvent methods of each attached OnItemTouchListener will be run
         * in the order in which each listener was added, before any other touch processing
         * by the RecyclerView itself or child views occurs.</p>
         *
         * @param e MotionEvent describing the touch event. All coordinates are in
         *          the RecyclerView's coordinate system.
         * @return true if this OnItemTouchListener wishes to begin intercepting touch events, false
         *         to continue with the current behavior and continue observing future events in
         *         the gesture.
         */
        public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e);

        /**
         * Process a touch event as part of a gesture that was claimed by returning true from
         * a previous call to {@link #onInterceptTouchEvent}.
         *
         * @param e MotionEvent describing the touch event. All coordinates are in
         *          the RecyclerView's coordinate system.
         */
        public void onTouchEvent(RecyclerView rv, MotionEvent e);
    }

    /**
     * An OnScrollListener can be set on a RecyclerView to receive messages
     * when a scrolling event has occurred on that RecyclerView.
     *
     * @see RecyclerView#setOnScrollListener(OnScrollListener)
     */
    public interface OnScrollListener {
        public void onScrollStateChanged(int newState);
        public void onScrolled(int dx, int dy);
    }

    /**
     * A RecyclerListener can be set on a RecyclerView to receive messages whenever
     * a view is recycled.
     *
     * @see RecyclerView#setRecyclerListener(RecyclerListener)
     */
    public interface RecyclerListener {

        /**
         * This method is called whenever the view in the ViewHolder is recycled.
         *
         * @param holder The ViewHolder containing the view that was recycled
         */
        public void onViewRecycled(ViewHolder holder);
    }

    /**
     * A ViewHolder describes an item view and metadata about its place within the RecyclerView.
     *
     * <p>{@link Adapter} implementations should subclass ViewHolder and add fields for caching
     * potentially expensive {@link View#findViewById(int)} results.</p>
     *
     * <p>While {@link LayoutParams} belong to the {@link LayoutManager},
     * {@link ViewHolder ViewHolders} belong to the adapter. Adapters should feel free to use
     * their own custom ViewHolder implementations to store data that makes binding view contents
     * easier. Implementations should assume that individual item views will hold strong references
     * to <code>ViewHolder</code> objects and that <code>RecyclerView</code> instances may hold
     * strong references to extra off-screen item views for caching purposes</p>
     */
    public static abstract class ViewHolder {
        public final View itemView;
        int mPosition = NO_POSITION;
        int mOldPosition = NO_POSITION;
        long mItemId = NO_ID;
        int mItemViewType = INVALID_TYPE;
        int mPreLayoutPosition = NO_POSITION;

        // The item that this holder is shadowing during an item change event/animation
        ViewHolder mShadowedHolder = null;
        // The item that is shadowing this holder during an item change event/animation
        ViewHolder mShadowingHolder = null;

        /**
         * This ViewHolder has been bound to a position; mPosition, mItemId and mItemViewType
         * are all valid.
         */
        static final int FLAG_BOUND = 1 << 0;

        /**
         * The data this ViewHolder's view reflects is stale and needs to be rebound
         * by the adapter. mPosition and mItemId are consistent.
         */
        static final int FLAG_UPDATE = 1 << 1;

        /**
         * This ViewHolder's data is invalid. The identity implied by mPosition and mItemId
         * are not to be trusted and may no longer match the item view type.
         * This ViewHolder must be fully rebound to different data.
         */
        static final int FLAG_INVALID = 1 << 2;

        /**
         * This ViewHolder points at data that represents an item previously removed from the
         * data set. Its view may still be used for things like outgoing animations.
         */
        static final int FLAG_REMOVED = 1 << 3;

        /**
         * This ViewHolder should not be recycled. This flag is set via setIsRecyclable()
         * and is intended to keep views around during animations.
         */
        static final int FLAG_NOT_RECYCLABLE = 1 << 4;

        /**
         * This ViewHolder is returned from scrap which means we are expecting an addView call
         * for this itemView. When returned from scrap, ViewHolder stays in the scrap list until
         * the end of the layout pass and then recycled by RecyclerView if it is not added back to
         * the RecyclerView.
         */
        static final int FLAG_RETURNED_FROM_SCRAP = 1 << 5;

        /**
         * This ViewHolder's contents have changed. This flag is used as an indication that
         * change animations may be used, if supported by the ItemAnimator.
         */
        static final int FLAG_CHANGED = 1 << 6;

        /**
         * This ViewHolder is fully managed by the LayoutManager. We do not scrap, recycle or remove
         * it unless LayoutManager is replaced.
         * It is still fully visible to the LayoutManager.
         */
        static final int FLAG_IGNORE = 1 << 7;

        private int mFlags;

        private int mIsRecyclableCount = 0;

        // If non-null, view is currently considered scrap and may be reused for other data by the
        // scrap container.
        private Recycler mScrapContainer = null;

        public ViewHolder(View itemView) {
            if (itemView == null) {
                throw new IllegalArgumentException("itemView may not be null");
            }
            this.itemView = itemView;
        }

        void offsetPosition(int offset, boolean applyToPreLayout) {
            if (mOldPosition == NO_POSITION) {
                mOldPosition = mPosition;
            }
            if (mPreLayoutPosition == NO_POSITION) {
                mPreLayoutPosition = mPosition;
            }
            if (applyToPreLayout) {
                mPreLayoutPosition += offset;
            }
            mPosition += offset;
            if (itemView.getLayoutParams() != null) {
                ((LayoutParams) itemView.getLayoutParams()).mInsetsDirty = true;
            }
        }

        void clearOldPosition() {
            mOldPosition = NO_POSITION;
            mPreLayoutPosition = NO_POSITION;
        }

        void saveOldPosition() {
            if (mOldPosition == NO_POSITION) {
                mOldPosition = mPosition;
            }
        }

        boolean shouldIgnore() {
            return (mFlags & FLAG_IGNORE) != 0;
        }

        public final int getPosition() {
            return mPreLayoutPosition == NO_POSITION ? mPosition : mPreLayoutPosition;
        }

        /**
         * When LayoutManager supports animations, RecyclerView tracks 3 positions for ViewHolders
         * to perform animations.
         * <p>
         * If a ViewHolder was laid out in the previous onLayout call, old position will keep its
         * adapter index in the previous layout.
         *
         * @return The previous adapter index of the Item represented by this ViewHolder or
         * {@link #NO_POSITION} if old position does not exists or cleared (pre-layout is
         * complete).
         */
        public final int getOldPosition() {
            return mOldPosition;
        }

        /**
         * Returns The itemId represented by this ViewHolder.
         *
         * @return The the item's id if adapter has stable ids, {@link RecyclerView#NO_ID}
         * otherwise
         */
        public final long getItemId() {
            return mItemId;
        }

        /**
         * @return The view type of this ViewHolder.
         */
        public final int getItemViewType() {
            return mItemViewType;
        }

        boolean isScrap() {
            return mScrapContainer != null;
        }

        void unScrap() {
            mScrapContainer.unscrapView(this);
        }

        boolean wasReturnedFromScrap() {
            return (mFlags & FLAG_RETURNED_FROM_SCRAP) != 0;
        }

        void clearReturnedFromScrapFlag() {
            mFlags = mFlags & ~FLAG_RETURNED_FROM_SCRAP;
        }

        void stopIgnoring() {
            mFlags = mFlags & ~FLAG_IGNORE;
        }

        void setScrapContainer(Recycler recycler) {
            mScrapContainer = recycler;
        }

        boolean isInvalid() {
            return (mFlags & FLAG_INVALID) != 0;
        }

        boolean needsUpdate() {
            return (mFlags & FLAG_UPDATE) != 0;
        }

        boolean isChanged() {
            return (mFlags & FLAG_CHANGED) != 0;
        }

        boolean isBound() {
            return (mFlags & FLAG_BOUND) != 0;
        }

        boolean isRemoved() {
            return (mFlags & FLAG_REMOVED) != 0;
        }

        void setFlags(int flags, int mask) {
            mFlags = (mFlags & ~mask) | (flags & mask);
        }

        void addFlags(int flags) {
            mFlags |= flags;
        }

        void resetInternal() {
            mFlags = 0;
            mPosition = NO_POSITION;
            mOldPosition = NO_POSITION;
            mItemId = NO_ID;
            mPreLayoutPosition = NO_POSITION;
            mIsRecyclableCount = 0;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("ViewHolder{" +
                    Integer.toHexString(hashCode()) + " position=" + mPosition + " id=" + mItemId +
                    ", oldPos=" + mOldPosition + ", pLpos:" + mPreLayoutPosition);
            if (isScrap()) sb.append(" scrap");
            if (isInvalid()) sb.append(" invalid");
            if (!isBound()) sb.append(" unbound");
            if (needsUpdate()) sb.append(" update");
            if (isRemoved()) sb.append(" removed");
            if (shouldIgnore()) sb.append(" ignored");
            if (!isRecyclable()) sb.append(" not recyclable(" + mIsRecyclableCount + ")");
            if (itemView.getParent() == null) sb.append(" no parent");
            sb.append("}");
            return sb.toString();
        }

        /**
         * Informs the recycler whether this item can be recycled. Views which are not
         * recyclable will not be reused for other items until setIsRecyclable() is
         * later set to true. Calls to setIsRecyclable() should always be paired (one
         * call to setIsRecyclabe(false) should always be matched with a later call to
         * setIsRecyclable(true)). Pairs of calls may be nested, as the state is internally
         * reference-counted.
         *
         * @param recyclable Whether this item is available to be recycled. Default value
         * is true.
         */
        public final void setIsRecyclable(boolean recyclable) {
            mIsRecyclableCount = recyclable ? mIsRecyclableCount - 1 : mIsRecyclableCount + 1;
            if (mIsRecyclableCount < 0) {
                mIsRecyclableCount = 0;
                Log.e(VIEW_LOG_TAG, "isRecyclable decremented below 0: " +
                        "unmatched pair of setIsRecyable() calls");
            } else if (!recyclable && mIsRecyclableCount == 1) {
                mFlags |= FLAG_NOT_RECYCLABLE;
            } else if (recyclable && mIsRecyclableCount == 0) {
                mFlags &= ~FLAG_NOT_RECYCLABLE;
            }
            if (DEBUG) {
                Log.d(TAG, "setIsRecyclable val:" + recyclable + this);
            }
        }

        /**
         * @see {@link #setIsRecyclable(boolean)}
         *
         * @return true if this item is available to be recycled, false otherwise.
         */
        public final boolean isRecyclable() {
            return (mFlags & FLAG_NOT_RECYCLABLE) == 0 &&
                    !ViewCompat.hasTransientState(itemView);
        }
    }

    /**
     * {@link android.view.ViewGroup.MarginLayoutParams LayoutParams} subclass for children of
     * {@link RecyclerView}. Custom {@link LayoutManager layout managers} are encouraged
     * to create their own subclass of this <code>LayoutParams</code> class
     * to store any additional required per-child view metadata about the layout.
     */
    public static class LayoutParams extends android.view.ViewGroup.MarginLayoutParams {
        ViewHolder mViewHolder;
        final Rect mDecorInsets = new Rect();
        boolean mInsetsDirty = true;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }

        public LayoutParams(LayoutParams source) {
            super((ViewGroup.LayoutParams) source);
        }

        /**
         * Returns true if the view this LayoutParams is attached to needs to have its content
         * updated from the corresponding adapter.
         *
         * @return true if the view should have its content updated
         */
        public boolean viewNeedsUpdate() {
            return mViewHolder.needsUpdate();
        }

        /**
         * Returns true if the view this LayoutParams is attached to is now representing
         * potentially invalid data. A LayoutManager should scrap/recycle it.
         *
         * @return true if the view is invalid
         */
        public boolean isViewInvalid() {
            return mViewHolder.isInvalid();
        }

        /**
         * Returns true if the adapter data item corresponding to the view this LayoutParams
         * is attached to has been removed from the data set. A LayoutManager may choose to
         * treat it differently in order to animate its outgoing or disappearing state.
         *
         * @return true if the item the view corresponds to was removed from the data set
         */
        public boolean isItemRemoved() {
            return mViewHolder.isRemoved();
        }

        /**
         * Returns the position that the view this LayoutParams is attached to corresponds to.
         *
         * @return the adapter position this view was bound from
         */
        public int getViewPosition() {
            return mViewHolder.getPosition();
        }
    }

    /**
     * Observer base class for watching changes to an {@link Adapter}.
     * See {@link Adapter#registerAdapterDataObserver(AdapterDataObserver)}.
     */
    public static abstract class AdapterDataObserver {
        public void onChanged() {
            // Do nothing
        }

        public void onItemRangeChanged(int positionStart, int itemCount) {
            // do nothing
        }

        public void onItemRangeInserted(int positionStart, int itemCount) {
            // do nothing
        }

        public void onItemRangeRemoved(int positionStart, int itemCount) {
            // do nothing
        }

        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            // do nothing
        }
    }

    /**
     * <p>Base class for smooth scrolling. Handles basic tracking of the target view position and
     * provides methods to trigger a programmatic scroll.</p>
     *
     * @see LinearSmoothScroller
     */
    public static abstract class SmoothScroller {

        private int mTargetPosition = RecyclerView.NO_POSITION;

        private RecyclerView mRecyclerView;

        private LayoutManager mLayoutManager;

        private boolean mPendingInitialRun;

        private boolean mRunning;

        private View mTargetView;

        private final Action mRecyclingAction;

        public SmoothScroller() {
            mRecyclingAction = new Action(0, 0);
        }

        /**
         * Starts a smooth scroll for the given target position.
         * <p>In each animation step, {@link RecyclerView} will check
         * for the target view and call either
         * {@link #onTargetFound(android.view.View, RecyclerView.State, SmoothScroller.Action)} or
         * {@link #onSeekTargetStep(int, int, RecyclerView.State, SmoothScroller.Action)} until
         * SmoothScroller is stopped.</p>
         *
         * <p>Note that if RecyclerView finds the target view, it will automatically stop the
         * SmoothScroller. This <b>does not</b> mean that scroll will stop, it only means it will
         * stop calling SmoothScroller in each animation step.</p>
         */
        void start(RecyclerView recyclerView, LayoutManager layoutManager) {
            mRecyclerView = recyclerView;
            mLayoutManager = layoutManager;
            if (mTargetPosition == RecyclerView.NO_POSITION) {
                throw new IllegalArgumentException("Invalid target position");
            }
            mRecyclerView.mState.mTargetPosition = mTargetPosition;
            mRunning = true;
            mPendingInitialRun = true;
            mTargetView = findViewByPosition(getTargetPosition());
            onStart();
            mRecyclerView.mViewFlinger.postOnAnimation();
        }

        public void setTargetPosition(int targetPosition) {
            mTargetPosition = targetPosition;
        }

        /**
         * @return The LayoutManager to which this SmoothScroller is attached
         */
        public LayoutManager getLayoutManager() {
            return mLayoutManager;
        }

        /**
         * Stops running the SmoothScroller in each animation callback. Note that this does not
         * cancel any existing {@link Action} updated by
         * {@link #onTargetFound(android.view.View, RecyclerView.State, SmoothScroller.Action)} or
         * {@link #onSeekTargetStep(int, int, RecyclerView.State, SmoothScroller.Action)}.
         */
        final protected void stop() {
            if (!mRunning) {
                return;
            }
            onStop();
            mRecyclerView.mState.mTargetPosition = RecyclerView.NO_POSITION;
            mTargetView = null;
            mTargetPosition = RecyclerView.NO_POSITION;
            mPendingInitialRun = false;
            mRunning = false;
            // trigger a cleanup
            mLayoutManager.onSmoothScrollerStopped(this);
            // clear references to avoid any potential leak by a custom smooth scroller
            mLayoutManager = null;
            mRecyclerView = null;
        }

        /**
         * Returns true if SmoothScroller has beens started but has not received the first
         * animation
         * callback yet.
         *
         * @return True if this SmoothScroller is waiting to start
         */
        public boolean isPendingInitialRun() {
            return mPendingInitialRun;
        }


        /**
         * @return True if SmoothScroller is currently active
         */
        public boolean isRunning() {
            return mRunning;
        }

        /**
         * Returns the adapter position of the target item
         *
         * @return Adapter position of the target item or
         * {@link RecyclerView#NO_POSITION} if no target view is set.
         */
        public int getTargetPosition() {
            return mTargetPosition;
        }

        private void onAnimation(int dx, int dy) {
            if (!mRunning || mTargetPosition == RecyclerView.NO_POSITION) {
                stop();
            }
            mPendingInitialRun = false;
            if (mTargetView != null) {
                // verify target position
                if (getChildPosition(mTargetView) == mTargetPosition) {
                    onTargetFound(mTargetView, mRecyclerView.mState, mRecyclingAction);
                    mRecyclingAction.runIfNecessary(mRecyclerView);
                    stop();
                } else {
                    Log.e(TAG, "Passed over target position while smooth scrolling.");
                    mTargetView = null;
                }
            }
            if (mRunning) {
                onSeekTargetStep(dx, dy, mRecyclerView.mState, mRecyclingAction);
                mRecyclingAction.runIfNecessary(mRecyclerView);
            }
        }

        /**
         * @see RecyclerView#getChildPosition(android.view.View)
         */
        public int getChildPosition(View view) {
            return mRecyclerView.getChildPosition(view);
        }

        /**
         * @see RecyclerView.LayoutManager#getChildCount()
         */
        public int getChildCount() {
            return mRecyclerView.mLayout.getChildCount();
        }

        /**
         * @see RecyclerView.LayoutManager#findViewByPosition(int)
         */
        public View findViewByPosition(int position) {
            return mRecyclerView.mLayout.findViewByPosition(position);
        }

        /**
         * @see RecyclerView#scrollToPosition(int)
         */
        public void instantScrollToPosition(int position) {
            mRecyclerView.scrollToPosition(position);
        }

        protected void onChildAttachedToWindow(View child) {
            if (getChildPosition(child) == getTargetPosition()) {
                mTargetView = child;
                if (DEBUG) {
                    Log.d(TAG, "smooth scroll target view has been attached");
                }
            }
        }

        /**
         * Normalizes the vector.
         * @param scrollVector The vector that points to the target scroll position
         */
        protected void normalize(PointF scrollVector) {
            final double magnitute = Math.sqrt(scrollVector.x * scrollVector.x + scrollVector.y *
                    scrollVector.y);
            scrollVector.x /= magnitute;
            scrollVector.y /= magnitute;
        }

        /**
         * Called when smooth scroll is started. This might be a good time to do setup.
         */
        abstract protected void onStart();

        /**
         * Called when smooth scroller is stopped. This is a good place to cleanup your state etc.
         * @see #stop()
         */
        abstract protected void onStop();

        /**
         * <p>RecyclerView will call this method each time it scrolls until it can find the target
         * position in the layout.</p>
         * <p>SmoothScroller should check dx, dy and if scroll should be changed, update the
         * provided {@link Action} to define the next scroll.</p>
         *
         * @param dx        Last scroll amount horizontally
         * @param dy        Last scroll amount verticaully
         * @param state     Transient state of RecyclerView
         * @param action    If you want to trigger a new smooth scroll and cancel the previous one,
         *                  update this object.
         */
        abstract protected void onSeekTargetStep(int dx, int dy, State state, Action action);

        /**
         * Called when the target position is laid out. This is the last callback SmoothScroller
         * will receive and it should update the provided {@link Action} to define the scroll
         * details towards the target view.
         * @param targetView    The view element which render the target position.
         * @param state         Transient state of RecyclerView
         * @param action        Action instance that you should update to define final scroll action
         *                      towards the targetView
         * @return An {@link Action} to finalize the smooth scrolling
         */
        abstract protected void onTargetFound(View targetView, State state, Action action);

        /**
         * Holds information about a smooth scroll request by a {@link SmoothScroller}.
         */
        public static class Action {

            public static final int UNDEFINED_DURATION = Integer.MIN_VALUE;

            private int mDx;

            private int mDy;

            private int mDuration;

            private Interpolator mInterpolator;

            private boolean changed = false;

            // we track this variable to inform custom implementer if they are updating the action
            // in every animation callback
            private int consecutiveUpdates = 0;

            /**
             * @param dx Pixels to scroll horizontally
             * @param dy Pixels to scroll vertically
             */
            public Action(int dx, int dy) {
                this(dx, dy, UNDEFINED_DURATION, null);
            }

            /**
             * @param dx       Pixels to scroll horizontally
             * @param dy       Pixels to scroll vertically
             * @param duration Duration of the animation in milliseconds
             */
            public Action(int dx, int dy, int duration) {
                this(dx, dy, duration, null);
            }

            /**
             * @param dx           Pixels to scroll horizontally
             * @param dy           Pixels to scroll vertically
             * @param duration     Duration of the animation in milliseconds
             * @param interpolator Interpolator to be used when calculating scroll position in each
             *                     animation step
             */
            public Action(int dx, int dy, int duration, Interpolator interpolator) {
                mDx = dx;
                mDy = dy;
                mDuration = duration;
                mInterpolator = interpolator;
            }
            private void runIfNecessary(RecyclerView recyclerView) {
                if (changed) {
                    validate();
                    if (mInterpolator == null) {
                        if (mDuration == UNDEFINED_DURATION) {
                            recyclerView.mViewFlinger.smoothScrollBy(mDx, mDy);
                        } else {
                            recyclerView.mViewFlinger.smoothScrollBy(mDx, mDy, mDuration);
                        }
                    } else {
                        recyclerView.mViewFlinger.smoothScrollBy(mDx, mDy, mDuration, mInterpolator);
                    }
                    consecutiveUpdates ++;
                    if (consecutiveUpdates > 10) {
                        // A new action is being set in every animation step. This looks like a bad
                        // implementation. Inform developer.
                        Log.e(TAG, "Smooth Scroll action is being updated too frequently. Make sure"
                                + " you are not changing it unless necessary");
                    }
                    changed = false;
                } else {
                    consecutiveUpdates = 0;
                }
            }

            private void validate() {
                if (mInterpolator != null && mDuration < 1) {
                    throw new IllegalStateException("If you provide an interpolator, you must"
                            + " set a positive duration");
                } else if (mDuration < 1) {
                    throw new IllegalStateException("Scroll duration must be a positive number");
                }
            }

            public int getDx() {
                return mDx;
            }

            public void setDx(int dx) {
                changed = true;
                mDx = dx;
            }

            public int getDy() {
                return mDy;
            }

            public void setDy(int dy) {
                changed = true;
                mDy = dy;
            }

            public int getDuration() {
                return mDuration;
            }

            public void setDuration(int duration) {
                changed = true;
                mDuration = duration;
            }

            public Interpolator getInterpolator() {
                return mInterpolator;
            }

            /**
             * Sets the interpolator to calculate scroll steps
             * @param interpolator The interpolator to use. If you specify an interpolator, you must
             *                     also set the duration.
             * @see #setDuration(int)
             */
            public void setInterpolator(Interpolator interpolator) {
                changed = true;
                mInterpolator = interpolator;
            }

            /**
             * Updates the action with given parameters.
             * @param dx Pixels to scroll horizontally
             * @param dy Pixels to scroll vertically
             * @param duration Duration of the animation in milliseconds
             * @param interpolator Interpolator to be used when calculating scroll position in each
             *                     animation step
             */
            public void update(int dx, int dy, int duration, Interpolator interpolator) {
                mDx = dx;
                mDy = dy;
                mDuration = duration;
                mInterpolator = interpolator;
                changed = true;
            }
        }
    }

    static class AdapterDataObservable extends Observable<AdapterDataObserver> {
        public boolean hasObservers() {
            return !mObservers.isEmpty();
        }

        public void notifyChanged() {
            // since onChanged() is implemented by the app, it could do anything, including
            // removing itself from {@link mObservers} - and that could cause problems if
            // an iterator is used on the ArrayList {@link mObservers}.
            // to avoid such problems, just march thru the list in the reverse order.
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onChanged();
            }
        }

        public void notifyItemRangeChanged(int positionStart, int itemCount) {
            // since onItemRangeChanged() is implemented by the app, it could do anything, including
            // removing itself from {@link mObservers} - and that could cause problems if
            // an iterator is used on the ArrayList {@link mObservers}.
            // to avoid such problems, just march thru the list in the reverse order.
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onItemRangeChanged(positionStart, itemCount);
            }
        }

        public void notifyItemRangeInserted(int positionStart, int itemCount) {
            // since onItemRangeInserted() is implemented by the app, it could do anything,
            // including removing itself from {@link mObservers} - and that could cause problems if
            // an iterator is used on the ArrayList {@link mObservers}.
            // to avoid such problems, just march thru the list in the reverse order.
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onItemRangeInserted(positionStart, itemCount);
            }
        }

        public void notifyItemRangeRemoved(int positionStart, int itemCount) {
            // since onItemRangeRemoved() is implemented by the app, it could do anything, including
            // removing itself from {@link mObservers} - and that could cause problems if
            // an iterator is used on the ArrayList {@link mObservers}.
            // to avoid such problems, just march thru the list in the reverse order.
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onItemRangeRemoved(positionStart, itemCount);
            }
        }

        public void notifyItemMoved(int fromPosition, int toPosition) {
            for (int i = mObservers.size() - 1; i >= 0; i--) {
                mObservers.get(i).onItemRangeMoved(fromPosition, toPosition, 1);
            }
        }
    }

    static class SavedState extends android.view.View.BaseSavedState {

        Parcelable mLayoutState;

        /**
         * called by CREATOR
         */
        SavedState(Parcel in) {
            super(in);
            mLayoutState = in.readParcelable(LayoutManager.class.getClassLoader());
        }

        /**
         * Called by onSaveInstanceState
         */
        SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeParcelable(mLayoutState, 0);
        }

        private void copyFrom(SavedState other) {
            mLayoutState = other.mLayoutState;
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
    /**
     * <p>Contains useful information about the current RecyclerView state like target scroll
     * position or view focus. State object can also keep arbitrary data, identified by resource
     * ids.</p>
     * <p>Often times, RecyclerView components will need to pass information between each other.
     * To provide a well defined data bus between components, RecyclerView passes the same State
     * object to component callbacks and these components can use it to exchange data.</p>
     * <p>If you implement custom components, you can use State's put/get/remove methods to pass
     * data between your components without needing to manage their lifecycles.</p>
     */
    public static class State {

        private int mTargetPosition = RecyclerView.NO_POSITION;
        private ArrayMap<ViewHolder, ItemHolderInfo> mPreLayoutHolderMap =
                new ArrayMap<ViewHolder, ItemHolderInfo>();
        private ArrayMap<ViewHolder, ItemHolderInfo> mPostLayoutHolderMap =
                new ArrayMap<ViewHolder, ItemHolderInfo>();

        private SparseArray<Object> mData;

        /**
         * Number of items adapter has.
         */
        private int mItemCount = 0;

        /**
         * Number of items adapter had in the previous layout.
         */
        private int mPreviousLayoutItemCount = 0;

        /**
         * Number of items that were NOT laid out but has been deleted from the adapter after the
         * previous layout.
         */
        private int mDeletedInvisibleItemCountSincePreviousLayout = 0;

        private boolean mStructureChanged = false;

        private boolean mInPreLayout = false;

        private boolean mRunSimpleAnimations = false;

        private boolean mRunPredictiveAnimations = false;

        State reset() {
            mTargetPosition = RecyclerView.NO_POSITION;
            if (mData != null) {
                mData.clear();
            }
            mItemCount = 0;
            mStructureChanged = false;
            return this;
        }

        public boolean isPreLayout() {
            return mInPreLayout;
        }

        /**
         * Returns whether RecyclerView will run predictive animations in this layout pass
         * or not.
         *
         * @return true if RecyclerView is calculating predictive animations to be run at the end
         *         of the layout pass.
         */
        public boolean willRunPredictiveAnimations() {
            return mRunPredictiveAnimations;
        }

        /**
         * Returns whether RecyclerView will run simple animations in this layout pass
         * or not.
         *
         * @return true if RecyclerView is calculating simple animations to be run at the end of
         *         the layout pass.
         */
        public boolean willRunSimpleAnimations() {
            return mRunSimpleAnimations;
        }

        /**
         * Removes the mapping from the specified id, if there was any.
         * @param resourceId Id of the resource you want to remove. It is suggested to use R.id.* to
         *                   preserve cross functionality and avoid conflicts.
         */
        public void remove(int resourceId) {
            if (mData == null) {
                return;
            }
            mData.remove(resourceId);
        }

        /**
         * Gets the Object mapped from the specified id, or <code>null</code>
         * if no such data exists.
         *
         * @param resourceId Id of the resource you want to remove. It is suggested to use R.id.*
         *                   to
         *                   preserve cross functionality and avoid conflicts.
         */
        public <T> T get(int resourceId) {
            if (mData == null) {
                return null;
            }
            return (T) mData.get(resourceId);
        }

        /**
         * Adds a mapping from the specified id to the specified value, replacing the previous
         * mapping from the specified key if there was one.
         *
         * @param resourceId Id of the resource you want to add. It is suggested to use R.id.* to
         *                   preserve cross functionality and avoid conflicts.
         * @param data       The data you want to associate with the resourceId.
         */
        public void put(int resourceId, Object data) {
            if (mData == null) {
                mData = new SparseArray<Object>();
            }
            mData.put(resourceId, data);
        }

        /**
         * If scroll is triggered to make a certain item visible, this value will return the
         * adapter index of that item.
         * @return Adapter index of the target item or
         * {@link RecyclerView#NO_POSITION} if there is no target
         * position.
         */
        public int getTargetScrollPosition() {
            return mTargetPosition;
        }

        /**
         * Returns if current scroll has a target position.
         * @return true if scroll is being triggered to make a certain position visible
         * @see #getTargetScrollPosition()
         */
        public boolean hasTargetScrollPosition() {
            return mTargetPosition != RecyclerView.NO_POSITION;
        }

        /**
         * @return true if the structure of the data set has changed since the last call to
         *         onLayoutChildren, false otherwise
         */
        public boolean didStructureChange() {
            return mStructureChanged;
        }

        /**
         * @return Total number of items to be laid out. Note that, this number is not necessarily
         * equal to the number of items in the adapter, so you should always use this number for
         * your position calculations and never call adapter directly.
         */
        public int getItemCount() {
            return mInPreLayout ?
                    (mPreviousLayoutItemCount - mDeletedInvisibleItemCountSincePreviousLayout) :
                    mItemCount;
        }

        public void onViewRecycled(ViewHolder holder) {
            mPreLayoutHolderMap.remove(holder);
            mPostLayoutHolderMap.remove(holder);
        }

        public void onViewIgnored(ViewHolder holder) {
            mPreLayoutHolderMap.remove(holder);
            mPostLayoutHolderMap.remove(holder);
        }
    }

    /**
     * Internal listener that manages items after animations finish. This is how items are
     * retained (not recycled) during animations, but allowed to be recycled afterwards.
     * It depends on the contract with the ItemAnimator to call the appropriate dispatch*Finished()
     * method on the animator's listener when it is done animating any item.
     */
    private class ItemAnimatorRestoreListener implements ItemAnimator.ItemAnimatorListener {

        @Override
        public void onRemoveFinished(ViewHolder item) {
            item.setIsRecyclable(true);
            removeAnimatingView(item.itemView);
            removeDetachedView(item.itemView, false);
        }

        @Override
        public void onAddFinished(ViewHolder item) {
            item.setIsRecyclable(true);
            if (item.isRecyclable()) {
                removeAnimatingView(item.itemView);
            }
        }

        @Override
        public void onMoveFinished(ViewHolder item) {
            item.setIsRecyclable(true);
            if (item.isRecyclable()) {
                removeAnimatingView(item.itemView);
            }
        }

        @Override
        public void onChangeFinished(ViewHolder item) {
            item.setIsRecyclable(true);
            if (item.isRecyclable()) {
                removeAnimatingView(item.itemView);
            }
            item.setFlags(~ViewHolder.FLAG_CHANGED, item.mFlags);
            ViewHolder shadowedHolder = item.mShadowedHolder;
            if (shadowedHolder != null) {
                shadowedHolder.setIsRecyclable(true);
                shadowedHolder.mShadowingHolder = null;
            }
            item.mShadowedHolder = null;
        }
    };

    /**
     * This class defines the animations that take place on items as changes are made
     * to the adapter.
     *
     * Subclasses of ItemAnimator can be used to implement custom animations for actions on
     * ViewHolder items. The RecyclerView will manage retaining these items while they
     * are being animated, but implementors must call the appropriate "Finished"
     * method when each item animation is done ({@link #dispatchRemoveFinished(ViewHolder)},
     * {@link #dispatchMoveFinished(ViewHolder)}, or {@link #dispatchAddFinished(ViewHolder)}).
     *
     * <p>By default, RecyclerView uses {@link DefaultItemAnimator}</p>
     *
     * @see #setItemAnimator(ItemAnimator)
     */
    public static abstract class ItemAnimator {

        private ItemAnimatorListener mListener = null;
        private ArrayList<ItemAnimatorFinishedListener> mFinishedListeners =
                new ArrayList<ItemAnimatorFinishedListener>();

        private long mAddDuration = 120;
        private long mRemoveDuration = 120;
        private long mMoveDuration = 250;
        private long mChangeDuration = 250;

        private boolean mSupportsChangeAnimations = false;

        /**
         * Gets the current duration for which all move animations will run.
         *
         * @return The current move duration
         */
        public long getMoveDuration() {
            return mMoveDuration;
        }

        /**
         * Sets the duration for which all move animations will run.
         *
         * @param moveDuration The move duration
         */
        public void setMoveDuration(long moveDuration) {
            mMoveDuration = moveDuration;
        }

        /**
         * Gets the current duration for which all add animations will run.
         *
         * @return The current add duration
         */
        public long getAddDuration() {
            return mAddDuration;
        }

        /**
         * Sets the duration for which all add animations will run.
         *
         * @param addDuration The add duration
         */
        public void setAddDuration(long addDuration) {
            mAddDuration = addDuration;
        }

        /**
         * Gets the current duration for which all remove animations will run.
         *
         * @return The current remove duration
         */
        public long getRemoveDuration() {
            return mRemoveDuration;
        }

        /**
         * Sets the duration for which all remove animations will run.
         *
         * @param removeDuration The remove duration
         */
        public void setRemoveDuration(long removeDuration) {
            mRemoveDuration = removeDuration;
        }

        /**
         * Gets the current duration for which all change animations will run.
         *
         * @return The current change duration
         */
        public long getChangeDuration() {
            return mChangeDuration;
        }

        /**
         * Sets the duration for which all change animations will run.
         *
         * @param changeDuration The change duration
         */
        public void setChangeDuration(long changeDuration) {
            mChangeDuration = changeDuration;
        }

        /**
         * Returns whether this ItemAnimator supports animations of change events.
         *
         * @return true if change animations are supported, false otherwise
         */
        public boolean getSupportsChangeAnimations() {
            return mSupportsChangeAnimations;
        }

        /**
         * Sets whether this ItemAnimator supports animations of item change events.
         * By default, ItemAnimator only supports animations when items are added or removed.
         * By setting this property to true, actions on the data set which change the
         * contents of items may also be animated. What those animations are is left
         * up to the discretion of the ItemAnimator subclass, in its
         * {@link #animateChange(ViewHolder, ViewHolder)} implementation.
         * The value of this property is false by default.
         *
         * @see Adapter#notifyItemChanged(int)
         * @see Adapter#notifyItemRangeChanged(int, int)
         *
         * @param supportsChangeAnimations true if change animations are supported by
         * this ItemAnimator, false otherwise. If the property is false, the ItemAnimator
         * will not receive a call to {@link #animateChange(ViewHolder, ViewHolder)} when
         * changes occur.
         */
        public void setSupportsChangeAnimations(boolean supportsChangeAnimations) {
            mSupportsChangeAnimations = supportsChangeAnimations;
        }

        /**
         * Internal only:
         * Sets the listener that must be called when the animator is finished
         * animating the item (or immediately if no animation happens). This is set
         * internally and is not intended to be set by external code.
         *
         * @param listener The listener that must be called.
         */
        void setListener(ItemAnimatorListener listener) {
            mListener = listener;
        }

        /**
         * Called when there are pending animations waiting to be started. This state
         * is governed by the return values from {@link #animateAdd(ViewHolder) animateAdd()},
         * {@link #animateMove(ViewHolder, int, int, int, int) animateMove()}, and
         * {@link #animateRemove(ViewHolder) animateRemove()}, which inform the
         * RecyclerView that the ItemAnimator wants to be called later to start the
         * associated animations. runPendingAnimations() will be scheduled to be run
         * on the next frame.
         */
        abstract public void runPendingAnimations();

        /**
         * Called when an item is removed from the RecyclerView. Implementors can choose
         * whether and how to animate that change, but must always call
         * {@link #dispatchRemoveFinished(ViewHolder)} when done, either
         * immediately (if no animation will occur) or after the animation actually finishes.
         * The return value indicates whether an animation has been set up and whether the
         * ItemAnimator's {@link #runPendingAnimations()} method should be called at the
         * next opportunity. This mechanism allows ItemAnimator to set up individual animations
         * as separate calls to {@link #animateAdd(ViewHolder) animateAdd()},
         * {@link #animateMove(ViewHolder, int, int, int, int) animateMove()},
         * {@link #animateRemove(ViewHolder) animateRemove()}, and
         * {@link #animateChange(ViewHolder, ViewHolder)} come in one by one, then
         * start the animations together in the later call to {@link #runPendingAnimations()}.
         *
         * <p>This method may also be called for disappearing items which continue to exist in the
         * RecyclerView, but for which the system does not have enough information to animate
         * them out of view. In that case, the default animation for removing items is run
         * on those items as well.</p>
         *
         * @param holder The item that is being removed.
         * @return true if a later call to {@link #runPendingAnimations()} is requested,
         * false otherwise.
         */
        abstract public boolean animateRemove(ViewHolder holder);

        /**
         * Called when an item is added to the RecyclerView. Implementors can choose
         * whether and how to animate that change, but must always call
         * {@link #dispatchAddFinished(ViewHolder)} when done, either
         * immediately (if no animation will occur) or after the animation actually finishes.
         * The return value indicates whether an animation has been set up and whether the
         * ItemAnimator's {@link #runPendingAnimations()} method should be called at the
         * next opportunity. This mechanism allows ItemAnimator to set up individual animations
         * as separate calls to {@link #animateAdd(ViewHolder) animateAdd()},
         * {@link #animateMove(ViewHolder, int, int, int, int) animateMove()},
         * {@link #animateRemove(ViewHolder) animateRemove()}, and
         * {@link #animateChange(ViewHolder, ViewHolder)} come in one by one, then
         * start the animations together in the later call to {@link #runPendingAnimations()}.
         *
         * <p>This method may also be called for appearing items which were already in the
         * RecyclerView, but for which the system does not have enough information to animate
         * them into view. In that case, the default animation for adding items is run
         * on those items as well.</p>
         *
         * @param holder The item that is being added.
         * @return true if a later call to {@link #runPendingAnimations()} is requested,
         * false otherwise.
         */
        abstract public boolean animateAdd(ViewHolder holder);

        /**
         * Called when an item is moved in the RecyclerView. Implementors can choose
         * whether and how to animate that change, but must always call
         * {@link #dispatchMoveFinished(ViewHolder)} when done, either
         * immediately (if no animation will occur) or after the animation actually finishes.
         * The return value indicates whether an animation has been set up and whether the
         * ItemAnimator's {@link #runPendingAnimations()} method should be called at the
         * next opportunity. This mechanism allows ItemAnimator to set up individual animations
         * as separate calls to {@link #animateAdd(ViewHolder) animateAdd()},
         * {@link #animateMove(ViewHolder, int, int, int, int) animateMove()},
         * {@link #animateRemove(ViewHolder) animateRemove()}, and
         * {@link #animateChange(ViewHolder, ViewHolder)} come in one by one, then
         * start the animations together in the later call to {@link #runPendingAnimations()}.
         *
         * @param holder The item that is being moved.
         * @return true if a later call to {@link #runPendingAnimations()} is requested,
         * false otherwise.
         */
        abstract public boolean animateMove(ViewHolder holder, int fromX, int fromY,
                int toX, int toY);

        /**
         * Called when an item is changed in the RecyclerView, as indicated by a call to
         * {@link Adapter#notifyItemChanged(int)} or
         * {@link Adapter#notifyItemRangeChanged(int, int)}. Implementors can choose
         * whether and how to animate changes, but must always call
         * {@link #dispatchChangeFinished(ViewHolder)} with <code>oldHolder</code>when done, either
         * immediately (if no animation will occur) or after the animation actually finishes.
         * The return value indicates whether an animation has been set up and whether the
         * ItemAnimator's {@link #runPendingAnimations()} method should be called at the
         * next opportunity. This mechanism allows ItemAnimator to set up individual animations
         * as separate calls to {@link #animateAdd(ViewHolder) animateAdd()},
         * {@link #animateMove(ViewHolder, int, int, int, int) animateMove()},
         * {@link #animateRemove(ViewHolder) animateRemove()}, and
         * {@link #animateChange(ViewHolder, ViewHolder)} come in one by one, then
         * start the animations together in the later call to {@link #runPendingAnimations()}.
         *
         * @param oldHolder The original item that changed.
         * @param newHolder The new item that was created with the changed content.
         * @return true if a later call to {@link #runPendingAnimations()} is requested,
         * false otherwise.
         */
        abstract public boolean animateChange(ViewHolder oldHolder, ViewHolder newHolder);

        /**
         * Method to be called by subclasses when a remove animation is done.
         *
         * @param item The item which has been removed
         */
        public final void dispatchRemoveFinished(ViewHolder item) {
            if (mListener != null) {
                mListener.onRemoveFinished(item);
            }
        }

        /**
         * Method to be called by subclasses when a move animation is done.
         *
         * @param item The item which has been moved
         */
        public final void dispatchMoveFinished(ViewHolder item) {
            if (mListener != null) {
                mListener.onMoveFinished(item);
            }
        }

        /**
         * Method to be called by subclasses when an add animation is done.
         *
         * @param item The item which has been added
         */
        public final void dispatchAddFinished(ViewHolder item) {
            if (mListener != null) {
                mListener.onAddFinished(item);
            }
        }

        /**
         * Method to be called by subclasses when a change animation is done.
         *
         * @param item The item which has been changed (this is the original, or "old"
         * ViewHolder item, not the "new" holder which was also passed into the call to
         * {@link #animateChange(ViewHolder, ViewHolder)}).
         */
        public final void dispatchChangeFinished(ViewHolder item) {
            if (mListener != null) {
                mListener.onChangeFinished(item);
            }
        }

        /**
         * Method called when an animation on a view should be ended immediately.
         * This could happen when other events, like scrolling, occur, so that
         * animating views can be quickly put into their proper end locations.
         * Implementations should ensure that any animations running on the item
         * are canceled and affected properties are set to their end values.
         * Also, appropriate dispatch methods (e.g., {@link #dispatchAddFinished(ViewHolder)}
         * should be called since the animations are effectively done when this
         * method is called.
         *
         * @param item The item for which an animation should be stopped.
         */
        abstract public void endAnimation(ViewHolder item);

        /**
         * Method called when all item animations should be ended immediately.
         * This could happen when other events, like scrolling, occur, so that
         * animating views can be quickly put into their proper end locations.
         * Implementations should ensure that any animations running on any items
         * are canceled and affected properties are set to their end values.
         * Also, appropriate dispatch methods (e.g., {@link #dispatchAddFinished(ViewHolder)}
         * should be called since the animations are effectively done when this
         * method is called.
         */
        abstract public void endAnimations();

        /**
         * Method which returns whether there are any item animations currently running.
         * This method can be used to determine whether to delay other actions until
         * animations end.
         *
         * @return true if there are any item animations currently running, false otherwise.
         */
        abstract public boolean isRunning();

        /**
         * Like {@link #isRunning()}, this method returns whether there are any item
         * animations currently running. Addtionally, the listener passed in will be called
         * when there are no item animations running, either immediately (before the method
         * returns) if no animations are currently running, or when the currently running
         * animations are {@link #dispatchAnimationsFinished() finished}.
         *
         * <p>Note that the listener is transient - it is either called immediately and not
         * stored at all, or stored only until it is called when running animations
         * are finished sometime later.</p>
         *
         * @param listener A listener to be called immediately if no animations are running
         * or later when currently-running animations have finished. A null listener is
         * equivalent to calling {@link #isRunning()}.
         * @return true if there are any item animations currently running, false otherwise.
         */
        public final boolean isRunning(ItemAnimatorFinishedListener listener) {
            boolean running = isRunning();
            if (listener != null) {
                if (!running) {
                    listener.onAnimationsFinished();
                } else {
                    mFinishedListeners.add(listener);
                }
            }
            return running;
        }

        /**
         * The interface to be implemented by listeners to animation events from this
         * ItemAnimator. This is used internally and is not intended for developers to
         * create directly.
         */
        private interface ItemAnimatorListener {
            void onRemoveFinished(ViewHolder item);
            void onAddFinished(ViewHolder item);
            void onMoveFinished(ViewHolder item);
            void onChangeFinished(ViewHolder item);
        }

        /**
         * This method should be called by ItemAnimator implementations to notify
         * any listeners that all pending and active item animations are finished.
         */
        public final void dispatchAnimationsFinished() {
            final int count = mFinishedListeners.size();
            for (int i = 0; i < count; ++i) {
                mFinishedListeners.get(i).onAnimationsFinished();
            }
            mFinishedListeners.clear();
        }

        /**
         * This interface is used to inform listeners when all pending or running animations
         * in an ItemAnimator are finished. This can be used, for example, to delay an action
         * in a data set until currently-running animations are complete.
         *
         * @see #isRunning(ItemAnimatorFinishedListener)
         */
        public interface ItemAnimatorFinishedListener {
            void onAnimationsFinished();
        }
    }

    /**
     * Internal data structure that holds information about an item's bounds.
     * This information is used in calculating item animations.
     */
    private static class ItemHolderInfo {
        ViewHolder holder;
        int left, top, right, bottom;

        ItemHolderInfo(ViewHolder holder, int left, int top, int right, int bottom) {
            this.holder = holder;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
