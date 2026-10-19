source "$(dirname "${BASH_SOURCE[0]}")/t.sh"
coproc ADBSH { /c/Android/android-sdk/platform-tools/adb.exe -s $ADB_SERIAL shell; }
ish() { echo "$1" >&${ADBSH[1]}; }
