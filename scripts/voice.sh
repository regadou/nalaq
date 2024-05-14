#!/bin/sh

engine=$1
param=$2
if [ -z "$engine" ]; then
   echo Missing engine name, valid engines are pico and vosk
   exit
elif [ -z "$param" ]; then
   if [ $engine = pico ]; then
       echo Missing PicoVoice access key
   elif [ $engine = vosk ]; then
       echo Missing Vosk speech model folder
   else
       echo Unknown engine $engine, valid engines are pico and vosk
   fi
   exit
elif [ $engine = pico ]; then
   pname=picoAccessKey
elif [ $engine = vosk ]; then
   pname=voskSpeechModel
else
   echo Unknown engine $engine, valid engines are pico and vosk
   exit
fi
config="speechEngine=$engine&$pname=$param"
nalaq config "$config"
