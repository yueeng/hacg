package com.yue.anime.hacg

import java.text.{ParseException, SimpleDateFormat}
import java.util.Date

import android.os.AsyncTask
import android.view.View

import scala.language.implicitConversions

object Common {
  implicit def viewTo[T <: View](view: View): T = view.asInstanceOf[T]

  private val datefmt = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZZZZZ")

  implicit def string2date(str: String): Option[Date] = {
    try {
      return Option(datefmt.parse(str))
    } catch {
      case _: ParseException =>
    }
    None
  }

  implicit def date2long(date: Date): Long = date.getTime

  implicit def date2string(date: Date): String = datefmt.format(date)
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
