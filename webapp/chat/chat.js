async function init() {
    insertLayout()
    await initDevices()
//    populateChatlog("chatlog", "chatline", 50)
    document.querySelector("#message").focus()
}

function insertLayout() {
    const width = document.body.clientWidth - 10
    const height = document.body.clientHeight - 70
    const wtextarea = width /2 - 10
    const htextarea = 100
    const hbutton = 25
    const hbottom = htextarea + hbutton + 5
    applyTemplate("layout", "main", {
        width: width,
        height: height,
        wtextarea: wtextarea,
        htextarea: htextarea,
        hbutton: hbutton,
        hbottom: hbottom,
        maxhl: height - hbottom,
        maxhr: height
    })
}

async function initDevices() {
    navigator.getUserMedia = navigator.getUserMedia || navigator.webkitGetUserMedia || navigator.mozGetUserMedia || navigator.msGetUserMedia
    if (!navigator.getUserMedia)
        return alert("navigator getUserMedia not supported with this browser")
    var video = document.getElementById("video")
    var audio = document.getElementById("audio")
    var select, label, option
    await navigator.mediaDevices.getUserMedia({audio: true, video: true})
    navigator.mediaDevices.enumerateDevices().then(function(sourceInfos) {
        for (const sourceInfo of sourceInfos) {
            switch (sourceInfo.kind) {
                case "audioinput":
                    select = audio
                    label = sourceInfo.label || 'microphone'
                    break
                case "videoinput":
                    select = video
                    label = sourceInfo.label || 'camera'
                    break
                default:
                    select = null
                    console.log("Source type "+sourceInfo.kind+" ignored: "+sourceInfo.label)
                    continue
            }
            if (select) {
                option = document.createElement("option")
                option.value = sourceInfo.deviceId
                option.text = label
                select.add(option)
            }
        }
    })
}

function applyTemplate(templateId, targetId, values) {
    var html = document.querySelector("#"+templateId).innerHTML
    for (var key in values)
        html = html.split("{"+key+"}").join(values[key])
    if (targetId)
        document.querySelector("#"+targetId).innerHTML = html
    return html
} 

async function sendText(inputId, outputId, lineId) {
    const input = document.querySelector("#"+inputId)
    const text = input.value
    const response = await fetch("/api/nalaq", {
        method: 'POST',
        headers: {'Content-Type': 'text/plain', 'Accept': 'text/plain'},
        body: text
    }).then(r => r.text()).catch(e => e.toString())
    const chatlog = document.querySelector("#"+outputId)
    chatlog.innerHTML += applyTemplate(lineId, null, {prompt: "?", text: text})
                       + applyTemplate(lineId, null, {prompt: "=", text: response})
    input.value = ""
    chatlog.scrollTop = chatlog.scrollHeight
}

function setAudioDevice(select) {
    var audio = select.value
    var video = null
    var constraints = {
        audio: audio ? {deviceId: audio} : false,
        video: video ? {deviceId: video} : false
    }
    return navigator.mediaDevices.getUserMedia(constraints).then(stream => {
        window.stream = stream
        var display = document.getElementById("display")
        if (display)
            display.srcObject = stream
    }).catch(e => console.log("error on stream selection: "+e))
}

/****************************************** Functions below are for testing purposes only ****************************************************/

function populateChatlog(targetId, lineId, nblines) {
    var html = ""
    for (var i = 0; i < nblines; i++)
        html += applyTemplate(lineId, null, {prompt: (i%2)?"=":"?", text: generateText(20,200)})
    const chatlog = document.querySelector("#"+targetId)
    chatlog.innerHTML = html
    chatlog.scrollTop = chatlog.scrollHeight
}

function generateText(minChars, maxChars) {
    var txt = ""
    const nb = Math.round(Math.random()*(maxChars-minChars)+minChars)
    for (var c = 0; c < nb; c++) {
        var nc = Math.round(Math.random()*40)
        txt += (nc >= 26) ? ' ' : String.fromCharCode('a'.charCodeAt()+nc)
    }
    return txt
}

