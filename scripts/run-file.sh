#!/bin/sh

# export JAVA_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
config='outputFormat=text/plain'
read_input() { while read x; do echo $x; done }

if [ -z "$1" ]; then
   src=stdin
   txt="$(read_input)"
elif [ "$1" = "-" ]; then
   src=stdin
   txt="$(read_input)"
   shift
else
   src=$1
   txt="$(cat $src)"
   shift
fi

if [ -z "$(echo $txt|xargs)" ]; then
    echo "Empty data from $src"
else
    nalaq config "$config" "args as $@
    $txt"
fi
