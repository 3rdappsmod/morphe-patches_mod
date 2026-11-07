# Vendor stand-in generator (shared)

Off a phone maker's ROM, that maker's apps call framework classes only its ROM has and die with
`NoClassDefFoundError` at launch. These scripts write Java stand-ins for exactly the classes, methods and fields an app
references, answering "absent" (false / 0 / "" / null, a getter's own default argument, a fresh object for a builder or
factory) unless a vendor `known.py` gives a better answer. They carry the maker's names and signatures only, no maker
code; on that maker's phone the framework's own classes load first, so the stand-ins never run there.

`gen.py` is vendor-neutral. Each vendor supplies its own answers and runtime classes through `--known` and
`--runtime`; this directory holds the generator, `Missing.java`, and the default (HyperOS/Xiaomi) answers used when no
`--known` is given.

| Vendor | Answers | Runtime | Entry point |
| --- | --- | --- | --- |
| ColorOS (OPPO / OnePlus / realme) | `../coloros-standins/known.py` | `../coloros-standins/runtime/` | `../coloros-standins/gen.py` |

## Use

```bash
# 1. the phone's boot classpath, once per emulator image (Android 15, API 35)
mkdir boot && for p in $(adb shell 'echo $BOOTCLASSPATH' | tr ':' ' ') /system/framework/org.apache.http.legacy.jar; do adb pull $p boot/; done
# 2. what the app uses that the phone lacks
G=~/.gradle/caches/modules-2/files-2.1
CP=$(find $G/com.github.MorpheApp.smali/smali-dexlib2 $G/com.github.MorpheApp.smali/smali-util $G/com.google.guava/guava -name '*.jar' ! -name '*sources*' | tr '\n' ':')
java -Xmx4g -cp "$CP" Missing.java boot <app.apk> work/        # -> types.txt, members.txt, usage.json, abstract.json
# 3. stand-ins + runtime into the extension
python3 gen.py work/ extensions/coloros<app>/src/main/java "OnePlus <App>" --apk <app.apk> \
    --known ../coloros-standins/known.py --runtime org.ungoogled.coloros<app>
```

`gen.py` writes stand-ins for the classes the app references that the phone lacks, plus the vendor answers from
`known.py` (`SUPERS`, `FIELDS`, `METHODS`, `FACTORIES`, `ONLY`, `HEADER`, `SDK_BASES`, `REFLECTED`) and the runtime
classes from `known.RUNTIME_DIR` into `--runtime`'s Java package. Files it did not write (such as the extension's own
helpers) are never overwritten.

## What the generator handles

- Vendor classes inside `android.*` and vendor members added to Android classes (`members.txt`, for
  `redirectMembers` in the patch).
- Builders and factories (`build`, `create*`, `newInstance`, `getInstance`, `obtain`) return an object, not null.
- Superclasses: a stand-in an app component extends (manifest activity/service/receiver/provider/application/
  backupAgent) extends the matching Android class and implements its abstract methods; `known.SUPERS` and an
  `--extends` file ("Lchild; Lparent;" per line) cover the rest.
- Types named in a catch clause, thrown, or named `...Exception`/`...Error` extend `java.lang.Exception`.
- A vendor class nested in an Android class becomes a top-level class named `Outer$Inner`.
- Members whose signature names a hidden framework class or one of the app's own classes are skipped and listed; write
  those by hand if the app needs them.
- `SDK_BASES` (opt-in per vendor) makes stand-ins that extend a public Android class keep that class's members and
  constructors, and reads vendor widgets named only in the app's XML layouts (`--apk`).

## Things to know

- A vendor `known.py` decides what "absent" means for that vendor (e.g. no ROM version, the phone's own system
  properties, no vendor-only hardware). Never invent device identities.
- `known.REFLECTED` lists fields a vendor's libraries read only by reflection; `gen.py` adds them to a stand-in it
  writes anyway.
- `--only` limits generation to one vendor's framework packages; other missing classes (unused optional SDKs,
  `java.awt`) are left alone.
- Always run the ART verifier sweep on the patched app afterwards: 0 rejected classes.
