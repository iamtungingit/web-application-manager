package com.iamtungingit.wam.util

import com.iamtungingit.wam.util.PathSanitizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * New-project templates (spec §14): Blank, Hello (offline), Calculator demo (bridge),
 * Online site. Generates manifest.json + starter files.
 */
object Templates {

    const val TEMPLATE_BLANK = 0
    const val TEMPLATE_HELLO = 1
    const val TEMPLATE_CALCULATOR = 2
    const val TEMPLATE_ONLINE = 3

    fun writeTemplate(dir: File, templateIndex: Int) {
        val name = dir.name
        when (templateIndex) {
            TEMPLATE_ONLINE -> {
                File(dir, "manifest.json").writeText(
                    manifestJson(name, "online", "https://example.com")
                )
            }
            TEMPLATE_CALCULATOR -> {
                File(dir, "manifest.json").writeText(manifestJson(name, "offline", "index.html", bridge = true))
                File(dir, "index.html").writeText(CALC_HTML)
                File(dir, "style.css").writeText(CALC_CSS)
                File(dir, "app.js").writeText(CALC_JS)
            }
            TEMPLATE_HELLO -> {
                File(dir, "manifest.json").writeText(manifestJson(name, "offline", "index.html"))
                File(dir, "index.html").writeText(HELLO_HTML)
                File(dir, "style.css").writeText(HELLO_CSS)
                File(dir, "app.js").writeText(HELLO_JS)
            }
            else -> {
                File(dir, "manifest.json").writeText(manifestJson(name, "offline", "index.html"))
                File(dir, "index.html").writeText(BLANK_HTML)
            }
        }
    }

    /** Auto-generates a manifest for imported folders without one (spec §13). */
    fun generateManifestFor(dir: File, projectName: String) {
        val hasIndex = File(dir, "index.html").isFile
        val entry = if (hasIndex) "index.html" else
            (dir.walkTopDown().firstOrNull { it.isFile && it.extension.lowercase() == "html" }?.relativeToOrNull(dir)?.path
                ?.replace(File.separatorChar, '/')) ?: "index.html"
        File(dir, "manifest.json").writeText(manifestJson(projectName, "offline", entry))
    }

    /**
     * v1.7.0 (user: "jsapi examples on new project"): writes a self-contained
     * api-examples.html that demonstrates every official WAP.* call with working
     * buttons and a status log. Called when the "Include JS API examples" switch
     * in the New-project dialog is on (default ON) for offline templates.
     */
    fun writeApiExamples(dir: File) {
        File(dir, "api-examples.html").writeText(API_EXAMPLES_HTML)
    }

    // ---- Create-dialog templates (v1.5.0 user request: starter content for the
    // single Create button: html / css / js exactly as specified). ----

    const val CREATE_TYPE_HTML = 0
    const val CREATE_TYPE_CSS = 1
    const val CREATE_TYPE_JS = 2
    const val CREATE_TYPE_FOLDER = 3
    const val CREATE_TYPE_OTHER = 4

    fun starterContent(type: Int): String? = when (type) {
        CREATE_TYPE_HTML -> CREATE_HTML
        CREATE_TYPE_CSS -> CREATE_CSS
        CREATE_TYPE_JS -> CREATE_JS
        else -> null
    }

    /** Writes an empty [file] (with starter content for html/css/js). */
    fun writeStarter(file: File, type: Int) {
        file.parentFile?.mkdirs()
        when (type) {
            CREATE_TYPE_HTML -> file.writeText(CREATE_HTML)
            CREATE_TYPE_CSS -> file.writeText(CREATE_CSS)
            CREATE_TYPE_JS -> file.writeText(CREATE_JS)
            else -> file.writeText("")
        }
    }

    private val CREATE_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
    <!-- Sets the character encoding -->
    <meta charset="UTF-8">
    <!-- Makes the page responsive -->
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <!-- Sets the page title -->
    <title>My App</title>
    <!-- Connects the CSS file -->
    <link rel="stylesheet" href="style.css">
</head>
<body>

