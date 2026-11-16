# How these APKs were built (2026-11-16, hermes-ubuntu)

Host: `ssh hermes-ubuntu`, workdir `~/ztux/parity`, `ANDROID_HOME=~/android-sdk`
(android-36 / build-tools 36.0.0), `JAVA_HOME=~/android-jdk` (OpenJDK 17). No Gradle,
no AndroidX, no network. The Mac has no SDK, so the repo subset is staged first:

```sh
cd build/android-ux-research
rsync -a repo/launcher repo/actioncentre repo/ime repo/design repo/scripts \
      hermes-ubuntu:~/ztux/parity/
ssh hermes-ubuntu 'cd ~/ztux/parity && export JAVA_HOME=$HOME/android-jdk \
  PATH=$HOME/android-jdk/bin:$PATH && bash scripts/build-launcher.sh'
ssh hermes-ubuntu 'cd ~/ztux/parity && export JAVA_HOME=$HOME/android-jdk \
  PATH=$HOME/android-jdk/bin:$PATH && bash scripts/build-ime.sh'
ssh hermes-ubuntu 'cd ~/ztux/parity && export JAVA_HOME=$HOME/android-jdk \
  PATH=$HOME/android-jdk/bin:$PATH && ACTIONCENTRE_STAGE=1 \
  APP_DIR=$PWD/actioncentre/app/src/main BUILD_DIR=$PWD/actioncentre/build \
  bash scripts/build-actioncentre.sh'
```

The launcher and IME scripts are local-host builders up to now (they resolve an SDK on
the machine they run on); they were run on the Ubuntu host, not on the Mac. The
Action Centre script already had the `ACTIONCENTRE_STAGE=1` remote half.

At the moment of the final build `repo/actioncentre` was being edited by another agent
(`UnlockProxyActivity`, `LockClockPanel`, shade timeline). The AC APK is that combined
tree; its hash is pinned in `sha256.txt`. The shared `repo/*/build/out` paths are
written by other builds too — the copies under `evidence/parity/` are the pinned ones.

Build output (size / sha256):

```
 74155  f93f137a6c118c7957ed752c60556808620b7ac0a6b9e7c4204093527feb9639  ztmetro-start.apk
341708  fbd56147cb3235c6f75492d43b4f743174a4b90779e6a567a69b1f31ce2edaf9  ztmetro-actioncentre.apk
 20817  5eebf7b33ef7ddbe9d71c034368d59c9f37c5b3f3fdf2c2b19a400363363688e  ztmetro-ime.apk
```

All three passed their build scripts' static checks (`evidence/parity/build-facts/`).
