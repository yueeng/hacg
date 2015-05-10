package com.yue.anime.hacg

import org.jsoup.nodes.Element

case class Article(element: Element) {
  val content = element.select(".entry-content,.entry-summary").text().trim
  val image = element.select(".entry-content img").attr("abs:src").trim
}