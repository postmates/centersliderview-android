package com.postmates.widget.centersliderview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;

/**
 * Slider widget designed specifically for changing the time in the header.
 *
 * This will attempt to center the baseline in the view, if possible.  Otherwise, it will conform
 * to the parameters configured for it.
 *
 * The units on this can be configured by setting the plurals reference in `app:unitReference`.
 *
 * Drawbacks to using this (and not prioritized for release):
 * - is not accessible
 * - does not retain state
 * - does not handle velocity
 */
public class CenterSliderView extends View {

    static final String TAG = CenterSliderView.class.getSimpleName();

    static final int MS_PER_FRAME = 16; // ~= 60 frames/sec
    // since most values are in float and converted to int, add/sub a rounding constant
    static final int BAR_ROUNDING_CONSTANT = 2;
    static final int DRAGGER_ROUNDING_CONSTANT = 8; // larger for more forgiveness

    static Handler sHandler = new Handler();

    enum AnimationType {
        DRAGGER,
        CENTER;
    }

    enum AnimationValue {
        DRAGGER_BEFORE,
        DRAGGER_AFTER,
        CENTER;
    }

    List<OnSliderListener> mListeners = new ArrayList<>();

    int mHeightOfView;
    // arbitrary width defined
    int mWidthOfView = 500;
    int mCenterAnimationDurationMs;
    // the colors for drawing
    int mBaseLineColor, mDarkColor, mTooltipTextColor, mBaseLineTextColor;

    // configuration of slider
    SliderInfo mSliderInfo;
    int mCurrentValue;
    int mAnimateStartValue;
    int mPluralRes;

    // UI
    LinearGradient mBaseLineGradient;
    LinearGradient mBaseLineTextGradient;
    Paint mBaseLinePaint;
    Paint mBaseLineTextPaint;
    Paint mTooltipTextPaint;

    // data arrays for point coordinates
    LineInfo mBaseLine;
    List<TickLineInfo> mTickLines = new ArrayList<>();

    int mHeightBuffer;
    // drawing dimensions calculated after measure pass
    int mBaselineHeight;
    int mSmallTickHeight;
    int mLargeTickHeight;
    float mTickTextYOffset;
    float mTickIntervalWidth; // pixels between each interval

    // there's no "reverse" behavior for animated vector drawable, so swap forwards/backwards
    AnimatedVectorDrawable mDraggerDrawable;
    AnimatedVectorDrawable mDraggerBeforeDrawable;
    AnimatedVectorDrawable mDraggerAfterDrawable;
    // Wonder if a queue is necessary...
    Queue<AnimationValue> mDraggerQueue = new LinkedList<>();
    @SuppressLint("UseSparseArrays")
    Map<AnimationType, AnimationValue> mRunningAnimations = new HashMap<>();
    // dragger dimensions (shouldn't change)
    int mDraggerWidth;
    int mDraggerHeight;
    Rect mDraggerBounds;
    Rect mMutableDraggerBounds;

    VectorDrawable mTooltipDrawable;
    int mTooltipWidthDiff;
    int mTooltipTextPadding;
    int mTooltipHeight;
    int mTooltipAboveDragger = 4;
    int mTooltipTextHeightOffset;
    Rect mTooltipBounds;

    // motion event details
    int mPointerId = -1;
    float mStartX; // starting x for move events
    int mScaledWindowTouchSlop;

