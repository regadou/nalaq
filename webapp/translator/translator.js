const languages = {}
const translation = ["", ""]
const audioFormats = "mp3,mp4,mpeg,wav,ogg,webm,x-aiff,basic".split(",")
const audioBitRate = 16000

function init() {
    loadLanguages()
    initDevices()
}

async function loadLanguages() {
    var lang1 = document.querySelector("#lang1select")
    var lang2 = document.querySelector("#lang2select")
    const langs = await fetch("/api/languages", {
        method: 'POST',
        headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
        body: "null"
    }).then(r => httpResponse(r, "json")).catch(e => alert(e))
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
    var type = null
    for (var f in audioFormats) {
        var format = "audio/"+audioFormats[f]
        if (MediaRecorder.isTypeSupported(format)) {
            type = format
            break
        }
    }
    if (type == null)
        return alert("Cannot record audio: no format supported in "+audioFormats.join(", "))
    navigator.mediaDevices.getUserMedia({audio:{deviceId:device}}).then(stream => {
        const mediaRecorder = new MediaRecorder(stream, {audioBitsPerSecond: audioBitRate, mimeType: type})
        mediaRecorder.ondataavailable = (e) => sendAudio(new Blob([e.data], { type: "audio/x-wav; codecs=0" }), lang)
        mediaRecorder.start()
        var options = {};
        var speechEvents = hark(stream, options);
        speechEvents.on('speaking', function() {
            console.log('speaking');
        });
        speechEvents.on('stopped_speaking', function() {
            console.log('stopped speaking');
            speechEvents.stop()
            stream.getTracks().forEach(t => t.stop())
            mediaRecorder.stop()
            setButtonStatus(lang, "speak")
        });
    }).catch(e => console.log("error on stream selection: "+e))
}

async function sendAudio(blob, lang) {
    const type = blob.type.split(";")[0]
    const code = document.querySelector("#lang"+lang+"select").value
    var txt = await fetch("/api/speech?lang="+code, {
        method: 'POST',
        headers: {'Content-Type': type, 'accept': "text/plain"},
        body: await blob.arrayBuffer()
    }).then(r => httpResponse(r, "text")).catch(e => alert(e))

    document.querySelector("#lang"+lang+"text").value = txt
    await translateText(lang)
    speakRequest(3-lang)
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
    return (await fetch("/api/translate?src="+src+"&dst="+dst, {
        method: 'POST',
        headers: {'Content-Type': 'application/json', 'Accept': 'text/plain'},
        body: JSON.stringify({text:txt})
    }).then(r => httpResponse(r, "text")).catch(e => alert(e)))
}

function speakRequest(lang) {
    var code = document.querySelector("#lang"+lang+"select").value
    var text = document.querySelector("#lang"+lang+"text").value
    fetch("/api/speak?lang="+code, {
        method: 'POST',
        headers: {'Content-Type': "application/json", 'accept': "audio/x-wav"},
        body: JSON.stringify({text:text})
    })
    .then(r => httpResponse(r, "blob")).then(b => playAudio(b)).catch(e => alert(e))
}

async function setButtonStatus(lang, status) {
    var code = document.querySelector("#lang"+lang+"select").value
    var txt = (code == "en") ? status+" english" : (await translationRequest(status+" "+languages[code].name.toLowerCase(), "en", code))
    document.querySelector("#lang"+lang+"button").value = txt
    return code
}

function playAudio(blob) {
    new Audio(URL.createObjectURL(blob)).play()
}

function httpResponse(res, type) {
    if (res.status >= 400)
        throw new Error(res.status+" "+res.statusText)
    switch (type) {
        case "text": return res.text()
        case "json": return res.json()
        case "blob": return res.blob()
    }
}
