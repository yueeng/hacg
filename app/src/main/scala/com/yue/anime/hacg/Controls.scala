package com.yue.anime.hacg

import java.util

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Paint.{Join, Style}
import android.graphics._
import android.graphics.drawable.{BitmapDrawable, ColorDrawable, Drawable}
import android.os.Build
import android.support.v7.widget.RecyclerView.State
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.text.TextPaint
import android.text.style.LineBackgroundSpan
import android.util.{AttributeSet, Pair}
import android.view.View.MeasureSpec
import android.view.{View, ViewGroup}
import android.widget.{ListView, TextView}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

class UnScrollListView(context: Context, attrs: AttributeSet, defStyle: Int) extends ListView(context, attrs, defStyle) {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)

  def this(context: Context) = this(context, null)

  override def onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int): Unit = {
    val expandSpec = MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE >> 2, MeasureSpec.AT_MOST)
    super.onMeasure(widthMeasureSpec, expandSpec)
  }
}

/**
 * UnScrolledLinearLayoutManager
 * Created by Rain on 2015/5/16.
 */
class UnScrolledLinearLayoutManager(context: Context) extends LinearLayoutManager(context) {
  private val mMeasuredDimension: Array[Int] = new Array[Int](2)

  override def onMeasure(recycler: RecyclerView#Recycler, state: State, widthSpec: Int, heightSpec: Int): Unit = {
    super.onMeasure(recycler, state, widthSpec, heightSpec)
    val widthMode: Int = View.MeasureSpec.getMode(widthSpec)
    val heightMode: Int = View.MeasureSpec.getMode(heightSpec)
    val widthSize: Int = View.MeasureSpec.getSize(widthSpec)
    val heightSize: Int = View.MeasureSpec.getSize(heightSpec)
    var width: Int = 0
    var height: Int = 0

    for (i <- 0 until getItemCount) {
      measureScrapChild(recycler, i, View.MeasureSpec.makeMeasureSpec(i, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(i, View.MeasureSpec.UNSPECIFIED), mMeasuredDimension)

      if (getOrientation == LinearLayoutManager.HORIZONTAL) {
        width = width + mMeasuredDimension(0)
        if (i == 0) {
          height = mMeasuredDimension(1)
        }
      }
      else {
        height = height + mMeasuredDimension(1)
        if (i == 0) {
          width = mMeasuredDimension(0)
        }
      }
    }
    widthMode match {
      case View.MeasureSpec.EXACTLY =>
        width = widthSize
      case View.MeasureSpec.AT_MOST =>
      case View.MeasureSpec.UNSPECIFIED =>
    }

    heightMode match {
      case View.MeasureSpec.EXACTLY =>
        height = heightSize
      case View.MeasureSpec.AT_MOST =>
      case View.MeasureSpec.UNSPECIFIED =>
    }

    setMeasuredDimension(width, height)
  }

  private def measureScrapChild(recycler: RecyclerView#Recycler, position: Int, widthSpec: Int, heightSpec: Int, measuredDimension: Array[Int]) = {
    val view: View = recycler.getViewForPosition(position)
    if (view != null) {
      val p: RecyclerView.LayoutParams = view.getLayoutParams.asInstanceOf[RecyclerView.LayoutParams]
      val childWidthSpec: Int = ViewGroup.getChildMeasureSpec(widthSpec, getPaddingLeft + getPaddingRight, p.width)
      val childHeightSpec: Int = ViewGroup.getChildMeasureSpec(heightSpec, getPaddingTop + getPaddingBottom, p.height)
      view.measure(childWidthSpec, childHeightSpec)
      measuredDimension(0) = view.getMeasuredWidth + p.leftMargin + p.rightMargin
      measuredDimension(1) = view.getMeasuredHeight + p.bottomMargin + p.topMargin
      recycler.recycleView(view)
    }
  }
}

class RectBackgroundColorSpan(val backgroundColor: Int) extends LineBackgroundSpan {

  private val TOP: String = "top"
  private val LEFT: String = "left"
  private val BOTTOM: String = "bottom"
  private val RIGHT: String = "right"
  private val RADIUS_X: String = "radius-x"
  private val RADIUS_Y: String = "radius-y"

  private val _map = scala.collection.mutable.Map(TOP -> 0, LEFT -> 0, BOTTOM -> 0, RIGHT -> 0, RADIUS_X -> 0, RADIUS_Y -> 0)
  private val _rect = new RectF()

  def padding(left: Int, top: Int, right: Int, bottom: Int) = {
    _map ++= Map(LEFT -> left, TOP -> top, RIGHT -> right, BOTTOM -> bottom)
    this
  }

  def radius(x: Int, y: Int) = {
    _map ++= Map(RADIUS_X -> x, RADIUS_Y -> y)
    this
  }

