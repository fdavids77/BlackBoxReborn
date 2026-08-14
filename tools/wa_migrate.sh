#!/system/bin/sh
# wa_migrate.sh <SRC_USER> <TARGET_VUID>
# Migrate a REGISTERED WhatsApp session from Android user SRC_USER into the
# BlackBox virtual container TARGET_VUID (0,1,2,...). Run as ROOT.
# The BlackBox container TARGET_VUID must already exist (create the WhatsApp
# clone in BlackBox first) unless TARGET_VUID=0.
#
# Deploy:  adb push tools/wa_migrate.sh /data/local/tmp/  &&  adb shell "chmod 755 /data/local/tmp/wa_migrate.sh"
# Run:     su -c "sh /data/local/tmp/wa_migrate.sh 11 0"
SRC="$1"; VUID="$2"; PKG=com.whatsapp
[ -z "$SRC" ] || [ -z "$VUID" ] && { echo "usage: wa_migrate.sh SRC_USER TARGET_VUID"; exit 1; }
BB=/data/data/top.niunaijun.blackbox/blackbox/data
SRC_CE=/data/user/$SRC/$PKG;   SRC_DE=/data/user_de/$SRC/$PKG
DST_CE=$BB/user/$VUID/$PKG;     DST_DE=$BB/user_de/$VUID/$PKG
[ -d "$SRC_CE" ] || { echo "ERR: source $SRC_CE not found (is user $SRC registered/unlocked?)"; exit 1; }
BBUID=$(stat -c %u /data/data/top.niunaijun.blackbox)
CTX=$(ls -Zd "$BB/user/0/$PKG" 2>/dev/null | awk '{print $1}')
[ -z "$CTX" ] && CTX=u:object_r:app_data_file:s0:c203,c257,c512,c768
echo "src=user$SRC  container=$VUID  bbuid=$BBUID  ctx=$CTX"
am force-stop top.niunaijun.blackbox
am force-stop --user "$SRC" "$PKG"
[ -e "$DST_CE" ] && { rm -rf "$DST_CE.bak"; mv "$DST_CE" "$DST_CE.bak"; }
[ -e "$DST_DE" ] && { rm -rf "$DST_DE.bak"; mv "$DST_DE" "$DST_DE.bak"; }
mkdir -p "$BB/user/$VUID" "$BB/user_de/$VUID"
cp -a "$SRC_CE" "$DST_CE"
[ -d "$SRC_DE" ] && cp -a "$SRC_DE" "$DST_DE"
rm -rf "$DST_CE/cache" "$DST_CE/code_cache"
chown -R "$BBUID:$BBUID" "$DST_CE" "$DST_DE" 2>/dev/null
chcon -R "$CTX" "$DST_CE" "$DST_DE" 2>/dev/null
echo "MIGRATE_DONE: user$SRC -> container $VUID (old data at *.bak)"
