const languages = {}
const translation = ["", ""]
const saveRecording = false

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
    const code = document.querySelector("#lang"+lang+"select").value
    translation[lang-1] = {
        code: code,
        speak: (code == "en") ? "speak english" : (await translationRequest("speak "+languages[code].name.toLowerCase(), "en", code)),
        listen: (code == "en") ? "listening english" : (await translationRequest("listening "+languages[code].name.toLowerCase(), "en", code))
    }
    setButtonStatus(lang, "speak")
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
    var code = setButtonStatus(lang, "listen")
    if (!languages[code].model) {
        setButtonStatus(lang, "speak")
        return alert("Language does not support speech to text: "+languages[code].name)
    }
    recordAudio(device, lang)
}

function recordAudio(device, lang) {
    navigator.mediaDevices.getUserMedia({audio:{deviceId:device}}).then(stream => {
        const mediaRecorder = new MediaRecorder(stream)
        mediaRecorder.ondataavailable = (e) => sendAudio(e.data, lang)
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
    if (saveRecording)
        return saveAudioFile(blob)
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

async function saveAudioFile(blob) {
    const type = blob.type.split(";")[0]
    const extension = type.split("/")[1].replace("x-","")
    const filename = new Date().getTime()+"."+extension
    fetch("/audio/"+filename, {
        method: 'PUT',
        headers: {'Content-Type': type, 'accept': "text/plain"},
        body: await blob.arrayBuffer()
    }).then(r => httpResponse(r, "text")).then(t => alert(t)).catch(e => alert(e))
    playAudio(blob)
}

function setButtonStatus(lang, status) {
    document.querySelector("#lang"+lang+"button").value = translation[lang-1][status]
    return translation[lang-1].code
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
