/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.annotation.Widget;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.PopupWindow.OnDismissListener;
import android.annotation.LewaHook;
///LEWA BEGIN
import android.graphics.drawable.BitmapDrawable;
import android.view.inputmethod.InputMethodManager;
import lewa.util.LewaUiUtil;
///LEWA END
/**
 * A view that displays one child at a time and lets the user pick among them.
 * The items in the Spinner come from the {@link Adapter} associated with
 * this view.
 *
 * <p>See the <a href="{@docRoot}guide/topics/ui/controls/spinner.html">Spinners</a> guide.</p>
 *
 * @attr ref android.R.styleable#Spinner_dropDownHorizontalOffset
 * @attr ref android.R.styleable#Spinner_dropDownSelector
 * @attr ref android.R.styleable#Spinner_dropDownVerticalOffset
 * @attr ref android.R.styleable#Spinner_dropDownWidth
 * @attr ref android.R.styleable#Spinner_gravity
 * @attr ref android.R.styleable#Spinner_popupBackground
 * @attr ref android.R.styleable#Spinner_prompt
 * @attr ref android.R.styleable#Spinner_spinnerMode
 */
@Widget
public class Spinner extends AbsSpinner implements OnClickListener {
    private static final String TAG = "Spinner";

    // Only measure this many items to get a decent max width.
    private static final int MAX_ITEMS_MEASURED = 15;

    /**
     * Use a dialog window for selecting spinner options.
     */
    public static final int MODE_DIALOG = 0;

    /**
     * Use a dropdown anchored to the Spinner for selecting spinner options.
     */
    public static final int MODE_DROPDOWN = 1;
    
    /**
     * Use the theme-supplied value to select the dropdown mode.
     */
    private static final int MODE_THEME = -1;
    
    private SpinnerPopup mPopup;
    private DropDownAdapter mTempAdapter;
    int mDropDownWidth;

    private int mGravity;
    private boolean mDisableChildrenWhenDisabled;

    private Rect mTempRect = new Rect();

    /**
     * Construct a new spinner with the given context's theme.
     *
     * @param context The Context the view is running in, through which it can
     *        access the current theme, resources, etc.
     */
    public Spinner(Context context) {
        this(context, null);
    }

    /**
     * Construct a new spinner with the given context's theme and the supplied
     * mode of displaying choices. <code>mode</code> may be one of
     * {@link #MODE_DIALOG} or {@link #MODE_DROPDOWN}.
     *
     * @param context The Context the view is running in, through which it can
     *        access the current theme, resources, etc.
     * @param mode Constant describing how the user will select choices from the spinner.
     * 
     * @see #MODE_DIALOG
     * @see #MODE_DROPDOWN
     */
    public Spinner(Context context, int mode) {
        this(context, null, com.android.internal.R.attr.spinnerStyle, mode);
    }

