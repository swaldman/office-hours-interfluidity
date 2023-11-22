package com.interfluidity.officehours

import java.time.{DayOfWeek, LocalDate}
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
import java.time.temporal.TemporalAdjusters

import java.io.BufferedInputStream
import java.util.{Date,Properties}

import scala.util.Using
import scala.util.control.NonFatal

import com.mchange.sysadmin.{debugPrettyPrintHtml,prettyPrintHtml}
import com.mchange.mailutil.Smtp
import com.mchange.codegenutil.increaseIndent

import jakarta.mail.Message
import jakarta.mail.internet.*

object Prop:
  val HedgedocUrl      = "officehours.hedgedoc.url"
  val HedgedocEmail    = "officehours.hedgedoc.email"
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
          val mailFroms    = parseOutAddresses(seqm(0))
          val mailReplyTos = parseOutAddresses(seqm(1))
          val mailTos      = parseOutAddresses(seqm(2))
          val mailFrom =
            if mailFroms.size != 1 then
              throw new BadConfig(s"There should be precisely one from address, found ${mailFroms.size}: " + seqm(0))
            else
              mailFroms.head
          sendThisWeeksMail( isoLocalDate, doCreateNote, mailFrom, mailReplyTos, mailTos, mbSkipReason )
      
val MailParseRegexStr = """\s*\,\s*"""

def parseOutAddresses( s : String ) : List[String] = s.split(MailParseRegexStr).toList

def nextFridayIsoLocalDate() =
  val upcomingFriday = // this made it easy... https://www.w3resource.com/java-exercises/datetime/java-datetime-exercise-33.php
    LocalDate.now().`with`(TemporalAdjusters.next(DayOfWeek.FRIDAY))
  ISO_LOCAL_DATE.format(upcomingFriday)

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
  replyToAddresses : List[String],
  toAddresses : List[String],
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
  val msg = new MimeMessage(smtpContext.session)
  val htmlAlternative =
    val tmp = new MimeBodyPart()
    def pretty =
      //debugPrettyPrintHtml(htmlText)
      prettyPrintHtml(htmlText)
    tmp.setContent(pretty, "text/html")
    tmp
  val plainTextAlternative =
    val tmp = new MimeBodyPart()
    tmp.setContent(plainText, "text/plain")
    tmp
  // last entry is highest priority!
  val multipart = new MimeMultipart("alternative", plainTextAlternative, htmlAlternative)
  msg.setContent(multipart)
  msg.setSubject(s"[interfluidity-office-hours] Notes for ${isoLocalDate}")
  msg.setFrom(new InternetAddress(from))
  msg.setReplyTo( replyToAddresses.map( em => InternetAddress(em) ).toArray )
  toAddresses.foreach: em =>
    msg.addRecipient(Message.RecipientType.TO, new InternetAddress(em))
  msg.setSentDate(new Date())
  msg.saveChanges()
  smtpContext.sendMessage(msg)
 
def createInitialMarkdown(isoLocalDate : String) : String =
  s"""|# Office Hours ${isoLocalDate}
      |
      |_Start your edits!_
      |""".stripMargin

def createNote(isoLocalDate : String, hedgedocUrl : String, noteOwnerEmail : String, noteOwnerPassword : String) : ( String, hedgedoc.newNote.Result ) =
  val newNoteId = s"office-hours-${isoLocalDate}"
  val result = hedgedoc.newNote(hedgedocUrl,noteOwnerEmail,noteOwnerPassword,newNoteId,createInitialMarkdown(isoLocalDate) )
  ( newNoteId, result )

def plaintextNewNoteContent( isoLocalDate : String, newNoteUrl : String, poem : RandomPoem.Poem ) : String =
  val variableText =
    s"""|For your topicalizing, brainstorming, pre-agenda-izing displeasure, notes for interfluidity office
        |hours on ${isoLocalDate} are up and editable. [ ${newNoteUrl} ]
        |
        |Office hours will convene at 12:30pm Pacific / 1:30pm Mountain / 2:30pm Central / 3:30pm Eastern / 
        |7:30pm UTC @ https://www.interfluidity.com/office-hours""".stripMargin
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
