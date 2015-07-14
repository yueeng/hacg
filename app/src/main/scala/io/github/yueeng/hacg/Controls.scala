package io.github.yueeng.hacg

import android.content.Context
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.view.{View, ViewGroup}
import io.github.yueeng.hacg.Common.viewClick

class FullyLinearLayoutManager(context: Context) extends LinearLayoutManager(context) {

  private val mMeasuredDimension = new Array[Int](2)

  override def onMeasure(recycler: RecyclerView#Recycler, state: RecyclerView.State, widthSpec: Int, heightSpec: Int) {
    val widthMode = View.MeasureSpec.getMode(widthSpec)
    val heightMode = View.MeasureSpec.getMode(heightSpec)
    val widthSize = View.MeasureSpec.getSize(widthSpec)
    val heightSize = View.MeasureSpec.getSize(heightSpec)
    var width = 0
    var height = 0
    for (i <- 0 until getItemCount) {
      if (getOrientation == LinearLayoutManager.HORIZONTAL) {
        measureScrapChild(recycler, i, View.MeasureSpec.makeMeasureSpec(i, View.MeasureSpec.UNSPECIFIED), heightSpec, mMeasuredDimension)
        width += mMeasuredDimension(0)
        if (i == 0) {
          height = mMeasuredDimension(1)
        }
      } else {
        measureScrapChild(recycler, i, widthSpec, View.MeasureSpec.makeMeasureSpec(i, View.MeasureSpec.UNSPECIFIED), mMeasuredDimension)
        height += mMeasuredDimension(1)
        if (i == 0) {
          width = mMeasuredDimension(0)
        }
      }
    }
    widthMode match {
      case View.MeasureSpec.EXACTLY => width = widthSize
      case _ =>
    }

    heightMode match {
      case View.MeasureSpec.EXACTLY => height = heightSize
      case _ =>
    }

    setMeasuredDimension(width, height)
  }

  private def measureScrapChild(recycler: RecyclerView#Recycler, position: Int, widthSpec: Int, heightSpec: Int, measuredDimension: Array[Int]) {
    val view = recycler.getViewForPosition(position)
    recycler.bindViewToPosition(view, position)
    if (view != null) {
      val p = view.getLayoutParams.asInstanceOf[RecyclerView.LayoutParams]
      val childWidthSpec = ViewGroup.getChildMeasureSpec(widthSpec, getPaddingLeft + getPaddingRight + p.leftMargin + p.rightMargin, p.width)
      val childHeightSpec = ViewGroup.getChildMeasureSpec(heightSpec, getPaddingTop + getPaddingBottom + p.topMargin + p.bottomMargin, p.height)
      view.measure(childWidthSpec, childHeightSpec)
      measuredDimension(0) = view.getMeasuredWidth + p.leftMargin + p.rightMargin
      measuredDimension(1) = view.getMeasuredHeight + p.bottomMargin + p.topMargin
      recycler.recycleView(view)
    }
  }
}

object ViewEx {

  trait ViewEx[T, V <: View] {
    var _value: T = _

    def value = _value

    def value_=(v: T) = {
      _value = v
      _refresh()
    }

    var _view: V = _

    def view = _view

    def view_=(v: V) = {
      _view = v
      _refresh()
    }

    private def _refresh(): Unit = {
      if (view == null)
        return
      value match {
        case v: AnyRef if v != null =>
          refresh()
        case _ =>
      }
    }

    def refresh(): Unit
  }

  trait Error extends ViewEx[Boolean, View] {
    error = false

    def error = value

    def error_=(b: Boolean): Unit = value = b

    def image = view

    def image_=(p: View): Unit = {
      view = p
      view.setOnClickListener(click)
    }

    def retry(): Unit

    val click = viewClick(v => retry())

    override def refresh(): Unit = view.setVisibility(if (value) View.VISIBLE else View.INVISIBLE)
  }

}