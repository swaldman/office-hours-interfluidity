package com.interfluidity.officehours.hedgedoc

import java.net.URLEncoder

def newNote( hedgedocUrl : String, noteOwnerEmail : String, noteOwnerPassword : String, noteId : String, initialMarkdown : String ) : Either[String,Unit] =
  val stdurl =
    if hedgedocUrl.endsWith("/") then hedgedocUrl.init else hedgedocUrl
  val session = requests.Session()
  val loginResponse =
    session.post(
      s"${stdurl}/login",
      data=Map("email"->noteOwnerEmail,"password"->noteOwnerPassword),
      maxRedirects=0,
      check=false
    )
  val createResponse =
    session.post(s"${stdurl}/new/${URLEncoder.encode(noteId)}", data=initialMarkdown, maxRedirects=0, check=false)
  createResponse.statusCode match
    case 302 => Right( () ) // this is what we expect if it worked, it tries to redirect
    case 409 => Left(s"It appears that note '${noteId}' already exists.")
    case _   => Left(createResponse.statusMessage)
