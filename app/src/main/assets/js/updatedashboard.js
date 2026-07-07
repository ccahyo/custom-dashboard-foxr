
/* ===================================================== */
/* CREATE NUMBERS */
/* ===================================================== */

const gauge = document.getElementById('gauge');

/* ===================================================== */
/* DOM CACHE */
/* ===================================================== */

const dom = {

    needle: document.getElementById("needle"),
    needleTail: document.getElementById("needle-tail"),

    speed: document.getElementById("speed"),
    rpm: document.getElementById("rpm"),

    battCurr: document.getElementById("battCurr"),
    battPow: document.getElementById("battPow"),
    battVolt: document.getElementById("battVolt"),
    battSOC: document.getElementById("battSOC"),
    battTemp: document.getElementById("battTemp"),

    curmode: document.getElementById("curmode"),

    speedSourceIcon: document.getElementById("speedSourceIcon"),

    hoursMinutes: document.getElementById("hours-minutes")

};

const numbers = [-2,-1,0,1,2,3,4,5,6,7,8,9];

numbers.forEach((n,index)=>{

    let angle = 38 + (index * 26);

    const num = document.createElement('div');

    num.className = 'number';

    if(n < 0){
        num.classList.add('green');
        num.innerText = '+' + (0 - n);
    }else{
        if(n > 4)
            num.classList.add('red');
        num.innerText = n;
    }

    const radius = 250;

    const x = Math.cos(angle*Math.PI/180)*radius;
    const y = Math.sin(angle*Math.PI/180)*radius;

    num.style.left = `calc(50% + ${x}px - 30px)`;
    num.style.top  = `calc(49% + ${y}px - 40px)`;

    gauge.appendChild(num);
});

/* ===================================================== */
/* CREATE TICKS */
/* ===================================================== */

for (let i=0;i<60;i++){

    const tick = document.createElement('div');

    tick.className = 'tick';

    if (i > 33)
        tick.classList.add('red');
    else if (i < 13)
        tick.classList.add('green');

    tick.style.transform =
        `translateX(-50%) rotate(${-247.5 + i*5.2}deg)`;

    gauge.appendChild(tick);
}

/* SMALL TICKS */

for (let i=0;i<120;i++){

    const tick = document.createElement('div');

    tick.className = 'tick small';

    tick.style.transform =
        `translateX(-50%) rotate(${-250.1 + i*2.6}deg)`;

    gauge.appendChild(tick);
}

/* ===================================================== */
/* BATTERY CELLS */
/* ===================================================== */

const batteryBar = document.getElementById('batteryBar');
const batteryCells = [];

for (let i=0;i<10;i++){
    const cell = document.createElement('div');
    cell.className = 'cell';
    batteryBar.appendChild(cell);
    batteryCells.push(cell);
}

function getBatteryColorClass(activeCount) {
    if (activeCount > 50) return 'green';
    if (activeCount > 20) return 'yellow';
    return 'red';
}

function updateBatteryDisplay(socValue) {
    batteryCells.forEach((cell, index) => {
        if (index < Math.round(socValue/10)) {
            cell.className = 'cell ' + getBatteryColorClass(socValue);
        } else {
            cell.className = 'cell';
        }
    });
}

/* ===================================================== */
/* TEMP BARS */
/* ===================================================== */

const bldcTempBars = document.getElementById('bldcTempBars');
const ctrlTempBars = document.getElementById('ctrlTempBars');
const bldcBarElements = [];
const ctrlBarElements = [];

for (let i=0;i<10;i++){
    let bldcBar = document.createElement('div');
    bldcBar.className = 'bar';
    bldcTempBars.appendChild(bldcBar);
    bldcBarElements.push(bldcBar);

    let ctrlBar = document.createElement('div');
    ctrlBar.className = 'bar';
    ctrlTempBars.appendChild(ctrlBar);
    ctrlBarElements.push(ctrlBar);
}

function getTempColorClass(activeCount) {
    if (activeCount > 90) return 'red';
    if (activeCount > 70)  return 'yellow';
    if (activeCount > 30)  return 'green';
    if (activeCount > 10)  return 'cyan';
    return 'blue';
}

function updateBarDisplay(elements, value) {
    elements.forEach((bar, index) => {
        if (index < Math.round(value/10)) {
            bar.className = 'bar ' + getTempColorClass(value);
        } else {
            bar.className = 'bar';
        }
    });
}

/* ===================================================== */
/* DATA */
/* ===================================================== */

//let tripMeter = 123.456;
//let OdoMeter = 1234567;
let rpm = 0;
let speed = 0;
let ampere = 0;

/*
 * Smooth ampere needle animation.
 *
 * targetAmpere follows incoming data immediately.
 * displayAmpere moves gradually toward targetAmpere using requestAnimationFrame.
 */
let targetAmpere = 0;
let displayAmpere = 0;
let needleAnimationStarted = false;

let battPow = 84;
let battVolt = 84;
let battSOC = 0;
let battTemp = 1;
let bldcTemp = 7;
let ctrlTemp = 3;

/* ===================================================== */
/* Connection */
/* ===================================================== */

/* ===================================================== */
/* UPDATE */
/* ===================================================== */



function clampAmpere(value) {
    if (value > 100) return 100;
    if (value < -40) return -40;
    return value;
}

function ampereToDegree(value) {
    return 192.5 + ((value + 100) / 200) * 515;
}

function renderNeedle(value) {
    const degree = ampereToDegree(value);

    dom.needle.style.transform =
        `rotate(${degree}deg)`;

    dom.needleTail.style.transform =
        `rotate(${degree - 180}deg)`;
}