    <!-- JavaScript file -->
    <script src="script.js"></script>
</body>
</html>
"""

    private val CREATE_CSS = """/* Resets default spacing */
* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

/* Basic page styling */
body {
    background-color: white;
    font-family: Arial, sans-serif;
}
"""

    private val CREATE_JS = """// Runs when the JavaScript file loads
console.log("App is running!");
"""

    /** v1.7.0: the ready-made JS API demo written into new projects. */
    private val API_EXAMPLES_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>WAP API examples</title>
<style>
  body { margin:0; padding:16px; background:#f4f7f4; color:#1c2b1e;
         font-family:system-ui,sans-serif; }
  h1 { font-size:1.2rem; margin:0 0 4px; }
  p.tip { font-size:.8rem; opacity:.7; margin:0 0 12px; }
  .grid { display:grid; grid-template-columns:1fr 1fr; gap:8px; }
  button { padding:12px 8px; border:0; border-radius:12px; font-size:.85rem;
           background:#2e7d32; color:#fff; }
  button.gray { background:#546e7a; }
  pre { background:#10240f; color:#c8e6c9; padding:12px; border-radius:12px;
        font-size:.72rem; min-height:120px; overflow:auto; white-space:pre-wrap; }
</style>
</head>
<body>
<h1>WAP API examples</h1>
<p class="tip">Every call below is gated: enable the matching toggle in
Apps &rarr; long-press &rarr; App settings, or the call rejects with an error.</p>
<div class="grid">
  <button onclick="perms()">WAP.permissions.get()</button>
  <button onclick="save()">WAP.data.writeText()</button>
  <button onclick="load()">WAP.data.readText()</button>
  <button onclick="info()">WAP.device.info()</button>
  <button onclick="vibrate()">WAP.device.vibrate()</button>
  <button onclick="clipCopy()">WAP.clipboard.write()</button>
  <button onclick="clipRead()">WAP.clipboard.read() *</button>
  <button onclick="notify()">WAP.notify()</button>
  <button onclick="speak()">WAP.tts.speak()</button>
  <button onclick="shoot()">WAP.camera.capture()</button>
  <button onclick="record()">WAP.mic.record()</button>
  <button onclick="shareText()">WAP.share.text()</button>
  <button onclick="exportFile()">WAP.storage.export()</button>
  <button class="gray" onclick="clearLog()">clear log</button>
</div>
<pre id="log">Ready.</pre>
<script>
function log(label, value) {
  var el = document.getElementById('log');
  el.textContent = label + ': ' + (value === undefined ? 'ok' : value) + '\n' + el.textContent;
}
function clearLog() { document.getElementById('log').textContent = 'Ready.'; }
function run(label, p) {
  p.then(function (v) { log(label, typeof v === 'object' ? JSON.stringify(v) : v); })
   .catch(function (e) { log(label, 'ERROR — ' + e.message); });
}
function perms()   { run('permissions', WAP.permissions.get()); }
function save()    { run('data.writeText', WAP.data.writeText('note.txt', 'saved at ' + new Date().toLocaleTimeString())); }
function load()    { run('data.readText', WAP.data.readText('note.txt')); }
function info()    { run('device.info', WAP.device.info()); }
function vibrate() { run('device.vibrate', WAP.device.vibrate(200)); }
function clipCopy(){ run('clipboard.write', WAP.clipboard.write('Hello from WAP!')); }
function clipRead(){ run('clipboard.read', WAP.clipboard.read()); }
function notify()  { run('notify', WAP.notify('Hello!', 'Sent by the JS API example.')); }
function speak()   { run('tts.speak', WAP.tts.speak('Hello from Web Application Manager')); }
function shareText(){ run('share.text', WAP.share.text('Made with WAM!')); }
function shoot() {
  WAP.camera.capture().then(function (photo) {
    log('camera.capture', JSON.stringify(photo));
  }).catch(function (e) { log('camera.capture', 'ERROR — ' + e.message); });
}
function record() {
  log('mic.record', 'recording 3 s…');
  WAP.mic.record({ seconds: 3 })
    .then(function (r) { log('mic.record', JSON.stringify(r)); })
    .catch(function (e) { log('mic.record', 'ERROR — ' + e.message); });
}
function exportFile() { run('storage.export', WAP.storage.export('note.txt')); }
WAP.permissions.get().then(function (p) {
  log('permissions', JSON.stringify(p));
}).catch(function () {});
</script>
</body>
</html>
"""

    private fun manifestJson(name: String, type: String, entry: String, bridge: Boolean = false): String {
        val o = JSONObject()
            .put("manifestVersion", 1)
            .put("id", PathSanitizer.suggestId(name))
            .put("name", name)
            .put("version", "1.0.0")
            .put("description", "Created with Web Application Manager")
            .put("type", type)
            .put("entry", entry)
        o.put("display", JSONObject().put("theme", "system"))
        if (type == "offline") {
            o.put("widget", JSONObject()
                .put("enabled", true)
                .put("label", name.take(24))
                .put("style", JSONObject().put("shape", "rounded").put("showLabel", true)))
            o.put("launch", JSONObject().put("allowedEntries", JSONArray().put(entry.substringBefore('?'))))
            o.put("data", JSONObject().put("persistent", true).put("bridge", bridge))
        }
        return o.toString(2)
    }

