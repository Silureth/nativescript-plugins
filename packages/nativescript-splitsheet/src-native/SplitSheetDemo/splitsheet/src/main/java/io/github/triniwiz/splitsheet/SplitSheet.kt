package io.github.triniwiz.splitsheet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.util.TypedValue
import android.view.*
import android.view.ViewGroup.LayoutParams
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.Behavior.DragCallback
import com.google.android.material.appbar.AppBarLayout.OnOffsetChangedListener
import com.google.android.material.appbar.CollapsingToolbarLayout
import io.github.triniwiz.splitsheet.databinding.SplitsheetBinding
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong


class SplitSheet @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var mainView: View? = null
    var sheetView: View? = null

    /// If true, `mainViewController` will shift up as the sheet is shown.
    var displaceContent = true
        set(value) {
            field = value
            val params = binding.mainContainer.layoutParams as CollapsingToolbarLayout.LayoutParams
            if (value) {
                params.collapseMode = CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_OFF
                params.parallaxMultiplier = 1F
            } else {

                params.collapseMode = CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PARALLAX
                params.parallaxMultiplier = 0.5F
            }
            binding.mainContainer.layoutParams = params
        }

    /// Show a grabber handle.
    var showHandle = true
        set(value) {
            field = value
            if (field) {
                binding.handleView.visibility = View.VISIBLE
            } else {
                binding.handleView.visibility = View.INVISIBLE
            }
        }

    /// The minimum sheet height.
    var minimumSheetHeight = 400F

    /// Enforce a sheet height which always shows when in "not showing" state
    var closedSheetHeight = 90F

    /// When the sheet is shown and dragged within this limit, the sheet will bounce back.
    var snappingDistance = 150F

    /// How long the show/hide animation takes.
    var animationDuration = 0.4F
        set(value) {
            field = value
            val toolbar = binding.appbar.getChildAt(0) as CollapsingToolbarLayout
            toolbar.scrimAnimationDuration = (value * 1000).roundToLong()
        }

    /// If swiping up to show the sheet is allowed or not.
    var swipeUpToShowAllowed = true

    private var mScrollable: Boolean = true


    private val disabledBehaviour = object : DragCallback() {
        override fun canDrag(appBarLayout: AppBarLayout): Boolean {
            return false
        }
    }


    var isScrollEnabled = true
        set(value) {
            field = value
            /*
            val toolbar = binding.appbar.getChildAt(0) as CollapsingToolbarLayout
            toolbar.updateLayoutParams<AppBarLayout.LayoutParams> {
                scrollFlags = if (value) {
                    AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP or AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED
                } else {
                    AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                }
            }
             */
        }

    private var binding: SplitsheetBinding

    var showing = false
        private set


    // https://m2.material.io/design/environment/elevation.html 4dp
    var mainViewElevation = true
        set(value) {
            field = value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ViewCompat.setElevation(binding.appbar, 0F)
                if (value) {
                    binding.appbar.targetElevation = toPx(4).toFloat()
                } else {
                    binding.appbar.targetElevation = 0F
                }
            }
        }

    private var initialAppBarBackground: Drawable

    var mainViewBackgroundColor: Int? = null
        set(value) {
            field = value
            if (value == null) {
                binding.appbar.background = initialAppBarBackground
            } else {
                binding.appbar.background = ColorDrawable(value)
            }
        }

    private var state = Detents.Hidden

    private fun setState(state: Detents) {
        this.state = state
    }

    interface Events {
        fun event(name: String, value: Any?)
    }

    var eventListener: Events? = null

    private val metrics = resources.displayMetrics

    private fun toPx(dip: Float): Float {
        return dip * metrics.density
    }

    private fun toPx(dip: Int): Int {
        return (dip * metrics.density).roundToInt()
    }

    private fun toDip(px: Float): Float {
        return px / metrics.density
    }

    private fun getScrollableLength(): Int {
        return binding.root.height
    }

    private enum class Detents {
        Hidden,
        Shown,
        Expanded
    }

    private val detents =
        arrayOf(Pair(0F, Detents.Hidden), Pair(0F, Detents.Hidden), Pair(0F, Detents.Hidden))

    init {
        val inflator = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        binding = SplitsheetBinding.inflate(inflator, this, false)
        addView(binding.root)
        initialAppBarBackground = binding.appbar.background


        binding.appbar.addOnOffsetChangedListener(object : OnOffsetChangedListener {
            private var didEmit = false
            private var didInit = false
            override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
                if (verticalOffset == 0) {
                    if (showing) {
                        showing = false
                        eventListener?.event("showing", false)
                    }
                    if (didDrag) {
                        didDrag = false
                        didEmit = false
                        handler.postDelayed({
                            eventListener?.event("endDrag", null)
                        }, 40L)
                    }

                } else if (abs(verticalOffset) >= appBarLayout.totalScrollRange) {
                    if (!showing) {
                        showing = true
                        eventListener?.event("showing", true)
                    }

                    if (didDrag) {
                        didDrag = false
                        didEmit = false
                        handler.postDelayed({
                            eventListener?.event("endDrag", null)
                        }, 40L)
                    }
                } else {
                    if (isTouch && isDragging && !didEmit) {
                        didEmit = true
                        eventListener?.event("beginDrag", null)
                    }
                }
            }
        })
    }

    private var lastEvent = MotionEvent.ACTION_UP
    private var didDrag = false

    private var isDragging = false
    private var isTouch = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {

        if (!isScrollEnabled) {
            mainView?.dispatchTouchEvent(event)
            sheetView?.dispatchTouchEvent(event)
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastEvent = MotionEvent.ACTION_DOWN
                isTouch = true
            }
            MotionEvent.ACTION_MOVE -> {

                if (lastEvent == MotionEvent.ACTION_DOWN) {
                    lastEvent = MotionEvent.ACTION_MOVE
                    isDragging = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {

                if (lastEvent == MotionEvent.ACTION_MOVE) {
                    didDrag = true
                    isTouch = false
                }

                lastEvent = event.action
            }
        }

        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val h = MeasureSpec.getSize(heightMeasureSpec)

        val height =
            measuredHeight //MeasureSpec.makeMeasureSpec(heightMeasureSpec, MeasureSpec.EXACTLY)
        val toolbar = binding.appbar.getChildAt(0) as CollapsingToolbarLayout

        toolbar.minimumHeight =
            (height - toPx(minimumSheetHeight)).toInt() //(height - toPx(closedSheetHeight)).toInt()
        binding.sheetView.minimumHeight = toPx(minimumSheetHeight).roundToInt()

        binding.appbar.measure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(
                height - toPx(closedSheetHeight).roundToInt(),
                MeasureSpec.EXACTLY
            )
        )
    }

    fun setup(
        mainView: View,
        sheetView: View
    ) {
        this.mainView = mainView
        this.sheetView = sheetView
        setup()
    }

    private fun setup() {
        updateShowing(false)


        mainView?.let {
            binding.mainContainer.addView(mainView)
        }


        sheetView?.let {
            // it, -1, -2
            binding.sheetView.addView(it)
        }

        //     binding.sheetView.isVerticalScrollBarEnabled = false
        // overScrollMode = OVER_SCROLL_ALWAYS
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (binding.root == child) {
            super.addView(child, index, params)
            return
        }
        val lp = child.layoutParams as? LayoutParams ?: run {
            LayoutParams(child.layoutParams ?: generateDefaultLayoutParams())
        }

        val position = lp.position

        if (mainView == null && position == Position.Undefined) {
            mainView = child
            return
        }

        if (sheetView == null && position == Position.Undefined) {
            sheetView = child
            setup()
            return
        }

    }

    enum class Position(internal val value: Int) {
        Undefined(-1),
        Top(0),
        Bottom(1);

        companion object {
            internal fun fromInt(value: Int): Position? {
                return when (value) {
                    -1 -> Undefined
                    0 -> Top
                    1 -> Bottom
                    else -> null
                }
            }

            internal fun fromIntOrDefault(value: Int?, def: Position): Position {
                return fromInt(value ?: -2) ?: def
            }
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams {
        return LayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams {
        return LayoutParams(p)
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean {
        return p is LayoutParams
    }

    class LayoutParams : FrameLayout.LayoutParams {
        internal var numericAttributes = SparseArray<Float>()
        internal var stringAttributes = SparseArray<String>()


        constructor(width: Int, height: Int) : super(width, height) {
            numericAttributes.append(R.styleable.SplitSheet_Layout_sheet_position, -1F)
        }

        constructor(source: ViewGroup.LayoutParams) : super(source) {
            if (source is LayoutParams) {
                numericAttributes = source.numericAttributes.clone()
                stringAttributes = source.stringAttributes.clone()
            } else {
                numericAttributes.append(R.styleable.SplitSheet_Layout_sheet_position, -1F)
            }
        }

        constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.SplitSheet_Layout)

            numericAttributes.append(R.styleable.SplitSheet_Layout_sheet_position, -1F)

            val attributeCount = a.indexCount
            for (i in 0 until attributeCount) {
                val attribute = a.getIndex(i)
                val typedValue = TypedValue()
                a.getValue(attribute, typedValue)
                when (typedValue.type) {
                    TypedValue.TYPE_DIMENSION -> {
                        numericAttributes.put(
                            attribute, a.getDimensionPixelSize(attribute, 0).toFloat()
                        )
                    }
                    TypedValue.TYPE_STRING -> {
                        stringAttributes.put(attribute, a.getString(attribute))
                    }
                    else -> {
                        numericAttributes.put(attribute, a.getFloat(attribute, 0f))
                    }
                }
            }

            a.recycle()
        }

        var position: Position
            get() {
                return Position.fromIntOrDefault(
                    numericAttributes.get(R.styleable.SplitSheet_Layout_sheet_position)
                        .roundToInt(),
                    Position.Undefined
                )
            }
            set(value) {
                numericAttributes.put(
                    R.styleable.SplitSheet_Layout_sheet_position,
                    value.value.toFloat()
                )
            }
    }

    private fun scroll(amount: Int) {
        val params = binding.appbar.layoutParams as CoordinatorLayout.LayoutParams
        val behavior = params.behavior as AppBarLayout.Behavior?
        if (behavior != null) {

            val valueAnimator = ValueAnimator.ofInt()
            valueAnimator.interpolator = DecelerateInterpolator()
            valueAnimator.addUpdateListener { animation ->
                behavior.topAndBottomOffset = (animation.animatedValue as Int)
                binding.appbar.requestLayout()
            }
            valueAnimator.doOnEnd {
                val showing = amount == toPx(-minimumSheetHeight).roundToInt()
                this.showing = showing
                eventListener?.event("showing", showing)
            }
            valueAnimator.setIntValues(behavior.topAndBottomOffset, amount)
            valueAnimator.duration = (animationDuration * 1000).roundToLong()
            valueAnimator.start()
        }
    }

    fun show(shouldShow: Boolean) {
        if (shouldShow == this.showing) {
            return
        }
        this.showing = shouldShow

        val scrollAmount = if (shouldShow) {
            toPx(minimumSheetHeight)
        } else {
            toPx(closedSheetHeight)
        }.roundToInt()

        scroll(-scrollAmount)

        //   binding.scrollView.smoothScrollTo(0, 300)
        //  binding.appbar.setExpanded(!shouldShow)

        /*
        if (shouldShow && state == Detents.Shown || !shouldShow && state == Detents.Hidden) {
            return
        }
        animation?.cancel()
        animation = null

        val interpolator = this.interpolator
        val minimumSheetHeight = toPx(minimumSheetHeight).roundToInt()
        val closedSheetHeight = toPx(closedSheetHeight).roundToInt()

        val toSize = if (shouldShow) {
            minimumSheetHeight
        } else {
            closedSheetHeight
        }

        val scrollAnimation = ObjectAnimator.ofInt(this, "scrollY", toSize)
            .apply {
                addUpdateListener {
                    val value = (it.animatedValue as Int)
                    mainContainerView.translationY = value.toFloat()
                    sheetContainerView.translationY = value.toFloat()
                }
            }

        val mainContainerViewHeight = mainContainerView.height
        val newHeight = height - toSize
        val heightAnimation = ObjectAnimator.ofInt(
            mainContainerViewHeight,
            newHeight
        ).apply {
            addUpdateListener {
                val value = (it.animatedValue as Int)
                val params = mainContainerView.layoutParams
                params.height = value
                mainContainerView.layoutParams = params
            }
        }

        animation = AnimatorSet()
            .apply {
                playTogether(scrollAnimation, heightAnimation)
                this.interpolator = interpolator
                duration = (animationDuration * 1000).roundToLong()
                doOnEnd {
                    showing = shouldShow
                    state = if (shouldShow) Detents.Shown else Detents.Hidden
                    eventListener?.event("showing", showing)

                }
            }


        animation?.start()

        */

    }

    private fun updateShowing(showing: Boolean) {
        this.showing = showing

        eventListener?.event("showing", showing)

        /// If `swipeUpToShowAllowed` is not enabled, prevent scrolling up when hidden.
        if (!swipeUpToShowAllowed) {
            mScrollable = showing
        }

        if (showHandle) {
            binding.handleView.visibility = View.VISIBLE
        } else {
            binding.handleView.visibility = View.INVISIBLE
        }
    }
}