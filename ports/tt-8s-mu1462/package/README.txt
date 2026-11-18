AltScreen for Audi TT 8S, MHI2_US_AU43x_P5180 / MU1462 - v2
Based on aberbic's MU1438 AltScreen (GPL-3.0).

What it does
- CarPlay map (Apple/Google Maps) on the Virtual Cockpit maps tab, switched automatically.
- Classic window tuned for the TT (hook preset h320).
- Sport layout: the picture moves into the left half while /tmp/mu1438-cluster-sport
  exists. tt-zoom.jar creates and deletes that file when the cluster skin changes.
- tt-zoom.jar also sends the steering-wheel scroll zoom to the CarPlay map.

Install (parked, battery charging, iPhone unplugged), files copied flat to /tmp:
  chmod 755 /tmp/altscreen-sha256
  sh /tmp/altscreen-mu1462-install.sh --check   --parked
  sh /tmp/altscreen-mu1462-install.sh --install --parked     (note the backup path it prints)
  restart the MMI, then:
  sh /tmp/tt-zoom.sh --install --parked
  restart the MMI again.

Remove
  sh /tmp/tt-zoom.sh --remove --parked
  sh /mnt/app/root/altscreen-backups/mu1462-N/altscreen-mu1462-install.sh --restore --parked
  restart the MMI.

Without the jar, Sport can be set by hand: touch /tmp/mu1438-cluster-sport (rm to undo).
