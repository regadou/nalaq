echo "installing speech to text models (vosk) ..."
mkdir models
models='vosk-model-small-en-us-0.15
vosk-model-small-fr-0.22
vosk-model-small-es-0.42
vosk-model-small-pt-0.3
vosk-model-small-it-0.22
vosk-model-small-ca-0.4
vosk-model-small-cn-0.22
vosk-model-small-cs-0.4-rhasspy
vosk-model-small-de-0.15
vosk-model-small-eo-0.42
vosk-model-small-fa-0.5
vosk-model-small-hi-0.22
vosk-model-small-ja-0.22
vosk-model-small-ko-0.22
vosk-model-small-nl-0.22
vosk-model-small-pl-0.22
vosk-model-small-ru-0.22
vosk-model-small-sv-rhasspy-0.15
vosk-model-small-tr-0.3
vosk-model-small-uk-v3-small
vosk-model-small-vn-0.4
vosk-model-el-gr-0.7'
for model in $models; do
    echo "downloading $model ..."
    curl -Lo models/$model.zip https://alphacephei.com/vosk/models/$model.zip || exit 2
    unzip models/$model.zip -d models
done
rm models/*.zip

echo "installing text to speech (glow-speak) ..."
voices="en-us_mary_ann fr_siwis cmn_jing_li nl_rdh en-us_ljspeech fi_harri_tapani_ylilammi de_thorsten el_rapunzelina hu_diana_majlinger it_riccardo_fasol ko_kss ru_nikolaev es_tux sw_biblia_takatifu sv_talesyntese vi_vais1000"
git clone https://github.com/rhasspy/glow-speak.git || exit 3
cd glow-speak/
python3 -m venv .venv
source .venv/bin/activate
pip3 install --upgrade pip || exit 4
pip3 install --upgrade setuptools wheel || exit 5
pip3 install -f 'https://synesthesiam.github.io/prebuilt-apps/' -r requirements.txt || exit 6
python3 setup.py develop || exit 7
for voice in $voices; do
    echo "Downloading glow-speak voice $voice ..."
    glow-speak-download $voice || exit 8
done
deactivate
cd ..

script=glowspeak.sh
link="/usr/local/bin/glowspeak"
if [ -e $link ]; then
    echo "$link is already installed"
else
    echo '#!/bin/bash
if [ -z "$1" ]; then
    echo "Usage: glowspeak <voice>" 1>&2
    echo "Text to speak will be read from stdin" 1>&2
    echo "Audio to play will be sent to stdout" 1>&2
    echo "Info and error messages will be sent to stderr" 1>&2
    exit
fi

if [ "$2" = "debug" ]; then
    debug=yes
else
    debug=""
fi
test -n "$debug" && echo "glowspeak will use the voice $1" 1>&2
source '$(pwd)'/glow-speak/.venv/bin/activate
test -n "$debug" && echo "glowspeak is waiting for text input ..." 1>&2
glow-speak --output-file - -v $1
test -n "$debug" && echo "glowspeak is done reading text" 1>&2
deactivate
' > $script
    chmod 755 $script
    sudo ln -s $(pwd)/$script $link || exit 9
fi

echo "installing translator (LibreTranslate) ..."
python3 -m venv .venv
source .venv/bin/activate
pip3 install libretranslate

script=libtranslate.sh
link="/usr/local/bin/libretranslate"
if [ -e $link ]; then
    echo "$link is already installed"
else
    echo '#!/bin/bash
source '$(pwd)'/.venv/bin/activate
port=5000
if [ -n "$1" ]; then
    port=$1
fi
echo "starting libtranslate on port $port ..."
libretranslate --port $port  2>&1 > '$(pwd)'/server.log &
deactivate
' > $script
    chmod 755 $script
    sudo ln -s $(pwd)/$script $link
fi
deactivate
# TODO: create a service script to start the server at boot time

