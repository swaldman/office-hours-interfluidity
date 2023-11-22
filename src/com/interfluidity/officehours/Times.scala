package com.interfluidity.officehours

import java.time.*
import java.time.format.DateTimeFormatter
import DateTimeFormatter.ISO_LOCAL_DATE

object Times:
  val MeetingHourE   = 15
  val MeetingMinuteE = 30

  val TimePrinter = DateTimeFormatter.ofPattern("hh:mmaa")

  def printTime( zdt : ZonedDateTime ) : String = TimePrinter.format(zdt).toLowerCase

  def apply( isoLocalDate : String ) : Times =
    val meetingDate = LocalDate.from( ISO_LOCAL_DATE.parse(isoLocalDate) )
    val easternMeetingTime = meetingDate.atTime(MeetingHourE,MeetingMinuteE).atZone( ZoneId.of("US/Eastern") )
    val centralMeetingTime = easternMeetingTime.withZoneSameInstant( ZoneId.of("US/Central") )
    val mountainMeetingTime = easternMeetingTime.withZoneSameInstant( ZoneId.of("US/Mountain") )
    val pacificMeetingTime = easternMeetingTime.withZoneSameInstant( ZoneId.of("US/Pacific") )
    val utcMeetingTime = easternMeetingTime.withZoneSameInstant( ZoneId.of("UTC") )

    val E = printTime(easternMeetingTime)
    val C = printTime(centralMeetingTime)
    val M = printTime(mountainMeetingTime)
    val P = printTime(pacificMeetingTime)
    val U = printTime(utcMeetingTime)
    Times( E=E, C=C, M=M, P=P, U=U )
case class Times( E : String, C : String, M : String, P : String, U : String )
