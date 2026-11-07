<h1><img width="100" src="docs/icons/avatar.png" alt="Danpoong patches" align="absmiddle"> Danpoong patches</h1>

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Built for Morphe](https://img.shields.io/badge/Built%20for-Morphe-1E5AA8?style=flat-square)](https://morphe.software)

Install Morphe Manager if you have not yet: https://morphe.software

[Click here to add Danpoong patches to Morphe Manager](https://morphe.software/add-source?github=3rdappsmod/morphe-patches_mod)

Select the app you want to patch inside Morphe Manager, follow all instructions shown.

## Patches

<!-- PATCHES_START -->
> **[v1.1.0](https://github.com/3rdappsmod/morphe-patches_mod/releases/tag/v1.1.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;10 patches total
<details>
<summary>📱 Samsung Apps&nbsp;&nbsp;•&nbsp;&nbsp;2 apps&nbsp;&nbsp;•&nbsp;&nbsp;8 patches</summary>
<br>
<blockquote>

<details>
<summary><img src="docs/icons/samsung-keyboard.png" width="20" height="20" align="top"> Samsung Keyboard&nbsp;&nbsp;-&gt;&nbsp;&nbsp;<img src="docs/icons/samsung-keyboard.png" width="20" height="20" align="top"> Samsung Keyboard&nbsp;&nbsp;•&nbsp;&nbsp;7 patches</summary>
<br>

**Supported version(s):** 5.9.40.75

| Patch | Description | Options |
|----------|----------------|-----------|
| [Always incognito](#always-incognito) | Never learns from what you type. |  |
| [Change app name](#change-app-name) | Renames Samsung Keyboard, so the two keyboards can be told apart. | • App name |
| [Change package name](#change-package-name) | Installs next to the system Samsung Keyboard under its own package name. | • Package name |
| [Disable telemetry](#disable-telemetry) | Turns off Samsung Analytics, crash reports and typed-text collection. |  |
| [No GIF tracking](#no-gif-tracking) | Stops GIPHY and Tenor view and share pings. |  |
| [Remove internet permission](#remove-internet-permission) | Keeps the keyboard offline. GIFs, stickers, translation, AI tools and language downloads stop working. |  |
| [Stop remote updates](#stop-remote-updates) | Ignores keyboard policies pushed from Samsung's servers. |  |

</details>

<details>
<summary><img src="docs/icons/samsung-music.png" width="20" height="20" align="top"> Samsung Music&nbsp;&nbsp;-&gt;&nbsp;&nbsp;<img src="docs/icons/samsung-music.png" width="20" height="20" align="top"> Samsung Music&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**Supported version(s):** 16.2.45.1

| Patch | Description | Options |
|----------|----------------|-----------|
| [Enable non-One UI ROM support](#enable-non-one-ui-rom-support) | Makes Samsung Music installable and usable on non-One UI ROMs. |  |

</details>

</blockquote>
</details>

<details>
<summary>📱 ColorOS Apps&nbsp;&nbsp;•&nbsp;&nbsp;2 apps&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>
<blockquote>

<details>
<summary><img src="docs/icons/coloros-notes.png" width="20" height="20" align="top"> OnePlus Notes&nbsp;&nbsp;-&gt;&nbsp;&nbsp;<img src="docs/icons/coloros-notes.png" width="20" height="20" align="top"> OnePlus Notes&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**Supported version(s):** 17.0.30

| Patch | Description | Options |
|----------|----------------|-----------|
| [Enable non-ColorOS ROM support](#enable-non-coloros-rom-support) | Makes OnePlus Notes installable and usable on non-ColorOS ROMs. |  |

</details>

<details>
<summary><img src="docs/icons/coloros-recorder.png" width="20" height="20" align="top"> OnePlus Recorder&nbsp;&nbsp;-&gt;&nbsp;&nbsp;<img src="docs/icons/coloros-recorder.png" width="20" height="20" align="top"> OnePlus Recorder&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**Supported version(s):** 17.7.16

| Patch | Description | Options |
|----------|----------------|-----------|
| [Enable non-ColorOS ROM support](#enable-non-coloros-rom-support) | Makes OnePlus Recorder installable and usable on non-ColorOS ROMs. |  |

</details>

</blockquote>
</details>

<!-- PATCHES_END -->

## Building

To build Danpoong patches, follow the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation).

## Credits

This bundle started from [bearinmind patches](https://github.com/bearinmindcat/morphe-patches) (GPLv3) and keeps its
GPLv3 licence and attribution.

## Misc info for myself
Always use Semantic commit (https://kapeli.com/cheat_sheets/Semantic_Commits.docset/Contents/Resources/Documents/index) messages. 
To keep it simple use only 3 commit message types:
feat: / fix: / chore:
Commits of fix: and feat: will automatically generate new pre-releases and chore: will not create a new release.

feat:/fix: make pre-releases on dev, and stable releases on main.
