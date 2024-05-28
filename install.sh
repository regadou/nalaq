#!/bin/sh

if [ -z "$1" ]; then
    target=/usr/local/bin
else
    target=$1
fi

version=$(ls build|grep nalaq|cut -d - -f 2-)
src="build/nalaq-$version/bin/nalaq"
dst="$target/nalaq"
jsrc="build/nalaq-$version/lib/nalaq-$version.jar"
jdst="~/.m2/repository/com/magicreg/nalaq/$version/nalaq-$version.jar"
if [ -f $src ]; then
    echo "NaLaQ artefact already compiled"
else
    ./run.sh clean dictionary || exit
fi

echo "Building NaLaQ fat jar ..."
gradle publishToMavenLocal||exit

if [ -f $dst ]; then
    echo "NaLaQ command line already installed"
else
    echo "installing NaLaQ command line at $target ..."
    ln -s "$src" "$nalaq"
fi

# TODO: install server as a daemon service if configuration uri is given

