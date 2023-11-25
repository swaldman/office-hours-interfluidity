package com.interfluidity.officehours

import java.time.{DayOfWeek, Duration, LocalDate}
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import java.time.temporal.TemporalAdjusters

import java.io.BufferedInputStream
import java.util.{Date,Properties}

import scala.annotation.tailrec
import scala.util.Using
import scala.util.control.NonFatal

import com.mchange.sysadmin.{debugPrettyPrintHtml,prettyPrintHtml}
import com.mchange.mailutil.Smtp
import com.mchange.codegenutil.increaseIndent

import jakarta.mail.Message
import jakarta.mail.internet.*

object Prop:
  val HedgedocUrl      = "officehours.hedgedoc.url"
  val HedgedocEmail    = "officehours.hedgedoc.email" // the e-mail serving as the username to which this app should authenticate
  val HedgedocPassword = "officehours.hedgedoc.password"
  val NotesMailFrom    = "officehours.notesmail.from"
  val NotesMailTo      = "officehours.notesmail.to"
  val NotesMailReplyTo = "officehours.notesmail.replyto"
  val SkipPrefix       = "officehours.skip"

  def get(properties : Properties)( props : String* ) : Either[Set[String],Seq[String]] =
    val checks : Seq[Either[String,String]] =
      props.map: prop =>
        properties.getProperty( prop ) match
          case null  => Left(prop)
          case other => Right(other)
    if checks.forall( _.isRight ) then
      Right( checks.collect { case Right(v) => v } )
    else
      Left( (checks.collect { case Left(p) => p }).toSet )
end Prop

object Env:
  val OfficeHoursPropsFile = "OFFICE_HOURS_PROPSFILE"
end Env

object Test:
  lazy val properties =
    import Prop.*
    val tmp = new Properties()
    tmp.setProperty(HedgedocUrl, "https://notes.interfluidity.com/")
    tmp.setProperty(HedgedocEmail, "swaldman@mchange.com")
    tmp.setProperty(HedgedocPassword, System.console().readPassword("Please enter the hedgedoc password: ").mkString)
    tmp.setProperty(NotesMailFrom, "sysadmin@mchange.com")
    tmp.setProperty(NotesMailTo, "swaldman@mchange.com")
    tmp.setProperty(NotesMailReplyTo, "swaldman@mchange.com")
    tmp
  def createAndSendTestNote() : Unit =
    createAndSendThisWeeksNote( properties )

def createAndSendThisWeeksNote() : Unit =
  val propsFilePath = 
    val s = sys.env.get( Env.OfficeHoursPropsFile ).getOrElse:
      throw new BadConfig(s"Environment variable '${Env.OfficeHoursPropsFile}' expected, not found.")
    os.Path( s : String )
  val props = new Properties()  
  Using.resource( new BufferedInputStream( os.read.inputStream(propsFilePath) ) )( is => props.load(is) )
  createAndSendThisWeeksNote( props )

def createAndSendThisWeeksNote( properties : Properties ) : Unit =
  val isoLocalDate = nextFridayIsoLocalDate()
  val mbSkipReason = Option( properties.getProperty( Prop.SkipPrefix + "." + isoLocalDate ) )   
  Prop.get(properties)(Prop.HedgedocUrl, Prop.HedgedocEmail, Prop.HedgedocPassword) match
    case Left( missingKeys ) => throw new BadConfig( "Missing hedgedoc properties: " + missingKeys.mkString(", ") )
    case Right( seqhd ) =>
      val doCreateNote = () => createThisWeeksNote(isoLocalDate, seqhd(0), seqhd(1), seqhd(2))
      Prop.get(properties)(Prop.NotesMailFrom, Prop.NotesMailReplyTo, Prop.NotesMailTo) match
        case Left( missingKeys ) => throw new BadConfig( "Missing mail properties: " + missingKeys.mkString(", ") )
        case Right( seqm ) =>
          val mailFroms    = seqm(0)
          val mailReplyTos = seqm(1)
          val mailTos      = seqm(2)
          sendThisWeeksMail( isoLocalDate, doCreateNote, mailFroms, mailReplyTos, mailTos, mbSkipReason )

def nextFridayIsoLocalDate() =
  val upcomingFriday = // this made it easy... https://www.w3resource.com/java-exercises/datetime/java-datetime-exercise-33.php
    LocalDate.now().`with`(TemporalAdjusters.next(DayOfWeek.FRIDAY))
  ISO_LOCAL_DATE.format(upcomingFriday)

val MeetingNotesBase = "https://notes.interfluidity.com/office-hours-"
val Days90 = Duration.ofDays(90)

@tailrec
def mostRecentMeetingNotesUrlBefore( startCheckDate : LocalDate, checkDate : LocalDate ) : Option[String] =
  val day = checkDate.`with`(TemporalAdjusters.previous(DayOfWeek.FRIDAY))
  val url = MeetingNotesBase + ISO_LOCAL_DATE.format(day)
  val r = requests.head(url, check = false, maxRedirects = 0)
  (r.statusCode / 100) match
    case 2     => Some( url )
    case 3 | 4 =>
      if Duration.between( day.atTime(0,0,0), startCheckDate.atTime(0,0,0) ).compareTo( Days90 ) > 0 then
        System.err.println( "No meeting notes were found in the past 90 days." )
        None
      else
        mostRecentMeetingNotesUrlBefore( startCheckDate, day )
    case other =>
      System.err.println( s"Something went wrong while searching for previous meeting notes, response code ${r.statusCode}, response: ${r}" )
      None

def mostRecentMeetingNotesUrlBefore( isoLocalDate : String ) : Option[String] =
  try
    val meetingDay = LocalDate.from( ISO_LOCAL_DATE.parse(isoLocalDate) )
    mostRecentMeetingNotesUrlBefore( meetingDay, meetingDay )
  catch
    case t : Throwable =>
      t.printStackTrace()
      None

