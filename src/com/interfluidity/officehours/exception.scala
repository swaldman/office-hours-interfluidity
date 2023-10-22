package com.interfluidity.officehours

class OfficeHoursException(msg : String, cause : Throwable = null) extends Exception(msg, cause)

class CantCreateNotes(msg : String, cause : Throwable = null) extends OfficeHoursException(msg, cause)
class BadConfig(msg : String, cause : Throwable = null) extends OfficeHoursException(msg, cause)

