package com.yue.anime.hacg

import java.util

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Paint.{Join, Style}
import android.graphics.drawable.{BitmapDrawable, ColorDrawable, Drawable}
import android.graphics.{Bitmap, BlurMaskFilter, Canvas, Color, Paint, PorterDuff, PorterDuffXfermode, Rect, Typeface}
import android.os.Build
import android.text.TextPaint
import android.util.{AttributeSet, Pair}
import android.view.View
import android.widget.TextView

import scala.collection.JavaConversions._

class MagicTextView(context: Context, attrs: AttributeSet, defStyle: Int) extends TextView(context, attrs, defStyle) {

  class Shadow {
    private[hacg] var r: Float = 0F
    private[hacg] var dx: Float = 0F
    private[hacg] var dy: Float = 0F
    private[hacg] var color: Int = 0

    def this(r: Float, dx: Float, dy: Float, color: Int) {
      this()
      this.r = r
      this.dx = dx
      this.dy = dy
      this.color = color
    }
  }

  private val outerShadows = new util.ArrayList[Shadow]
  private val innerShadows = new util.ArrayList[Shadow]
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
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB && (innerShadows.size > 0 || foregroundDrawable != null)) {
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
    if (innerShadows.size > 0) {
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