  override def drawBackground(c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lnum: Int): Unit = {
    val textWidth = Math.round(p.measureText(text, start, end))
    val paintColor = p.getColor
    _rect.set(left - _map(LEFT),
      top - (if (lnum == 0) _map(TOP) / 2 else -(_map(TOP) / 2)),
      left + textWidth + _map(RIGHT),
      bottom + _map(BOTTOM) / 2)
    p.setColor(backgroundColor)
    c.drawRoundRect(_rect, _map(RADIUS_X), _map(RADIUS_Y), p)
    p.setColor(paintColor)
  }
}

class MagicTextView(context: Context, attrs: AttributeSet, defStyle: Int) extends TextView(context, attrs, defStyle) {

  case class Shadow(r: Float, dx: Float, dy: Float, color: Int)

  private val outerShadows = new ArrayBuffer[Shadow]
  private val innerShadows = new ArrayBuffer[Shadow]
  private val canvasStore = new util.WeakHashMap[String, Pair[Canvas, Bitmap]]
  private var tempCanvas: Canvas = null
  private var tempBitmap: Bitmap = null
  private var foregroundDrawable: Drawable = null
  private var strokeWidth: Float = 0F
  private var strokeColor: Integer = null
  private var strokeJoin: Paint.Join = null
  private var strokeMiter: Float = 0F
  private var lockedCompoundPadding: Array[Int] = null
  private var frozen: Boolean = false

  def this(context: Context) {
    this(context, null, 0)
  }

  def this(context: Context, attrs: AttributeSet) {
    this(context, attrs, 0)
  }

  if (attrs != null) {
    val a: TypedArray = getContext.obtainStyledAttributes(attrs, R.styleable.MagicTextView)
    val typeface = a.getString(R.styleable.MagicTextView_typeface)
    if (typeface != null) {
      val tf = Typeface.createFromAsset(getContext.getAssets, s"fonts/$typeface.ttf")
      setTypeface(tf)
    }
    if (a.hasValue(R.styleable.MagicTextView_innerShadowColor)) {
      this.addInnerShadow(a.getDimensionPixelSize(R.styleable.MagicTextView_innerShadowRadius, 0), a.getDimensionPixelOffset(R.styleable.MagicTextView_innerShadowDx, 0), a.getDimensionPixelOffset(R.styleable.MagicTextView_innerShadowDy, 0), a.getColor(R.styleable.MagicTextView_innerShadowColor, 0xff000000))
    }
    if (a.hasValue(R.styleable.MagicTextView_outerShadowColor)) {
      this.addOuterShadow(a.getDimensionPixelSize(R.styleable.MagicTextView_outerShadowRadius, 0), a.getDimensionPixelOffset(R.styleable.MagicTextView_outerShadowDx, 0), a.getDimensionPixelOffset(R.styleable.MagicTextView_outerShadowDy, 0), a.getColor(R.styleable.MagicTextView_outerShadowColor, 0xff000000))
    }
    if (a.hasValue(R.styleable.MagicTextView_strokeColor)) {
      val strokeWidth: Float = a.getDimensionPixelSize(R.styleable.MagicTextView_strokeWidth, 1)
      val strokeColor: Int = a.getColor(R.styleable.MagicTextView_strokeColor, 0xff000000)
      val strokeMiter: Float = a.getDimensionPixelSize(R.styleable.MagicTextView_strokeMiter, 10)
      var strokeJoin: Paint.Join = null
      a.getInt(R.styleable.MagicTextView_strokeJoinStyle, 0) match {
        case (0) =>
          strokeJoin = Join.MITER
        case (1) =>
          strokeJoin = Join.BEVEL
        case (2) =>
          strokeJoin = Join.ROUND
      }
      this.setStroke(strokeWidth, strokeColor, strokeJoin, strokeMiter)
    }
  }
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB && (innerShadows.nonEmpty || foregroundDrawable != null)) {
    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
  }

  def setStroke(width: Float, color: Int, join: Paint.Join, miter: Float) {
    strokeWidth = width
    strokeColor = color
    strokeJoin = join
    strokeMiter = miter
  }

  def setStroke(width: Float, color: Int) {
    setStroke(width, color, Join.MITER, 10)
  }

  def setStroke(color: Int) {
    strokeColor = color
  }

  def addOuterShadow(r: Float, dx: Float, dy: Float, color: Int) {
    outerShadows.add(new Shadow(if (r == 0) 0.0001F else r, dx, dy, color))
  }

  def addInnerShadow(r: Float, dx: Float, dy: Float, color: Int) {
    innerShadows.add(new Shadow(if (r == 0) 0.0001F else r, dx, dy, color))
  }

  def clearInnerShadows() {
    innerShadows.clear()
  }

  def clearOuterShadows() {
    outerShadows.clear()
  }

  def setForegroundDrawable(d: Drawable) {
    this.foregroundDrawable = d
  }

