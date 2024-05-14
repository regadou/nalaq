#!/bin/sh

if [ -z "$1" ]; then
   keyword=serverPort
else
   keyword="$1"
fi
procid=$(ps ax|grep nalaq|grep "$keyword"|cut -d ' ' -f 2)
if [ -z "$procid" ]; then
    echo No NaLaQ process server running with keyword "$keyword"
else
    echo Found $(echo "$procid"|grep -Fc "") NaLaQ process to kill ...
    kill -9 $procid
fi