    /**
     * Construct a new spinner with the given context's theme and the supplied attribute set.
     *
     * @param context The Context the view is running in, through which it can
     *        access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     */
    @LewaHook(LewaHook.LewaHookType.CHANGE_CODE)
    public Spinner(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.spinnerStyle);
    }

    /**
     * Construct a new spinner with the given context's theme, the supplied attribute set,
     * and default style.
     *
     * @param context The Context the view is running in, through which it can
     *        access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyle The default style to apply to this view. If 0, no style
     *        will be applied (beyond what is included in the theme). This may
     *        either be an attribute resource, whose value will be retrieved
     *        from the current theme, or an explicit style resource.
     */
    @LewaHook(LewaHook.LewaHookType.CHANGE_CODE)
    public Spinner(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs, defStyle, MODE_THEME);
    }

    /**
     * Construct a new spinner with the given context's theme, the supplied attribute set,
     * and default style. <code>mode</code> may be one of {@link #MODE_DIALOG} or
     * {@link #MODE_DROPDOWN} and determines how the user will select choices from the spinner.
     *
     * @param context The Context the view is running in, through which it can
     *        access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyle The default style to apply to this view. If 0, no style
     *        will be applied (beyond what is included in the theme). This may
     *        either be an attribute resource, whose value will be retrieved
     *        from the current theme, or an explicit style resource.
     * @param mode Constant describing how the user will select choices from the spinner.
     * 
     * @see #MODE_DIALOG
     * @see #MODE_DROPDOWN
     */
    @LewaHook(LewaHook.LewaHookType.CHANGE_CODE)
    public Spinner(Context context, AttributeSet attrs, int defStyle, int mode) {
        super(context, attrs, defStyle);
        //LEWA BEGIN
        isSpinnerV5Style=Injector.isSpinnerV5Style(context,attrs);
        isHideInputKeyBoard=Injector.isHideInputKeyBoard(context, attrs);
        Injector.setDefaultBackGroundResource(context,attrs);
        //LEWA END
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Spinner, defStyle, 0);

        if (mode == MODE_THEME) {
            mode = a.getInt(com.android.internal.R.styleable.Spinner_spinnerMode, MODE_DIALOG);
        }
        
        switch (mode) {
        case MODE_DIALOG: {
            mPopup = new DialogPopup();
            break;
        }

        case MODE_DROPDOWN: {
            DropdownPopup popup = new DropdownPopup(context, attrs, defStyle);

            mDropDownWidth = a.getLayoutDimension(
                    com.android.internal.R.styleable.Spinner_dropDownWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            popup.setBackgroundDrawable(a.getDrawable(
                    com.android.internal.R.styleable.Spinner_popupBackground));
            final int verticalOffset = a.getDimensionPixelOffset(
                    com.android.internal.R.styleable.Spinner_dropDownVerticalOffset, 0);
            if (verticalOffset != 0) {
                popup.setVerticalOffset(verticalOffset);
            }

            final int horizontalOffset = a.getDimensionPixelOffset(
                    com.android.internal.R.styleable.Spinner_dropDownHorizontalOffset, 0);
            if (horizontalOffset != 0) {
                popup.setHorizontalOffset(horizontalOffset);
            }

            mPopup = popup;
            break;
        }
        }
        
        mGravity = a.getInt(com.android.internal.R.styleable.Spinner_gravity, Gravity.CENTER);

        mPopup.setPromptText(a.getString(com.android.internal.R.styleable.Spinner_prompt));

        mDisableChildrenWhenDisabled = a.getBoolean(
                com.android.internal.R.styleable.Spinner_disableChildrenWhenDisabled, false);

        a.recycle();

        // Base constructor can call setAdapter before we initialize mPopup.
        // Finish setting things up if this happened.
        if (mTempAdapter != null) {
            mPopup.setAdapter(mTempAdapter);
            mTempAdapter = null;
        }
    }

    /**
     * Set the background drawable for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.
     *
     * @param background Background drawable
     *
     * @attr ref android.R.styleable#Spinner_popupBackground
     */
    public void setPopupBackgroundDrawable(Drawable background) {
        if (!(mPopup instanceof DropdownPopup)) {
            Log.e(TAG, "setPopupBackgroundDrawable: incompatible spinner mode; ignoring...");
            return;
        }
        ((DropdownPopup) mPopup).setBackgroundDrawable(background);
    }

    /**
     * Set the background drawable for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.
     *
     * @param resId Resource ID of a background drawable
     *
     * @attr ref android.R.styleable#Spinner_popupBackground
     */
    public void setPopupBackgroundResource(int resId) {
        setPopupBackgroundDrawable(getContext().getResources().getDrawable(resId));
    }

    /**
     * Get the background drawable for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; other modes will return null.
     *
     * @return background Background drawable
     *
     * @attr ref android.R.styleable#Spinner_popupBackground
     */
    public Drawable getPopupBackground() {
        return mPopup.getBackground();
    }

    /**
     * Set a vertical offset in pixels for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.
     *
     * @param pixels Vertical offset in pixels
     *
     * @attr ref android.R.styleable#Spinner_dropDownVerticalOffset
     */
    public void setDropDownVerticalOffset(int pixels) {
        mPopup.setVerticalOffset(pixels);
    }

    /**
     * Get the configured vertical offset in pixels for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; other modes will return 0.
     *
     * @return Vertical offset in pixels
     *
     * @attr ref android.R.styleable#Spinner_dropDownVerticalOffset
     */
    public int getDropDownVerticalOffset() {
        return mPopup.getVerticalOffset();
    }

    /**
     * Set a horizontal offset in pixels for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.
     *
     * @param pixels Horizontal offset in pixels
     *
     * @attr ref android.R.styleable#Spinner_dropDownHorizontalOffset
     */
    public void setDropDownHorizontalOffset(int pixels) {
        mPopup.setHorizontalOffset(pixels);
    }

    /**
     * Get the configured horizontal offset in pixels for the spinner's popup window of choices.
     * Only valid in {@link #MODE_DROPDOWN}; other modes will return 0.
     *
     * @return Horizontal offset in pixels
     *
     * @attr ref android.R.styleable#Spinner_dropDownHorizontalOffset
     */
    public int getDropDownHorizontalOffset() {
        return mPopup.getHorizontalOffset();
    }

    /**
     * Set the width of the spinner's popup window of choices in pixels. This value
     * may also be set to {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
     * to match the width of the Spinner itself, or
     * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} to wrap to the measured size
     * of contained dropdown list items.
     *
     * <p>Only valid in {@link #MODE_DROPDOWN}; this method is a no-op in other modes.</p>
     *
     * @param pixels Width in pixels, WRAP_CONTENT, or MATCH_PARENT
     *
     * @attr ref android.R.styleable#Spinner_dropDownWidth
     */
    public void setDropDownWidth(int pixels) {
        if (!(mPopup instanceof DropdownPopup)) {
            Log.e(TAG, "Cannot set dropdown width for MODE_DIALOG, ignoring");
            return;
        }
        mDropDownWidth = pixels;
    }

    /**
     * Get the configured width of the spinner's popup window of choices in pixels.
     * The returned value may also be {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
     * meaning the popup window will match the width of the Spinner itself, or
     * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} to wrap to the measured size
     * of contained dropdown list items.
     *
     * @return Width in pixels, WRAP_CONTENT, or MATCH_PARENT
     *
     * @attr ref android.R.styleable#Spinner_dropDownWidth
     */
    public int getDropDownWidth() {
        return mDropDownWidth;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (mDisableChildrenWhenDisabled) {
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                getChildAt(i).setEnabled(enabled);
            }
        }
    }

    /**
     * Describes how the selected item view is positioned. Currently only the horizontal component
     * is used. The default is determined by the current theme.
     *
     * @param gravity See {@link android.view.Gravity}
     *
     * @attr ref android.R.styleable#Spinner_gravity
     */
    public void setGravity(int gravity) {
        if (mGravity != gravity) {
            if ((gravity & Gravity.HORIZONTAL_GRAVITY_MASK) == 0) {
                gravity |= Gravity.START;
            }
            mGravity = gravity;
            requestLayout();
        }
    }

    /**
     * Describes how the selected item view is positioned. The default is determined by the
     * current theme.
     *
     * @return A {@link android.view.Gravity Gravity} value
     */
    public int getGravity() {
        return mGravity;
    }

    @Override
    public void setAdapter(SpinnerAdapter adapter) {
        super.setAdapter(adapter);

        if (mPopup != null) {
            mPopup.setAdapter(new DropDownAdapter(adapter));
        } else {
            mTempAdapter = new DropDownAdapter(adapter);
        }
    }

    @Override
    public int getBaseline() {
        View child = null;

        if (getChildCount() > 0) {
            child = getChildAt(0);
        } else if (mAdapter != null && mAdapter.getCount() > 0) {
            child = makeAndAddView(0);
            mRecycler.put(0, child);
            removeAllViewsInLayout();
        }

        if (child != null) {
            final int childBaseline = child.getBaseline();
            return childBaseline >= 0 ? child.getTop() + childBaseline : -1;
        } else {
            return -1;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        
        if (mPopup != null && mPopup.isShowing()) {
            mPopup.dismiss();
        }
    }

    /**
     * <p>A spinner does not support item click events. Calling this method
     * will raise an exception.</p>
     * <p>Instead use {@link AdapterView#setOnItemSelectedListener}.
     *
     * @param l this listener will be ignored
     */
    @Override
    public void setOnItemClickListener(OnItemClickListener l) {
        throw new RuntimeException("setOnItemClickListener cannot be used with a spinner.");
    }

    /**
     * @hide internal use only
     */
    public void setOnItemClickListenerInt(OnItemClickListener l) {
        super.setOnItemClickListener(l);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mPopup != null && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            final int measuredWidth = getMeasuredWidth();
            setMeasuredDimension(Math.min(Math.max(measuredWidth,
                    measureContentWidth(getAdapter(), getBackground())),
                    MeasureSpec.getSize(widthMeasureSpec)),
                    getMeasuredHeight());
        }
    }

    /**
     * @see android.view.View#onLayout(boolean,int,int,int,int)
     *
     * Creates and positions all views
     *
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mInLayout = true;
        layout(0, false);
        mInLayout = false;
    }

    /**
     * Creates and positions all views for this Spinner.
     *
     * @param delta Change in the selected position. +1 means selection is moving to the right,
     * so views are scrolling to the left. -1 means selection is moving to the left.
     */
    @Override
    void layout(int delta, boolean animate) {
        int childrenLeft = mSpinnerPadding.left;
        int childrenWidth = mRight - mLeft - mSpinnerPadding.left - mSpinnerPadding.right;

        if (mDataChanged) {
            handleDataChanged();
        }

        // Handle the empty set by removing all views
        if (mItemCount == 0) {
            resetList();
            return;
        }

        if (mNextSelectedPosition >= 0) {
            setSelectedPositionInt(mNextSelectedPosition);
        }

        recycleAllViews();

        // Clear out old views
        removeAllViewsInLayout();

        // Make selected view and position it
        mFirstPosition = mSelectedPosition;
        View sel = makeAndAddView(mSelectedPosition);
        int width = sel.getMeasuredWidth();
        int selectedOffset = childrenLeft;
        final int layoutDirection = getLayoutDirection();
        final int absoluteGravity = Gravity.getAbsoluteGravity(mGravity, layoutDirection);
        switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
            case Gravity.CENTER_HORIZONTAL:
                selectedOffset = childrenLeft + (childrenWidth / 2) - (width / 2);
                break;
            case Gravity.RIGHT:
                selectedOffset = childrenLeft + childrenWidth - width;
                break;
        }
        sel.offsetLeftAndRight(selectedOffset);

        // Flush any cached views that did not get reused above
        mRecycler.clear();

        invalidate();

        checkSelectionChanged();

        mDataChanged = false;
        mNeedSync = false;
        setNextSelectedPositionInt(mSelectedPosition);
    }

    /**
     * Obtain a view, either by pulling an existing view from the recycler or
     * by getting a new one from the adapter. If we are animating, make sure
     * there is enough information in the view's layout parameters to animate
     * from the old to new positions.
     *
     * @param position Position in the spinner for the view to obtain
     * @return A view that has been added to the spinner
     */
    @LewaHook(LewaHook.LewaHookType.CHANGE_CODE)
    private View makeAndAddView(int position) {

        View child;
        if (!mDataChanged) {
            child = mRecycler.get(position);
            if (child != null) {
                // Position the view
                setUpChild(child);
                //LEWA BEGIN
                childNow=child;
                //LEWA END
                return child;
            }
        }

        // Nothing found in the recycler -- ask the adapter for a view
        child = mAdapter.getView(position, null, this);
        // Position the view
        setUpChild(child);
        //LEWA BEGIN
        childNow=child;
        //LEWA END
        return child;
    }

    /**
     * Helper for makeAndAddView to set the position of a view
     * and fill out its layout paramters.
     *
     * @param child The view to position
     */
    private void setUpChild(View child) {

        // Respect layout params that are already in the view. Otherwise
        // make some up...
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp == null) {
            lp = generateDefaultLayoutParams();
        }

        addViewInLayout(child, 0, lp);

        child.setSelected(hasFocus());
        if (mDisableChildrenWhenDisabled) {
            child.setEnabled(isEnabled());
        }

        // Get measure specs
        int childHeightSpec = ViewGroup.getChildMeasureSpec(mHeightMeasureSpec,
                mSpinnerPadding.top + mSpinnerPadding.bottom, lp.height);
        int childWidthSpec = ViewGroup.getChildMeasureSpec(mWidthMeasureSpec,
                mSpinnerPadding.left + mSpinnerPadding.right, lp.width);

        // Measure child
        child.measure(childWidthSpec, childHeightSpec);

        int childLeft;
        int childRight;

        // Position vertically based on gravity setting
        int childTop = mSpinnerPadding.top
                + ((getMeasuredHeight() - mSpinnerPadding.bottom -
                        mSpinnerPadding.top - child.getMeasuredHeight()) / 2);
        int childBottom = childTop + child.getMeasuredHeight();

        int width = child.getMeasuredWidth();
        childLeft = 0;
        childRight = childLeft + width;

        child.layout(childLeft, childTop, childRight, childBottom);
    }
    @LewaHook(LewaHook.LewaHookType.CHANGE_CODE)
    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        
        if (!handled) {
            handled = true;

            if (!mPopup.isShowing()) {
                mPopup.show();
                //LEWA BEGIN
                if(isSpinnerV5Style){
                	Injector.hideInputField(Spinner.this);
	                Injector.storeSpinnerPadding(Spinner.this);
	                Spinner.this.setBackgroundResource(com.lewa.internal.R.drawable.spinner_popuwindow_top_bg);
	                Injector.setSpinnerPadding(Spinner.this);
	                Injector.setArrow(Spinner.this);
                }
                //LEWA END
            }
        }

        return handled;
    }

    public void onClick(DialogInterface dialog, int which) {
        setSelection(which);
        dialog.dismiss();
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(Spinner.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Spinner.class.getName());
    }

    /**
     * Sets the prompt to display when the dialog is shown.
     * @param prompt the prompt to set
     */
    public void setPrompt(CharSequence prompt) {
        mPopup.setPromptText(prompt);
    }

    /**
     * Sets the prompt to display when the dialog is shown.
     * @param promptId the resource ID of the prompt to display when the dialog is shown
     */
    public void setPromptId(int promptId) {
        setPrompt(getContext().getText(promptId));
    }

    /**
     * @return The prompt to display when the dialog is shown
     */
    public CharSequence getPrompt() {
        return mPopup.getHintText();
    }

    int measureContentWidth(SpinnerAdapter adapter, Drawable background) {
        if (adapter == null) {
            return 0;
        }

        int width = 0;
        View itemView = null;
        int itemType = 0;
        final int widthMeasureSpec =
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final int heightMeasureSpec =
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        // Make sure the number of items we'll measure is capped. If it's a huge data set
        // with wildly varying sizes, oh well.
        int start = Math.max(0, getSelectedItemPosition());
        final int end = Math.min(adapter.getCount(), start + MAX_ITEMS_MEASURED);
        final int count = end - start;
        start = Math.max(0, start - (MAX_ITEMS_MEASURED - count));
        for (int i = start; i < end; i++) {
            final int positionType = adapter.getItemViewType(i);
            if (positionType != itemType) {
                itemType = positionType;
                itemView = null;
            }
            itemView = adapter.getView(i, itemView, this);
            if (itemView.getLayoutParams() == null) {
                itemView.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            itemView.measure(widthMeasureSpec, heightMeasureSpec);
            width = Math.max(width, itemView.getMeasuredWidth());
        }

        // Add background padding to measured width
        if (background != null) {
            background.getPadding(mTempRect);
            width += mTempRect.left + mTempRect.right;
        }

        return width;
    }

    /**
     * <p>Wrapper class for an Adapter. Transforms the embedded Adapter instance
     * into a ListAdapter.</p>
     */
    private static class DropDownAdapter implements ListAdapter, SpinnerAdapter {
        private SpinnerAdapter mAdapter;
        private ListAdapter mListAdapter;

        /**
         * <p>Creates a new ListAdapter wrapper for the specified adapter.</p>
         *
         * @param adapter the Adapter to transform into a ListAdapter
         */
        public DropDownAdapter(SpinnerAdapter adapter) {
            this.mAdapter = adapter;
            if (adapter instanceof ListAdapter) {
                this.mListAdapter = (ListAdapter) adapter;
            }
        }

        public int getCount() {
            return mAdapter == null ? 0 : mAdapter.getCount();
        }

        public Object getItem(int position) {
            return mAdapter == null ? null : mAdapter.getItem(position);
        }

        public long getItemId(int position) {
            return mAdapter == null ? -1 : mAdapter.getItemId(position);
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            return getDropDownView(position, convertView, parent);
        }

        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return mAdapter == null ? null :
                    mAdapter.getDropDownView(position, convertView, parent);
        }

        public boolean hasStableIds() {
            return mAdapter != null && mAdapter.hasStableIds();
        }

        public void registerDataSetObserver(DataSetObserver observer) {
            if (mAdapter != null) {
                mAdapter.registerDataSetObserver(observer);
            }
        }

        public void unregisterDataSetObserver(DataSetObserver observer) {
            if (mAdapter != null) {
                mAdapter.unregisterDataSetObserver(observer);
            }
        }

        /**
         * If the wrapped SpinnerAdapter is also a ListAdapter, delegate this call.
         * Otherwise, return true. 
         */
        public boolean areAllItemsEnabled() {
            final ListAdapter adapter = mListAdapter;
            if (adapter != null) {
                return adapter.areAllItemsEnabled();
            } else {
                return true;
            }
        }

        /**
         * If the wrapped SpinnerAdapter is also a ListAdapter, delegate this call.
         * Otherwise, return true.
         */
        public boolean isEnabled(int position) {
            final ListAdapter adapter = mListAdapter;
            if (adapter != null) {
                return adapter.isEnabled(position);
            } else {
                return true;
            }
        }

        public int getItemViewType(int position) {
            return 0;
        }

        public int getViewTypeCount() {
            return 1;
        }
        
        public boolean isEmpty() {
            return getCount() == 0;
        }
    }
    
    /**
     * Implements some sort of popup selection interface for selecting a spinner option.
     * Allows for different spinner modes.
     */
    private interface SpinnerPopup {
        public void setAdapter(ListAdapter adapter);
        
        /**
         * Show the popup
         */
        public void show();
        
        /**
         * Dismiss the popup
         */
        public void dismiss();
        
        /**
         * @return true if the popup is showing, false otherwise.
         */
        public boolean isShowing();
        
        /**
         * Set hint text to be displayed to the user. This should provide
         * a description of the choice being made.
         * @param hintText Hint text to set.
         */
        public void setPromptText(CharSequence hintText);
        public CharSequence getHintText();

        public void setBackgroundDrawable(Drawable bg);
        public void setVerticalOffset(int px);
        public void setHorizontalOffset(int px);
        public Drawable getBackground();
        public int getVerticalOffset();
        public int getHorizontalOffset();
    }
    
    private class DialogPopup implements SpinnerPopup, DialogInterface.OnClickListener {
        private AlertDialog mPopup;
        private ListAdapter mListAdapter;
        private CharSequence mPrompt;

        public void dismiss() {
            mPopup.dismiss();
            mPopup = null;
        }

        public boolean isShowing() {
            return mPopup != null ? mPopup.isShowing() : false;
        }

        public void setAdapter(ListAdapter adapter) {
            mListAdapter = adapter;
        }

        public void setPromptText(CharSequence hintText) {
            mPrompt = hintText;
        }
        
        public CharSequence getHintText() {
            return mPrompt;
        }

        public void show() {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            if (mPrompt != null) {
                builder.setTitle(mPrompt);
            }
            mPopup = builder.setSingleChoiceItems(mListAdapter,
                    getSelectedItemPosition(), this).show();
        }
        
        public void onClick(DialogInterface dialog, int which) {
            setSelection(which);
            if (mOnItemClickListener != null) {
                performItemClick(null, which, mListAdapter.getItemId(which));
            }
            dismiss();
        }

        @Override
        public void setBackgroundDrawable(Drawable bg) {
            Log.e(TAG, "Cannot set popup background for MODE_DIALOG, ignoring");
        }

        @Override
        public void setVerticalOffset(int px) {
            Log.e(TAG, "Cannot set vertical offset for MODE_DIALOG, ignoring");
        }

        @Override
        public void setHorizontalOffset(int px) {
            Log.e(TAG, "Cannot set horizontal offset for MODE_DIALOG, ignoring");
        }

        @Override
        public Drawable getBackground() {
            return null;
        }

        @Override
        public int getVerticalOffset() {
            return 0;
        }

        @Override
        public int getHorizontalOffset() {
            return 0;
        }
    }
    
    private class DropdownPopup extends ListPopupWindow implements SpinnerPopup {
        private CharSequence mHintText;
        private ListAdapter mAdapter;
        @LewaHook(LewaHook.LewaHookType.CHANGE_CODE)
        public DropdownPopup(Context context, AttributeSet attrs, int defStyleRes) {
            super(context, attrs, 0, defStyleRes);

            setAnchorView(Spinner.this);
            setModal(true);
            setPromptPosition(POSITION_PROMPT_ABOVE);
            setOnItemClickListener(new OnItemClickListener() {
                public void onItemClick(AdapterView parent, View v, int position, long id) {
                    Spinner.this.setSelection(position);
                    if (mOnItemClickListener != null) {
                        Spinner.this.performItemClick(v, position, mAdapter.getItemId(position));
                    }
                    dismiss();
                    //LEWA BEGIN
                    Injector.setSpinnerBackGround(Spinner.this);
                    //LEWA END
                }
            });
        }
        
        @Override
        public void setAdapter(ListAdapter adapter) {
            super.setAdapter(adapter);
            mAdapter = adapter;
        }

        public CharSequence getHintText() {
            return mHintText;
        }
        
        public void setPromptText(CharSequence hintText) {
            // Hint text is ignored for dropdowns, but maintain it here.
            mHintText = hintText;
        }
        @LewaHook(LewaHook.LewaHookType.CHANGE_CODE)
        @Override
        public void show() {
            final Drawable background = getBackground();
            int hOffset = 0;
            if (background != null) {
                background.getPadding(mTempRect);
                hOffset = isLayoutRtl() ? mTempRect.right : -mTempRect.left;
            } else {
                mTempRect.left = mTempRect.right = 0;
            }

            final int spinnerPaddingLeft = Spinner.this.getPaddingLeft();
            final int spinnerPaddingRight = Spinner.this.getPaddingRight();
            final int spinnerWidth = Spinner.this.getWidth();
            if (mDropDownWidth == WRAP_CONTENT) {
                int contentWidth =  measureContentWidth(
                        (SpinnerAdapter) mAdapter, getBackground());
                final int contentWidthLimit = mContext.getResources()
                        .getDisplayMetrics().widthPixels - mTempRect.left - mTempRect.right;
                if (contentWidth > contentWidthLimit) {
                    contentWidth = contentWidthLimit;
                }
                setContentWidth(Math.max(
                       contentWidth, spinnerWidth - spinnerPaddingLeft - spinnerPaddingRight));
            } else if (mDropDownWidth == MATCH_PARENT) {
 ///LEWA BEGIN
            	if(!isSpinnerV5Style){
            		setContentWidth(spinnerWidth - spinnerPaddingLeft - spinnerPaddingRight);
                 }else{
                	 setContentWidth((int)(spinnerWidth-mContext.getResources().getDimension(com.lewa.internal.R.dimen.spinner_v5_width_offset)));
            	 }
 ///LEWA END
            } else {
                setContentWidth(mDropDownWidth);
            }
///LEWA BEGIN
            if (!isSpinnerV5Style) {
                if (isLayoutRtl()) {
                    hOffset += spinnerWidth - spinnerPaddingRight - getWidth();
                } else {
                    hOffset += spinnerPaddingLeft;
                }
                setHorizontalOffset(hOffset);
            }
///LEWA END
            setInputMethodMode(ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
            super.show();
            getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            setSelection(Spinner.this.getSelectedItemPosition());

            // Make sure we hide if our anchor goes away.
            // TODO: This might be appropriate to push all the way down to PopupWindow,
            // but it may have other side effects to investigate first. (Text editing handles, etc.)
            final ViewTreeObserver vto = getViewTreeObserver();
            if (vto != null) {
                final OnGlobalLayoutListener layoutListener = new OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (!Spinner.this.isVisibleToUser()) {
                            dismiss();
                            //LEWA BEGIN
                            Injector.setSpinnerBackGround(Spinner.this);
                            //LEWA END
                        }
                    }
                };
                vto.addOnGlobalLayoutListener(layoutListener);
                setOnDismissListener(new OnDismissListener() {
                    @Override public void onDismiss() {
                        final ViewTreeObserver vto = getViewTreeObserver();
                        if (vto != null) {
                            vto.removeOnGlobalLayoutListener(layoutListener);
                        }
                        //LEWA BEGIN
                        Injector.setSpinnerBackGround(Spinner.this);
                        //LEWA END
                    }
                });
            }
        }
    }

    /**
     * M: Dismiss popup window by application's request.
     * 
     * @hide
     */
    @LewaHook(LewaHook.LewaHookType.CHANGE_CODE)
    public void dismissPopup() {
        if (mPopup != null && mPopup.isShowing()) {
            mPopup.dismiss();
            //LEWA BEGIN
            Injector.setSpinnerBackGround(Spinner.this);
            //LEWA END
        }
    }

    /**
     * M: Judge whether the popup window is showing.
     * 
     * @hide
     */
    public boolean isPopupShowing() {
        return (mPopup != null && mPopup.isShowing());
    }
