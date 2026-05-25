# Getting set up for FM Team Hundo

Please follow all these steps **before** the FM Team Hundo begins.

## Download required software

You need the following software to participate. If you've previously downloaded any of these, make sure you have the latest releases:

1. A PS1 emulator supported by FM Team Hundo, either:
   
   - BizHawk, version 2.10 or 2.11 (officially supported and tested). Later versions may still work. Earlier versions will NOT work.
   
   - DuckStation custom build (experimental).

2. The FM_Sentinel program: download for your OS from the [FM Team Hundo GitHub](https://github.com/sg4e/FM_Team_Hundo/releases/latest).

The BizHawk project releases BizHawk builds for Windows and Linux. Therefore, these are currently the only operating systems supported for FM Team Hundo. Additional emulators may become supported in the future.

### Setting up BizHawk for FM Team Hundo

1. Download the latest `YGOFMPlugin.dll` from the [FM Team Hundo GitHub](https://github.com/sg4e/FM_Team_Hundo/releases/latest).

2. Locate the `ExternalTools` folder inside your BizHawk folder.

3. Move `YGOFMPlugin.dll` to inside of `ExternalTools`.

**Warning: BizHawk is notorious for destroying save files.** You are recommended to use savestates in addition to in-game saves to prevent loss of progress. Consult the [Rules](/docs/rules) page for legal uses of savestates for the event. BizHawk also comes pre-configured with many hotkeys and settings you probably want to disable.

### Setting up DuckStation for FM Team Hundo (experimental)

DuckStation is experimentally supported by FM Team Hundo. However, its license restricts the distribution of modified source code and binaries. Therefore, you'll have to clone the [DuckStation repo](https://github.com/stenzek/duckstation), download and apply the [FM Team Hundo Python patch to the DuckStation source code](https://github.com/sg4e/FM_Team_Hundo/releases/latest), then build DuckStation yourself.

The FM Team Hundo Python patch specifies in its readme the exact git commit it works with.

## Register at [hundo.maika.moe](/)

1. Click the login button at the top-right corner of the [hundo.maika.moe](/) page. Authorize FM Team Hundo with your primary Twitch account. After being redirected to the FM Team Hundo homepage, click on your username at the top-right corner, then "Profile" to access your [profile page](/profile).

2. Optionally set an alt account on your profile. FM Team Hundo requires you to livestream on Twitch whenever you're playing, either on the primary account you used as your login, or on the alt Twitch account set here. **Double-check the spelling of your alt account** since there's no verification step. Other livestreaming platforms aren't currently supported.

3. Click the Download Credentials File button and read through the pop-up message to download your credentials. Place this file inside the `FM_Sentinel` folder (same location as the `FM_Sentinel` executable).
