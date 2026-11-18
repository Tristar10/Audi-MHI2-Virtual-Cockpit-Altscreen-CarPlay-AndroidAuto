#!/bin/sh
# Switch the AltScreen hook between visible-window presets (TT MU1462).
# Only the hook library changes; replug the iPhone afterwards.
#
#   sh /tmp/safe-preset.sh low --parked
#   presets: stock low lowwide full midhigh mid midtall combo combolow combowide
set -eu
PATH=/proc/boot:/bin:/usr/bin:/sbin:/usr/sbin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin
export PATH
case $0 in */*) D=${0%/*};; *) D=.;; esac
D=$(cd "$D" && pwd)
name=${1:-}
test "${2:-}" = --parked || { echo 'Usage: safe-preset.sh <preset> --parked  (stock low lowwide full midhigh mid midtall combo combolow combowide h340 h325 h320 h310 up10 up20 sport auto auto2)'; exit 2; }
case $name in
    stock)   want=9a8c01ec8323d5de7695e212ff6168b0bb7df99d8554c9971f5449c5a21d6256;;
    low)     want=050e3ff9d6bd7f95fd14c0359a19878b5c483dbeb316261703ec85dad9f78102;;
    lowwide) want=534c7d26f794aa01838c8fd12e7dd4d5a595ae43453427a22269e7649503c57b;;
    full)    want=a24b5cfd55b2956d956f9910c568b596cfa3a8e8161ccede62f5fe1179424d55;;
    midhigh) want=b94dfaaadb918ebd8338f80004b4652c0695f8c5f58c74eb800bffedefd315b7;;
    mid)     want=05209ae70acd69fae95cb9bf4bbc8a0c8cb1d01961f6e894287acfde5e98eeed;;
    midtall) want=db1727930b486a5457f8f34a5772433da83afe8e4c1870b301068d991fdd57f3;;
    combo)     want=d829824e39a67dfbf51b879e0e656011031eff38ace42f8d6e4314d15a417fbb;;
    combolow)  want=7b299075442f676074fee6fc8a2ebab39b1b423bbad9276ec9202e93f4d57705;;
    combowide) want=9481af4923f7fa407a2ac430e76b6a7b58c9f403bedc83a6c16401645c056b1d;;
    h340) want=0614cf7106f33f94a7cb69dc2fd22e99b9a8cc17a3ed9b9830ed483a648b3c68;;
    h325) want=60060eddcf6ac34d0c673ee36bc75ffbb17f48ae4920de29ab716867d435688a;;
    h310) want=b2f9e468b7d3884fedf23e755fb33957de4c6c1dbd70c55992825fc0fe03d81b;;
    up10) want=094d401059180f29b73591b715891e5ab9eb390470f2cfe992020dbbcc030394;;
    up20) want=3b878e1414e2837dd2d28d180bd0fa855871c9cbabb33dca0e00ede2a83789ab;;
    h320) want=36aedadb8e0680ad03a9a511f3844e629ca3b2ffaaf40c9745298f730fc0b3f8;;
    auto)  want=37ac2d2994bd09822d6b19cf7d54463e1f933ee9318aee8ed899d5a1716ea155;;
    auto2) want=d8bad58e8101d23a01e12261147466edbc6022e664a19c0a6c3b0a49907b72b2;;
    sport) want=ec62cc08181886c9aa28a891bb0ceb5e21f4baa4a556407545b68c4172dbbb75;;
    *) echo "Unknown preset: $name"; exit 2;;
esac
LIB=/mnt/app/root/lib-target/libaltscreen111.so
SRC=$D/libaltscreen111-$name.so
tool=/tmp/altscreen-sha256
test -x "$tool" || { echo 'Copy altscreen-sha256 to /tmp and chmod 755 it first.'; exit 1; }
test -f "$LIB" || { echo "$LIB not found - is the AltScreen port installed?"; exit 1; }
"$tool" "$want" "$SRC" || { echo "$SRC missing or damaged"; exit 1; }
rw=0
cleanup(){ if test "$rw" = 1; then sync || :; mount -ur /mnt/app || echo 'WARNING: /mnt/app still writable'; fi; }
trap cleanup 0
trap 'exit 1' 1 2 15
mount -uw /mnt/app; rw=1
cp "$SRC" "$LIB.new"
chmod 755 "$LIB.new"
"$tool" "$want" "$LIB.new"
mv "$LIB.new" "$LIB"
"$tool" "$want" "$LIB"
echo "Preset '$name' active. Unplug the iPhone, wait for dio_manager to exit, replug."
