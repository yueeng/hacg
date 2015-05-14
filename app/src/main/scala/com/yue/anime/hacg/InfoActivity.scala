package com.yue.anime.hacg

import android.os.Bundle
import android.support.v7.app.AppCompatActivity

/**
 * Info activity
 * Created by Rain on 2015/5/12.
 */
class InfoActivity extends AppCompatActivity {
  lazy val article = getIntent.getParcelableExtra[Article]("article")

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)

  }
}