///LEWA BEGIN
    @LewaHook(LewaHook.LewaHookType.NEW_CLASS)
    static class Injector{
        static void storeSpinnerPadding(Spinner spinner) {
            spinner.paddingLeft=spinner.getPaddingLeft();
            spinner.paddingRight=spinner.getPaddingRight();
            spinner.paddingTop=spinner.getPaddingTop();
            spinner.paddingBottom=spinner.getPaddingBottom();
        }
        static void setSpinnerPadding(Spinner spinner) {
            spinner.setPadding(spinner.paddingLeft,spinner.paddingTop,spinner.paddingRight,spinner.paddingBottom);
        }
        static void setArrow(Spinner spinner) {
            if (spinner.childNow==null)
                return;
            ImageView iv=(ImageView)spinner.childNow.findViewById(com.lewa.internal.R.id.v5_spinner_iv);
            if (spinner.isPopupShowing()) {
                iv.setImageResource(com.lewa.internal.R.drawable.spinner_arrow_close);
            } else {
                iv.setImageResource(com.lewa.internal.R.drawable.spinner_arrow_open);
            }
        }
        static boolean isSpinnerV5Style( Context context,AttributeSet attrs) {
            return LewaUiUtil.isSpinnerV5Style(context,attrs);
        }
    	 static boolean isHideInputKeyBoard( Context context,AttributeSet attrs){
    		 TypedArray a = context.obtainStyledAttributes(attrs,  
    	                com.lewa.internal.R.styleable.V5spinner);  
    	    	return 	a.getBoolean(com.lewa.internal.R.styleable.V5spinner_isHideInputKeyBoard,true);
 	}
        static void setDefaultBackGroundResource(Context context,AttributeSet attrs) {
            TypedArray a = context.obtainStyledAttributes(attrs, android.R.styleable.View);
            backGroundResource=a.getResourceId(android.R.styleable.View_background,com.lewa.internal.R.drawable.spinner_selector);
        }
        static void setSpinnerBackGround(Spinner spinner) {
            if(isSpinnerV5Style) {
                storeSpinnerPadding(spinner);
                spinner.setBackgroundResource(backGroundResource);
                setSpinnerPadding(spinner);
                setArrow(spinner);
            }
        }
        static void hideInputField(Spinner spinner) {
    		 if(isHideInputKeyBoard){
	    		 InputMethodManager inputMethodManager = (InputMethodManager)spinner.mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
	             inputMethodManager.hideSoftInputFromWindow(spinner.getWindowToken(),InputMethodManager.HIDE_NOT_ALWAYS);
	        }
        }
    }
    private int paddingLeft;
    private int paddingRight;
    private int paddingTop;
    private int paddingBottom;
    private View childNow=null;
    private static boolean isSpinnerV5Style=false;
    private static boolean isHideInputKeyBoard=true;
    private static int backGroundResource;
///LEWA END
}
