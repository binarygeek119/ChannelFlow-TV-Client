<h1 align="center">ChannelFlow TV</h1>
<h3 align="center">Android TV client for <a href="https://github.com/binarygeek119/ChannelFlow">ChannelFlow</a></h3>

---

<p align="center">
<img alt="ChannelFlow TV" src="logo.png" width="220"/>
<br/><br/>
<a href="LICENSE">
<img alt="GPL 2.0 License" src="https://img.shields.io/github/license/binarygeek119/ChannelFlow-TV-Client.svg"/>
</a>
<a href="https://github.com/binarygeek119/ChannelFlow-TV-Client/releases">
<img alt="Current Release" src="https://img.shields.io/github/v/release/binarygeek119/ChannelFlow-TV-Client.svg"/>
</a>
<br/>
<a href="https://github.com/binarygeek119/ChannelFlow-TV-Client/releases">Download the latest APK</a>
<br/><br/>
<strong>Downloader code: <code>6869959</code></strong>
</p>

ChannelFlow TV is a Leanback Android TV app for watching live IPTV from a [ChannelFlow](https://github.com/binarygeek119/ChannelFlow) server. It opens to the live guide, plays M3U streams, and pairs with a server using a quick pin. There is no Jellyfin login and no DVR.

It is a fork of [Jellyfin for Android TV](https://github.com/jellyfin/jellyfin-androidtv), cut down to live TV and the guide.

Author: [binarygeek119](https://github.com/binarygeek119)

## Install

On Fire TV or Android TV, install [Downloader](https://www.aftvnews.com/downloader/) from the Amazon Appstore (or Play Store), open it, and enter:

```
3745820
```

That code fetches the ChannelFlow TV APK. After it downloads, install it and allow unknown sources if the TV asks.

You can also download `ChannelFlow-TV-v*-release.apk` from [GitHub Releases](https://github.com/binarygeek119/ChannelFlow-TV-Client/releases).

## Features

- Live TV guide loaded from the ChannelFlow M3U playlist and XMLTV listings
- Direct playback of live MPEG-TS streams
- Quick pin pairing (no server URL to type on the TV)
- Automatic local/public URL selection: uses the server's local URL on the same network and its public URL everywhere else, with the choice (and a manual override) per saved server
- Multiple saved servers, with switch / add / remove in settings
- Channel up/down by number, including decimals such as `119.1`
- Program details for listings that are not on now
- Reminders for upcoming programs, with a watch-now prompt when they start
- In-app updates from GitHub Releases

## Pairing

1. Install ChannelFlow TV with Downloader code `3745820`, or sideload the release APK.
2. On the TV, open the app and note the pin shown on screen.
3. In ChannelFlow, open **Quick Pin** and enter that code.
4. The TV saves the server and opens the guide.

The pin relay is `https://channelflow.duckdns.org` and is not user-configurable. Pins last 10 minutes.

### Local and public URLs

ChannelFlow-Server hands the TV both its public URL and its local (LAN) URL during pairing. The TV checks the local URL with a short `GET /health` and:

- uses the **local URL** while it answers, so guide data and streams stay on your network
- uses the **public URL** when it does not, for example when the TV is away from home
- switches on its own when a request on the current URL stops working, and remembers the switch

Both URLs are shown for each saved server under **Settings → Server → Switch server**, where the route can also be pinned to **Automatic**, **Local URL only**, or **Public URL only**. Servers paired before this update keep working with their single address; the reachability check only runs when both URLs were handed out.

Set **Local Base URL** in ChannelFlow-Server (General settings) so the local URL is a fixed LAN address rather than whatever host the browser happened to use.

## Building

The app uses Gradle and needs the Android SDK. Android Studio includes the required tooling. For a command-line debug build, use JDK 21 and the Gradle wrapper:

```shell
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/` as `ChannelFlow-TV-v<version>-debug.apk`. Debug builds use a `.debug` application id, so they can sit next to a release install.

A local release APK (minified, signed with `keystore/channelflow-release.jks` unless another keystore is configured):

```shell
CHANNELFLOW_VERSION=0.0.14 ./gradlew assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/` as `ChannelFlow-TV-v0.0.14-release.apk`.

## Releases

Pushing a `v*` tag (or running **App / Release APK** from GitHub Actions) builds the release APK and attaches `ChannelFlow-TV-vX.Y.Z-release.apk` to the GitHub Release. The app checks that release from **Settings**, and can download and install it as an update.

```shell
git tag v0.0.14
git push origin v0.0.14
```

Release APKs are signed with `keystore/channelflow-release.jks` so GitHub updates can install over each other. Optional repository secrets for a different production key: `KEYSTORE` (base64 of the `.jks` file), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Builds from v0.0.6 and earlier used a new debug key on every CI run. Uninstall that install once, then sideload this release APK. Later updates install in place. Debug and release installs do not update each other.

## License

ChannelFlow TV is licensed under the [GNU General Public License v2.0](LICENSE), the same license as Jellyfin for Android TV, which this project is based on.
