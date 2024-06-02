const languages = {}
const translation = ["", ""]

function init() {
    loadLanguages()
    initDevices()
}

async function loadLanguages() {
    var lang1 = document.querySelector("#lang1select")
    var lang2 = document.querySelector("#lang2select")
    const langs = await fetch(document.URL, {
        method: 'POST',
        headers: {'Content-Type': 'text/x-kotlin', 'Accept': 'application/json'},
        body: "getLanguages()"
    }).then(r => r.json()).catch(e => alert(e))
    for (const lang of langs) {
        languages[lang.code] = lang
        var option = document.createElement("option")
        option.value = lang.code
        option.text = lang.name
        lang1.add(option)
        option = document.createElement("option")
        option.value = lang.code
        option.text = lang.name
        lang2.add(option)
    }
}

async function initDevices() {
    navigator.getUserMedia = navigator.getUserMedia || navigator.webkitGetUserMedia || navigator.mozGetUserMedia || navigator.msGetUserMedia
    if (!navigator.getUserMedia)
        return alert("navigator getUserMedia not supported with this browser")
    var option, audio = document.querySelector("#audio")
    await navigator.mediaDevices.getUserMedia({audio: true, video: false})
    navigator.mediaDevices.enumerateDevices().then(function(sourceInfos) {
        for (const sourceInfo of sourceInfos) {
            if (sourceInfo.kind == "audioinput") {
                option = document.createElement("option")
                option.value = sourceInfo.deviceId
                option.text = sourceInfo.label || 'microphone'
                audio.add(option)
            }
            else
                console.log("Source type "+sourceInfo.kind+" ignored: "+sourceInfo.label)
        }
    })
}

async function setLanguage(lang) {
    translation[lang-1] = await setButtonStatus(lang, "speak")
}

function listenSpeech(lang) {
    if (!translation[0]) {
        if (!translation[1])
            return alert("please select languages to translate")
        else
            return alert("first language not selected")
    }
    if (!translation[1])
        return alert("second language not selected")
    var audioDevice = document.querySelector("#audio").value
    if (!audioDevice)
        translateText(lang)
    else
        translateAudio(audioDevice, lang)
}

async function translateAudio(device, lang) {
    var code = await setButtonStatus(lang, "listening")
    if (!languages[code].model) {
        setButtonStatus(lang, "speak")
        return alert("Language does not support speech to text: "+languages[code].name)
    }
    recordAudio(device, lang)
}

function recordAudio(device, lang) {
    let chunks = []
    navigator.mediaDevices.getUserMedia({audio:{deviceId:device}}).then(stream => {
        const mediaRecorder = new MediaRecorder(stream)
        mediaRecorder.ondataavailable = (e) => {
            console.log('pushing '+JSON.stringify(e.data)+' to chunks#'+chunks.length);
            chunks.push(e.data);
        }
        mediaRecorder.start()
        var options = {};
        var speechEvents = hark(stream, options);
        speechEvents.on('speaking', function() {
            console.log('speaking');
        });
        speechEvents.on('stopped_speaking', function() {
            console.log('stopped speaking');
            speechEvents.stop()
            mediaRecorder.stop()
            setButtonStatus(lang, "speak")
            sendAudio(new Blob(chunks, { type: "audio/wav" }), lang)
        });
    }).catch(e => console.log("error on stream selection: "+e))
}

function sendAudio(blob, lang) {
    const filename = new Date().getTime() + ".wav"
    fetch(filename, {
        method: 'PUT',
        headers: {'Content-Type': 'audio/wav'},
        body: blob
    }).then(r => r.text()).catch(e => alert(e))
}

async function translateText(lang) {
    var lang2 = 3 - lang
    var src = document.querySelector("#lang"+lang+"select").value
    var dst = document.querySelector("#lang"+lang2+"select").value
    var lang1text = document.querySelector("#lang"+lang+"text")
    var lang2text = document.querySelector("#lang"+lang2+"text")
    var txt = lang1text.value
    lang2text.value = ""
    if (txt.trim()) {
        var response = (await translationRequest(txt, src, dst))
        lang2text.value = response
        return response
    }
    else
        return ""
}

async function translationRequest(txt, src, dst) {
    var code = JSON.stringify(txt)+'.translate('+JSON.stringify(src)+','+JSON.stringify(dst)+')'
    return (await fetch(document.URL, {
        method: 'POST',
        headers: {'Content-Type': 'text/x-kotlin', 'Accept': 'text/plain'},
        body: code
    }).then(r => r.text()).catch(e => alert(e)))
}

async function setButtonStatus(lang, status) {
    var code = document.querySelector("#lang"+lang+"select").value
    var txt = (code == "en") ? status+" english" : (await translationRequest(status+" "+languages[code].name.toLowerCase(), "en", code))
    document.querySelector("#lang"+lang+"button").value = txt
    return code
}
