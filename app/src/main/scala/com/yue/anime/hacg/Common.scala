package com.yue.anime.hacg

import android.os.AsyncTask
import android.view.View

import scala.language.implicitConversions

object Common {
  implicit def viewTo[T <: View](view: View): T = view.asInstanceOf[T]
}

abstract class ScalaTask[A, P, R] extends AsyncTask[A, P, R] {
  final override def doInBackground(params: A*): R = background(params: _*)

  final override def onPreExecute(): Unit = super.onPreExecute()

  final override def onPostExecute(result: R): Unit = post(result)

  final override def onProgressUpdate(values: P*): Unit = progress(values: _*)

  def pre(): Unit = {}

  def post(result: R): Unit = {}

  def progress(values: P*): Unit = {}

  def background(params: A*): R
}
