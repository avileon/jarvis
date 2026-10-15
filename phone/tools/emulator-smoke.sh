#!/usr/bin/env bash
# Emulator smoke test for the JARVIS assistant debug build (run by .github/workflows/assistant-apk.yml).
# Exercises the real Android pieces end to end and prints evidence ("SMOKE PASS/FAIL") to the CI log:
# contacts provider → Hebrew command → contact matching → TelecomManager call, the assistant role +
# VoiceInteractionService, the assistant overlay, and the microphone foreground-service start rules.
set -u
PKG=il.co.vibit.jarvis.assistant
APK=phone/app/build/outputs/apk/debug/app-debug.apk
DBG=$PKG.debug
RCV=$PKG/.debug.DebugReceiver
FAILS=0
pass() { echo "SMOKE PASS: $*"; }
fail() { echo "SMOKE FAIL: $*"; FAILS=$((FAILS+1)); }
section() { echo; echo "================ $* ================"; }
bc() { adb shell am broadcast -a "$DBG.$1" -n "$RCV" "${@:2}" >/dev/null; }
applog() { adb logcat -d -v brief -s JarvisDebug:I AssistantEngine:I ListeningService:V JarvisVIS:V WakeWord:V SpeechInput:V JarvisTts:V CallPlacer:V; }
texts() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > /tmp/ui.xml
  python3 - <<'EOF'
import re, html
x = open('/tmp/ui.xml', encoding='utf-8', errors='replace').read()
items = [html.unescape(t) for t in re.findall(r'(?:text|content-desc)="([^"]+)"', x) if t.strip()]
print("UI TEXT:", " | ".join(items))
EOF
}
shot() {
  adb exec-out screencap -p > "/tmp/$1.png"
  python3 - "$1" <<'EOF'
import sys, base64, io
from PIL import Image
name = sys.argv[1]
im = Image.open(f"/tmp/{name}.png").convert("RGB")
im.thumbnail((320, 720))
buf = io.BytesIO(); im.save(buf, "JPEG", quality=45)
b = base64.b64encode(buf.getvalue()).decode()
print(f"SHOT-BEGIN {name} {len(b)}")
for i in range(0, len(b), 200): print(b[i:i+200])
print(f"SHOT-END {name}")
EOF
}
call_check() { # $1 = expected number
  sleep 1
  if applog | grep -q "call $1 -> PLACED"; then pass "placed call to $1"; else fail "no call to $1"; fi
  adb shell dumpsys telecom | grep -m3 -E "Call TC@|callState|state=" | sed 's/^/  telecom: /'
  adb shell input keyevent KEYCODE_ENDCALL; sleep 2
}
say() { # typed command into the engine, then wait for TTS/turn
  echo ">>> user: $1"
  bc COMMAND --es text "$1"
  sleep "${2:-7}"
}

section "device"
adb shell getprop ro.build.version.release; adb shell getprop ro.build.version.sdk
adb install -r -g "$APK" | tail -1
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell input keyevent 82
adb logcat -c

section "launch"
adb shell am start -W -n $PKG/.ui.MainActivity | grep -E "Status|TotalTime"
sleep 4
texts
shot main
bc SEED_CONTACTS; sleep 3
bc STATUS; sleep 8
applog | grep -E "seeded|tts=|stt=|recognizers=|vis.active" | sed 's/^/  /'

section "commands (typed into the engine; recogniser bypassed)"
say "היי ג׳ארביס, תתקשרי לשלי"; call_check 0501111111
say "ג׳ארביס, תחייגי לבר"; call_check 0522222222
say "ג׳ארביס, תתקשרי לגל לנייד" 5
if applog | grep -q "turn user='ג׳ארביס, תתקשרי לגל לנייד'.*expectAnswer=true"; then pass "ambiguous Gal → asked which"; else fail "Gal not asked"; fi
say "גל מזרחי"; call_check 0544444444
say "ג׳ארביס, חייגי למספר 0501234567" 5
if applog | grep -q "turn user='ג׳ארביס, חייגי למספר 0501234567'.*expectAnswer=true"; then pass "number read back for confirmation"; else fail "number not confirmed"; fi
say "כן"; call_check 0501234567
say "תתקשרי לשלו כהן" 5
say "לא" 4
if applog | grep -q "call 0501111111 -> PLACED" && [ "$(applog | grep -c 'call 0501111111 -> PLACED')" -gt 1 ]; then fail "dialled on a weak match"; else pass "weak match did not dial"; fi
say "היי ג׳ארביס, מה השעה?" 5
say "ג׳ארביס, תפסיקי להקשיב" 5
applog | grep "turn user=" | sed 's/^/  /'

