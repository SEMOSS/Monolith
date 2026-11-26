
var socket = new WebSocket("ws://localhost:8080/Monolith_Dev/insightSocket?insightId=insightUUID");
socket.onmessage = onMessage;

function onMessage(event) {
    var pixelResponse = JSON.parse(event.data);
    displayPixelResponse(pixelResponse);
}

// function displayPixelResponse(pixelResponse) {
//     var content = document.getElementById("content");
//     var div = document.createElement("div");
    
//     var html = "<h3>New Pixel</h3>";
//     html += "<p>" +  JSON.stringify(pixelResponse) + "</p>";

//     div.innerHTML= html;
//     content.appendChild(div);
// }
function displayPixelResponse(pixelResponse) {
    var content = document.getElementById("content");
    var div = document.createElement("div");

    var title = document.createElement("h3");
    title.textContent = "New Pixel";
    div.appendChild(title);

    var para = document.createElement("p");
    para.textContent = JSON.stringify(pixelResponse); // Safe: textContent escapes HTML
    div.appendChild(para);

    content.appendChild(div);
}

function sendSocket() {
	var insightId = document.getElementById("insightId").value;
	var pixel = document.getElementById("pixel").value;
    var message = { 
    		"insightId" : insightId,
    		"pixel" : pixel
    };
    socket.send(JSON.stringify(message));
}