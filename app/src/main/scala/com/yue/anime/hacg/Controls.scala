package com.yue.anime.hacg

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec
import android.widget.{ListView, ProgressBar}
import com.yue.anime.hacg.Common.viewClick

class UnScrollListView(context: Context, attrs: AttributeSet, defStyle: Int) extends ListView(context, attrs, defStyle) {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)

  def this(context: Context) = this(context, null)

  override def onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int): Unit = {
    val expandSpec = MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE >> 2, MeasureSpec.AT_MOST)
    super.onMeasure(widthMeasureSpec, expandSpec)
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

  trait Busy extends ViewEx[Boolean, ProgressBar] {
    busy = false

    def busy = value

    def busy_=(b: Boolean): Unit = value = b

    def progress = view

    def progress_=(p: ProgressBar): Unit = view = p

    override def refresh(): Unit = {
      view.setVisibility(if (busy) View.VISIBLE else View.INVISIBLE)
      view.setIndeterminate(busy)
    }
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