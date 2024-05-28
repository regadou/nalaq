#!/bin/bash

models=$1
if [ -z "$models" ]; then
   echo "The models folder was not specified"
   exit
fi

port=7777
#port=$(echo $(( $RANDOM % 10000 + 10000 )))
config="---
expressionPrompt:
textParser=translate
speechModelsFolder: $models
translateEndpoint: http://localhost:5000/translate
voiceCommand: glowspeak
language: ""
outputFormat: text/plain
startMethod: server
serverPort: $port
staticFolder: webapp
"
nalaq config "$config" &
sleep 3
open http://localhost:$port
