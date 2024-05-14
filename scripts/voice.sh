#!/bin/sh

engine=$1
param=$2
if [ -z "$engine" ]; then
   echo Missing engine name, valid engines are pico and vosk
   exit
elif [ -z "$param" ]; then
   echo Missing engine parameter
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
