#!/bin/sh

models=$1
if [ -z "$models" ]; then
   echo "Usage: scripts/voice-server.sh <models-folder>"
   echo "Make sure you have LibreTranslate running locally on port 5000 and glowspeak script installed in your path"
   echo "Otherwise, change the config values in the script to reflect your system setup"
   exit
fi
config="---
serverPort: 7777
webFolder: webapp
startMethod: server
speechModelsFolder: $models
translateEndpoint: http://localhost:5000/translate
voiceCommand: glowspeak
"
nalaq config "$config"

