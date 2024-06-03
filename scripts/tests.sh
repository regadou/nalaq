#!/bin/sh

# some random tests to validate interpretation of NaLaQ scripting

debug='{
  "consolePrompt": "\n? ",
  "resultPrompt": "= ",
  "expressionPrompt": null,
  "printConfig": false
}'

txtdebug='{
  "consolePrompt": "\n? ",
  "resultPrompt": "= ",
  "expressionPrompt": null,
  "printConfig": false,
  "outputFormat": "text/plain"
}'

batch='{
  "consolePrompt": null,
  "resultPrompt": null,
  "expressionPrompt": null,
  "printConfig": false
}'

txtbatch='{
  "consolePrompt": null,
  "resultPrompt": null,
  "expressionPrompt": null,
  "printConfig": false,
  "outputFormat": "text/plain"
}'

echo "******** running simple expressions test ********"
nalaq config "$debug" "
    all
    42
    type of all
    type of 42
    class of all
    class of 42
    size of all
    size of 42
    keys of all
    keys of 42
    continents as ./webapp/json/continent.json
    type of continents
    class of continents
    size of continents
    keys of continents
    eu as continents with name is Europe
    type of eu
    class of eu
    size of eu
    keys of eu
" ||exit

echo "******** running print text test ********"
nalaq config "$batch" "
    eol as binary 10
    stdout as output null
    hello you eol to stdout
    type of 42 to stdout
    text eol hello you eol to stdout
" ||exit

echo "******** running simple each test ********"
nalaq each all key do text key " -> " do type of key done to output null ||exit

echo "******** running simple with test ********"
nalaq name region population from ./webapp/json/city.json with country_id is CAN and population more 5e5 ||exit

echo "******** running select and save to file test ********"
nalaq do ./webapp/json/city.json with region is quebec and population not less 3e5 done to ./build/quebec_cities.json ||exit

echo "******** running data uri test ********"
nalaq config "$txtdebug" entity outputFormat text/plain to data:application/json ||exit

echo "******** running system properties test ********"
nalaq config "$batch" "
    eol as binary 10
    stdout as output null
    props as java.lang.System.getProperties null
    names as keys of props
    each names n do
        v as n of props
        text n ' -> ' v eol to stdout
    done
" ||exit
