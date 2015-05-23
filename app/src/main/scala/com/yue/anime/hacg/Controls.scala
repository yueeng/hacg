package com.yue.anime.hacg

import android.content.Context
import android.graphics.{Canvas, Paint, RectF}
import android.support.v7.widget.RecyclerView.State
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.text.style.LineBackgroundSpan
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.view.{View, ViewGroup}
import android.widget.ListView

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