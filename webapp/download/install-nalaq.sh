baseurl="$1"
jarname="nalaq-all.jar"
languages="install-languages.sh"
translator="translator.sh"
request="request.html"
folder="$HOME/nalaq"

if [ -z "$baseurl" ]; then
    echo "This script needs the reference url from which it was downloaded as its first parameter"
    exit 1
fi

start_time=$(date '+%Y-%m-%d %H:%M:%S')
echo "Looking for java installation ..."
version=$(which java|java -version 2>&1|grep version)
if [ -z "$version" ]; then
    echo "No java executable found, please install a JVM"
    exit 2
fi
echo "Found java executable $version"

echo "Looking for previous NaLaQ installation ..."
if [ -d "$folder" ]; then
    echo "$folder already exists, updating installation ..."
elif [ -f "$folder" ]; then
    echo "$folder is an existing file, please delete or rename it"
    exit 3
else
    echo "Creating new folder $folder ..."
    result=$(mkdir "$folder" && echo yes || echo no)
    if [ "$result" = "yes" ]; then
        echo "Folder $folder created"
    else
        echo "Could not create $folder"
        exit 4
    fi
fi

echo "Downloading jar file ..."
jar="$baseurl/$jarname"
result=$(curl -sLfo "$folder/$jarname" "$jar" && echo yes || echo no)
if [ "$result" = "yes" ]; then
    echo "Jar file downloaded"
else
    echo "Could not download $jar"
    exit 5
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

echo "Downloading request HTML script ..."
result=$(curl -sLfo "$folder/$request" "$baseurl/$request" && echo yes || echo no)
if [ "$result" = "yes" ]; then
    echo "Request HTML script downloaded"
else
    echo "Could not download $baseurl/$request"
    exit 7
fi

echo "Downloading translation demo ..."
result=$(curl -sLfo "$folder/$translator" "$baseurl/$translator" && echo yes || echo no)
if [ "$result" = "yes" ]; then
    echo "Translation demo downloaded"
else
    echo "Could not download $baseurl/$translator"
    exit 8
fi

echo "Downloading languages script ..."
result=$(curl -sLfo "$folder/$languages" "$baseurl/$languages" && echo yes || echo no)
if [ "$result" = "yes" ]; then
    echo "Language script file downloaded"
else
    echo "Could not download $baseurl/$languages"
    exit 9
fi

while true; do
    read -n 1 -p "Do you want to install the language files ? [y/n]" answer < /dev/tty
    echo ""
    case $answer in
        "y" )
            previous=$(pwd)
            cd $folder
            bash $folder/$languages || exit 10
            cd previous
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
    read -n 1 -p "Do you want to start the server[s], the console[c], the translator[t] or none[n] ?" answer < /dev/tty
    echo ""
    case $answer in
        "c" ) nalaq < /dev/tty; break;;
        "s" )
            port=$(echo $(( $RANDOM % 10000 + 10000 )))
            nalaq $port &
            sleep 3
            open http://localhost:$port &
            break;;
        "t" )
            if [ -d $folder/modesl && -d $folder/glow-speak ]; then
                bash $folder/$translator $folder/models < /dev/tty
            else
                echo "Cannot start the translation demo: language files are not installed"
            fi
            break;;
        "n" )
            echo "You can later start the NaLaQ application by executing the command nalaq"
            break;;
        * ) echo "Invalid answer: $answer";;
    esac
done
