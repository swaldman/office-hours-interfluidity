package com.interfluidity.officehours

import upickle.default.{ReadWriter, read => jsonRead}

// see https://poetrydb.org/index.html
object RandomPoem:

  final case class Poem(title: String, author: String, lines : List[String], linecount : Int) derives ReadWriter
  
  def jsonToPoem(jsonText : String) : Poem = jsonRead[List[Poem]](jsonText).head

  def fetchJson() : String =
    val r = requests.get("https://poetrydb.org/random")
    r.text()

  def fetch() : Poem = jsonToPoem(fetchJson())