function startNeedleAnimation() {
    if (needleAnimationStarted) {
        return;
    }

    needleAnimationStarted = true;

    function animateNeedle() {
        /*
         * Easing factor:
         * - smaller value = smoother/slower
         * - larger value = faster/more responsive
         */
        const easing = 0.09;//0.12;

        displayAmpere += (targetAmpere - displayAmpere) * easing;

        if (Math.abs(targetAmpere - displayAmpere) < 0.03) {
            displayAmpere = targetAmpere;
        }

        renderNeedle(displayAmpere);

        requestAnimationFrame(animateNeedle);
    }

    requestAnimationFrame(animateNeedle);
}

function updateDashboard(data){
    
    // Update variabel global agar digunakan oleh updateDashboard()
    if (data.speed !== undefined || data.gpsSpeed !== undefined) {

        const sourceIcon = dom.speedSourceIcon;

        if (data.speedSource === 1) {

            speed = data.gpsSpeed ?? 0;

            //sourceIcon.innerText = "🛰";   // GPS
            //sourceIcon.innerText = "🛰️";   // GPS
            sourceIcon.innerText = "📡";
            sourceIcon.style.display = "block";

        } else {

            speed = data.speed ?? 0;

            //sourceIcon.innerText = "🎛️";    // VOTOL
            //sourceIcon.innerText = "⚙️"; // VOTOL
            sourceIcon.innerText = "";    // VOTOL
            sourceIcon.style.display = "none";

        }

        dom.speed.innerText = Math.round(speed);

    }
    if (data.rpm !== undefined) {
        rpm = data.rpm;
        dom.rpm.innerText = rpm;
    }
    if (data.amps !== undefined) {
        ampere = data.amps;
        targetAmpere = clampAmpere(0 - ampere);

        // Nilai teks tetap mengikuti data aktual.
        if(dom.battCurr) {
            if (ampere < 0)
        dom.battCurr.innerText = 0 - ampere + ' A';
            else
        dom.battCurr.innerText = '+' + ampere + ' A';
        }
    }
    if (data.power !== undefined) {
        battPow = data.power;
        if (dom.battPow) {
            if (battPow < 1)
        dom.battPow.innerText = 0 - battPow + '  W';
            else
        dom.battPow.innerText = '+' + battPow + '  W';
        }
    }
    if (data.volts !== undefined) {
        battVolt = data.volts;
        dom.battVolt.innerText = battVolt + '  V';
    }
    if (data.soc !== undefined) {
        battSOC = data.soc;
        dom.battSOC.innerText = battSOC + '%';
        if(typeof updateBatteryDisplay === "function") updateBatteryDisplay(battSOC);
    }
//    if (d.odometer !== undefined) {
//        odometer = d.odometer/1000;
//        document.getElementById('odoMeter').innerText = odometer.toFixed(0);
//        trip = d.odometer;
//        document.getElementById('tripMeter').innerText = trip.toFixed(3);
//    }

    // Mapping Suhu (Nested Object dari types.go)
    if (data.temps) {
        bldcTemp = data.temps.motor ?? bldcTemp;
        ctrlTemp = data.temps.ctrl ?? ctrlTemp;
        battTemp = data.temps.batt ?? battTemp;
        
        if(typeof updateBatteryDisplay === "function") updateBarDisplay(bldcBarElements, bldcTemp);
        if(typeof updateBatteryDisplay === "function") updateBarDisplay(ctrlBarElements, ctrlTemp);
        if(dom.battTemp) dom.battTemp.innerText = battTemp + ' ℃';
    }

    /* mode */
    if (data.mode !== undefined) {
        dom.curmode.innerText = data.mode;
    }

    /*
     * Needle animation is handled by requestAnimationFrame.
     * targetAmpere is updated above; displayAmpere moves smoothly.
     */
}

function updateDateTime() {
    const now = new Date();

    /*
    const hari = [
        "Minggu", "Senin", "Selasa", "Rabu",
        "Kamis", "Jumat", "Sabtu"
    ];

    const bulan = [
        "Januari", "Februari", "Maret", "April",
        "Mei", "Juni", "Juli", "Agustus",
        "September", "Oktober", "November", "Desember"
    ];

    const dayName = hari[now.getDay()];
    const date = String(now.getDate()).padStart(2, "0");
    const monthName = bulan[now.getMonth()];
    const year = now.getFullYear();
    */

    const hour = String(now.getHours()).padStart(1, "0");
    const minute = String(now.getMinutes()).padStart(2, "0");
    const second = String(now.getSeconds()).padStart(2, "0");

    /*
    document.getElementById("dateText").innerText =
        `${dayName}, ${date} ${monthName} ${year}`;

    document.getElementById("timeText").innerText =
        `${hour}:${minute}:${second}`;
    */

    dom.hoursMinutes.innerText =
        `${hour}:${minute}`;
}

function connect(){
    const protocol=location.protocol==="https:"?"wss:":"ws:";
    const socket=new WebSocket(`${protocol}//${location.host}/ws`);

    socket.onopen = () => {
//        document.getElementById('cell-label').innerText = "connecting";
        return
    }

    socket.onmessage=(event)=>{
        try{
            const msg=JSON.parse(event.data);
            if(msg.type!=="dashboard_data") return;
            updateDashboard(msg.data||{});
            updateDateTime();
        }catch(err){}
    };

    socket.onclose=()=>setTimeout(connect,2000);
    socket.onerror=()=>socket.close();
}

connect();
startNeedleAnimation();

/*
 * updateDashboard() opens one WebSocket connection.
 * Do not call it repeatedly with setInterval.
 */