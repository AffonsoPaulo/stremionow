# CA certificates

`android/player/src/main/assets/cacert.pem` is the Mozilla CA certificate store in PEM format, as published by the curl project at <https://curl.se/docs/caextract.html>. It lets mpv verify HTTPS servers on Android, where it cannot read the system store.

The file is distributed under the [Mozilla Public License 2.0](https://www.mozilla.org/en-US/MPL/2.0/), which is compatible with the GPL. It is unmodified; refresh it from <https://curl.se/ca/cacert.pem> and check it against <https://curl.se/ca/cacert.pem.sha256>.