  def getForeground: Drawable = {
    if (this.foregroundDrawable == null) this.foregroundDrawable else new ColorDrawable(this.getCurrentTextColor)
  }

  override def onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    freeze()
    val restoreBackground: Drawable = this.getBackground
    val restoreDrawables: Array[Drawable] = this.getCompoundDrawables
    val restoreColor: Int = this.getCurrentTextColor
    this.setCompoundDrawables(null, null, null, null)
    for (shadow <- outerShadows) {
      this.setShadowLayer(shadow.r, shadow.dx, shadow.dy, shadow.color)
      super.onDraw(canvas)
    }
    this.setShadowLayer(0, 0, 0, 0)
    this.setTextColor(restoreColor)
    if (this.foregroundDrawable != null && this.foregroundDrawable.isInstanceOf[BitmapDrawable]) {
      generateTempCanvas()
      super.onDraw(tempCanvas)
      val paint: Paint = this.foregroundDrawable.asInstanceOf[BitmapDrawable].getPaint
      paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP))
      this.foregroundDrawable.setBounds(canvas.getClipBounds)
      this.foregroundDrawable.draw(tempCanvas)
      canvas.drawBitmap(tempBitmap, 0, 0, null)
      tempCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }
    if (strokeColor != null) {
      val paint: TextPaint = this.getPaint
      paint.setStyle(Style.STROKE)
      paint.setStrokeJoin(strokeJoin)
      paint.setStrokeMiter(strokeMiter)
      this.setTextColor(strokeColor)
      paint.setStrokeWidth(strokeWidth)
      super.onDraw(canvas)
      paint.setStyle(Style.FILL)
      this.setTextColor(restoreColor)
    }
    if (innerShadows.nonEmpty) {
      generateTempCanvas()
      val paint: TextPaint = this.getPaint
      for (shadow <- innerShadows) {
        this.setTextColor(shadow.color)
        super.onDraw(tempCanvas)
        this.setTextColor(0xFF000000)
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT))
        paint.setMaskFilter(new BlurMaskFilter(shadow.r, BlurMaskFilter.Blur.NORMAL))
        tempCanvas.save
        tempCanvas.translate(shadow.dx, shadow.dy)
        super.onDraw(tempCanvas)
        tempCanvas.restore()
        canvas.drawBitmap(tempBitmap, 0, 0, null)
        tempCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        paint.setXfermode(null)
        paint.setMaskFilter(null)
        this.setTextColor(restoreColor)
        this.setShadowLayer(0, 0, 0, 0)
      }
    }
    this.setCompoundDrawablesWithIntrinsicBounds(restoreDrawables(0), restoreDrawables(1), restoreDrawables(2), restoreDrawables(3))
    this.setBackground(restoreBackground)
    this.setTextColor(restoreColor)
    unfreeze()
  }

  private def generateTempCanvas() {
    val key = s"$getWidth*$getHeight"
    val stored: Pair[Canvas, Bitmap] = canvasStore.get(key)
    if (stored != null) {
      tempCanvas = stored.first
      tempBitmap = stored.second
    }
    else {
      tempCanvas = new Canvas
      tempBitmap = Bitmap.createBitmap(getWidth, getHeight, Bitmap.Config.ARGB_8888)
      tempCanvas.setBitmap(tempBitmap)
      canvasStore.put(key, new Pair[Canvas, Bitmap](tempCanvas, tempBitmap))
    }
  }

  def freeze() {
    lockedCompoundPadding = Array[Int](getCompoundPaddingLeft, getCompoundPaddingRight, getCompoundPaddingTop, getCompoundPaddingBottom)
    frozen = true
  }

  def unfreeze() {
    frozen = false
  }

  override def requestLayout() {
    if (!frozen) super.requestLayout()
  }

  override def postInvalidate() {
    if (!frozen) super.postInvalidate()
  }

  override def postInvalidate(left: Int, top: Int, right: Int, bottom: Int) {
    if (!frozen) super.postInvalidate(left, top, right, bottom)
  }

  override def invalidate() {
    if (!frozen) super.invalidate()
  }

  override def invalidate(rect: Rect) {
    if (!frozen) super.invalidate(rect)
  }

  override def invalidate(l: Int, t: Int, r: Int, b: Int) {
    if (!frozen) super.invalidate(l, t, r, b)
  }

  override def getCompoundPaddingLeft: Int = {
    if (!frozen) super.getCompoundPaddingLeft else lockedCompoundPadding(0)
  }

  override def getCompoundPaddingRight: Int = {
    if (!frozen) super.getCompoundPaddingRight else lockedCompoundPadding(1)
  }

  override def getCompoundPaddingTop: Int = {
    if (!frozen) super.getCompoundPaddingTop else lockedCompoundPadding(2)
  }

  override def getCompoundPaddingBottom: Int = {
    if (!frozen) super.getCompoundPaddingBottom else lockedCompoundPadding(3)
  }
}