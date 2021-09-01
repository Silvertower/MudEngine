package net.roninmud.mudengine.utility

const val MAX_STRING_LENGTH: Int = 4096
const val MAX_INPUT_LENGTH: Int = 512

const val CRLF: String = "\n\r"

const val ASCII_NULL: Char = '\u0000'

fun isAsciiBackspaceOrDelete(c: Char): Boolean {
  return c == '\b' || c == '\u007f'
}

fun isAsciiNewline(c: Char): Boolean {
  return c == '\n' || c == '\r'
}

fun isAsciiNull(c: Char): Boolean {
  return c == ASCII_NULL
}

fun isAsciiPrintable(c: Char): Boolean {
  return c in '\u0020'..'\u007e'
}
