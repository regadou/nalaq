#!/bin/sh

models=$1
slang=$2
tlang=$3
if [ -z "$models" -o -z "$slang" -o -z "$tlang" ]; then
   echo "Usage: scripts/voice.sh <models-folder> <source-language> <target-language>"
   echo "Make sure you have LibreTranslate running locally on port 5000 and glowspeak script installed in your path"
   echo "Otherwise, change the config values in the script to reflect what is your system setup"
   exit
fi
config="---
expressionPrompt:
outputFormat: text/plain
language: $slang
targetLanguage: $tlang
translateEndpoint: http://localhost:5000/translate
voiceCommand: glowspeak
textParser: translate
speechEngine: vosk
voskModelFolder: $models
"
nalaq config "$config"
