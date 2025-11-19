package com.interfluidity.officehours.hedgedoc

import java.net.URLEncoder

// to play with the logic that this implements interactively and debug,
//   see https://github.com/swaldman/scalascripts-misc/blob/main/bin/hedgedoc-new-note
//
// we rely upon notes being created with `defaultPermission` `freely`, as there is
// no API for setting permissions.
//   see "Update 2023-10-20", https://tech.interfluidity.com/2023/08/23/getting-started-with-hedgedoc/index.html#update-2023-10-20

object newNote:
  object Result:
    case class Created(url : String)                        extends Result
    case class AlreadyExists(url : String)                  extends Result
    case class Failed( statusCode : Int, message : String ) extends Result
  sealed trait Result

  def apply( hedgedocUrl : String, noteOwnerEmail : String, noteOwnerPassword : String, noteId : String, initialMarkdown : String ) : Result =
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
      session.post(
        s"${stdurl}/new/${URLEncoder.encode(noteId, scala.io.Codec.UTF8.charSet)}",
        data=initialMarkdown,
        maxRedirects=0,
        check=false
      )
    createResponse.statusCode match
      case 302 => Result.Created(s"${stdurl}/${noteId}") // this is what we expect if it worked, it tries to redirect
      case 409 => Result.AlreadyExists(s"${stdurl}/${noteId}")
      case oth => Result.Failed(oth, createResponse.toString())
  end apply
end newNote
