package io.github.yueeng.hacg

import android.view.View
import io.github.yueeng.hacg.Common.viewClick

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