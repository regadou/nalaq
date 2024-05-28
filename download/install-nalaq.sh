start_time=$(date '+%Y-%m-%d %H:%M:%S')
baseurl="$1"
jarname="nalaq-all.jar"
languages="install-languages.sh"
translator="translator.sh"
webapp="webapp.zip"
folder="$HOME/nalaq"

if [ -z "$baseurl" ]; then
    echo "This script needs the reference url from which it was downloaded as its first parameter"
    exit 1
fi

echo "checking if required commands are installed ..."
for cmd in curl pip3 python3 unzip git java; do
    exist=$(which $cmd)
    if [ -z "$exist" ]; then
        errors=" $cmd"
    fi
done
if [ -n "$errors" ]; then
    echo "Command files missing:$errors"
    exit 2
fi
venv=$(python3 -c "import venv" 2>/dev/null && echo yes || echo no)
if [ "$venv" == "no" ]; then
    echo "Python venv is not installed, please install it before running this install script"
    exit 2
fi

echo "Looking for previous NaLaQ installation ..."
if [ -d "$folder" ]; then
    echo "$folder already exists, updating installation ..."
elif [ -f "$folder" ]; then
    echo "$folder is an existing file, please delete or rename it"
    exit 4
else
    echo "Creating new folder $folder ..."
    result=$(mkdir "$folder" && echo yes || echo no)
    if [ "$result" = "yes" ]; then
        echo "Folder $folder created"
    else
        echo "Could not create $folder"
        exit 5
    fi
fi

echo "Downloading jar file ..."
jar="$baseurl/$jarname"
result=$(curl -sLfo "$folder/$jarname" "$jar" && echo yes || echo no)
if [ "$result" = "yes" ]; then
    echo "Jar file downloaded"
else
    echo "Could not download $jar"
    exit 6
fi

echo "Installing nalaq executable ..."
link="/usr/local/bin/nalaq"
if [ -e $link ]; then
    echo "$link is already installed"
else
    script=$folder/nalaq.sh
    echo "#!/bin/bash
java -jar $folder/$jarname $@
" > $script
    chmod 755 $script
    sudo ln -s $script $link || exit 6
fi

echo "Downloading and installing webapp archive ..."
result=$(curl -sLfo "$folder/$webapp" "$baseurl/$webapp" && echo yes || echo no)
if [ "$result" = "yes" ]; then
    previous=$(pwd)
    cd $folder
    unzip $webapp
    cd $previous
else
    echo "Could not download $baseurl/$webapp"
    exit 7
fi

echo "Downloading languages script ..."
result=$(curl -sLfo "$folder/$languages" "$baseurl/$languages" && echo yes || echo no)
if [ "$result" = "yes" ]; then
    echo "Language script file downloaded"
else
    echo "Could not download $baseurl/$languages"
    exit 8
fi

while true; do
    read -n 1 -p "Do you want to install the language files ? [y/n]" answer < /dev/tty
    echo ""
    case $answer in
        "y" )
            previous=$(pwd)
            cd $folder
            bash $folder/$languages || exit 9
            cd $previous
            break
            ;;
        "n" )
            echo "The language files will not be installed"
            echo "You can later execute $folder/$languages if you change your mind"
            break
            ;;
        * ) echo "Please answer y or n";;
    esac
done

end_time=$(date '+%Y-%m-%d %H:%M:%S')
echo "The NaLaQ installation have been completed and lasted from $start_time to $end_time"

while true; do
    read -n 1 -p "Do you want to start the server[s], the console[c] or none[n] ?" answer < /dev/tty
    echo ""
    case $answer in
        "c" ) nalaq < /dev/tty; break;;
        "s" )
            lport=$(echo $(( $RANDOM % 10000 + 10000 )))
            libretranslate $lport
            nport=$(echo $(( $RANDOM % 10000 + 10000 )))
            config="serverPort=$nport&webFolder=$folder/webapp&startMethod=server&speechModelsFolder=$folder/models&voiceCommand=glowspeak&translateEndpoint=http://localhost:$lport/translate" 
            nalaq config "$config" &
            sleep 3
            open http://localhost:$nport &
            break;;
        "n" )
            echo "You can later start the NaLaQ application by executing the command nalaq"
            break;;
        * ) echo "Invalid answer: $answer";;
    esac
done
