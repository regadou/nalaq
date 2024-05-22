#!/bin/sh

# A script to test the data import capabilities of NaLaQ
# The script is a mix of Bash and NaLaQ languages
# Bash is used to support needed expressiveness that is not yet implemented within NaLaQ

folder=webapp/json
dbname=entities
dbpath=$(pwd)/build/$dbname
dbprefix=jdbc:h2:file:
dbsuffix=.mv.db
contacts=./$folder/contact.json
config="---
consolePrompt: null
resultPrompt: null
expressionPrompt: null
outputFormat: text/plain"

if [ -f "$dbprefix$dbpath$dbsuffix" ]; then
    echo "Database file already exists"
    echo "Please delete all $dbpath.* files to retry import"
else
    echo Looking for a VCF file to convert to JSON ...
    if [ -z "$1" ]; then
        echo No given VCF file
        echo Please export your contact book data as a VCF file and give it as parameter of this script
        exit
    elif [  ! -f "$1" ]; then
        echo Given VCF file does not exist: $1
        echo Please make sure the filename exists and that it is readable
        exit
    else
        echo Converting file $1 to $contacts ...
        nalaq config "$config" "
            stdout as output null
            cards as $1
            persons as list null
            each cards x do
              newp as person x to persons
              n as name of newp
              text 'person ' newp ' ...' to stdout
            done
            persons to $contacts
        " || exit
    fi
    echo Building $dbname database: $dbpath ...
    config="$config
namespaces:
  db: $dbprefix$dbpath"
    for file in $folder/*.json; do
        name="$(echo $file|rev|cut -d / -f 1|rev|cut -d . -f 1)"
        echo "  Importing table $name ..."
        nalaq config "$config" "
            eol as binary 10
            stdout as output null
            data as $file
            db:$name as type data
            text 'populating new type $name ...' eol to stdout
            data to db:$name
        " || exit
    done
fi
