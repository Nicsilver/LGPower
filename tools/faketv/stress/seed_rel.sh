UID_=$(stat -c %u /data/data/com.nic.lgpower)
mkdir -p /data/data/com.nic.lgpower/shared_prefs
cat > /data/data/com.nic.lgpower/shared_prefs/webos.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="tvs">[{&quot;id&quot;:&quot;b0a70b21-8ca7-4248-9587-e7af695de027&quot;,&quot;name&quot;:&quot;LG TV&quot;,&quot;ip&quot;:&quot;10.0.2.2&quot;,&quot;mac&quot;:&quot;AA:BB:CC:DD:EE:FF, 20:3D:BD:DE:49:12&quot;,&quot;key&quot;:&quot;faketv-key-123&quot;,&quot;udn&quot;:&quot;&quot;}]</string>
    <string name="active_tv">b0a70b21-8ca7-4248-9587-e7af695de027</string>
    <string name="tv_ip">10.0.2.2</string>
    <string name="tv_mac">AA:BB:CC:DD:EE:FF, 20:3D:BD:DE:49:12</string>
    <string name="client_key">faketv-key-123</string>
    <int name="last_seen_version" value="46" />
</map>
XML
chown $UID_:$UID_ /data/data/com.nic.lgpower/shared_prefs /data/data/com.nic.lgpower/shared_prefs/webos.xml
chmod 660 /data/data/com.nic.lgpower/shared_prefs/webos.xml
restorecon -R /data/data/com.nic.lgpower/shared_prefs