section "screenshot after conversation"
adb shell am start -n $PKG/.ui.MainActivity >/dev/null; sleep 3
texts
shot conversation

section "microphone FGS from background, NOT default assistant"
adb shell cmd role get-role-holders android.app.role.ASSISTANT
adb shell input keyevent KEYCODE_HOME; sleep 2
adb logcat -c
bc START_LISTENING; sleep 4
applog | grep -E "start listening|cannot start|refused|startForeground" | sed 's/^/  /'
adb shell dumpsys activity services $PKG/.service.ListeningService | grep -E "isForeground|foregroundServiceType|ServiceRecord" | sed 's/^/  /'

section "make JARVIS the default digital assistant"
adb shell cmd role add-role-holder android.app.role.ASSISTANT $PKG 0
sleep 3
adb shell cmd role get-role-holders android.app.role.ASSISTANT
adb shell settings get secure voice_interaction_service
adb shell settings get secure assistant
adb shell settings get secure voice_recognition_service
adb shell dumpsys voiceinteraction | grep -E -i "mComponent|mValid|Service info|parse|session|recognition|ActiveSession|mBound" | head -20 | sed 's/^/  /'
if adb shell settings get secure voice_interaction_service | grep -q $PKG; then pass "JARVIS is the active VoiceInteractionService"; else fail "VIS not active"; fi

section "microphone FGS from background, AS default assistant"
adb shell am force-stop $PKG; sleep 1
adb shell cmd role add-role-holder android.app.role.ASSISTANT $PKG 0; sleep 4
adb logcat -c
bc START_LISTENING; sleep 5
applog | grep -E "start listening|onReady|cannot start|refused|wake word|listener error" | sed 's/^/  /'
if adb shell dumpsys activity services $PKG/.service.ListeningService | grep -q "isForeground=true"; then
  pass "microphone FGS started from background while default assistant"
else
  fail "microphone FGS not foreground"
fi
adb shell dumpsys activity services $PKG/.service.ListeningService | grep -E "isForeground|foregroundServiceType|types=" | sed 's/^/  /'

section "screen off for 20 s"
adb shell input keyevent KEYCODE_SLEEP; sleep 20
adb shell dumpsys activity services $PKG/.service.ListeningService | grep -E "isForeground" | sed 's/^/  screen-off: /'
applog | grep -E "WakeWord|listener error|AudioRecord" | tail -5 | sed 's/^/  /'
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; sleep 2

section "assistant overlay (system session)"
adb logcat -c
adb shell cmd voiceinteraction show; sleep 4
texts
shot overlay
if grep -q "J.A.R.V.I.S" /tmp/ui.xml; then pass "assist session overlay shown"; else
  adb shell input keyevent KEYCODE_ASSIST; sleep 4; texts
  if grep -q "J.A.R.V.I.S" /tmp/ui.xml; then pass "assist overlay via KEYCODE_ASSIST"; else fail "overlay not shown"; fi
fi
adb shell input keyevent KEYCODE_BACK; sleep 1

section "settings screen"
adb shell am start -n $PKG/.ui.MainActivity >/dev/null; sleep 3
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > /tmp/ui.xml
XY=$(python3 - <<'EOF'
import re
x = open('/tmp/ui.xml', encoding='utf-8').read()
m = re.search(r'content-desc="הגדרות"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
print(f"{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}" if m else "")
EOF
)
if [ -n "$XY" ]; then adb shell input tap $XY; sleep 4; texts; shot settings; else fail "settings button not found"; fi

section "crashes"
if adb logcat -d -b crash | grep -q "$PKG"; then adb logcat -d -b crash | tail -40; fail "app crashed"; else pass "no crashes"; fi

echo; echo "SMOKE RESULT: $FAILS failure(s)"
exit 0
