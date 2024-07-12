#!/bin/bash

if [ -z "$1" ]; then
  echo "Missing text to analyse"
  exit
fi
nalaq config "textParser=kotlin&outputFormat=text/x-kotlin" '
  val parser = getParser(TextParser.SNLP) as SnlpParser
  val sentences = parser.getSentences("'$@'")
  for (s in sentences) {
    println(s.text())
    println(s.parse().pennString())
    println(getFormat("json")!!.encodeText(s.toExpression().resolve()))
    println("***********************************************************************************")
  }
'