    private val BLANK_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Blank WAP</title>
<style>body{font-family:sans-serif;display:grid;place-items:center;height:100vh;margin:0;background:#f6f8f6;color:#243b27}main{text-align:center}</style>
</head>
<body>
<main>
  <h1>Blank WAP</h1>
  <p>Edit <code>index.html</code> in the WAM editor, then Package.</p>
</main>
</body>
</html>
"""

    private val HELLO_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Hello</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<main class="card">
  <h1>Hello from WAM!</h1>
  <p id="counter">Opened 0 time(s)</p>
  <button id="inc">Tap me</button>
</main>
<script src="app.js"></script>
</body>
</html>
"""

    private val HELLO_CSS = """body {
  margin: 0;
  min-height: 100vh;
  display: grid;
  place-items: center;
  /* Light, bright page (user report: the old dark gradient read as a "black
     screen" on a low-brightness LCD). Light pages are also what force-dark
     darkens well, so the look stays correct in every theme. */
  background: #f1f8f2;
  color: #1b3a1f;
  font-family: system-ui, sans-serif;
}
.card {
  background: #ffffff;
  padding: 2rem 2.5rem;
  border-radius: 20px;
  text-align: center;
  border: 1px solid #d5e8d8;
  box-shadow: 0 10px 30px rgba(27, 94, 32, .18);
}
h1 {
  margin: 0 0 .4rem;
  color: #1b5e20;
}
#counter {
  margin: .2rem 0 0;
  opacity: .75;
}
button {
  margin-top: 1rem;
  padding: .6rem 1.4rem;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #43a047, #2e7d32);
  color: #fff;
  font-weight: 700;
  font-size: 1rem;
}
"""

    private val HELLO_JS = """var n = 0;
var label = document.getElementById('counter');
document.getElementById('inc').addEventListener('click', function () {
  n++;
  label.textContent = 'Opened ' + n + ' time(s)';
});
"""

    private val CALC_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Calculator demo</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<main class="calc">
  <output id="display">0</output>
  <div class="keys">
    <button data-k="7">7</button><button data-k="8">8</button><button data-k="9">9</button><button data-k="/" class="op">÷</button>
    <button data-k="4">4</button><button data-k="5">5</button><button data-k="6">6</button><button data-k="*" class="op">×</button>
    <button data-k="1">1</button><button data-k="2">2</button><button data-k="3">3</button><button data-k="-" class="op">−</button>
    <button data-k="C" class="op">C</button><button data-k="0">0</button><button data-k="=" class="eq">=</button><button data-k="+" class="op">+</button>
  </div>
  <p id="best">Best result: –</p>
</main>
<script src="app.js"></script>
</body>
</html>
"""

    private val CALC_CSS = """body {
  margin: 0;
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: #101410;
  color: #e8f5e9;
  font-family: system-ui, sans-serif;
}
.calc { width: min(92vw, 340px); }
output {
  display: block;
  text-align: right;
  font-size: 2.6rem;
  padding: .4rem .8rem;
  background: #1c231c;
  border-radius: 14px;
  margin-bottom: .6rem;
}
.keys { display: grid; grid-template-columns: repeat(4, 1fr); gap: .5rem; }
button {
  padding: 1rem 0;
  font-size: 1.2rem;
  border: 0;
  border-radius: 12px;
  background: #263026;
  color: #e8f5e9;
}
button.op { background: #33512f; }
button.eq { background: #2e7d32; font-weight: 700; }
#best { opacity: .7; font-size: .85rem; }
"""

    private val CALC_JS = """// Calculator demo — uses the restricted WAP bridge to persist the best result
// in files/app_data/<id>/user_score.json (spec §9.6).
var current = '0';
var display = document.getElementById('display');
var best = null;

function render() { display.textContent = current; }

function press(k) {
  if (k === 'C') { current = '0'; }
  else if (k === '=') {
    try {
      var value = String(eval(current));
      if (!isFinite(Number(value))) { current = 'Error'; }
      else {
        current = value;
        if (best === null || Number(value) > best) {
          best = Number(value);
          saveBest();
        }
      }
    } catch (e) { current = 'Error'; }
  }
  else {
    if (current === '0' || current === 'Error') current = '';
    current += k;
  }
  render();
}

function saveBest() {
  var p = (window.WAP && window.WAP.data)
    ? window.WAP.data.writeJson('user_score.json', { best: best, at: Date.now() })
    : null;
  if (p && p.finally) { p.finally(showBestLabel); } else { showBestLabel(); }
}

function loadBest() {
  if (!(window.WAP && window.WAP.data)) { showBestLabel(); return; }
  window.WAP.data.readJson('user_score.json').then(function (v) {
    if (v && typeof v.best === 'number') { best = v.best; }
    showBestLabel();
  }).catch(function () { showBestLabel(); });
}

function showBestLabel() {
  document.getElementById('best').textContent =
    'Best result: ' + (best === null ? '–' : best);
}

document.querySelectorAll('.keys button').forEach(function (b) {
  b.addEventListener('click', function () { press(b.dataset.k); });
});
render();
loadBest();
"""
}
