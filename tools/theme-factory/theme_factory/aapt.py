"""Locate and drive the Android build tools (aapt2, zipalign, apksigner, keytool).

The factory is deliberately tolerant about *where* the SDK lives: it looks at
--sdk / $ANDROID_SDK / $ANDROID_HOME / ~/android-sdk and the newest build-tools
directory it finds.  Nothing here shells out through a shell; every call is a
list argv so APK paths with spaces stay intact.
"""

from __future__ import annotations

import glob
import os
import re
import shutil
import subprocess
import sys

from .util import log


class ToolError(RuntimeError):
    pass


def _version_key(path: str):
    name = os.path.basename(path.rstrip("/"))
    parts = re.findall(r"\d+", name)
    return tuple(int(p) for p in parts) if parts else (0,)


class AndroidTools:
    def __init__(self, sdk: str | None = None, jdk: str | None = None,
                 build_tools: str | None = None, platform: str | None = None):
        self.sdk = sdk or os.environ.get("ANDROID_SDK") or os.environ.get("ANDROID_HOME") \
            or os.path.expanduser("~/android-sdk")
        self.jdk = jdk or os.environ.get("ANDROID_JDK") or os.path.expanduser("~/android-jdk")
        self.build_tools = build_tools or os.environ.get("BUILD_TOOLS_VERSION")
        self.platform = platform or os.environ.get("ANDROID_PLATFORM")

    # -- resolution ---------------------------------------------------------
    def _bt_dir(self) -> str:
        if self.build_tools:
            cand = os.path.join(self.sdk, "build-tools", self.build_tools)
            if os.path.isdir(cand):
                return cand
        dirs = [d for d in glob.glob(os.path.join(self.sdk, "build-tools", "*"))
                if os.path.isdir(d)]
        if not dirs:
            raise ToolError("no build-tools under %s/build-tools" % self.sdk)
        return sorted(dirs, key=_version_key)[-1]

    def tool(self, name: str) -> str:
        cand = os.path.join(self._bt_dir(), name)
        if os.path.isfile(cand) and os.access(cand, os.X_OK):
            return cand
        found = shutil.which(name)
        if found:
            return found
        raise ToolError("missing build tool %r (looked in %s and PATH)" % (name, self._bt_dir()))

    @property
    def aapt2(self) -> str:
        return self.tool("aapt2")

    @property
    def zipalign(self) -> str:
        return self.tool("zipalign")

    @property
    def apksigner(self) -> str:
        return self.tool("apksigner")

    def java(self) -> str | None:
        for cand in (os.path.join(self.jdk, "bin", "java"), shutil.which("java")):
            if cand and os.path.isfile(cand) and os.access(cand, os.X_OK):
                return cand
        return None

    def keytool(self) -> str:
        cand = os.path.join(self.jdk, "bin", "keytool")
        if os.path.isfile(cand) and os.access(cand, os.X_OK):
            return cand
        found = shutil.which("keytool")
        if found:
            return found
        raise ToolError("missing keytool")

    def platform_jar(self, target_sdk: int) -> str:
        if self.platform:
            p = os.path.join(self.sdk, "platforms", self.platform, "android.jar")
            if os.path.isfile(p):
                return p
        for cand in (os.path.join(self.sdk, "platforms", "android-%d" % target_sdk, "android.jar"),
                     os.path.join(self.sdk, "platforms", "android-%d" % (target_sdk - 1), "android.jar")):
            if os.path.isfile(cand):
                return cand
        dirs = sorted(glob.glob(os.path.join(self.sdk, "platforms", "android-*")), key=_version_key)
        if dirs:
            cand = os.path.join(dirs[-1], "android.jar")
            if os.path.isfile(cand):
                return cand
        raise ToolError("no android.jar under %s/platforms" % self.sdk)

    # -- execution ----------------------------------------------------------
    def run(self, args, check=True, capture=True):
        env = dict(os.environ)
        java = self.java()
        if java:
            env["JAVA_HOME"] = os.path.dirname(os.path.dirname(java))
            env["PATH"] = os.path.dirname(java) + os.pathsep + env.get("PATH", "")
        proc = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              env=env, text=True, errors="replace")
        if check and proc.returncode != 0:
            err = (proc.stderr or proc.stdout or "").strip()
            raise ToolError("%s failed (rc=%d): %s" % (os.path.basename(args[0]),
                                                       proc.returncode, err[:2000]))
        return proc

    def dump_resources(self, apk: str) -> str:
        return self.run([self.aapt2, "dump", "resources", apk]).stdout

    def dump_badging(self, apk: str) -> str:
        return self.run([self.aapt2, "dump", "badging", apk]).stdout

    def dump_xmltree(self, apk: str, path_in_apk: str) -> str:
        proc = self.run([self.aapt2, "dump", "xmltree", "--file", path_in_apk, apk], check=False)
        return proc.stdout if proc.returncode == 0 else ""

    def framework_attr_names(self, android_jar: str) -> dict:
        """id -> name for framework attrs and colours, from android.jar's arsc."""
        dump = self.dump_resources(android_jar)
        attrs, colors = {}, {}
        for line in dump.splitlines():
            m = re.match(r"    resource (0x[0-9a-f]+) (attr|color)/(\S+?)(?: PUBLIC)?$", line)
            if not m:
                continue
            (attrs if m.group(2) == "attr" else colors)[m.group(1)] = m.group(3)
        log("   framework: %d attrs, %d colours" % (len(attrs), len(colors)))
        return {"attrs": attrs, "colors": colors}
