"""Builds inj.dex from Inj.java. Needs a JDK (javac) and the Android SDK build-tools (d8) plus a platform android.jar.

    python build.py            # writes inj.dex next to this file
"""
import glob, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or r"C:\Android\android-sdk"
# d8 needs Java 11+ and the system default here is JDK 8, so it gets JAVA_HOME pointed at a newer one
JAVA_HOME = os.environ.get("PHONELAB_JAVA_HOME") or r"C:\Program Files\Java\jdk-21"
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe" if os.name == "nt" else "javac")


def newest(pattern):
    hits = sorted(glob.glob(os.path.join(SDK, pattern)))
    if not hits:
        sys.exit(f"nothing matches {pattern} under {SDK}")
    return hits[-1]


def main():
    d8 = newest(os.path.join("build-tools", "*", "d8.bat" if os.name == "nt" else "d8"))
    jar = newest(os.path.join("platforms", "android-*", "android.jar"))
    out = tempfile.mkdtemp(prefix="inj-build-")
    try:
        # --release 8 keeps the class files readable by d8 whatever JDK compiled them
        subprocess.run([JAVAC, "--release", "8", "-cp", jar, "-d", out, os.path.join(HERE, "Inj.java")], check=True)
        classes = glob.glob(os.path.join(out, "*.class"))
        subprocess.run([d8, "--lib", jar, "--output", out, *classes], check=True, env={**os.environ, "JAVA_HOME": JAVA_HOME})
        shutil.copy(os.path.join(out, "classes.dex"), os.path.join(HERE, "inj.dex"))
        print("wrote", os.path.join(HERE, "inj.dex"), os.path.getsize(os.path.join(HERE, "inj.dex")), "bytes")
    finally:
        shutil.rmtree(out, ignore_errors=True)


if __name__ == "__main__":
    main()