// returns note URL
def createThisWeeksNote(isoLocalDate : String, hedgedocUrl : String, noteOwnerEmail : String, noteOwnerPassword : String) : String =
  val ( _, result ) = createNote(isoLocalDate, hedgedocUrl, noteOwnerEmail, noteOwnerPassword)
  result match
    case hedgedoc.newNote.Result.Created( url )            => url
    case hedgedoc.newNote.Result.AlreadyExists( url )      => url
    case hedgedoc.newNote.Result.Failed( statusCode, msg ) =>
      throw new CantCreateNotes( msg + s" [status code: ${statusCode}]" )

def sendThisWeeksMail(
  isoLocalDate : String,
  doCreateNote : () => String, // we defer this until we've checked whether office hours are skipped
  from : String,
  replyTo : String,
  to : String,
  mbSkipReason : Option[String]
)(using smtpContext : Smtp.Context) : Unit =
  val poem =
    try
      RandomPoem.fetch()
    catch
      case NonFatal(t) =>
        t.printStackTrace()
        DummyPoem
  val ( htmlText, plainText ) =
    mbSkipReason match
      case Some( reason ) =>
        ( mail.this_week_skipped_html( isoLocalDate, reason, poem ).text, plaintextSkippedContent( isoLocalDate, reason, poem ) )
      case None =>
        val newNoteUrl = doCreateNote()
        ( mail.this_weeks_office_hours_html( isoLocalDate, newNoteUrl, poem ).text, plaintextNewNoteContent( isoLocalDate, newNoteUrl, poem ) )
  val prettyHtml =
    //debugPrettyPrintHtml(htmlText)
    prettyPrintHtml(htmlText)
  val subject = s"[interfluidity-office-hours] Notes for ${isoLocalDate}"
  Smtp.sendSimpleHtmlPlaintextAlternative(
    html = prettyHtml,
    plaintext = plainText,
    subject = subject,
    from = from,
    to = to,
    replyTo = replyTo
  )

def createInitialMarkdown(isoLocalDate : String) : String =
  val priorNotesUrl = mostRecentMeetingNotesUrlBefore( isoLocalDate : String )

  val priorNotesMessage = priorNotesUrl.fold(""): url =>
    s"_Here are our [previous meeting's notes](${url})._"
    
  s"""|# Office Hours ${isoLocalDate}
      |
      |${priorNotesMessage}
      |
      |---
      |
      |**Delete me, and start your edits here!**
      |""".stripMargin

def createNote(isoLocalDate : String, hedgedocUrl : String, noteOwnerEmail : String, noteOwnerPassword : String) : ( String, hedgedoc.newNote.Result ) =
  val newNoteId = s"office-hours-${isoLocalDate}"
  val result = hedgedoc.newNote(hedgedocUrl,noteOwnerEmail,noteOwnerPassword,newNoteId,createInitialMarkdown(isoLocalDate) )
  ( newNoteId, result )

def plaintextNewNoteContent( isoLocalDate : String, newNoteUrl : String, poem : RandomPoem.Poem ) : String =
  val t = Times(isoLocalDate)
  val variableText =
    s"""|For your topicalizing, brainstorming, pre-agenda-izing displeasure, notes for interfluidity office
        |hours on ${isoLocalDate} are up and editable. [ ${newNoteUrl} ]
        |
        |Office hours will convene at ${t.P} Pacific / ${t.M} Mountain / ${t.C} Central / ${t.E} Eastern / 
        |${t.U} UTC @ https://www.interfluidity.com/office-hours""".stripMargin
  plaintextAnyContent( isoLocalDate, poem, variableText )

def plaintextSkippedContent( isoLocalDate : String, reason : String, poem : RandomPoem.Poem ) : String =
  val variableText =
    s"""|This week's interfluidity office hours, scheduled for ${isoLocalDate}, have, alas, been canceled.  
        |
        |The deadbeat organizer gave the following reason: 
        |
        |> ${reason}""".stripMargin
  plaintextAnyContent( isoLocalDate, poem, variableText )

def plaintextAnyContent( isoLocalDate : String, poem : RandomPoem.Poem, variableText : String ) : String =
  val mainText =
    s"""|Hi!
        |
        |${variableText}
        |
        |God help us, this is an automated e-mail. I'm really sorry for that.
        |
        |Please reply with some cussing to be removed with no fussing.
        |
        |    Love,
        |      Office Hours Bot
        |
        |p.s. Now, courtesy of poetrydb, here is your random poem:
        |
        |""".stripMargin
  val poemPart =
    val header =
      s"""|${poem.title}
          |    by ${poem.author}
          |""".stripMargin
    header + poem.lines.mkString("\n")      
  mainText + increaseIndent(4)(poemPart)

val PoemItalicsRegex = """_(.*)_""".r
val SingleSpaceRegex = """ """.r
val EmDashRegex      = """--""".r

def htmlizePoemLine( line : String ) : String =
  val step1 = PoemItalicsRegex.replaceAllIn(line, m => s"<i>${m.group(1)}</i>")
  val step2 = SingleSpaceRegex.replaceAllIn(step1, m => "&nbsp;")
  val step3 = EmDashRegex.replaceAllIn(step2, m => "&mdash;")
  step3.trim

val DummyPoem =
  val lines = List(
    "Something went wrong",
    "While I was singing my song",
    "So now this",
    "Is all",
    "You get."
  )
  RandomPoem.Poem(
    title = "I couldn't fetch the poem",
    author = "Office Hours Bot",
    lines = lines,
    linecount = lines.size
  )