    // Animation details
    float xBarDistance;
    float xDraggerDistance;
        // offset to add when drawing the bar during an animation
    float xBarDrawOffset;
    Interpolator mInterpolator;
    int mTimeMs = 0;
    boolean mIsAnimating = false;
    // single runnable to process queues and running set of animations
    Runnable mAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) {
                return;
            }

            boolean isDraggerRunning = handleDraggerAnimation();
            boolean isBaseLineRunning = handleCenterAnimation();
            boolean hasAnimationsRunning = isDraggerRunning || isBaseLineRunning;
            if (hasAnimationsRunning) {
                invalidate();
                sHandler.postDelayed(this, MS_PER_FRAME);
            } else {
                mIsAnimating = false;
            }
        }
    };

    public CenterSliderView(Context context) {
        this(context, null);
    }

    public CenterSliderView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CenterSliderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public CenterSliderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    private void init(Context c, @Nullable AttributeSet attrs) {
        Resources res = c.getResources();
        // set defaults
        int baseLineTextSize = res.getDimensionPixelSize(R.dimen.center_slider_view_base_line_text_size);
        mCenterAnimationDurationMs = res.getInteger(R.integer.center_slider_view_center_animation_ms);
        mDarkColor = Color.BLACK;
        mBaseLineColor = Color.WHITE;
        mBaseLineTextColor = Color.WHITE;
        mTooltipTextColor = Color.WHITE;
        mLargeTickHeight = res.getDimensionPixelSize(R.dimen.center_slider_view_tick_height_large);
        mSmallTickHeight = res.getDimensionPixelSize(R.dimen.center_slider_view_tick_height_small);
        mTooltipHeight = res.getDimensionPixelSize(R.dimen.center_slider_view_tooltip_height);
        int tooltipTextSize = res.getDimensionPixelSize(R.dimen.center_slider_view_tooltip_text_size);
        mPluralRes = R.plurals.center_slider_view_units;

        if (attrs != null) {
            TypedArray a = c.getTheme().obtainStyledAttributes(
                    attrs,
                    R.styleable.CenterSliderView,
                    0, 0);
            try {
                mBaseLineColor = a.getColor(R.styleable.CenterSliderView_baseLineColor, Color.WHITE);
                mBaseLineTextColor = a.getColor(R.styleable.CenterSliderView_baseLineTextColor, mBaseLineTextColor);
                baseLineTextSize = a.getDimensionPixelSize(R.styleable.CenterSliderView_baseLineTextSize, baseLineTextSize);
                mCenterAnimationDurationMs = a.getInteger(R.styleable.CenterSliderView_centerAnimationDuration, mCenterAnimationDurationMs);
                mDarkColor = a.getColor(R.styleable.CenterSliderView_paintColorFade, Color.BLACK);
                mLargeTickHeight = a.getDimensionPixelSize(R.styleable.CenterSliderView_tickHeightLarge, mLargeTickHeight);
                mSmallTickHeight = a.getDimensionPixelSize(R.styleable.CenterSliderView_tickHeightSmall, mSmallTickHeight);
                mTooltipTextColor = a.getColor(R.styleable.CenterSliderView_tooltipTextColor, mTooltipTextColor);
                tooltipTextSize = a.getDimensionPixelSize(R.styleable.CenterSliderView_tooltipTextSize, tooltipTextSize);
                mTooltipHeight = a.getDimensionPixelSize(R.styleable.CenterSliderView_tooltipHeight, mTooltipHeight);
                mPluralRes = a.getResourceId(R.styleable.CenterSliderView_unitReference, mPluralRes);
            } finally {
                a.recycle();
            }
        }

        mDraggerWidth = res.getDimensionPixelSize(R.dimen.center_slider_view_dragger_width);
        mDraggerHeight = res.getDimensionPixelSize(R.dimen.center_slider_view_dragger_height);
        mTooltipAboveDragger = res.getDimensionPixelSize(R.dimen.center_slider_view_tooltip_dragger_distance);
        int tickTextPadding = res.getDimensionPixelSize(R.dimen.center_slider_view_tick_text_padding);
        mTickTextYOffset = baseLineTextSize / 2 + tickTextPadding; // add half text size, b/c centered
        mTooltipTextPadding = res.getDimensionPixelOffset(R.dimen.center_slider_view_tooltip_text_padding);

        mBaseLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBaseLinePaint.setColor(mBaseLineColor);
        mBaseLinePaint.setStrokeWidth(2);
        mBaseLinePaint.setTextSize(baseLineTextSize);
        mBaseLinePaint.setTextAlign(Paint.Align.CENTER);

        mBaseLineTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBaseLineTextPaint.setColor(mBaseLineTextColor);
        mBaseLineTextPaint.setTextSize(baseLineTextSize);
        mBaseLineTextPaint.setTextAlign(Paint.Align.CENTER);

        mTooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTooltipTextPaint.setColor(mTooltipTextColor);
        mTooltipTextPaint.setTextSize(tooltipTextSize);
        mTooltipTextPaint.setTextAlign(Paint.Align.CENTER);
        // extra ascend/descend space +
        // offset for half the arrow height (48 height = 4 arrow height -> half means /24)
        mTooltipTextHeightOffset = (int) ((mTooltipTextPaint.descent() + mTooltipTextPaint.ascent()) / 2) + mTooltipHeight / 24;

        mInterpolator = new OvershootInterpolator(1f);

        // with the min/max, current, and intervalsToEdge we can evaluate our window of the slider
        mSliderInfo = new SliderInfo();
        mCurrentValue = mSliderInfo.mStartValue;

        mHeightBuffer = res.getDimensionPixelSize(R.dimen.center_slider_view_height_buffer);
        // add all the elements together + buffer
        mHeightOfView = mTooltipHeight + mTooltipAboveDragger +
                mDraggerHeight / 2 +
                Math.max(mLargeTickHeight + tickTextPadding + baseLineTextSize, mSmallTickHeight) +
                mHeightBuffer;

        mScaledWindowTouchSlop = ViewConfiguration.get(c).getScaledWindowTouchSlop();

        // may be worth exploring how an AnimatedStateListDrawable would work instead
        mDraggerBeforeDrawable =
                (AnimatedVectorDrawable) getContext().getDrawable(R.drawable.anim_dragger_before);
        mDraggerAfterDrawable =
                (AnimatedVectorDrawable) getContext().getDrawable(R.drawable.anim_dragger_after);
        mDraggerDrawable = mDraggerBeforeDrawable;

        mTooltipDrawable =
                (VectorDrawable) getContext().getDrawable(R.drawable.ic_tooltip);
    }

    /**
     * Call this to initialize the slider's data from Activity/Fragment
     * @param sliderInfo
     */
    public void setSliderInfo(SliderInfo sliderInfo) {
        this.mSliderInfo = sliderInfo;
        mCurrentValue = sliderInfo.mStartValue;
        int width = getMeasuredWidth();
        if (width > 0) {
            initializeTickLines(width);
        }
        // redraw all lines and reset
        requestLayout();
        mIsAnimating = false;
        mPointerId = -1;
    }

    private void initializeTickLines(int width) {
        mTickLines.clear();
        // add current tick line, then let setXOffset do the rest
        int value = mCurrentValue;
        PointF currentStart = new PointF(width / 2, mBaselineHeight);
        TickLineInfo current = createTickLineInfo(currentStart, value);
        mTickLines.add(current);

        setXOffset(0, true);
    }

    /**
     * Current value of this slider.
     * @return current value of slider
     */
    public int getCurrentValue() {
        return mCurrentValue;
    }

    /**
     * Returns current value with units of tooltip in slider
     * @return
     */
    public String getCurrentValueStringUnits() {
        return getContext().getResources().getQuantityString(mPluralRes, mCurrentValue, mCurrentValue);
    }

    public void addOnSliderListener(OnSliderListener listener) {
        mListeners.add(listener);
    }

    public void removeOnSliderListener(OnSliderListener listener) {
        mListeners.remove(listener);
    }

    private TickLineInfo getTickLineInfo(int value) {
        if (mTickLines == null || mTickLines.isEmpty()) {
            return null;
        }

        TickLineInfo first = mTickLines.get(0);
        if (first.value == value) {
            return first;
        }

        int diff = value - first.value;
        if (diff >= mTickLines.size()) {
            return null;
        }

        return mTickLines.get(diff);
    }

    /**
     * Measure view's dimensions
     * @param widthMeasureSpec
     * @param heightMeasureSpec
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int height = getPaddingTop() + getPaddingBottom();
        switch (heightMode) {
            case MeasureSpec.EXACTLY:
                height = heightSize;
                break;
            case MeasureSpec.AT_MOST:
                height += Math.min(heightSize, mHeightOfView);
                break;
            case MeasureSpec.UNSPECIFIED:
                height += mHeightOfView;
                break;
            default:
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
        }

        int width = getDefaultSize(mWidthOfView, widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) {
            return;
        }

        if (w != oldw || h != oldh || mTickLines == null || mTickLines.isEmpty()) {
            int above = mTooltipHeight
                    + mTooltipAboveDragger
                    + mDraggerHeight / 2
                    + getPaddingTop()
                    + mHeightBuffer / 2;
            int below = mHeightOfView - above + getPaddingBottom() + mHeightBuffer/2;
            if (below < h/2) {
                mBaselineHeight = Math.max(above, h/2);
            } else {
                mBaselineHeight = above;
            }

            // base line across - left and right paddings need to be equal
            PointF start = new PointF(getPaddingLeft(), mBaselineHeight);
            PointF end = new PointF(w-getPaddingRight(), mBaselineHeight);
            mBaseLine = new LineInfo(start, end);

            int totalIntervals = mSliderInfo.mIntervalsToEdge * 2;
            mTickIntervalWidth = Math.abs(mBaseLine.getXDiff()) / totalIntervals;

            initializeTickLines(w);

            // add/sub affordances to guarantee the proper color at the gradient points
            float edgeAffordance = 0.01f;
            float tickAffordance = edgeAffordance + 0.02f;

            float leftEdge = getPaddingLeft() / (float) w + edgeAffordance;
            float rightEdge = (w - getPaddingRight()) / (float) w - edgeAffordance;

            float leftStart = leftEdge + 1f / totalIntervals - tickAffordance;
            float rightEnd = (totalIntervals - 1f) / totalIntervals - leftEdge + tickAffordance;
            float[] gradientPoints = new float[]{0f, leftEdge, leftStart, rightEnd, rightEdge, 1f};
            mBaseLineGradient = new LinearGradient(0, mBaselineHeight, w, mBaselineHeight,
                    new int[]{mDarkColor, mDarkColor,
                            mBaseLineColor, mBaseLineColor,
                            mDarkColor, mDarkColor},
                    gradientPoints,
                    Shader.TileMode.CLAMP);

            mBaseLineTextGradient = new LinearGradient(0, mBaselineHeight, w, mBaselineHeight,
                    new int[]{mDarkColor, mDarkColor,
                            mBaseLineTextColor, mBaseLineTextColor,
                            mDarkColor, mDarkColor},
                    gradientPoints,
                    Shader.TileMode.CLAMP);

            mBaseLinePaint.setShader(mBaseLineGradient);
            mBaseLineTextPaint.setShader(mBaseLineTextGradient);

            mDraggerBounds =
                    new Rect(w / 2 - mDraggerWidth / 2,  // left
                            mBaselineHeight - mDraggerHeight / 2,  // top
                            w / 2 + mDraggerWidth / 2,   // right
                            mBaselineHeight + mDraggerHeight / 2); // bottom
            mMutableDraggerBounds = new Rect(mDraggerBounds);
            mDraggerBeforeDrawable.setBounds(mMutableDraggerBounds);
            mDraggerAfterDrawable.setBounds(mMutableDraggerBounds);

            int halfWidth =
                    (int) (mTooltipTextPaint.measureText(getCurrentValueStringUnits()) / 2) + mTooltipTextPadding;
            mTooltipBounds =
                    new Rect(w / 2 - halfWidth, // l
                            mDraggerBounds.top - mTooltipHeight - mTooltipAboveDragger, // t
                            w / 2 + halfWidth, // r
                            mDraggerBounds.top - mTooltipAboveDragger); // b
            mTooltipDrawable.setBounds(mTooltipBounds);

            // precalculate this, so we don't have to do math every time in onDraw
            mTooltipWidthDiff = mTooltipBounds.left - mDraggerBounds.left;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // terminate early if we haven't configured the baseline.
        if (mBaseLine == null) {
            return;
        }

        float start = mBaseLine.pointStart.x;
        float end = mBaseLine.pointEnd.x;
        // draw tick lines (and text below)
        if (mTickLines != null) {
            for (TickLineInfo tickLine : mTickLines) {
                if (tickLine.value == mSliderInfo.mMinValue) {
                    start = tickLine.pointStart.x + xBarDrawOffset;
                } else if (tickLine.value == mSliderInfo.mMaxValue) {
                    end = tickLine.pointEnd.x + xBarDrawOffset;
                }
                canvas.drawLine(
                        tickLine.pointStart.x + xBarDrawOffset,
                        tickLine.pointStart.y,
                        tickLine.pointEnd.x + xBarDrawOffset,
                        tickLine.pointEnd.y,
                        mBaseLinePaint);
                if (tickLine.text != null) {
                    canvas.drawText(
                            tickLine.text,
                            tickLine.pointStart.x + xBarDrawOffset,
                            tickLine.pointEnd.y + mTickTextYOffset,
                            mBaseLineTextPaint);
                }
            }
        }

        // Draw baseline
        canvas.drawLine(
                start,
                mBaseLine.pointStart.y,
                end,
                mBaseLine.pointEnd.y,
                mBaseLinePaint);

        // draw slider button
        mDraggerDrawable.draw(canvas);

        // draw tooltip
        mTooltipDrawable.draw(canvas);
        String text = getCurrentValueStringUnits();
        canvas.drawText(text,
                mTooltipBounds.centerX(),
                mTooltipBounds.centerY() - mTooltipTextHeightOffset,
                mTooltipTextPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();

        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // don't allow multiple down events or "fast" moving animations
                if (this.mPointerId != -1 || mIsAnimating) {
                    return false;
                }
                mStartX = event.getX(pointerIndex);
                float startY = event.getY(pointerIndex);
                int left = Math.max(mDraggerBounds.left, mTooltipBounds.left);
                int right = Math.min(mDraggerBounds.right, mTooltipBounds.right);
                if (mStartX + mScaledWindowTouchSlop > left &&
                        mStartX - mScaledWindowTouchSlop < right &&
                        startY + mScaledWindowTouchSlop > mTooltipBounds.top &&
                        startY - mScaledWindowTouchSlop < mDraggerBounds.bottom) {
                    mAnimateStartValue = mCurrentValue;
                    this.mPointerId = pointerId;
                    queueDraggerAnimation(true);
                    invalidate();
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (pointerId == this.mPointerId) {
                    // positive vector
                    float move = event.getX(pointerIndex) - mStartX;

                    float xPos = mDraggerBounds.centerX() + (int) move;
                    TickLineInfo firstTick = mTickLines.get(0);
                    TickLineInfo lastTick = mTickLines.get(mTickLines.size()-1);

                    float max = lastTick.pointStart.x;
                    float min = firstTick.pointEnd.x;
                    if (xPos <= min) {
                        xPos = min;
                    } else if (xPos >= max) {
                        xPos = max;
                    }

                    // need to floor/ceiling around animateStartValue based on move
                    float intervals;
                    if (move > 0) {
                        intervals = (xPos - getPaddingLeft() + DRAGGER_ROUNDING_CONSTANT)/ mTickIntervalWidth;
                        mCurrentValue =
                                mAnimateStartValue
                                        + (int) Math.floor(intervals)
                                        - mSliderInfo.mIntervalsToEdge;
                    } else {
                        intervals = (xPos - getPaddingLeft() - DRAGGER_ROUNDING_CONSTANT)/ mTickIntervalWidth;
                        mCurrentValue =
                                mAnimateStartValue
                                        + (int) Math.ceil(intervals)
                                        - mSliderInfo.mIntervalsToEdge;
                    }

                    int draggerOffset = (int) xPos - mDraggerBounds.width()/2;
                    mMutableDraggerBounds.offsetTo(draggerOffset, mDraggerBounds.top);
                    mDraggerDrawable.setBounds(mMutableDraggerBounds);

                    mTooltipBounds.offsetTo(draggerOffset + mTooltipWidthDiff, mTooltipBounds.top);
                    mTooltipDrawable.setBounds(mTooltipBounds);
                    invalidate();
                }

                break;

            case MotionEvent.ACTION_UP:
                // reset
                if (pointerId == this.mPointerId) {
                    for (OnSliderListener listener : mListeners) {
                        listener.onValueSelected(mCurrentValue);
                    }
                    queueDraggerAnimation(false);
                    queueCenterAnimation();
                    invalidate();
                    this.mPointerId = -1;
                }
                break;
        }

        // eat motion events
        return true;
    }

    /**
     * Whenever we need to move the bar, use this to offset the x position.
     *
     * In addition to setting the offset, it will add/remove the appropriate lines
     * to the {@link #mTickLines} list as they enter/leave the screen
     * @param offset
     */
    private void setXOffset(float offset, boolean forceTickMeasure) {
        TickLineInfo first = mTickLines.get(0);
        TickLineInfo last = mTickLines.get(mTickLines.size()-1);

        float halfWidth = getMeasuredWidth()/2;
        boolean matchStart = first.value == mSliderInfo.mMinValue;
        if (!forceTickMeasure &&
                ((matchStart && first.pointStart.x + offset >= halfWidth) ||
                 last.value == mSliderInfo.mMaxValue && last.pointEnd.x + offset <= halfWidth)) {
            // clamp current offset or return if current matches min/max
            float xPos = matchStart ? first.pointStart.x : last.pointEnd.x;
            float clamp = halfWidth - xPos;
            if (this.xBarDrawOffset == clamp) {
                return;
            }

            this.xBarDrawOffset = clamp;
        } else {
            this.xBarDrawOffset = offset;
        }

        // line parameters - end and text handled by createTickLineInfo()
        PointF start;
        int value;

        // check left side (pre) first
        float firstPos = first.pointStart.x + offset;
        float prePos = firstPos - mTickIntervalWidth;
        value = first.value - 1;
        // remove first if item pushed off screen
        if (firstPos < mBaseLine.pointStart.x) {
            mTickLines.remove(0);
        } else if (prePos > mBaseLine.pointStart.x) {
            // account for moving more frames than a single tickIntervalWidth
            while (prePos - BAR_ROUNDING_CONSTANT > mBaseLine.pointStart.x &&
                    value >= mSliderInfo.mMinValue) {
                // logically: x = first point - tickIntervalWidth
                start = new PointF(prePos - offset, mBaselineHeight);
                TickLineInfo addLeft = createTickLineInfo(start, value);
                mTickLines.add(0, addLeft);
                prePos -= mTickIntervalWidth;
                value--;
            }
        }

        // check right side (post) next
        float lastPos = last.pointStart.x + offset;
        float postPos = lastPos + mTickIntervalWidth;
        value = last.value + 1;
        // remove last if item pushed off screen
        if (lastPos > mBaseLine.pointEnd.x && mTickLines.size() > 0) {
            mTickLines.remove(mTickLines.size() - 1);
        } else if (postPos < mBaseLine.pointEnd.x) {
            // account for moving more frames than a single tickIntervalWidth
            while (postPos + BAR_ROUNDING_CONSTANT < mBaseLine.pointEnd.x &&
                    value <= mSliderInfo.mMaxValue) {
                start = new PointF(postPos - offset, mBaselineHeight);
                TickLineInfo addRight = createTickLineInfo(start, value);
                mTickLines.add(addRight);
                postPos += mTickIntervalWidth;
                value++;
            }
        }
    }

    private TickLineInfo createTickLineInfo(PointF start, int value) {
        PointF end;
        String text = null;
        int diff = value - mSliderInfo.mStartValue;
        // check if this should have a large tick
        if (Math.abs(diff) % mSliderInfo.mLargeTickInterval == 0) {
            end = new PointF(start.x, mBaselineHeight + mLargeTickHeight);
            if (mSliderInfo.mTextOverrides.get(value) != null) {
                text = mSliderInfo.mTextOverrides.get(value);
            } else {
                // locale shouldn't matter for formatting a number
                text = String.format(Locale.getDefault(), "%+d", diff);
            }
        } else {
            end = new PointF(start.x, mBaselineHeight + mSmallTickHeight);
        }

        TickLineInfo lineInfo = new TickLineInfo(start, end);
        if (text != null) {
            lineInfo.text = text;
        }
        lineInfo.value = value;
        return lineInfo;
    }

    /**
     * After a move or animation completes, we need to normalize the view's data required to reset
     * the xBarDrawOffset to 0.
     */
    private void normalizeXOffset() {
        if (mTickLines == null || mTickLines.size() == 0) {
            xBarDrawOffset = 0;
            return;
        }

        for (int i = mTickLines.size()-1; i >= 0; i--) {
            TickLineInfo tickLine = mTickLines.get(i);
            tickLine.pointStart.x += xBarDrawOffset;
            tickLine.pointEnd.x += xBarDrawOffset;
            if (tickLine.pointStart.x - BAR_ROUNDING_CONSTANT < mBaseLine.pointStart.x ||
                    tickLine.pointEnd.x + BAR_ROUNDING_CONSTANT > mBaseLine.pointEnd.x) {
                mTickLines.remove(i);
            }
        }

        xBarDrawOffset = 0;
    }

    //
    // Animation methods
    //

    private void queueDraggerAnimation(boolean start) {
        AnimationValue animationValue = start ?
                AnimationValue.DRAGGER_BEFORE : AnimationValue.DRAGGER_AFTER;
        AnimationValue queueValue = mDraggerQueue.peek();
        if (queueValue != null && queueValue == animationValue) {
            // already queued
            return;
        }

        mDraggerQueue.add(animationValue);

        if (!mIsAnimating) {
            mIsAnimating = true;
            sHandler.postDelayed(mAnimationRunnable, MS_PER_FRAME);
        }
    }

    /**
     * @return true if dragger has animation running
     */
    private boolean handleDraggerAnimation() {
        AnimationValue dragAnimation = mRunningAnimations.get(AnimationType.DRAGGER);
        if (dragAnimation != null) {
            if (!mDraggerDrawable.isRunning()) {
                mDraggerDrawable.reset();

                // replace dragger drawable
                if (dragAnimation == AnimationValue.DRAGGER_BEFORE) {
                    mDraggerDrawable = mDraggerAfterDrawable;
                } else {
                    mDraggerDrawable = mDraggerBeforeDrawable;
                }

                // not running anymore
                mRunningAnimations.remove(AnimationType.DRAGGER);
                dragAnimation = null;
            }
        }

        // no drag happening, see if we have a new one to run
        if (dragAnimation == null) {
            dragAnimation = mDraggerQueue.peek();
            if (dragAnimation != null) {
                if (dragAnimation == AnimationValue.DRAGGER_BEFORE) {
                    mDraggerDrawable = mDraggerBeforeDrawable;
                } else {
                    mDraggerDrawable = mDraggerAfterDrawable;
                }
                mRunningAnimations.put(AnimationType.DRAGGER, dragAnimation);
                mDraggerDrawable.start();
                mDraggerQueue.poll();
            }
        }
        return dragAnimation != null;
    }

    /**
     * Adds animation (if required) for ticks/baseline
     */
    private void queueCenterAnimation() {
        if (mCurrentValue != mAnimateStartValue) {
            TickLineInfo startTick = getTickLineInfo(mAnimateStartValue);
            TickLineInfo currentTick = getTickLineInfo(mCurrentValue);
            if (startTick == null || currentTick == null) {
                return;
            }

            xBarDistance = startTick.pointStart.x - currentTick.pointStart.x;
        } else {
            xBarDistance = 0;
        }

        if (mMutableDraggerBounds.left != mDraggerBounds.left) {
            // just recenter this
            xDraggerDistance = mMutableDraggerBounds.left - mDraggerBounds.left;
        } else {
            xDraggerDistance = 0;
        }

        // do nothing if no displacement
        if (xBarDistance == 0 && xDraggerDistance == 0) {
            Log.d(TAG, "no need to run center animation");
            return;
        }

        // TODO - need a queue?
        mRunningAnimations.put(AnimationType.CENTER, AnimationValue.CENTER);

        if (!mIsAnimating) {
            mIsAnimating = true;
            sHandler.postDelayed(mAnimationRunnable, MS_PER_FRAME);
        }
    }

    /**
     * Horizontal animation to offset ticks and baseline
     * @return true if has baseline animation running
     */
    private boolean handleCenterAnimation() {
        AnimationValue baseAnimation = mRunningAnimations.get(AnimationType.CENTER);
        if (baseAnimation != null) {
            if (mTimeMs > mCenterAnimationDurationMs &&
                    mTimeMs % mCenterAnimationDurationMs > MS_PER_FRAME) {
                normalizeXOffset();
                mMutableDraggerBounds.left = mDraggerBounds.left;
                mMutableDraggerBounds.right = mDraggerBounds.right;

                mRunningAnimations.remove(AnimationType.CENTER);
                mTimeMs = 0;
                xBarDistance = 0;
                xDraggerDistance = 0;
            } else {
                // ensure that the end is reached
                float interpolationTime = mTimeMs > mCenterAnimationDurationMs ?
                        1 : (float) mTimeMs/mCenterAnimationDurationMs;
                // offset based on interpolation
                float interpolation = mInterpolator.getInterpolation(interpolationTime);
                // baseline + ticks
                setXOffset(xBarDistance * interpolation, false);

                // dragger
                int left = (int) (mDraggerBounds.left + (xDraggerDistance * (1 - interpolation)));
                mMutableDraggerBounds.offsetTo(left, mMutableDraggerBounds.top);
                mDraggerDrawable.setBounds(mMutableDraggerBounds);

                mTooltipBounds.offsetTo(left + mTooltipWidthDiff, mTooltipBounds.top);
                mTooltipDrawable.setBounds(mTooltipBounds);

                mTimeMs += MS_PER_FRAME;
            }
        }

        return baseAnimation != null;
    }

    //
    // Various Data classes
    //

    static class LineInfo {
        // line coordinates
        public final PointF pointStart, pointEnd;

        public LineInfo(PointF start, PointF end) {
            pointStart = start;
            pointEnd = end;
        }

        /**
         * Difference in x coordinates (end - start)
         */
        public float getXDiff() {
            return pointEnd.x - pointStart.x;
        }

        @Override
        public String toString() {
            return "LineInfo{" +
                    "pointStart=" + pointStart +
                    ", pointEnd=" + pointEnd +
                    '}';
        }
    }

    static class TickLineInfo extends LineInfo {
        public int value;
        public String text;

        public TickLineInfo(PointF start, PointF end) {
            super(start, end);
        }

        @Override
        public String toString() {
            return "TickLineInfo{" +
                    "value=" + value +
                    ", text='" + text + '\'' +
                    "} " + super.toString();
        }
    }

    // public facing classes/interfaces

    /**
     * Slider Info to configure the slider; presently achieved
     * via {@link CenterSliderView#setSliderInfo(SliderInfo)}
     */
    public static class SliderInfo {
        int mMinValue, mMaxValue, mStartValue;
        int mIntervalsToEdge; // number of "spaces" to edge
        int mLargeTickInterval;
        @SuppressLint("UseSparseArrays")
        Map<Integer, String> mTextOverrides = new HashMap<>(); // raw value to override with string

        // define defaults
        SliderInfo() {
            mMinValue = 0;
            mMaxValue = 60;
            mStartValue = mMaxValue/2;
            mIntervalsToEdge = 6;   // gaps to reach the edge
            mLargeTickInterval = 5; // every Xth tick will be a "large" tick
        }

        /**
         * Builder for constructing SliderInfo. If set* not called, defaults will be used instead.
         */
        public static class Builder {
            SliderInfo mInfo;

            public Builder() {
                mInfo = new SliderInfo();
            }

            public Builder setBounds(int min, int max) {
                mInfo.mMinValue = min;
                mInfo.mMaxValue = max;
                return this;
            }

            public Builder setStartValue(int startValue) {
                mInfo.mStartValue = startValue;
                return this;
            }

            public Builder setIntervalsToEdge(int intervalsToEdge) {
                mInfo.mIntervalsToEdge = intervalsToEdge;
                return this;
            }

            public Builder setLargeTickInterval(int interval) {
                mInfo.mLargeTickInterval = interval;
                return this;
            }

            public Builder setValueTextOverride(int value, String textOverride) {
                mInfo.mTextOverrides.put(value, textOverride);
                return this;
            }

            public SliderInfo build() {
                return mInfo;
            }
        }

        @Override
        public String toString() {
            return "SliderInfo{" +
                    "mMinValue=" + mMinValue +
                    ", mMaxValue=" + mMaxValue +
                    ", mStartValue=" + mStartValue +
                    ", mIntervalsToEdge=" + mIntervalsToEdge +
                    ", mLargeTickInterval=" + mLargeTickInterval +
                    ", mTextOverrides=" + mTextOverrides +
                    '}';
        }
    }

    /**
     * Interface for listening to slider events
     */
    public interface OnSliderListener {
        void onValueSelected(int newValue);
    }
}
